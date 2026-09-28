package com.chris64233.cc.bloodbank.service;

/**
 * 预留因批次召回正在换血（或换血库存不足）而暂停发放时抛出，HTTP 409。
 * 被召回单位绝不能发放；待换血完成且预留解除阻止后才可发放。
 */
public class RecallBlockedException extends RuntimeException {

    public RecallBlockedException(String message) {
        super(message);
    }
}
