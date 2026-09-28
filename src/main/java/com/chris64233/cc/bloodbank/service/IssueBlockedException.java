package com.chris64233.cc.bloodbank.service;

/**
 * 预留含被召回单位且换血尚未完成时发放：被召回单位绝不允许出库，返回 409。
 */
public class IssueBlockedException extends RuntimeException {

    public IssueBlockedException(String message) {
        super(message);
    }
}
