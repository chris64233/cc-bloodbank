package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.ReplacementStatus;

import java.time.Instant;
import java.util.List;

public record ReplacementTaskView(Long taskId, String eventNo, String requestNo,
                                  ReplacementStatus status, Instant createdAt, Instant completedAt,
                                  String unresolvedReason,
                                  List<ReplacementItemView> items) {
}
