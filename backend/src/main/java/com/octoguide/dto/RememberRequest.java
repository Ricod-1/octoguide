package com.octoguide.dto;

import javax.validation.constraints.NotBlank;

/** 用户确认后保存知识条目的请求。 */
public class RememberRequest {
    @NotBlank(message = "问题不能为空")
    private String question;
    @NotBlank(message = "回答不能为空")
    private String answer;
    private String sourceUrl;
    private String sourceTitle;
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getSourceTitle() { return sourceTitle; }
    public void setSourceTitle(String sourceTitle) { this.sourceTitle = sourceTitle; }
}
