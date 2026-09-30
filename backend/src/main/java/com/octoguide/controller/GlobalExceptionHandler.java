package com.octoguide.controller;

import com.octoguide.service.AiNotConfiguredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Collections;
import java.util.Map;

/** 统一处理 REST 接口中的参数、配置和未知异常。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 处理 Bean Validation 参数校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().isEmpty()
                ? "请求参数不正确"
                : exception.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return ResponseEntity.badRequest().body(Collections.singletonMap("error", message));
    }

    /** 处理请求体不是合法 JSON 的情况。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> malformedJson(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(Collections.singletonMap("error", "请求体不是合法 JSON"));
    }

    /** 处理需要 AI 但未配置 API Key 的请求。 */
    @ExceptionHandler(AiNotConfiguredException.class)
    public ResponseEntity<Map<String, String>> aiNotConfigured(AiNotConfiguredException exception) {
        return ResponseEntity.badRequest().body(Collections.singletonMap("error", exception.getMessage()));
    }

    /** 兜底处理未被业务层明确转换的异常，并记录完整堆栈供服务端排查。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handle(Exception exception) {
        LOGGER.error("API request failed", exception);
        return ResponseEntity.internalServerError()
                .body(Collections.singletonMap("error", "服务暂时不可用，请稍后重试"));
    }
}
