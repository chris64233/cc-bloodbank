package com.chris64233.cc.bloodbank.service;

import com.chris64233.cc.bloodbank.domain.ReplacementItem;

import java.time.Instant;

/**
 * 换血明细视图：被替换单位、替代单位、触发召回事件号。
 */
public record ReplacementItemView(String eventNo, String oldBarcode, String newBarcode, Instant replacedAt) {

    public static ReplacementItemView from(ReplacementItem item) {
        return new ReplacementItemView(item.getRecall().getEventNo(),
                item.getOldComponent().getBarcode(),
                item.getNewComponent().getBarcode(),
                item.getCreatedAt());
    }
}
