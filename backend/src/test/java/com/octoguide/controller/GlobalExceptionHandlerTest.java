package com.octoguide.controller;

import com.octoguide.dto.AskRequest;
import com.octoguide.service.AiNotConfiguredException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * GlobalExceptionHandler 单元测试。
 *
 * <p>按《阿里巴巴Java开发手册》单元测试规约，直接调用各异常处理方法，
 * 验证参数校验、非法 JSON、AI 未配置、未知异常四类响应的状态码与错误信息。</p>
 */
public class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** 参数校验失败应返回 400 和字段校验信息。 */
    @Test
    void validationReturnsFieldErrorMessage() {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new AskRequest(), "askRequest");
        binding.addError(new FieldError("askRequest", "question", "问题不能为空"));
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(null, binding);

        ResponseEntity<Map<String, String>> response = handler.validation(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("问题不能为空", response.getBody().get("error"));
    }

    /** 请求体不是合法 JSON 应返回 400 和统一错误提示。 */
    @Test
    void malformedJsonReturnsBadRequest() {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException("bad json");

        ResponseEntity<Map<String, String>> response = handler.malformedJson(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("请求体不是合法 JSON", response.getBody().get("error"));
    }

    /** AI 未配置应返回 400 并透传异常信息。 */
    @Test
    void aiNotConfiguredReturnsBadRequestWithMessage() {
        AiNotConfiguredException exception = new AiNotConfiguredException("AI 未配置，无法整理知识条目");

        ResponseEntity<Map<String, String>> response = handler.aiNotConfigured(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("AI 未配置，无法整理知识条目", response.getBody().get("error"));
    }

    /** 未知异常应返回 500 和通用提示，不泄露内部细节。 */
    @Test
    void unexpectedExceptionReturnsInternalServerError() {
        RuntimeException exception = new RuntimeException("内部数据库超时");

        ResponseEntity<Map<String, String>> response = handler.handle(exception);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("服务暂时不可用，请稍后重试", response.getBody().get("error"));
        assertNotNull(response.getBody());
    }
}
