package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.AboGroup;
import com.chris64233.cc.bloodbank.domain.BloodComponent;
import com.chris64233.cc.bloodbank.domain.ClinicalRequest;
import com.chris64233.cc.bloodbank.domain.ComponentStatus;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.ItemState;
import com.chris64233.cc.bloodbank.domain.Reservation;
import com.chris64233.cc.bloodbank.domain.ReservationItem;
import com.chris64233.cc.bloodbank.domain.ReservationStatus;
import com.chris64233.cc.bloodbank.domain.RhFactor;
import com.chris64233.cc.bloodbank.repository.BloodComponentRepository;
import com.chris64233.cc.bloodbank.repository.ClinicalRequestRepository;
import com.chris64233.cc.bloodbank.repository.RecallRepository;
import com.chris64233.cc.bloodbank.repository.ReplacementTaskRepository;
import com.chris64233.cc.bloodbank.repository.ReservationItemRepository;
import com.chris64233.cc.bloodbank.repository.ReservationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 预留核心服务。所有状态与库存变更都在同一事务内完成：
 * 并发预留靠候选行悲观锁 + 状态条件更新兜底；
 * 发放/到期释放靠预留行悲观锁 + 条件状态流转保证只有一个生效；
 * 召回换血与发放/释放互斥：三者都先锁预留行，且发放额外要求 issueBlocked=false。
 */
@Service
public class ReservationService {

    private final BloodComponentRepository componentRepository;
    private final ClinicalRequestRepository requestRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationItemRepository itemRepository;
    private final ReplacementTaskRepository taskRepository;
    private final RecallRepository recallRepository;
    private final ReplacementQuerySupport querySupport;
    private final Clock clock;
    private final Duration reservationTtl;

    public ReservationService(BloodComponentRepository componentRepository,
                              ClinicalRequestRepository requestRepository,
                              ReservationRepository reservationRepository,
                              ReservationItemRepository itemRepository,
                              ReplacementTaskRepository taskRepository,
                              RecallRepository recallRepository,
                              ReplacementQuerySupport querySupport,
                              Clock clock,
                              @Value("${bloodbank.reservation-ttl:PT24H}") Duration reservationTtl) {
        this.componentRepository = componentRepository;
        this.requestRepository = requestRepository;
        this.reservationRepository = reservationRepository;
        this.itemRepository = itemRepository;
        this.taskRepository = taskRepository;
        this.recallRepository = recallRepository;
        this.querySupport = querySupport;
        this.clock = clock;
        this.reservationTtl = reservationTtl;
    }

    /**
     * 提交临床申请并原子预留：单位不足或任一单位被并发占用/批次刚被召回时整笔失败回滚。
     * 相同申请号重放：内容一致返回原分配，内容不同抛冲突。
     */
    @Transactional
    public ReservationView createReservation(String requestNo, AboGroup patientAbo, RhFactor patientRh,
                                             ComponentType componentType, int unitCount, Instant latestUseTime) {
        Instant now = clock.instant();
        if (unitCount < 1) {
            throw new BadRequestException("单位数必须为正整数");
        }
        if (!latestUseTime.isAfter(now)) {
            throw new BadRequestException("最晚使用时间必须晚于当前时间");
        }

        var existing = requestRepository.findByRequestNo(requestNo);
        if (existing.isPresent()) {
            ClinicalRequest req = existing.get();
            if (!req.sameContentAs(patientAbo, patientRh, componentType, unitCount, latestUseTime)) {
                throw new RequestConflictException("申请号 " + requestNo + " 已存在且申请内容不一致");
            }
            // 幂等重放：返回原分配（无论预留当前处于何种状态）
            Reservation reservation = reservationRepository.findByRequest_RequestNo(requestNo).orElseThrow();
            return viewOf(reservation.getId());
        }

        Set<AboGroup> aboOk = componentType.aboCompatibleDonors(patientAbo);
        Set<RhFactor> rhOk = RhCompatibility.acceptableDonorRh(patientRh);
        List<BloodComponent> candidates =
                componentRepository.lockAvailableCandidates(componentType, aboOk, rhOk, now, latestUseTime);
        if (candidates.size() < unitCount) {
            throw new InsufficientStockException(
                    "相容可用单位不足：需要 " + unitCount + "，可用 " + candidates.size());
        }
        List<BloodComponent> chosen = candidates.subList(0, unitCount);
        for (BloodComponent c : chosen) {
            // 数据库条件更新兜底：并发下已被占用或批次刚被召回的单位在这里必然失败，整笔回滚
            if (componentRepository.reserveIfAvailable(c.getId()) != 1) {
                throw new InsufficientStockException("单位 " + c.getBarcode() + " 已被并发预留或已被召回");
            }
        }

        ClinicalRequest req = requestRepository.save(
                new ClinicalRequest(requestNo, patientAbo, patientRh, componentType, unitCount, latestUseTime));
        Reservation reservation = new Reservation(req, now, now.plus(reservationTtl));
        for (BloodComponent c : chosen) {
            reservation.addItem(new ReservationItem(c));
        }
        reservationRepository.save(reservation);
        return viewOf(reservation.getId());
    }

