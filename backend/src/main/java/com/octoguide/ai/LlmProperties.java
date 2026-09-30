package com.octoguide.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 绑定 {@code octoguide.llm} 配置项。 */
@Component
@ConfigurationProperties(prefix = "octoguide.llm")
public class LlmProperties {
    private String baseUrl;
    private String apiKey;
    private String model;
    private int timeoutSeconds = 30;
    /** 流式回答的空闲超时（秒）：两段数据之间的最大间隔，超时才判定上游卡死。 */
    private int streamIdleTimeoutSeconds = 60;
    /** 流式回答的总时长上限（秒）：整段回答从开始到结束的硬上限，应小于 emitter 超时。 */
    private int streamTotalTimeoutSeconds = 300;
    private int historyLimit = 12;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getStreamIdleTimeoutSeconds() { return streamIdleTimeoutSeconds; }
    public void setStreamIdleTimeoutSeconds(int streamIdleTimeoutSeconds) {
        this.streamIdleTimeoutSeconds = streamIdleTimeoutSeconds;
    }
    public int getStreamTotalTimeoutSeconds() { return streamTotalTimeoutSeconds; }
    public void setStreamTotalTimeoutSeconds(int streamTotalTimeoutSeconds) {
        this.streamTotalTimeoutSeconds = streamTotalTimeoutSeconds;
    }
    public int getHistoryLimit() { return historyLimit; }
    public void setHistoryLimit(int historyLimit) { this.historyLimit = historyLimit; }

    /** API Key 非空时才认为 AI 已启用。 */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }
}
