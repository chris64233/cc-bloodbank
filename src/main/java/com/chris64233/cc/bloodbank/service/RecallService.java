package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.BatchRecall;
import com.chris64233.cc.bloodbank.domain.BloodComponent;
import com.chris64233.cc.bloodbank.domain.ComponentStatus;
import com.chris64233.cc.bloodbank.domain.Reservation;
import com.chris64233.cc.bloodbank.domain.ReservationItem;
import com.chris64233.cc.bloodbank.domain.ReservationStatus;
import com.chris64233.cc.bloodbank.domain.ReplacementItem;
import com.chris64233.cc.bloodbank.domain.ReplacementStatus;
import com.chris64233.cc.bloodbank.domain.ReplacementTask;
import com.chris64233.cc.bloodbank.repository.BatchRecallRepository;
import com.chris64233.cc.bloodbank.repository.BloodComponentRepository;
import com.chris64233.cc.bloodbank.repository.ReplacementItemRepository;
import com.chris64233.cc.bloodbank.repository.ReplacementTaskRepository;
import com.chris64233.cc.bloodbank.repository.ReservationItemRepository;
import com.chris64233.cc.bloodbank.repository.ReservationRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 批次召回与召回换血服务。
 *
 * <p>召回处理分两步提交，避免与新建预留/发放交叉加锁死锁：
 * <ol>
 *   <li>{@link #lockAndFreeze} 独立事务：锁定并冻结批次在库/已预留单位（AVAILABLE/RESERVED -> RECALLED），
 *       一旦提交批次立即不能参与新预留、被召回单位也不能发放；</li>
 *   <li>{@link #createTasksForRecall} 独立事务：按申请锁定预留，扫描被冻结单位当前仍占用它的未发放预留，
 *       为每笔申请生成换血任务。已发放/已释放的单位 active 明细已不存在，不会生成任务。</li>
 * </ol>
 *
 * <p>换血以预留为单位：先锁预留行（与发放/到期释放互斥），候选单位行锁 + 条件预留兜底；
 * 全部受影响单位都找到替代品才一次性提交，库存不足时保留原预留关系、标记为不可发放，绝不换一半。
 */
@Service
public class RecallService {

    private final BatchRecallRepository recallRepository;
    private final BloodComponentRepository componentRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationItemRepository itemRepository;
    private final ReplacementTaskRepository taskRepository;
    private final ReplacementItemRepository replacementItemRepository;
    private final ReservationService reservationService;
    private final Clock clock;
    private final RecallService self;

    public RecallService(BatchRecallRepository recallRepository,
                         BloodComponentRepository componentRepository,
                         ReservationRepository reservationRepository,
                         ReservationItemRepository itemRepository,
                         ReplacementTaskRepository taskRepository,
                         ReplacementItemRepository replacementItemRepository,
                         ReservationService reservationService,
                         Clock clock,
                         @Lazy RecallService self) {
        this.recallRepository = recallRepository;
        this.componentRepository = componentRepository;
        this.reservationRepository = reservationRepository;
        this.itemRepository = itemRepository;
        this.taskRepository = taskRepository;
        this.replacementItemRepository = replacementItemRepository;
        this.reservationService = reservationService;
        this.clock = clock;
        this.self = self;
    }

    /**
     * 发布召回（外部事件号唯一）：同号同内容重复提交返回原结果，同号异内容返回 409。
     * 已到生效时间则立即激活；返回的视图反映当前处理状态。
     */
    public RecallView publishRecall(String eventNo, String batchNo, String reason, Instant effectiveAt) {
        if (eventNo == null || eventNo.isBlank()) {
            throw new BadRequestException("事件号不能为空");
        }
        if (batchNo == null || batchNo.isBlank()) {
            throw new BadRequestException("召回批次不能为空");
        }
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("召回原因不能为空");
        }
        if (effectiveAt == null) {
            throw new BadRequestException("生效时间不能为空");
        }

        BatchRecall recall = recallRepository.findByEventNo(eventNo).orElse(null);
        if (recall == null) {
            try {
                recall = recallRepository.saveAndFlush(
                        new BatchRecall(eventNo, batchNo, reason, effectiveAt, clock.instant()));
            } catch (RuntimeException dup) {
                // 并发同事件号提交：唯一约束兜底，重读返回已有记录
                recall = recallRepository.findByEventNo(eventNo).orElseThrow();
                if (!recall.sameContentAs(batchNo, reason, effectiveAt)) {
                    throw new RequestConflictException("事件号 " + eventNo + " 已存在且召回内容不一致");
                }
                return activateIfDue(recall);
            }
        } else if (!recall.sameContentAs(batchNo, reason, effectiveAt)) {
            throw new RequestConflictException("事件号 " + eventNo + " 已存在且召回内容不一致");
        }
        return activateIfDue(recall);
    }

    private RecallView activateIfDue(BatchRecall recall) {
        if (!recall.isActivated() && !clock.instant().isBefore(recall.getEffectiveAt())) {
            activateRecall(recall.getId());
        }
        return getRecall(recall.getEventNo());
    }

    /**
     * 激活所有已到生效时间的召回（定时任务与手动触发共用），返回本次新激活数。
     * 非事务编排：每个召回的"冻结"与"建任务"各自独立事务提交。
     */
    public int activateDueRecalls() {
        Instant now = clock.instant();
        List<BatchRecall> due = recallRepository.findDueRecalls(now);
        int activated = 0;
        for (BatchRecall d : due) {
            activateRecall(d.getId());
            activated++;
        }
        return activated;
    }

    /**
     * 激活单个召回：非事务编排，冻结与建任务分两个事务提交，保证冻结先行可见。
     */
    public void activateRecall(Long recallId) {
        BatchRecall recall = self.lockAndFreeze(recallId);
        if (recall == null) {
            return;
        }
        self.createTasksForRecall(recallId);
    }

    /**
     * 第一步事务：行锁召回记录（幂等），冻结批次单位。
     * 返回召回供后续建任务；已激活则返回 null。
     */
    @Transactional
    public BatchRecall lockAndFreeze(Long recallId) {
        BatchRecall recall = recallRepository.findByIdForUpdate(recallId).orElseThrow();
        if (recall.isActivated()) {
            return null;
        }
        Instant now = clock.instant();
        // 冻结该批次当前在库或已被预留的单位；已发放单位不动
        List<BloodComponent> batch = componentRepository.lockBatchForRecall(recall.getBatchNo());
        for (BloodComponent c : batch) {
            c.setStatus(ComponentStatus.RECALLED);
        }
        recall.markActivated(now);
        return recall;
    }

    /**
     * 第二步事务：扫描被冻结单位当前仍占用它的未发放预留，按申请锁定并生成换血任务。
     * 每个 (预留, 召回) 至多一个任务；并发重复激活由唯一约束兜底。
     */
    @Transactional
    public void createTasksForRecall(Long recallId) {
        BatchRecall recall = recallRepository.findById(recallId).orElseThrow();
        Instant now = clock.instant();

        List<Long> recalledIds = componentRepository.findByBatchNoOrderByIdAsc(recall.getBatchNo()).stream()
                .filter(c -> c.getStatus() == ComponentStatus.RECALLED)
                .map(BloodComponent::getId)
                .toList();
        if (recalledIds.isEmpty()) {
            return;
        }
        // 仍被生效预留占用的被冻结单位（已发放/已释放的预留 active 明细已不再指向它们）
        List<ReservationItem> held = itemRepository.findActiveByComponentIds(recalledIds);
        Set<Long> reservationIds = held.stream()
                .map(i -> i.getReservation().getId())
                .collect(Collectors.toCollection(java.util.TreeSet::new));

        for (Long reservationId : reservationIds) {
            Reservation locked = reservationRepository.findByIdForUpdate(reservationId).orElseThrow();
            if (locked.getStatus() != ReservationStatus.RESERVED) {
                continue;
            }
            try {
                taskRepository.saveAndFlush(new ReplacementTask(locked, recall, now));
            } catch (RuntimeException dup) {
                // 同 (预留, 召回) 任务已存在，幂等忽略
            }
        }
    }

    /**
     * 为一笔申请执行换血（处理该预留全部待处理任务）。
     * 全部受影响单位找到替代品才一次性完成；库存不足保留原关系并标记不可发放。
     * 同一任务重复执行返回已有结果。
     */
    @Transactional
    public ReplacementTaskView replaceForRequest(String requestNo) {
        Reservation reservation = reservationRepository.findByRequestNoForUpdate(requestNo)
                .orElseThrow(() -> new NotFoundException("申请号 " + requestNo + " 不存在预留"));
        Long reservationId = reservation.getId();
        List<ReplacementTask> pending = taskRepository.findPendingByReservationId(reservationId);

        // 没有待处理任务：返回该预留最近一个任务的已有结果（若存在）
        if (pending.isEmpty()) {
            return latestTaskView(reservationId);
        }

        Instant now = clock.instant();
        // 预留已到期未发放：复用到期释放语义（合格单位回库、被召回单位保持冻结、明细停用、任务作废）。
        // 若预留已被并发的发放/释放流转走（releaseLocked 未生效），仍把遗留的待处理任务幂等作废。
        if (!now.isBefore(reservation.getExpiresAt())
                || reservation.getStatus() != ReservationStatus.RESERVED) {
            reservationService.releaseLocked(reservationId, now, reservation.getStateVersion());
            taskRepository.markPendingObsolete(reservationId, now);
            return taskView(taskRepository.findById(pending.get(0).getId()).orElseThrow());
        }

        List<ReservationItem> activeItems = itemRepository.findActiveByReservationId(reservationId);
        List<ReservationItem> recalledItems = activeItems.stream()
                .filter(i -> i.getComponent().getStatus() == ComponentStatus.RECALLED)
                .toList();

        if (recalledItems.isEmpty()) {
            // 受影响单位已不在当前分配中（理论上不会发生于待处理任务），任务作废
            for (ReplacementTask t : pending) {
                t.setStatus(ReplacementStatus.OBSOLETE);
                t.setProcessedAt(now);
            }
            reservation.setBlocked(false);
            return taskView(pending.get(0));
        }

        Set<Long> heldGoodIds = activeItems.stream()
                .filter(i -> i.getComponent().getStatus() != ComponentStatus.RECALLED)
                .map(i -> i.getComponent().getId())
                .collect(Collectors.toCollection(HashSet::new));

        var request = reservation.getRequest();
        var aboOk = request.getComponentType().aboCompatibleDonors(request.getPatientAbo());
        var rhOk = RhCompatibility.acceptableDonorRh(request.getPatientRh());
        List<BloodComponent> candidates = componentRepository.lockAvailableCandidates(
                request.getComponentType(), aboOk, rhOk, now, request.getLatestUseTime());
        List<BloodComponent> usable = candidates.stream()
                .filter(c -> !heldGoodIds.contains(c.getId()))
                .toList();

        if (usable.size() < recalledItems.size()) {
            // 库存不足：保留原预留关系，标记不可发放；记录未解决原因，不做任何库存变更
            String reason = "相容合格单位不足：需要替换 " + recalledItems.size()
                    + "，可用 " + usable.size();
            reservation.setBlocked(true);
            for (ReplacementTask t : pending) {
                t.setUnresolvedReason(reason);
            }
            return taskView(pending.get(0));
        }

        List<BloodComponent> chosen = usable.subList(0, recalledItems.size());
        for (BloodComponent c : chosen) {
            // 条件更新兜底：并发下被其他申请占用则整笔回滚，不会换一部分
            if (componentRepository.reserveIfAvailable(c.getId()) != 1) {
                throw new InsufficientStockException("替代单位 " + c.getBarcode() + " 已被并发预留");
            }
        }

        // 被召回单位按批次找到触发召回
        Map<String, BatchRecall> recallByBatch = new HashMap<>();
        for (ReplacementTask t : pending) {
            recallByBatch.putIfAbsent(t.getRecall().getBatchNo(), t.getRecall());
        }

        // 原子提交：旧明细分历史、被召回单位保持 RECALLED、新单位挂入预留并生成换血明细。
        // 被替换单位与替代单位按候选顺序（早到期优先）逐一对齐。
        List<ReplacementItem> records = new ArrayList<>();
        for (int i = 0; i < recalledItems.size(); i++) {
            ReservationItem oldItem = recalledItems.get(i);
            BloodComponent oldUnit = oldItem.getComponent();
            BloodComponent newUnit = chosen.get(i);
            BatchRecall trigger = Objects.requireNonNull(recallByBatch.get(oldUnit.getBatchNo()),
                    "被召回单位缺少触发召回");
            oldItem.setActive(false);
            reservation.addItem(new ReservationItem(newUnit));
            ReplacementTask owner = pending.stream()
                    .filter(t -> t.getRecall().getId().equals(trigger.getId()))
                    .findFirst()
                    .orElseThrow();
            records.add(new ReplacementItem(owner, trigger, oldUnit, newUnit, now));
        }
        replacementItemRepository.saveAll(records);

        for (ReplacementTask t : pending) {
            t.setStatus(ReplacementStatus.REPLACED);
            t.setUnresolvedReason(null);
            t.setProcessedAt(now);
        }
        reservation.setBlocked(false);
        reservation.incrementStateVersion();
        return taskView(pending.get(0));
    }

    /**
     * 人工确认无法换血：释放预留仍占用的合格单位（被召回单位保持冻结），终止预留，任务记为不可解决。
     * 重复确认幂等，返回已有结果。
     */
    @Transactional
    public ReplacementTaskView confirmUnresolvable(String requestNo) {
        Reservation reservation = reservationRepository.findByRequestNoForUpdate(requestNo)
                .orElseThrow(() -> new NotFoundException("申请号 " + requestNo + " 不存在预留"));
        Long reservationId = reservation.getId();
        List<ReplacementTask> pending = taskRepository.findPendingByReservationId(reservationId);
        if (pending.isEmpty()) {
            return latestTaskView(reservationId);
        }
        Instant now = clock.instant();
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            for (ReplacementTask t : pending) {
                t.setStatus(ReplacementStatus.OBSOLETE);
                t.setProcessedAt(now);
            }
            return taskView(pending.get(0));
        }

        // 先释放仍占用的合格单位（RECALLED 条件更新自动跳过）、停用明细
        List<Long> componentIds = itemRepository.findActiveByReservationId(reservationId).stream()
                .map(i -> i.getComponent().getId()).toList();
        List<Long> activeItemIds = itemRepository.findActiveByReservationId(reservationId).stream()
                .map(ReservationItem::getId).toList();
        componentRepository.releaseReservedIn(componentIds);
        for (Long itemId : activeItemIds) {
            itemRepository.findById(itemId).ifPresent(item -> item.setActive(false));
        }
        // 最后做预留终态条件流转（清空上下文），再把待处理任务记为不可解决
        if (reservationRepository.markTerminatedIfReserved(reservationId, now) != 1) {
            // 并发下预留已被其他流转带走：任务作废，不重复处理
            taskRepository.markPendingObsolete(reservationId, now);
            return taskView(taskRepository.findById(pending.get(0).getId()).orElseThrow());
        }
        for (ReplacementTask t : taskRepository.findPendingByReservationId(reservationId)) {
            t.setStatus(ReplacementStatus.UNRESOLVABLE);
            t.setProcessedAt(now);
        }
        return taskView(taskRepository.findById(pending.get(0).getId()).orElseThrow());
    }

    @Transactional(readOnly = true)
    public RecallView getRecall(String eventNo) {
        BatchRecall recall = recallRepository.findByEventNo(eventNo)
                .orElseThrow(() -> new NotFoundException("召回事件号 " + eventNo + " 不存在"));
        List<ReplacementTaskView> tasks = taskRepository.findByRecallId(recall.getId()).stream()
                .map(this::taskView)
                .toList();
        return RecallView.of(recall, tasks);
    }

    @Transactional(readOnly = true)
    public List<RecallView> listRecalls() {
        return recallRepository.findAllByOrderByIdAsc().stream()
                .map(r -> RecallView.of(r, taskRepository.findByRecallId(r.getId()).stream()
                        .map(this::taskView)
                        .toList()))
                .toList();
    }

    private ReplacementTaskView latestTaskView(Long reservationId) {
        List<ReplacementTask> tasks = taskRepository.findByReservationId(reservationId);
        if (tasks.isEmpty()) {
            throw new NotFoundException("该申请没有换血任务");
        }
        return taskView(tasks.get(tasks.size() - 1));
    }

    private ReplacementTaskView taskView(ReplacementTask task) {
        List<ReplacementItemView> items = replacementItemRepository.findByTaskIdWithDetails(task.getId())
                .stream()
                .map(ReplacementItemView::from)
                .toList();
        return ReplacementTaskView.of(task, items);
    }
}
