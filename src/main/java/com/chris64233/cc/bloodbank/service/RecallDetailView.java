package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.ComponentStatus;

import java.util.List;
import java.util.Map;

/**
 * 召回影响查询：召回范围（批次内全部单位及状态分布）、受影响申请的换血任务与换血前后明细。
 */
public record RecallDetailView(RecallView recall,
                               List<ComponentView> scope,
                               Map<ComponentStatus, Long> scopeStatusCounts,
                               List<ReplacementTaskView> affectedRequests) {
}
