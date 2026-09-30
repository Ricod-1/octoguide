package com.octoguide.controller;

import com.octoguide.dto.AskRequest;
import com.octoguide.dto.CategoriesResponse;
import com.octoguide.dto.TermsResponse;
import com.octoguide.service.KnowledgeService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.Map;

/** 提供分类、术语和同步知识库问答接口。 */
@RestController
@RequestMapping("/api")
@Validated
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /** 返回左侧分类、快捷问题和自动知识数量。 */
    @GetMapping("/categories")
    public CategoriesResponse categories() {
        return new CategoriesResponse(knowledgeService.categories(), knowledgeService.autoCount());
    }

    /** 返回右侧术语表。 */
    @GetMapping("/terms")
    public TermsResponse terms() {
        return new TermsResponse(knowledgeService.terms());
    }

    /** 执行不需要流式输出的本地知识库问答。 */
    @PostMapping("/ask")
    public ResponseEntity<Map<String, Object>> ask(@Valid @RequestBody AskRequest request) {
        return ResponseEntity.ok(knowledgeService.ask(request.getQuestion().trim()));
    }
}
