package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.ComponentStatus;
import com.chris64233.cc.bloodbank.domain.Recall;
import com.chris64233.cc.bloodbank.repository.BloodComponentRepository;
import com.chris64233.cc.bloodbank.repository.RecallRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次召回发布与查询。
 * 外部事件号幂等：同号同内容返回原结果，同号异内容返回 409 冲突。
 * 生效时间已到则在发布事务内同步激活；未来生效的召回由定时任务/手动触发激活。
 */
@Service
public class RecallService {

    private final RecallRepository recallRepository;
    private final BloodComponentRepository componentRepository;
    private final RecallActivationRunner activationRunner;
    private final ReplacementQuerySupport querySupport;
    private final Clock clock;

    public RecallService(RecallRepository recallRepository,
                         BloodComponentRepository componentRepository,
                         RecallActivationRunner activationRunner,
                         ReplacementQuerySupport querySupport,
                         Clock clock) {
        this.recallRepository = recallRepository;
        this.componentRepository = componentRepository;
        this.activationRunner = activationRunner;
        this.querySupport = querySupport;
        this.clock = clock;
    }

    /**
     * 发布召回。相同事件号重复提交：
     * 内容一致返回原结果（即使本次重新提交时召回已生效），内容不一致抛冲突。
     */
    @Transactional
    public RecallView publish(String eventNo, String batchNo, String reason, Instant effectiveAt) {
        Instant now = clock.instant();
        if (eventNo == null || eventNo.isBlank()) {
            throw new BadRequestException("外部事件号不能为空");
        }
        if (batchNo == null || batchNo.isBlank()) {
            throw new BadRequestException("批次号不能为空");
        }
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("召回原因不能为空");
        }
        if (effectiveAt == null) {
            throw new BadRequestException("生效时间不能为空");
        }

        var existing = recallRepository.findByEventNoForUpdate(eventNo);
        if (existing.isPresent()) {
            Recall r = existing.get();
            if (!r.sameContentAs(batchNo, reason, effectiveAt)) {
                throw new RequestConflictException("事件号 " + eventNo + " 已存在且召回内容不一致");
            }
            // 幂等重放：返回当前结果（已激活/待激活均原样返回）
            return RecallView.from(r);
        }

        Recall recall = recallRepository.save(new Recall(eventNo, batchNo, reason, effectiveAt, now));
        // 发布即生效：在发布事务内同步激活；未来生效的召回等待定时任务
        if (!effectiveAt.isAfter(now)) {
            activationRunner.activateInCurrentTransaction(recall.getId());
        }
        return RecallView.from(recallRepository.findById(recall.getId()).orElseThrow());
    }

    /**
     * 激活所有已到生效时间的召回（定时任务或手动触发），返回本次激活数。重复执行幂等。
     */
    @Transactional
    public int activateDue() {
        Instant now = clock.instant();
        int activated = 0;
        for (Recall recall : recallRepository.findDueActivations(now)) {
            if (activationRunner.activate(recall.getId())) {
                activated++;
            }
        }
        return activated;
    }

    /** 召回详情：召回范围（批次单位与库存状态分布）、受影响申请及换血前后明细 */
    @Transactional(readOnly = true)
    public RecallDetailView getRecall(String eventNo) {
        Recall recall = recallRepository.findByEventNo(eventNo)
                .orElseThrow(() -> new NotFoundException("召回事件 " + eventNo + " 不存在"));
        List<ComponentView> scope = componentRepository.findByBatchNoOrderByBarcodeAsc(recall.getBatchNo())
                .stream()
                .map(ComponentView::from)
                .toList();
        Map<ComponentStatus, Long> statusCounts = new LinkedHashMap<>();
        for (ComponentStatus s : ComponentStatus.values()) {
            statusCounts.put(s, scope.stream().filter(c -> c.status() == s).count());
        }
        return new RecallDetailView(RecallView.from(recall), scope, statusCounts,
                querySupport.taskViewsForRecall(eventNo));
    }
}
