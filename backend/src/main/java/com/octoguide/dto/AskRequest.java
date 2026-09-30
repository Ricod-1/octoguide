package com.octoguide.dto;

import javax.validation.constraints.NotBlank;

/** 同步知识库问答请求。 */
public class AskRequest {
    @NotBlank(message = "问题不能为空")
    private String question;
    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }
}
