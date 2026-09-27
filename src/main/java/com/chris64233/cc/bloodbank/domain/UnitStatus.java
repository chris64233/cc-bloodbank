package com.chris64233.cc.bloodbank.domain;

/**
 * 血液成分单位的库存状态。
 */
public enum UnitStatus {

    /** 可用：可被预留 */
    AVAILABLE,
    /** 已预留：绑定在一笔有效预留上 */
    RESERVED,
    /** 已发放：随预留确认发放，终态，不可恢复 */
    ISSUED
}
