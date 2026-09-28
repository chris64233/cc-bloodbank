package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.ReplacementStatus;
import com.chris64233.cc.bloodbank.domain.ReplacementTask;

import java.time.Instant;
import java.util.List;

/**
 * 换血任务视图：触发召回、受影响申请、处理状态、换血前后明细与未解决原因。
 */
public record ReplacementTaskView(Long taskId, String requestNo, String eventNo, ReplacementStatus status,
                                  String unresolvedReason, Instant createdAt, Instant processedAt,
                                  List<ReplacementItemView> replacements) {

    public static ReplacementTaskView of(ReplacementTask task, List<ReplacementItemView> replacements) {
        return new ReplacementTaskView(task.getId(), task.getReservation().getRequest().getRequestNo(),
                task.getRecall().getEventNo(), task.getStatus(), task.getUnresolvedReason(),
                task.getCreatedAt(), task.getProcessedAt(), replacements);
    }
}
