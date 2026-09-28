package com.chris64233.cc.bloodbank.web;

/**
 * 人工确认无法换血时的备注（可选）。
 */
public record TerminateRequest(String reason) {
}
