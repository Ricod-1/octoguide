package com.octoguide.dto;

import java.util.List;

/** 分类下的快捷问题响应。 */
public class CategoryQuestionResponse {
    private final String id; private final String q; private final List<String> tags;
    public CategoryQuestionResponse(String id, String q, List<String> tags) { this.id=id; this.q=q; this.tags=tags; }
    public String getId() { return id; } public String getQ() { return q; } public List<String> getTags() { return tags; }
}
