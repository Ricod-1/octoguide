package com.octoguide.controller;

import com.octoguide.knowledge.service.KnowledgeUpdater;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.octoguide.config.KnowledgeUpdateProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import java.util.Map;

/** 提供手动触发知识更新任务的管理接口。 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeUpdateController {

    private final KnowledgeUpdater knowledgeUpdater;
    private final KnowledgeUpdateProperties properties;

    public KnowledgeUpdateController(KnowledgeUpdater knowledgeUpdater, KnowledgeUpdateProperties properties) {
        this.knowledgeUpdater = knowledgeUpdater;
        this.properties = properties;
    }

    /** 立即触发一次知识更新，并返回任务触发结果。 */
    @PostMapping("/update")
    public ResponseEntity<Map<String, Object>> update(
            @RequestHeader(value = "X-Knowledge-Update-Token", required = false) String requestToken) {
        if (!properties.isConfigured() || requestToken == null
                || !MessageDigest.isEqual(properties.getToken().getBytes(StandardCharsets.UTF_8),
                        requestToken.getBytes(StandardCharsets.UTF_8))) {
            Map<String, Object> body = new java.util.LinkedHashMap<String, Object>();
            body.put("error", "知识更新接口未授权");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }
        return ResponseEntity.ok(knowledgeUpdater.run());
    }
}
