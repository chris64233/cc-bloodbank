package com.chris64233.cc.bloodbank.domain;

public enum ReplacementStatus {
    /** 待处理：受影响单位尚未完成替换，预留不可发放 */
    PENDING,
    /** 已完成换血：全部受影响单位一次性替换成功 */
    REPLACED,
    /** 人工确认无法换血：已释放仍合格的单位并终止预留 */
    UNRESOLVABLE,
    /** 已失效：任务处理前预留已发放或已到期释放，无需换血 */
    OBSOLETE
}
