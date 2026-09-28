package com.chris64233.cc.bloodbank.domain;

public enum ReservationStatus {
    /** 预留生效中，截止前可确认发放 */
    RESERVED,
    /** 已确认发放 */
    ISSUED,
    /** 到期未发放，已释放全部单位 */
    RELEASED,
    /** 召回后人工确认无法换血，已释放仍合格的单位并终止该预留 */
    TERMINATED
}
