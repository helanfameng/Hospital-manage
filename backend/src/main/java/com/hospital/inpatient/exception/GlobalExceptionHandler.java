package com.hospital.inpatient.exception;

import com.hospital.inpatient.common.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private final com.hospital.inpatient.service.AuditService audit;
    public GlobalExceptionHandler(com.hospital.inpatient.service.AuditService audit) { this.audit=audit; }
    private void recordFailure(String code) {
        try { audit.failure(code); } catch (RuntimeException e) { log.warn("Failed to persist failure audit",e); }
    }
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> business(BusinessException exception) {
        recordFailure(exception.getCode());
        return ResponseEntity.status(exception.getStatus())
                .body(ApiResponse.failure(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ApiResponse<Void>> validation(Exception exception) {
        String message;
        if (exception instanceof MethodArgumentNotValidException invalid) {
            message = invalid.getBindingResult().getFieldErrors().stream()
                    .map(FieldError::getDefaultMessage)
                    .collect(Collectors.joining("；"));
        } else {
            message = exception.getMessage();
        }
        return ResponseEntity.badRequest().body(ApiResponse.failure("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResponse<Void>> denied(AccessDeniedException exception) {
        recordFailure("FORBIDDEN");
        return ResponseEntity.status(403).body(ApiResponse.failure("FORBIDDEN", "当前角色无权执行该操作"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception exception) {
        recordFailure("INTERNAL_ERROR");
        log.error("Unhandled application error", exception);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.failure("INTERNAL_ERROR", "系统处理失败，请根据追踪编号联系管理员"));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<ApiResponse<Void>> database(org.springframework.dao.DataAccessException exception) {
        recordFailure("DATA_CONFLICT");
        log.warn("Database operation rejected",exception);
        return ResponseEntity.status(409).body(ApiResponse.failure("DATA_CONFLICT","数据冲突：记录可能已处理、存在重复或关联状态已变化，请刷新后核对"));
    }

    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiResponse<Void>> badRequest(Exception exception) {
        return ResponseEntity.badRequest().body(ApiResponse.failure("INVALID_REQUEST","请求参数缺失或格式不正确"));
    }
}
