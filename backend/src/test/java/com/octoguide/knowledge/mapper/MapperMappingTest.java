package com.octoguide.knowledge.mapper;

import com.octoguide.knowledge.domain.KnowledgeBlock;
import com.octoguide.knowledge.domain.KnowledgeItem;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 MyBatis XML Mapper 的核心字段映射和批量查询契约。
 *
 * <p>测试使用 H2 的 MySQL 兼容模式，不依赖本地 MySQL 实例。</p>
 */
@MybatisTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "mybatis.mapper-locations=classpath:/mapper/*.xml"
})
class MapperMappingTest {

    @Autowired
    private KnowledgeItemMapper itemMapper;

    @Autowired
    private KnowledgeBlockMapper blockMapper;

    @Autowired
    private TermMapper termMapper;

    @Autowired
    private KnowledgeCategoryMapper categoryMapper;

    /** 验证下划线字段、分类关联、批量答案块和术语排序均能正确映射。 */
    @Test
    void mapsSnakeCaseColumnsAndBatchBlocks() {
        List<KnowledgeItem> items = itemMapper.findEnabledItems();

        assertEquals("intro-1", items.get(0).getId());
        assertEquals("intro", items.get(0).getCategory().getId());
        assertEquals(2, blockMapper.findByItemIds(Collections.singletonList("intro-1")).size());
        assertEquals("Repository", termMapper.findAllOrdered().get(0).getEnglish());
    }

    /** 验证 is_auto 标记与按标记的分类计数能正确映射（统计口径统一的底座）。 */
    @Test
    void mapsAutoFlagAndCountsCategoriesByAuto() {
        java.util.Map<String, Boolean> autoById = new java.util.HashMap<String, Boolean>();
        for (com.octoguide.knowledge.domain.KnowledgeCategory category : categoryMapper.findEnabledCategories()) {
            autoById.put(category.getId(), category.getAuto());
        }
        assertEquals(Boolean.FALSE, autoById.get("intro"));
        assertEquals(Boolean.TRUE, autoById.get("auto-collected"));
        assertEquals(1L, categoryMapper.countByAuto(true));
        // intro 与 test-cat 均为人工分类
        assertEquals(2L, categoryMapper.countByAuto(false));
    }

    /** 验证空 ID 集合不会生成非法的 IN () SQL。 */
    @Test
    void emptyItemIdsReturnEmptyBlocks() {
        assertTrue(blockMapper.findByItemIds(Collections.<String>emptyList()).isEmpty());
    }
}