    /**
     * 确认发放：仅生效中、未被召回阻断且在截止前的预留可发放；到期未发放则转为释放。
     * 含被召回单位且换血未完成时拒绝发放（被召回单位绝不出库）。重复发放幂等。
     */
    @Transactional
    public ReservationView issue(String requestNo) {
        Reservation locked = reservationRepository.findByRequestNoForUpdate(requestNo)
                .orElseThrow(() -> new NotFoundException("申请号 " + requestNo + " 不存在预留"));
        Long id = locked.getId();
        Instant now = clock.instant();

        if (locked.getStatus() == ReservationStatus.RESERVED) {
            if (!now.isBefore(locked.getExpiresAt())) {
                // 已到期：按到期释放处理，与后台清理语义一致
                releaseLocked(locked, now);
            } else if (locked.isIssueBlocked()) {
                // 与召回换血互斥后的稳定状态：被召回单位未全部替换，禁止发放
                throw new IssueBlockedException(
                        "申请 " + requestNo + " 含被召回单位且换血尚未完成，不能发放");
            } else if (reservationRepository.markIssuedIfReserved(id, now) == 1) {
                for (ReservationItem item : itemRepository.findActiveByReservationIdWithComponent(id)) {
                    item.getComponent().setStatus(ComponentStatus.ISSUED);
                }
            }
            // 条件更新返回 0：并发流转已先生效，直接返回当前状态
        }
        return viewOf(id);
    }

    /**
     * 释放所有到期未发放的预留，返回释放笔数。重复执行幂等。
     * 被召回阻断的预留到期同样释放：合格单位回库、被召回单位留库隔离。
     */
    @Transactional
    public int releaseExpired() {
        Instant now = clock.instant();
        int released = 0;
        for (Long id : reservationRepository.findExpiredReservedIds(now)) {
            var locked = reservationRepository.findByIdForUpdate(id);
            if (locked.isEmpty()
                    || locked.get().getStatus() != ReservationStatus.RESERVED
                    || locked.get().getExpiresAt().isAfter(now)) {
                continue;
            }
            if (releaseLocked(locked.get(), now)) {
                released++;
            }
        }
        return released;
    }

    /**
     * 到期/发放时释放一笔已行锁的预留：
     * 合格占用单位回 AVAILABLE，被召回占用单位保持 RECALLED 隔离；全部 ACTIVE 明细标记 RELEASED，
     * 并作废该预留全部未完成的换血任务，使换血完成后不可能再被旧释放退回库存。
     */
    boolean releaseLocked(Reservation locked, Instant now) {
        Long reservationId = locked.getId();
        if (reservationRepository.markReleasedIfReserved(reservationId, now) != 1) {
            return false;
        }
        for (ReservationItem item : itemRepository.findActiveByReservationIdWithComponent(reservationId)) {
            item.markReleased();
            BloodComponent c = item.getComponent();
            // 并发窗口兜底：本事务持有预留行锁后，若批次在释放期间被召回，单位隔离而非回库
            if (recallRepository.existsByBatchNoAndActivatedTrue(c.getBatchNo())) {
                c.setStatus(ComponentStatus.RECALLED);
            } else if (c.getStatus() == ComponentStatus.RESERVED) {
                c.setStatus(ComponentStatus.AVAILABLE);
            }
        }
        // 作废全部未完成换血任务：预留既已释放，换血不得再改变库存
        taskRepository.abandonPendingForReservation(reservationId, now, "预留已到期释放");
        return true;
    }

    @Transactional(readOnly = true)
    public RequestView getRequest(String requestNo) {
        ClinicalRequest req = requestRepository.findByRequestNo(requestNo)
                .orElseThrow(() -> new NotFoundException("申请号 " + requestNo + " 不存在"));
        ReservationView reservation = reservationRepository.findByRequest_RequestNo(requestNo)
                .map(r -> viewOf(r.getId()))
                .orElse(null);
        var tasks = reservation == null
                ? java.util.List.<ReplacementTaskView>of()
                : querySupport.taskViewsForReservation(reservation.reservationId());
        return RequestView.of(req, reservation, tasks);
    }

    /** 当前占用视图：只包含 ACTIVE 明细（已替换/已释放的历史行不出现在预留明细中） */
    ReservationView viewOf(Long reservationId) {
        Reservation r = reservationRepository.findById(reservationId).orElseThrow();
        List<ReservationItemView> items = itemRepository.findActiveByReservationIdWithComponent(reservationId)
                .stream()
                .map(i -> ReservationItemView.from(i.getComponent()))
                .toList();
        return ReservationView.of(r, items);
    }
}
