package com.octoguide.dto;

import java.util.List;

/** 术语列表响应。 */
public class TermsResponse {
    private final List<TermResponse> terms;

    public TermsResponse(List<TermResponse> terms) {
        this.terms = terms;
    }

    public List<TermResponse> getTerms() {
        return terms;
    }
}
