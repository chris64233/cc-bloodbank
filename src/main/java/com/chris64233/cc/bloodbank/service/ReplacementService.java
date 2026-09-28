package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.BloodComponent;
import com.chris64233.cc.bloodbank.domain.ClinicalRequest;
import com.chris64233.cc.bloodbank.domain.ComponentStatus;
import com.chris64233.cc.bloodbank.domain.AboGroup;
import com.chris64233.cc.bloodbank.domain.Recall;
import com.chris64233.cc.bloodbank.domain.ReplacementItem;
import com.chris64233.cc.bloodbank.domain.ReplacementStatus;
import com.chris64233.cc.bloodbank.domain.ReplacementTask;
import com.chris64233.cc.bloodbank.domain.Reservation;
import com.chris64233.cc.bloodbank.domain.ReservationItem;
import com.chris64233.cc.bloodbank.domain.ReservationStatus;
import com.chris64233.cc.bloodbank.domain.RhFactor;
import com.chris64233.cc.bloodbank.repository.BloodComponentRepository;
import com.chris64233.cc.bloodbank.repository.RecallRepository;
import com.chris64233.cc.bloodbank.repository.ReplacementItemRepository;
import com.chris64233.cc.bloodbank.repository.ReplacementTaskRepository;
import com.chris64233.cc.bloodbank.repository.ReservationItemRepository;
import com.chris64233.cc.bloodbank.repository.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 换血核心服务。一笔换血任务要么一次性替换该召回影响的全部单位，要么不动任何库存：
 * 合格替代品不足时保留原预留关系（被召回单位隔离、预留不可发放），任务停留 PENDING 可重试。
 *
 * 并发协议：与发放/到期释放/人工终止相同，一律先锁预留行再锁任务行（统一加锁顺序避免死锁），
 * 候选替代单位用 SELECT ... FOR UPDATE + AVAILABLE 条件更新兜底，杜绝被其他申请重复占用。
 */
@Service
public class ReplacementService {

    private final RecallRepository recallRepository;
    private final ReplacementTaskRepository taskRepository;
    private final ReplacementItemRepository replacementItemRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationItemRepository itemRepository;
    private final BloodComponentRepository componentRepository;
    private final ReservationService reservationService;
    private final ReplacementQuerySupport querySupport;
    private final Clock clock;

    public ReplacementService(RecallRepository recallRepository,
                              ReplacementTaskRepository taskRepository,
                              ReplacementItemRepository replacementItemRepository,
                              ReservationRepository reservationRepository,
                              ReservationItemRepository itemRepository,
                              BloodComponentRepository componentRepository,
                              ReservationService reservationService,
                              ReplacementQuerySupport querySupport,
                              Clock clock) {
        this.recallRepository = recallRepository;
        this.taskRepository = taskRepository;
        this.replacementItemRepository = replacementItemRepository;
        this.reservationRepository = reservationRepository;
        this.itemRepository = itemRepository;
        this.componentRepository = componentRepository;
        this.reservationService = reservationService;
        this.querySupport = querySupport;
        this.clock = clock;
    }

    /** 按任务 id 执行换血；终态任务/重复执行直接返回已有结果。 */
    @Transactional
    public ReplacementTaskView executeTask(Long taskId) {
        ReplacementTask referenced = taskRepository.findById(taskId)
                .orElseThrow(() -> new NotFoundException("换血任务 " + taskId + " 不存在"));
        return executeLocked(referenced.getRecall().getEventNo(), referenced.getReservation().getId());
    }

    /** 按外部事件号 + 申请号执行换血（幂等：同事件同申请只有一条任务）。 */
    @Transactional
    public ReplacementTaskView executeForRequest(String eventNo, String requestNo) {
        Recall recall = recallRepository.findByEventNo(eventNo)
                .orElseThrow(() -> new NotFoundException("召回事件 " + eventNo + " 不存在"));
        Reservation reservation = reservationRepository.findByRequest_RequestNo(requestNo)
                .orElseThrow(() -> new NotFoundException("申请号 " + requestNo + " 不存在预留"));
        return executeLocked(recall.getEventNo(), reservation.getId());
    }

