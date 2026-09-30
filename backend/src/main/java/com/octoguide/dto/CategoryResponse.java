package com.octoguide.dto;

import java.util.List;

/** 分类及其快捷问题列表响应。auto 标记是否为自动采集分类，供前端统一统计口径。 */
public class CategoryResponse {
    private final String id; private final String name; private final String icon; private final String desc; private final boolean auto; private final List<CategoryQuestionResponse> questions;
    public CategoryResponse(String id, String name, String icon, String desc, boolean auto, List<CategoryQuestionResponse> questions) { this.id=id; this.name=name; this.icon=icon; this.desc=desc; this.auto=auto; this.questions=questions; }
    public String getId() { return id; } public String getName() { return name; } public String getIcon() { return icon; } public String getDesc() { return desc; } public boolean isAuto() { return auto; } public List<CategoryQuestionResponse> getQuestions() { return questions; }
}
