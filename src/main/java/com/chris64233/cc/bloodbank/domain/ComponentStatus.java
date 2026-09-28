package com.chris64233.cc.bloodbank.domain;

public enum ComponentStatus {
    /** 在库可用 */
    AVAILABLE,
    /** 已被预留，等待发放或到期释放 */
    RESERVED,
    /** 已发放给患者，不可恢复 */
    ISSUED,
    /** 已被生效召回锁定：在库单位不得再参与新预留，已预留单位不得发放 */
    RECALLED
}
