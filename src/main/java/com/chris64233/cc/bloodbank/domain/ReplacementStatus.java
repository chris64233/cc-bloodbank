package com.chris64233.cc.bloodbank.domain;

/**
 * 换血任务状态。PENDING 可重复尝试；其余为终态，重复执行直接返回已有结果。
 */
public enum ReplacementStatus {
    /** 已生成、尚未完成全部替换（库存不足时停留于此，预留保留但不可发放） */
    PENDING,
    /** 全部受影响单位均已一次性替换完成 */
    COMPLETED,
    /** 人工确认无法换血，已释放合格单位并终止预留 */
    TERMINATED,
    /** 预留已先行到期释放，任务失去处理对象 */
    ABANDONED
}
