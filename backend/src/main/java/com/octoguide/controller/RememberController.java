package com.octoguide.controller;

import com.octoguide.dto.RememberRequest;
import com.octoguide.dto.RememberResponse;
import com.octoguide.service.KnowledgeMemoryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/** 提供用户确认后的知识记忆接口。 */
@RestController
@RequestMapping("/api/remember")
public class RememberController {

    private final KnowledgeMemoryService memoryService;

    public RememberController(KnowledgeMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /** 清洗并保存一条用户确认的知识条目，重复问题会返回已有条目标识。 */
    @PostMapping
    public RememberResponse remember(@Valid @RequestBody RememberRequest request) {
        return memoryService.remember(request);
    }
}
