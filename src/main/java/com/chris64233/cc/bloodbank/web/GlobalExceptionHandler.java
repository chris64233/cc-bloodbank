package com.chris64233.cc.bloodbank.web;

import com.chris64233.cc.bloodbank.service.BadRequestException;
import com.chris64233.cc.bloodbank.service.InsufficientStockException;
import com.chris64233.cc.bloodbank.service.IssueBlockedException;
import com.chris64233.cc.bloodbank.service.NotFoundException;
import com.chris64233.cc.bloodbank.service.RequestConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(RequestConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(RequestConflictException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<Map<String, String>> insufficient(InsufficientStockException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    /** 预留含被召回单位且换血未完成：被召回单位绝不出库 */
    @ExceptionHandler(IssueBlockedException.class)
    public ResponseEntity<Map<String, String>> issueBlocked(IssueBlockedException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, String>> badRequest(BadRequestException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException e) {
        return error(HttpStatus.BAD_REQUEST, "请求参数校验失败");
    }

    /** 数据库唯一约束兜底（如并发相同申请号/条码），事务已回滚，可安全重试 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> dataIntegrity(DataIntegrityViolationException e) {
        return error(HttpStatus.CONFLICT, "数据唯一性冲突，请重试");
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
