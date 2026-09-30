package com.octoguide.dto;

import java.util.List;

/** 分类接口响应，附带自动知识条目数量。 */
public class CategoriesResponse {
    private final List<CategoryResponse> categories;
    private final long autoCount;

    public CategoriesResponse(List<CategoryResponse> categories, long autoCount) {
        this.categories = categories;
        this.autoCount = autoCount;
    }

    public List<CategoryResponse> getCategories() {
        return categories;
    }

    public long getAutoCount() {
        return autoCount;
    }
}
