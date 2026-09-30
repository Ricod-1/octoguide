package com.octoguide.controller;

import com.octoguide.dto.CategoryResponse;
import com.octoguide.dto.RememberResponse;
import com.octoguide.dto.RememberRequest;
import com.octoguide.dto.StatusResponse;
import com.octoguide.dto.TermResponse;
import com.octoguide.service.KnowledgeService;
import com.octoguide.service.KnowledgeMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {KnowledgeController.class, StatusController.class, RememberController.class})
class NodeApiCompatibilityTest {
    @Autowired MockMvc mvc;
    @MockBean KnowledgeService knowledgeService;
    @MockBean KnowledgeMemoryService memoryService;

    @Test
    void termsKeepNodeResponseWrapper() throws Exception {
        when(knowledgeService.terms()).thenReturn(Collections.singletonList(new TermResponse("PR", "合并请求", "说明")));
        mvc.perform(get("/api/terms").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terms").isArray())
                .andExpect(jsonPath("$.terms[0].en").value("PR"));
    }

    @Test
    void categoriesKeepAutoCountField() throws Exception {
        when(knowledgeService.categories()).thenReturn(Collections.singletonList(new CategoryResponse("intro", "入门", "◎", "说明", false, Collections.emptyList())));
        mvc.perform(get("/api/categories").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").isArray())
                .andExpect(jsonPath("$.autoCount").value(0));
    }

    @Test
    void statusKeepsNodeStatisticsFields() throws Exception {
        when(knowledgeService.status()).thenReturn(new StatusResponse(19, 18, false, false));
        mvc.perform(get("/api/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manualCount").value(19))
                .andExpect(jsonPath("$.totalCount").value(19))
                .andExpect(jsonPath("$.sourceBreakdown").exists())
                .andExpect(jsonPath("$.sourceBreakdown.other").value(19));
    }

    @Test
    void rememberKeepsExistingIdForDuplicate() throws Exception {
        when(memoryService.remember(org.mockito.ArgumentMatchers.any(RememberRequest.class)))
                .thenReturn(new RememberResponse(false, true, null, "learned-existing"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/remember")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"什么是 PR？\",\"answer\":\"回答\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.existingId").value("learned-existing"));
    }

    @Test
    void askKeepsNodeAnswerMetadata() throws Exception {
        Map<String, Object> answer = new LinkedHashMap<String, Object>();
        answer.put("found", true);
        answer.put("questionId", "collab-2");
        answer.put("question", "什么是 Pull Request（PR）？");
        answer.put("category", "协作");
        answer.put("categoryId", "collab");
        answer.put("icon", "♧");
        answer.put("answer", Collections.singletonList(Collections.singletonMap("t", "text")));
        answer.put("sourceUrl", "https://docs.github.com/zh/pull-requests");
        answer.put("sourceTitle", "GitHub 文档");
        answer.put("suggestions", Collections.emptyList());
        answer.put("score", 15);
        when(knowledgeService.ask("什么是 PR？")).thenReturn(answer);
        mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"什么是 PR？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value("collab"))
                .andExpect(jsonPath("$.icon").value("♧"))
                .andExpect(jsonPath("$.sourceUrl").value("https://docs.github.com/zh/pull-requests"))
                .andExpect(jsonPath("$.sourceTitle").value("GitHub 文档"));
    }

    @Test
    void askRejectsMalformedJsonWithBadRequest() throws Exception {
        mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON).content("{bad json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请求体不是合法 JSON"));
    }
}
