package com.octoguide.dto;

import javax.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/** 流式聊天请求，包含当前问题和可选的历史消息。 */
public class ChatRequest {
    @NotBlank(message = "问题不能为空")
    private String question;
    private List<Map<String, Object>> history;
    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public List<Map<String, Object>> getHistory() {
        return history;
    }

    public void setHistory(List<Map<String, Object>> history) {
        this.history = history;
    }
}
