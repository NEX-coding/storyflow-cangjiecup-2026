package com.storyflow.common;

import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        int code = ex.getCode();
        HttpStatus status = code == 401 ? HttpStatus.UNAUTHORIZED
                : code == 403 ? HttpStatus.FORBIDDEN
                : code == 429 ? HttpStatus.TOO_MANY_REQUESTS
                : HttpStatus.BAD_REQUEST;
        return org.springframework.http.ResponseEntity.status(status).body(ApiResponse.error(code, ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ApiResponse.error(400, message);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGeneric(Exception ex) {
        ex.printStackTrace();
        return ApiResponse.error(500, ex.getMessage() != null ? ex.getMessage() : "服务器内部错误");
    }
}
