package com.chris64233.cc.bloodbank.domain;

/**
 * 预留明细行状态：同一单位在历史上可能经历"占用 -> 被召回换血替换 -> 释放"，
 * 明细保留历史，因此用行状态区分"当前占用"与"已结束的历史行"。
 */
public enum ItemState {
    /** 当前仍占用该单位 */
    ACTIVE,
    /** 换血时被合格替代品替换（被召回单位） */
    REPLACED,
    /** 随预留到期释放或人工终止而释放 */
    RELEASED
}
