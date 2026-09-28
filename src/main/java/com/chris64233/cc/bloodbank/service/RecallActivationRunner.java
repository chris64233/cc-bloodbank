package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.BloodComponent;
import com.chris64233.cc.bloodbank.domain.ComponentStatus;
import com.chris64233.cc.bloodbank.domain.Recall;
import com.chris64233.cc.bloodbank.domain.ReplacementItem;
import com.chris64233.cc.bloodbank.domain.ReplacementTask;
import com.chris64233.cc.bloodbank.domain.Reservation;
import com.chris64233.cc.bloodbank.repository.BloodComponentRepository;
import com.chris64233.cc.bloodbank.repository.RecallRepository;
import com.chris64233.cc.bloodbank.repository.ReplacementItemRepository;
import com.chris64233.cc.bloodbank.repository.ReplacementTaskRepository;
import com.chris64233.cc.bloodbank.repository.ReservationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 召回生效激活：每个召回独立事务执行。
 * 加锁顺序与发放/换血保持一致——先按 id 升序锁受影响预留行，再锁批次单位行，杜绝死锁。
 */
@Component
public class RecallActivationRunner {

    private final RecallRepository recallRepository;
    private final ReservationRepository reservationRepository;
    private final BloodComponentRepository componentRepository;
    private final ReplacementTaskRepository taskRepository;
    private final ReplacementItemRepository replacementItemRepository;
    private final Clock clock;

    public RecallActivationRunner(RecallRepository recallRepository,
                                  ReservationRepository reservationRepository,
                                  BloodComponentRepository componentRepository,
                                  ReplacementTaskRepository taskRepository,
                                  ReplacementItemRepository replacementItemRepository,
                                  Clock clock) {
        this.recallRepository = recallRepository;
        this.reservationRepository = reservationRepository;
        this.componentRepository = componentRepository;
        this.taskRepository = taskRepository;
        this.replacementItemRepository = replacementItemRepository;
        this.clock = clock;
    }

    /**
     * 激活指定召回，独立事务提交：定时批量处理时各召回互不影响，单个失败不牵连其他召回。
     * 返回 true 表示本次完成激活；false 表示未到生效时间或已被激活。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean activate(Long recallId) {
        return doActivate(recallId);
    }

    /**
     * 发布即生效入口：召回记录在当前事务内刚落库、尚未提交，独立事务的新连接不可见，
     * 因此并入发布事务执行（Propagation.REQUIRED）。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean activateInCurrentTransaction(Long recallId) {
        return doActivate(recallId);
    }

    private boolean doActivate(Long recallId) {
        Recall recall = recallRepository.findByIdForUpdate(recallId).orElseThrow();
        Instant now = clock.instant();
        if (recall.isActivated() || recall.getEffectiveAt().isAfter(now)) {
            return false;
        }

        String batchNo = recall.getBatchNo();

        // 1) 先按 id 升序锁定所有受影响预留行（统一"预留先于单位"加锁顺序）
        List<Long> affectedReservationIds = new ArrayList<>(
                reservationRepository.findAffectedReservationIdsForBatch(batchNo));
        List<Reservation> lockedReservations = new ArrayList<>();
        for (Long rid : affectedReservationIds) {
            lockedReservations.add(reservationRepository.findByIdForUpdate(rid).orElseThrow());
        }

        // 2) 在库单位立即隔离：AVAILABLE -> RECALLED，不得再参与新预留
        componentRepository.markAvailableInBatchRecalled(batchNo);

        // 3) 再次扫描：覆盖加锁窗口内刚提交、占用了批次单位的新预留
        for (Long rid : reservationRepository.findAffectedReservationIdsForBatch(batchNo)) {
            if (!affectedReservationIds.contains(rid)) {
                affectedReservationIds.add(rid);
                lockedReservations.add(reservationRepository.findByIdForUpdate(rid).orElseThrow());
            }
        }

        // 4) 逐笔预留：被占用的批次单位置 RECALLED 隔离，生成换血任务与换血前明细，阻断发放
        for (Reservation reservation : lockedReservations) {
            List<BloodComponent> occupied =
                    componentRepository.lockReservedOrRecalledInBatchForReservation(batchNo, reservation.getId());
            if (occupied.isEmpty()) {
                continue;
            }
            ReplacementTask task = new ReplacementTask(recall, reservation, now);
            try {
                task = taskRepository.saveAndFlush(task);
            } catch (DataIntegrityViolationException e) {
                // 唯一约束兜底：同召回同预留任务已存在（极端并发），跳过不重复生成
                continue;
            }
            for (BloodComponent c : occupied) {
                c.setStatus(ComponentStatus.RECALLED);
                replacementItemRepository.save(new ReplacementItem(task, c));
            }
            reservation.setIssueBlocked(true);
        }

        recall.markActivated(now);
        return true;
    }
}
