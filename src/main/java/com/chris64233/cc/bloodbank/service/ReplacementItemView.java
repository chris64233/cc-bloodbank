package com.chris64233.cc.bloodbank.service;

/**
 * 一对换血明细：被替换单位（换血前）与替代单位（换血后），以及处理结果。
 * replacement 为 null 表示该单位暂无合格替代品（未解决）。
 */
public record ReplacementItemView(ReservationItemView removed, ReservationItemView replacement, String outcome) {

    public static ReplacementItemView unresolved(ReservationItemView removed) {
        return new ReplacementItemView(removed, null, "UNRESOLVED");
    }

    public static ReplacementItemView replaced(ReservationItemView removed, ReservationItemView replacement) {
        return new ReplacementItemView(removed, replacement, "REPLACED");
    }
}