    /**
     * 调用方已提供定位信息；此处按"预留行 -> 任务行"顺序加锁后执行。
     */
    private ReplacementTaskView executeLocked(String eventNo, Long reservationId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId).orElseThrow();
        ReplacementTask task = taskRepository
                .findByRecall_EventNoAndReservation_Request_RequestNo(eventNo,
                        reservation.getRequest().getRequestNo())
                .orElseThrow(() -> new NotFoundException(
                        "召回 " + eventNo + " 对申请 " + reservation.getRequest().getRequestNo() + " 无换血任务"));
        // 重新行锁任务（顺序：预留 -> 任务），保证重复/并发执行串行化
        task = taskRepository.findByIdForUpdate(task.getId()).orElseThrow();
        Instant now = clock.instant();

        // 终态任务幂等：重复执行返回已有结果，库存不再变化
        if (task.getStatus() != ReplacementStatus.PENDING) {
            return querySupport.viewOf(task);
        }

        // 预留已不处于 RESERVED（与到期释放/发放/终止竞争落败）：作废任务，记录原因
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            task.markAbandoned(now, "预留已处于 " + reservation.getStatus() + " 状态，无需换血");
            return querySupport.viewOf(task);
        }
        // 预留已到期：与"到期释放"语义一致，先释放再作废任务；条件流转会清空持久化上下文，之后重新读取
        if (!now.isBefore(reservation.getExpiresAt())) {
            reservationService.releaseLocked(reservation, now);
            return reloadView(task.getId());
        }

        Recall recall = recallRepository.findByIdForUpdate(task.getRecall().getId()).orElseThrow();
        List<ReservationItem> activeItems = itemRepository.findActiveByReservationIdWithComponent(reservationId);
        // 本次召回在该预留中仍占用的被召回单位（其他召回的单位不在本任务范围内）
        List<ReservationItem> affected = activeItems.stream()
                .filter(i -> i.getComponent().getBatchNo().equals(recall.getBatchNo()))
                .filter(i -> i.getComponent().getStatus() == ComponentStatus.RECALLED)
                .sorted(java.util.Comparator
                        .comparing((ReservationItem i) -> i.getComponent().getExpiresAt())
                        .thenComparing(i -> i.getComponent().getBarcode()))
                .toList();

        if (affected.isEmpty()) {
            // 无可换单位（理论上仅在边界情况下发生）：直接完成并按当前占用重算阻断标记
            task.markCompleted(now);
            reservation.setIssueBlocked(stillBlocked(reservationId));
            return querySupport.viewOf(task);
        }

        ClinicalRequest req = reservation.getRequest();
        Set<AboGroup> aboOk = req.getComponentType().aboCompatibleDonors(req.getPatientAbo());
        Set<RhFactor> rhOk = RhCompatibility.acceptableDonorRh(req.getPatientRh());
        List<Long> occupiedIds = activeItems.stream().map(i -> i.getComponent().getId()).toList();
        List<BloodComponent> candidates = componentRepository.lockReplacementCandidates(
                req.getComponentType(), aboOk, rhOk, now, req.getLatestUseTime(), occupiedIds);

        if (candidates.size() < affected.size()) {
            // 库存不足：不换任何一个单位，原预留保留但继续不可发放，记录未解决原因供查询展示
            task.noteUnresolved("合格替代品不足：需要 " + affected.size()
                    + "，当前可用 " + candidates.size() + "（须成分/ABO/Rh 相容且不早于最晚使用时间）");
            reservation.setIssueBlocked(true);
            return querySupport.viewOf(task);
        }

        // 全部替代品齐备：同一事务内一次性完成替换，任一条件更新失败整笔回滚
        List<BloodComponent> chosen = candidates.subList(0, affected.size());
        List<ReplacementItem> mappings =
                replacementItemRepository.findByTaskIdWithComponents(task.getId()).stream()
                        .filter(i -> i.getReplacementComponent() == null)
                        .sorted(java.util.Comparator
                                .comparing((ReplacementItem i) -> i.getRemovedComponent().getExpiresAt())
                                .thenComparing(i -> i.getRemovedComponent().getBarcode()))
                        .toList();

        for (int idx = 0; idx < affected.size(); idx++) {
            BloodComponent replacement = chosen.get(idx);
            // 条件更新兜底：并发下被其他申请抢走的候选在此必然失败，整笔回滚——绝不只换一部分
            if (componentRepository.reserveIfAvailable(replacement.getId()) != 1) {
                throw new InsufficientStockException(
                        "替代单位 " + replacement.getBarcode() + " 已被并发占用");
            }
            ReservationItem oldItem = affected.get(idx);
            oldItem.markReplaced(recall);                 // 旧明细置 REPLACED（被召回单位保持 RECALLED 隔离）
            reservation.addItem(new ReservationItem(replacement)); // 新单位 ACTIVE 占用
            ReplacementItem mapping = mappings.stream()
                    .filter(m -> m.getRemovedComponent().getId().equals(oldItem.getComponent().getId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("换血明细缺失"));
            mapping.resolve(replacement);
        }

        task.markCompleted(now);
        // 若该预留还受其他未完成召回影响，继续阻断发放
        reservation.setIssueBlocked(stillBlocked(reservationId));
        return querySupport.viewOf(task);
    }

    /** 重新读取任务视图（条件流转清空持久化上下文后使用） */
    private ReplacementTaskView reloadView(Long taskId) {
        return querySupport.viewOf(taskRepository.findById(taskId).orElseThrow());
    }

    /** 完成替换后重算：当前仍占用 RECALLED 单位（来自其他未完成召回）则继续不可发放 */
    private boolean stillBlocked(Long reservationId) {
        return itemRepository.findActiveByReservationIdWithComponent(reservationId).stream()
                .anyMatch(i -> i.getComponent().getStatus() == ComponentStatus.RECALLED);
    }

    /**
     * 人工确认无法换血：终止整笔预留。当前占用的合格单位（含历次换上的替代品）释放回库，
     * 被召回单位保持 RECALLED 隔离；该召回下未完成任务记 TERMINATED。终态幂等。
     *
     * @param eventNo 触发终止的召回事件号：该申请在此召回下必须存在换血任务
     */
    @Transactional
    public ReservationView terminateUnresolvable(String requestNo, String eventNo, String reason) {
        Reservation reservation = reservationRepository.findByRequestNoForUpdate(requestNo)
                .orElseThrow(() -> new NotFoundException("申请号 " + requestNo + " 不存在预留"));
        Instant now = clock.instant();

        ReplacementTask task = taskRepository
                .findByRecall_EventNoAndReservation_Request_RequestNo(eventNo, requestNo)
                .orElseThrow(() -> new NotFoundException(
                        "召回 " + eventNo + " 对申请 " + requestNo + " 无换血任务"));

        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            // 已终态：幂等返回当前状态
            return reservationService.viewOf(reservation.getId());
        }
        if (!reservation.isIssueBlocked()) {
            throw new BadRequestException("申请 " + requestNo + " 无未完成召回换血，不能按无法换血终止");
        }
        if (reservationRepository.markTerminatedIfReserved(reservation.getId(), now) != 1) {
            return reservationService.viewOf(reservation.getId());
        }
        for (ReservationItem item : itemRepository.findActiveByReservationIdWithComponent(reservation.getId())) {
            item.markReleased();
            BloodComponent c = item.getComponent();
            if (recallRepository.existsByBatchNoAndActivatedTrue(c.getBatchNo())) {
                c.setStatus(ComponentStatus.RECALLED);
            } else if (c.getStatus() == ComponentStatus.RESERVED) {
                c.setStatus(ComponentStatus.AVAILABLE);
            }
        }
        String note = (reason == null || reason.isBlank() ? "人工确认无法换血：" : reason + "（")
                + "事件 " + eventNo + ")";
        // 仅终止本召回下仍在 PENDING 的任务；其他召回的任务随预留终态作废
        taskRepository.terminateByIdIfPending(task.getId(), now, note);
        taskRepository.abandonPendingForReservation(reservation.getId(), now,
                "预留已因事件 " + eventNo + " 人工终止");
        return reservationService.viewOf(reservation.getId());
    }
}
