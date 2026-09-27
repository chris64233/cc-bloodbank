package com.chris64233.cc.bloodbank.domain;

/**
 * 临床申请（预留）状态。
 */
public enum ReservationStatus {

    /** 预留有效，等待发放 */
    RESERVED,
    /** 已确认发放，终态 */
    ISSUED,
    /** 到期未发放，单位已全部释放，终态 */
    RELEASED
}
