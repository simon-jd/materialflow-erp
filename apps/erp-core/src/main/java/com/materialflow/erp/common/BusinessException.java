package com.materialflow.erp.common;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {
    private final String errorCode;
    private final HttpStatus status;

    public BusinessException(String errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public String errorCode() {
        return errorCode;
    }

    public HttpStatus status() {
        return status;
    }

    public static BusinessException notFound(String resource) {
        return new BusinessException("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND, resource + " was not found");
    }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(code, HttpStatus.CONFLICT, message);
    }
}

