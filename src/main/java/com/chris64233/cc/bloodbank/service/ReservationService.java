package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.api.dto.ReservationRequest;
import com.chris64233.cc.bloodbank.api.error.BusinessConflictException;
import com.chris64233.cc.bloodbank.api.error.ResourceNotFoundException;
import com.chris64233.cc.bloodbank.domain.BloodType;
import com.chris64233.cc.bloodbank.domain.BloodUnit;
import com.chris64233.cc.bloodbank.domain.ItemStatus;
import com.chris64233.cc.bloodbank.domain.Reservation;
import com.chris64233.cc.bloodbank.domain.ReservationItem;
import com.chris64233.cc.bloodbank.domain.ReservationStatus;
import com.chris64233.cc.bloodbank.domain.UnitStatus;
import com.chris64233.cc.bloodbank.repo.BloodUnitRepository;
import com.chris64233.cc.bloodbank.repo.ReservationItemRepository;
import com.chris64233.cc.bloodbank.repo.ReservationRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 预留、发放与到期释放。
 *
 * <p>所有库存/状态变更都在单事务内完成，并发正确性以数据库为准：
 * 候选单位行锁 + 库存条件更新 + 预留单条件状态机 + 有效明细唯一约束（见 {@link ReservationItem}）。
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationRepository reservationRepository;
    private final ReservationItemRepository itemRepository;
    private final BloodUnitRepository unitRepository;
    private final EntityManager entityManager;
    private final Clock clock;
    private final Duration defaultTtl;

    public ReservationService(ReservationRepository reservationRepository,
                              ReservationItemRepository itemRepository,
                              BloodUnitRepository unitRepository,
                              EntityManager entityManager,
                              Clock clock,
                              @Value("${bloodbank.reservation.default-ttl:PT30M}") Duration defaultTtl) {
        this.reservationRepository = reservationRepository;
        this.itemRepository = itemRepository;
        this.unitRepository = unitRepository;
        this.entityManager = entityManager;
        this.clock = clock;
        this.defaultTtl = defaultTtl;
    }

    /**
     * 原子预留。
     *
     * <p>同一申请号重放：内容相同返回原分配（任何状态下均幂等，{@code created=false}）；内容不同返回冲突。
     * READ_COMMITTED 下以 {@code SELECT … FOR UPDATE} 锁定候选单位并在同事务完成全部状态变更：
     * 行锁保证并发预留串行化，等锁后语句读到最新已提交快照，败者可继续挑选其余可用单位；
     * 单位不足整笔回滚。另有条件更新与有效明细唯一约束兜底。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationOutcome reserve(ReservationRequest request) {
        Instant now = Instant.now(clock);

        Reservation existing = reservationRepository.findWithItems(request.requestNo()).orElse(null);
        if (existing != null) {
            // 重放优先于一切时间校验：旧申请到期后重放同内容仍返回原分配
            if (!sameContent(existing, request)) {
                throw new BusinessConflictException("REQUEST_CONTENT_CONFLICT",
                        "申请号 " + request.requestNo() + " 已存在且申请内容不同");
            }
            return new ReservationOutcome(existing, false);
        }

        Instant expireAt = request.expireAt() != null ? request.expireAt() : now.plus(defaultTtl);
        if (!expireAt.isAfter(now)) {
            throw new IllegalArgumentException("预留截止时间必须晚于当前时间");
        }

        Set<BloodType> donorTypes = BloodType.compatibleDonors(request.patientBloodType());
        // 行锁串行化并发预留；排序即“失效最早优先、条码稳定排序”
        List<BloodUnit> candidates = unitRepository.lockCandidates(
                request.componentType(), donorTypes, UnitStatus.AVAILABLE, now, request.latestUseAt());
        if (candidates.size() < request.unitCount()) {
            // 整笔失败：不写入任何预留/明细，也不改变任何库存
            throw new BusinessConflictException("INSUFFICIENT_UNITS",
                    "符合条件的可用单位不足：需要 " + request.unitCount() + "，当前可选 " + candidates.size());
        }

        List<BloodUnit> allocated = candidates.subList(0, request.unitCount()).stream().toList();
        List<String> barcodes = allocated.stream().map(BloodUnit::getBarcode).toList();

        // 库存兜底：仅 AVAILABLE→RESERVED 生效；行数不符说明被并发事务抢走，整笔回滚
        int changed = unitRepository.compareAndSetStatusBatch(
                barcodes, UnitStatus.AVAILABLE, UnitStatus.RESERVED);
        if (changed != barcodes.size()) {
            throw new BusinessConflictException("INSUFFICIENT_UNITS",
                    "符合条件的可用单位不足（并发竞争）：需要 " + request.unitCount() + "，锁定 " + changed);
        }

        Reservation reservation = new Reservation(
                request.requestNo(),
                request.patientBloodType(),
                request.componentType(),
                request.unitCount(),
                request.latestUseAt(),
                expireAt);
        for (int i = 0; i < allocated.size(); i++) {
            reservation.addItem(new ReservationItem(reservation, i + 1, allocated.get(i)));
        }
        try {
            // flush 触发主键与有效明细唯一约束：同一单位不可能同时出现在两笔有效预留中
            reservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException ex) {
            // 并发竞争（候选单位被抢或同申请号并发送达）：整笔回滚，重试同内容申请即幂等返回原分配
            throw new BusinessConflictException("RESERVATION_RACE_CONFLICT",
                    "预留与并发事务冲突，整笔失败: " + request.requestNo());
        }
        // 条件 UPDATE 绕过持久化上下文，清空后重读以返回 RESERVED 最新状态
        entityManager.clear();
        return new ReservationOutcome(getRequiredWithItems(request.requestNo()), true);
    }

    /**
     * 确认发放。重复发放幂等；超过截止时间或已释放不能发放；与到期释放并发时只有一方状态变化生效。
     */
    @Transactional
    public Reservation issue(String requestNo) {
        Reservation reservation = getRequiredWithItems(requestNo);
        if (reservation.getStatus() == ReservationStatus.ISSUED) {
            // 重复发放：原样返回，库存不再变化
            return reservation;
        }
        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            throw new BusinessConflictException("RESERVATION_RELEASED",
                    "申请已到期释放，不能发放: " + requestNo);
        }
        Instant now = Instant.now(clock);
        if (!reservation.getExpireAt().isAfter(now)) {
            // 快速失败路径，数据库 CAS 同样兜底
            throw new BusinessConflictException("RESERVATION_EXPIRED",
                    "预留已超过截止时间，不能发放: " + requestNo);
        }
        // 条件更新含 expireAt > now：到点后发放必然落空，与到期释放互斥
        if (reservationRepository.compareAndSetForIssue(
                requestNo, ReservationStatus.RESERVED, ReservationStatus.ISSUED, now) != 1) {
            // 与到期释放/重复发放并发输掉：重读以数据库最终状态决定
            entityManager.clear();
            Reservation latest = getRequiredWithItems(requestNo);
            if (latest.getStatus() == ReservationStatus.ISSUED) {
                return latest;
            }
            if (latest.getStatus() == ReservationStatus.RELEASED) {
                throw new BusinessConflictException("RESERVATION_RELEASED",
                        "申请已到期释放，不能发放: " + requestNo);
            }
            throw new BusinessConflictException("RESERVATION_EXPIRED",
                    "预留已超过截止时间，不能发放: " + requestNo);
        }
        transitionItems(requestNo, ItemStatus.ALLOCATED, ItemStatus.ISSUED, reservation.getItems().size());
        transitionUnits(requestNo, reservation, UnitStatus.RESERVED, UnitStatus.ISSUED);
        log.info("预留 {} 已确认发放，单位数 {}", requestNo, reservation.getItems().size());
        // 条件 UPDATE 绕过持久化上下文，清空后重读以返回 ISSUED 最新状态
        entityManager.flush();
        entityManager.clear();
        return getRequiredWithItems(requestNo);
    }

    /**
     * 释放单笔到期预留。重复释放幂等；未到期、不存在或已终态时不做任何变更。
     *
     * @return 本次是否真正发生了释放
     */
    @Transactional
    public boolean releaseIfExpired(String requestNo) {
        Reservation reservation = reservationRepository.findWithItems(requestNo).orElse(null);
        if (reservation == null || reservation.getStatus() != ReservationStatus.RESERVED) {
            return false;
        }
        if (reservation.getExpireAt().isAfter(Instant.now(clock))) {
            return false;
        }
        return doRelease(reservation);
    }

    private boolean doRelease(Reservation reservation) {
        String requestNo = reservation.getRequestNo();
        // 条件状态机：与发放并发时恰好一方成功
        if (reservationRepository.compareAndSetStatus(
                requestNo, ReservationStatus.RESERVED, ReservationStatus.RELEASED) != 1) {
            return false;
        }
        int itemCount = reservation.getItems().size();
        transitionItems(requestNo, ItemStatus.ALLOCATED, ItemStatus.RELEASED, itemCount);
        transitionUnits(requestNo, reservation, UnitStatus.RESERVED, UnitStatus.AVAILABLE);
        log.info("预留 {} 到期未发放，已释放 {} 个单位", requestNo, itemCount);
        return true;
    }

    private void transitionItems(String requestNo, ItemStatus from, ItemStatus to, int expected) {
        int changed = itemRepository.compareAndSetStatusForReservation(requestNo, from, to);
        if (changed != expected) {
            throw new IllegalStateException("明细状态流转行数异常: " + requestNo
                    + ", expected=" + expected + ", actual=" + changed);
        }
    }

    private void transitionUnits(String requestNo, Reservation reservation, UnitStatus from, UnitStatus to) {
        List<String> barcodes = reservation.getItems().stream()
                .map(item -> item.getUnit().getBarcode())
                .toList();
        if (barcodes.isEmpty()) {
            return;
        }
        int changed = unitRepository.compareAndSetStatusBatch(barcodes, from, to);
        if (changed != barcodes.size()) {
            // 预留行已被本方 CAS 赢得，库存却未全部流转属于数据不一致：回滚整笔状态变更
            throw new IllegalStateException("库存状态流转行数异常: " + requestNo
                    + ", expected=" + barcodes.size() + ", actual=" + changed);
        }
    }

    @Transactional(readOnly = true)
    public Reservation getRequiredWithItems(String requestNo) {
        return reservationRepository.findWithItems(requestNo)
                .orElseThrow(() -> new ResourceNotFoundException("申请不存在: " + requestNo));
    }

    @Transactional(readOnly = true)
    public List<Reservation> listAll() {
        return reservationRepository.findAllWithItems();
    }

    private boolean sameContent(Reservation existing, ReservationRequest request) {
        return existing.getPatientBloodType() == request.patientBloodType()
                && existing.getComponentType() == request.componentType()
                && existing.getUnitCount() == request.unitCount()
                && Objects.equals(existing.getLatestUseAt(), request.latestUseAt());
    }
}
