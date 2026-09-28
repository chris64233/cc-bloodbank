package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.AboGroup;
import com.chris64233.cc.bloodbank.domain.BloodComponent;
import com.chris64233.cc.bloodbank.domain.ClinicalRequest;
import com.chris64233.cc.bloodbank.domain.ComponentStatus;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.Reservation;
import com.chris64233.cc.bloodbank.domain.ReservationItem;
import com.chris64233.cc.bloodbank.domain.ReservationStatus;
import com.chris64233.cc.bloodbank.domain.RhFactor;
import com.chris64233.cc.bloodbank.repository.BloodComponentRepository;
import com.chris64233.cc.bloodbank.repository.ClinicalRequestRepository;
import com.chris64233.cc.bloodbank.repository.ReplacementItemRepository;
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
 * 发放/到期释放靠预留行悲观锁 + 条件状态流转（含状态版本）保证只有一个生效；
 * 召回冻结的单位（RECALLED）不能发放，到期释放也不会退回库存。
 */
@Service
public class ReservationService {

    private final BloodComponentRepository componentRepository;
    private final ClinicalRequestRepository requestRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationItemRepository itemRepository;
    private final ReplacementTaskRepository taskRepository;
    private final ReplacementItemRepository replacementItemRepository;
    private final Clock clock;
    private final Duration reservationTtl;

    public ReservationService(BloodComponentRepository componentRepository,
                              ClinicalRequestRepository requestRepository,
                              ReservationRepository reservationRepository,
                              ReservationItemRepository itemRepository,
                              ReplacementTaskRepository taskRepository,
                              ReplacementItemRepository replacementItemRepository,
                              Clock clock,
                              @Value("${bloodbank.reservation-ttl:PT24H}") Duration reservationTtl) {
        this.componentRepository = componentRepository;
        this.requestRepository = requestRepository;
        this.reservationRepository = reservationRepository;
        this.itemRepository = itemRepository;
        this.taskRepository = taskRepository;
        this.replacementItemRepository = replacementItemRepository;
        this.clock = clock;
        this.reservationTtl = reservationTtl;
    }

    /**
     * 提交临床申请并原子预留：单位不足或任一单位被并发占用时整笔失败回滚。
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
            // 幂等重放：返回原分配（无论预留当前状态）
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
            // 数据库条件更新兜底：并发下已被占用或已被召回冻结的单位在这里必然失败，整笔回滚
            if (componentRepository.reserveIfAvailable(c.getId()) != 1) {
                throw new InsufficientStockException("单位 " + c.getBarcode() + " 已被并发预留或召回");
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
     * 确认发放：仅生效中的预留且在截止前、未因召回暂停时可发放；到期未发放则转为释放。
     * 被召回单位绝不能发放：存在待处理换血任务（blocked）或当前明细含 RECALLED 单位时拒绝发放。
     * 重复发放幂等，返回当前状态，不重复改变库存。
     */
    @Transactional
    public ReservationView issue(String requestNo) {
        Reservation locked = reservationRepository.findByRequestNoForUpdate(requestNo)
                .orElseThrow(() -> new NotFoundException("申请号 " + requestNo + " 不存在预留"));
        Long id = locked.getId();
        Instant now = clock.instant();

        if (locked.getStatus() != ReservationStatus.RESERVED) {
            return viewOf(id);
        }
        long version = locked.getStateVersion();
        List<ReservationItem> activeItems = itemRepository.findActiveByReservationId(id);
        if (!now.isBefore(locked.getExpiresAt())) {
            // 已到期：按到期释放处理，与后台清理语义一致
            releaseLocked(id, now, version);
            return viewOf(id);
        }
        if (locked.isBlocked() || activeItems.stream().anyMatch(i -> i.getComponent().getStatus() == ComponentStatus.RECALLED)) {
            throw new RecallBlockedException("申请号 " + requestNo + " 的预留含被召回单位，须完成换血后才能发放");
        }
        List<Long> componentIds = activeItems.stream().map(i -> i.getComponent().getId()).toList();
        if (reservationRepository.markIssuedIfReserved(id, now, version) != 1) {
            // 条件更新返回 0：并发流转已先生效或持锁期间发生换血，直接返回当前状态
            return viewOf(id);
        }
        // 预留已条件流转为 ISSUED（上下文已清空）。仅仍为 RESERVED 的单位会发放；
        // 若有单位在并发下被召回冻结，受影响行数必然少于当前单位数，
        // 抛异常回滚整笔（含发放状态），被召回单位绝不发放。
        int issued = componentRepository.issueReservedIn(componentIds);
        if (issued != componentIds.size()) {
            throw new RecallBlockedException("申请号 " + requestNo + " 的单位在发放时被召回，已阻止发放");
        }
        for (Long itemId : activeItems.stream().map(ReservationItem::getId).toList()) {
            itemRepository.findById(itemId).ifPresent(item -> item.setActive(false));
        }
        return viewOf(id);
    }

    /**
     * 释放所有到期未发放的预留，返回释放笔数。重复执行幂等。
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
            if (releaseLocked(id, now, locked.get().getStateVersion())) {
                released++;
            }
        }
        return released;
    }

    /**
     * 条件释放：状态版本不匹配（持锁期间完成换血）则放弃，避免把替代单位退回库存。
     * 释放时仅仍为 RESERVED 的合格单位回到 AVAILABLE，RECALLED 单位由条件更新自动跳过、保持冻结；
     * 当前明细全部停用为历史，待处理换血任务作废。供到期清理与换血到期分支共用。
     */
    public boolean releaseLocked(Long reservationId, Instant now, long version) {
        List<Long> activeItemIds = itemRepository.findActiveByReservationId(reservationId).stream()
                .map(ReservationItem::getId).toList();
        List<Long> componentIds = itemRepository.findActiveByReservationId(reservationId).stream()
                .map(i -> i.getComponent().getId()).toList();
        if (reservationRepository.markReleasedIfReserved(reservationId, now, version) != 1) {
            return false;
        }
        // 预留已条件流转（上下文已清空）：仅仍为 RESERVED 的合格单位回库，RECALLED 自动跳过
        componentRepository.releaseReservedIn(componentIds);
        for (Long itemId : activeItemIds) {
            itemRepository.findById(itemId).ifPresent(item -> item.setActive(false));
        }
        taskRepository.markPendingObsolete(reservationId, now);
        return true;
    }

    @Transactional(readOnly = true)
    public RequestView getRequest(String requestNo) {
        ClinicalRequest req = requestRepository.findByRequestNo(requestNo)
                .orElseThrow(() -> new NotFoundException("申请号 " + requestNo + " 不存在"));
        ReservationView reservation = reservationRepository.findByRequest_RequestNo(requestNo)
                .map(r -> viewOf(r.getId()))
                .orElse(null);
        return RequestView.of(req, reservation);
    }

    private ReservationView viewOf(Long reservationId) {
        Reservation r = reservationRepository.findById(reservationId).orElseThrow();
        List<ReservationItemView> items = itemRepository.findActiveByReservationId(reservationId)
                .stream()
                .map(i -> ReservationItemView.from(i.getComponent()))
                .toList();
        List<ReplacementItemView> replacements = replacementItemRepository
                .findByReservationIdWithDetails(reservationId)
                .stream()
                .map(ReplacementItemView::from)
                .toList();
        return ReservationView.of(r, items, replacements);
    }
}
