package com.chris64233.cc.bloodbank.service;

public class RequestConflictException extends RuntimeException {

    public RequestConflictException(String message) {
        super(message);
    }
}
