package com.chris64233.cc.bloodbank.domain;

/**
 * 预留明细行状态，随所属预留一起流转。
 */
public enum ItemStatus {

    /** 已分配（有效预留中） */
    ALLOCATED,
    /** 随预留确认发放 */
    ISSUED,
    /** 随预留到期释放 */
    RELEASED
}
