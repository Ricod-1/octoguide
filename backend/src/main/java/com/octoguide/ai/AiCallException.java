package com.octoguide.ai;

/** 表示调用上游大模型服务失败。 */
public class AiCallException extends RuntimeException {
    /** 使用错误摘要创建异常。 */
    public AiCallException(String message) {
        super(message);
    }

    /** 使用错误摘要和原始异常创建异常。 */
    public AiCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
