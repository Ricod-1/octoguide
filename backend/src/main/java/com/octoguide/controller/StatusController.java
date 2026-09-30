package com.octoguide.controller;

import com.octoguide.dto.StatusResponse;
import com.octoguide.service.KnowledgeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供知识库数量、来源分布和 AI 配置状态。 */
@RestController
@RequestMapping("/api/status")
public class StatusController {

    private final KnowledgeService knowledgeService;

    public StatusController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /** 返回当前系统状态。 */
    @GetMapping
    public StatusResponse status() {
        return knowledgeService.status();
    }
}
