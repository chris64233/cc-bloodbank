package com.chris64233.cc.bloodbank.api.error;

/**
 * 请求与当前业务状态冲突：库存不足、同申请号内容不一致、对已终态预留执行状态变更等。
 */
public class BusinessConflictException extends RuntimeException {

    private final String code;

    public BusinessConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
