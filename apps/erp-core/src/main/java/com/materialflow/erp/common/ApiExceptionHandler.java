package com.materialflow.erp.common;

import com.materialflow.contracts.events.ApiError;
import com.materialflow.identity.ActorContext;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> business(BusinessException exception) {
        return response(exception.status(), exception.errorCode(), exception.getMessage(), Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> constraint(ConstraintViolationException exception) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", Map.of());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> parameterValidation(HandlerMethodValidationException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getParameterValidationResults().forEach(result -> result.getResolvableErrors()
                .forEach(error -> fields.putIfAbsent(result.getMethodParameter().getParameterName(),
                        error.getDefaultMessage())));
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", fields);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException exception) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
                Map.of(exception.getName(), "has an invalid value"));
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    ResponseEntity<ApiError> binding(ServletRequestBindingException exception) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "A required request header or parameter is missing",
                Map.of());
    }

    @ExceptionHandler({AccessDeniedException.class, ActorContext.AccessDeniedException.class})
    ResponseEntity<ApiError> denied(RuntimeException exception) {
        return response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "The operation is outside the actor's scope", Map.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException exception) {
        LOG.warn("Database constraint rejected a request: {}", exception.getMostSpecificCause().getMessage());
        return response(HttpStatus.CONFLICT, "CONSTRAINT_CONFLICT", "The request conflicts with existing state", Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception) {
        LOG.error("Unhandled request failure", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The request could not be completed", Map.of());
    }

    private ResponseEntity<ApiError> response(
            HttpStatus status,
            String code,
            String message,
            Map<String, String> fields) {
        String traceId = TraceIdFilter.currentTraceId();
        return ResponseEntity.status(status).body(new ApiError(code, message, traceId, Instant.now(), fields));
    }
}

