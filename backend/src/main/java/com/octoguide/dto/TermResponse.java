package com.octoguide.dto;

/** 单个 GitHub 术语响应。 */
public class TermResponse {
    private final String en; private final String zh; private final String desc;
    public TermResponse(String en, String zh, String desc) { this.en=en; this.zh=zh; this.desc=desc; }
    public String getEn() { return en; } public String getZh() { return zh; } public String getDesc() { return desc; }
}
