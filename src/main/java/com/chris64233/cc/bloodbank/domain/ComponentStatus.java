package com.chris64233.cc.bloodbank.domain;

public enum ComponentStatus {
    /** 在库可用 */
    AVAILABLE,
    /** 已被预留，等待发放或到期释放 */
    RESERVED,
    /** 已发放给患者，不可恢复 */
    ISSUED
}
