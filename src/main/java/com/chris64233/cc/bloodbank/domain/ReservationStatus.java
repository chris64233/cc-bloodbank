package com.chris64233.cc.bloodbank.domain;

public enum ReservationStatus {
    /** 预留生效中，截止前可确认发放 */
    RESERVED,
    /** 已确认发放 */
    ISSUED,
    /** 到期未发放，已释放全部单位 */
    RELEASED,
    /** 受召回影响且人工确认无法换血，已终止预留（合格单位释放，被召回单位留库隔离） */
    TERMINATED
}
