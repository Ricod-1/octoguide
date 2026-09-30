package com.octoguide.service;

/** 表示当前业务需要 AI，但系统未配置 API Key。 */
public class AiNotConfiguredException extends RuntimeException {
    /** 创建未配置 AI 异常。 */
    public AiNotConfiguredException(String message) {
        super(message);
    }
}
