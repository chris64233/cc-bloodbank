package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.BatchRecall;

import java.time.Instant;
import java.util.List;

/**
 * 召回视图：召回范围（事件号/批次/原因/生效时间）、激活状态与受影响申请的换血任务。
 */
public record RecallView(Long recallId, String eventNo, String batchNo, String reason,
                         Instant effectiveAt, boolean activated, Instant activatedAt, Instant createdAt,
                         List<ReplacementTaskView> tasks) {

    public static RecallView of(BatchRecall recall, List<ReplacementTaskView> tasks) {
        return new RecallView(recall.getId(), recall.getEventNo(), recall.getBatchNo(), recall.getReason(),
                recall.getEffectiveAt(), recall.isActivated(), recall.getActivatedAt(), recall.getCreatedAt(), tasks);
    }
}
