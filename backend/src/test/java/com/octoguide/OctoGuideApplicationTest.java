package com.octoguide;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.octoguide.knowledge.mapper.KnowledgeBlockMapper;
import com.octoguide.knowledge.mapper.KnowledgeCategoryMapper;
import com.octoguide.knowledge.mapper.KnowledgeItemMapper;
import com.octoguide.knowledge.mapper.KnowledgeSourceMapper;
import com.octoguide.knowledge.mapper.TermMapper;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
class OctoGuideApplicationTest {

    @MockBean KnowledgeCategoryMapper categoryMapper;
    @MockBean KnowledgeItemMapper itemMapper;
    @MockBean KnowledgeBlockMapper blockMapper;
    @MockBean KnowledgeSourceMapper sourceMapper;
    @MockBean TermMapper termMapper;

    @Test
    void contextLoads() {
        // Verifies the application entry point and component scan can start without MySQL.
    }
}
