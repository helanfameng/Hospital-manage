package com.hospital.inpatient.exception;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {
    public static BusinessException fromDatabase(org.springframework.dao.DataAccessException exception, String code, String fallback) {
        Throwable cause=exception.getMostSpecificCause();
        if (cause instanceof java.sql.SQLException sql && "45000".equals(sql.getSQLState())) {
            return new BusinessException(code,sql.getMessage());
        }
        org.slf4j.LoggerFactory.getLogger(BusinessException.class).warn("Database operation failed: {}",code,exception);
        return new BusinessException(code,fallback+"：数据状态已变化或发生约束冲突，请刷新后重试",HttpStatus.CONFLICT);
    }
    private final String code;
    private final HttpStatus status;

    public BusinessException(String code, String message) {
        this(code, message, HttpStatus.BAD_REQUEST);
    }

    public BusinessException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
