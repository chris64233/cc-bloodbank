package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.Recall;

import java.time.Instant;

public record RecallView(Long id, String eventNo, String batchNo, String reason,
                         Instant effectiveAt, Instant publishedAt,
                         boolean activated, Instant activatedAt) {

    public static RecallView from(Recall r) {
        return new RecallView(r.getId(), r.getEventNo(), r.getBatchNo(), r.getReason(),
                r.getEffectiveAt(), r.getPublishedAt(), r.isActivated(), r.getActivatedAt());
    }
}
