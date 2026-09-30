package com.octoguide.controller;

import com.octoguide.knowledge.service.KnowledgeUpdater;
import com.octoguide.config.KnowledgeUpdateProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;
import java.util.Collections;

/** 验证知识更新管理接口必须携带配置的更新令牌。 */
@WebMvcTest(KnowledgeUpdateController.class)
@Import(KnowledgeUpdateProperties.class)
class KnowledgeUpdateControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private KnowledgeUpdater updater;

    @Autowired
    private KnowledgeUpdateProperties properties;

    @Test
    void rejectsMissingOrWrongToken() throws Exception {
        properties.setToken("expected-token");
        mvc.perform(post("/api/knowledge/update").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/knowledge/update")
                        .header("X-Knowledge-Update-Token", "wrong-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void acceptsConfiguredToken() throws Exception {
        properties.setToken("expected-token");
        when(updater.run()).thenReturn(Collections.<String, Object>singletonMap("ok", true));
        mvc.perform(post("/api/knowledge/update")
                        .header("X-Knowledge-Update-Token", "expected-token"))
                .andExpect(status().isOk());
    }
}
