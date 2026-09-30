package com.octoguide.knowledge.service;

import com.octoguide.knowledge.domain.KnowledgeBlock;
import com.octoguide.knowledge.domain.KnowledgeCategory;
import com.octoguide.knowledge.domain.KnowledgeItem;
import com.octoguide.knowledge.mapper.KnowledgeBlockMapper;
import com.octoguide.knowledge.mapper.KnowledgeCategoryMapper;
import com.octoguide.knowledge.mapper.KnowledgeItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AnswerService 检索与问答测试。
 *
 * <p>合并自原 AnswerServiceTest 与 AnswerServiceDetailedTest，覆盖：批量答案块装配、
 * 空查询 / 空来源等边界值、命中返回完整元数据、未知问题回退建议、来源与 RAG 上下文拼接、
 * 分类分组、按得分排序等场景。</p>
 */
class AnswerServiceTest {

    private KnowledgeItemMapper itemMapper;
    private KnowledgeBlockMapper blockMapper;
    private KnowledgeCategoryMapper categoryMapper;
    private AnswerService service;

    @BeforeEach
    void setUp() {
        itemMapper = mock(KnowledgeItemMapper.class);
        blockMapper = mock(KnowledgeBlockMapper.class);
        categoryMapper = mock(KnowledgeCategoryMapper.class);
        service = new AnswerService(categoryMapper, itemMapper, blockMapper);
    }

    /** 批量答案块应在检索前填充到对应条目。 */
    @Test
    void searchIncludesBlocksLoadedByBatchMapper() {
        KnowledgeCategory category = category("intro", "入门");
        KnowledgeItem item = item("intro-1", "什么是仓库", "仓库,repository", category,
                Collections.<KnowledgeBlock>emptyList());
        KnowledgeBlock block = block("text", "仓库用于保存代码和历史");
        block.setItemId("intro-1");
        when(itemMapper.findEnabledItems()).thenReturn(Collections.singletonList(item));
        when(blockMapper.findByItemIds(anyList())).thenReturn(Collections.singletonList(block));

        List<Map<String, Object>> results = service.search("仓库");

        assertEquals("仓库用于保存代码和历史",
                ((Map<?, ?>) ((List<?>) results.get(0).get("answer")).get(0)).get("c"));
    }

    /** 空查询应直接返回空列表，不触发任何数据库查询。 */
    @Test
    void searchReturnsEmptyForBlankQuery() {
        assertTrue(service.search("   ").isEmpty());
        assertTrue(service.search(null).isEmpty());
    }

    /** ask() 空输入应返回未命中提示，不抛出异常。 */
    @Test
    void askReturnsPromptForBlankInput() {
        Map<String, Object> result = service.ask("  ");

        assertFalse((Boolean) result.get("found"));
        assertEquals(0, ((Number) result.get("score")).intValue());
    }

    /** ask() 未知问题应回退到推荐建议，且回答包含提示文案。 */
    @Test
    void askReturnsSuggestionsForUnknownQuestion() {
        KnowledgeCategory category = category("intro", "入门");
        KnowledgeItem item = item("intro-1", "什么是仓库？", "仓库,repository", category,
                Collections.<KnowledgeBlock>emptyList());
        when(itemMapper.findEnabledItems()).thenReturn(Collections.<KnowledgeItem>emptyList());
        when(blockMapper.findByItemIds(anyList())).thenReturn(Collections.<KnowledgeBlock>emptyList());
        when(itemMapper.findById("intro-1")).thenReturn(item);

        Map<String, Object> result = service.ask("完全不相关的问题xyz");

        assertFalse((Boolean) result.get("found"));
        assertTrue(result.get("answer").toString().contains("还没收录"));
        // 预置推荐问题应出现在建议中
        List<Map<String, Object>> suggestions = (List<Map<String, Object>>) result.get("suggestions");
        assertTrue(suggestions.stream().anyMatch(s -> "什么是仓库？".equals(s.get("q"))));
    }

    /** ask() 命中问题应返回完整元数据：分类、图标、答案块、分数。 */
    @Test
    void askReturnsFullMetadataOnHit() {
        KnowledgeCategory category = category("collab", "协作");
        KnowledgeBlock block = block("text", "PR 是向他人仓库提交修改的合并请求");
        block.setItemId("collab-2");
        KnowledgeItem item = item("collab-2", "什么是 Pull Request（PR）？", "pr,pullrequest", category,
                Collections.singletonList(block));
        when(itemMapper.findEnabledItems()).thenReturn(Collections.singletonList(item));
        when(blockMapper.findByItemIds(anyList())).thenReturn(Collections.singletonList(block));

        Map<String, Object> result = service.ask("什么是 PR？");

        assertTrue((Boolean) result.get("found"));
        assertEquals("collab-2", result.get("questionId"));
        assertEquals("协作", result.get("category"));
        assertEquals("collab", result.get("categoryId"));
        assertEquals(category.getIcon(), result.get("icon"));
        assertTrue(((Number) result.get("score")).doubleValue() > 0);
    }

    /** ask() 命中有来源的条目时应附带 sourceUrl / sourceTitle。 */
    @Test
    void askIncludesSourceWhenPresent() {
        KnowledgeCategory category = category("docs", "文档");
        KnowledgeItem item = item("docs-1", "GitHub 是什么？", "github,入门", category,
                Collections.<KnowledgeBlock>emptyList());
        item.setSourceUrl("https://docs.github.com/zh");
        item.setSourceTitle("GitHub 文档");
        when(itemMapper.findEnabledItems()).thenReturn(Collections.singletonList(item));
        when(blockMapper.findByItemIds(anyList())).thenReturn(Collections.<KnowledgeBlock>emptyList());

        Map<String, Object> result = service.ask("GitHub 是什么");

        assertTrue((Boolean) result.get("found"));
        assertEquals("https://docs.github.com/zh", result.get("sourceUrl"));
        assertEquals("GitHub 文档", result.get("sourceTitle"));
    }

    /** sources() 应过滤空来源并遵守数量上限。 */
    @Test
    void sourcesFiltersEmptyUrlsAndHonorsLimit() {
        KnowledgeCategory category = category("c", "分类");
        KnowledgeItem withUrl = item("i1", "问题一", "", category, Collections.<KnowledgeBlock>emptyList());
        withUrl.setSourceUrl("https://github.com/a");
        withUrl.setSourceTitle("来源A");
        KnowledgeItem withoutUrl = item("i2", "问题二", "", category, Collections.<KnowledgeBlock>emptyList());
        KnowledgeItem withUrl2 = item("i3", "问题三", "", category, Collections.<KnowledgeBlock>emptyList());
        withUrl2.setSourceUrl("https://github.com/b");
        withUrl2.setSourceTitle("来源B");
        when(itemMapper.findEnabledItems()).thenReturn(Arrays.asList(withUrl, withoutUrl, withUrl2));
        when(blockMapper.findByItemIds(anyList())).thenReturn(Collections.<KnowledgeBlock>emptyList());

        List<Map<String, Object>> sources = service.sources("问题", 1);

        assertEquals(1, sources.size(), "limit=1 时只应返回 1 条来源");
        assertEquals("来源A", sources.get(0).get("title"));
        assertEquals("https://github.com/a", sources.get(0).get("url"));
    }

    /** ragContext() 应拼接资料与来源信息，并遵守 limit 上限。 */
    @Test
    void ragContextFormatsEntriesAndSources() {
        KnowledgeCategory category = category("c", "分类");
        KnowledgeBlock block = block("text", "答案正文");
        block.setItemId("i1");
        KnowledgeItem item = item("i1", "什么是仓库？", "", category, Collections.singletonList(block));
        item.setSourceTitle("仓库文档");
        when(itemMapper.findEnabledItems()).thenReturn(Collections.singletonList(item));
        when(blockMapper.findByItemIds(anyList())).thenReturn(Collections.singletonList(block));

        String context = service.ragContext("仓库", 4);

        assertTrue(context.contains("[资料1] 问：什么是仓库？"));
        assertTrue(context.contains("答案正文"));
        assertTrue(context.contains("（来源：仓库文档）"));
    }

    /** ragContext() limit 为 0 时应返回空字符串。 */
    @Test
    void ragContextReturnsEmptyWhenLimitIsZero() {
        KnowledgeCategory category = category("c", "分类");
        KnowledgeItem item = item("i1", "问题", "", category, Collections.<KnowledgeBlock>emptyList());
        when(itemMapper.findEnabledItems()).thenReturn(Collections.singletonList(item));
        when(blockMapper.findByItemIds(anyList())).thenReturn(Collections.<KnowledgeBlock>emptyList());

        assertEquals("", service.ragContext("问题", 0));
    }

    /** categories() 应把条目按分类分组，无条目的分类使用空列表。 */
    @Test
    void categoriesGroupsItemsByCategory() {
        KnowledgeCategory catA = category("a", "分类A");
        KnowledgeCategory catB = category("b", "分类B");
        KnowledgeItem itemA = item("a1", "问题A", "", catA, Collections.<KnowledgeBlock>emptyList());
        when(categoryMapper.findEnabledCategories()).thenReturn(Arrays.asList(catA, catB));
        when(itemMapper.findEnabledItems()).thenReturn(Collections.singletonList(itemA));
        when(blockMapper.findByItemIds(anyList())).thenReturn(Collections.<KnowledgeBlock>emptyList());

        List<KnowledgeCategory> result = service.categories();

        assertEquals(2, result.size());
        assertEquals(1, result.get(0).getItems().size());
        assertTrue(result.get(1).getItems().isEmpty(), "无条目分类应返回空列表而非 null");
    }

    /** search() 结果应按得分降序排列：完整短语命中（+5）的条目排在前。 */
    @Test
    void searchSortsByScoreDescending() {
        KnowledgeCategory category = category("c", "分类");
        // 高分组：问题/标签含"仓库"，同时命中完整短语 +5
        KnowledgeItem high = item("h", "什么是仓库", "仓库", category, Collections.<KnowledgeBlock>emptyList());
        // 低分组：仅标签含"repository"（同义词映射命中），无短语加分
        KnowledgeItem low = item("l", "如何上传代码", "repository", category, Collections.<KnowledgeBlock>emptyList());
        when(itemMapper.findEnabledItems()).thenReturn(Arrays.asList(low, high));
        when(blockMapper.findByItemIds(anyList())).thenReturn(Collections.<KnowledgeBlock>emptyList());

        List<Map<String, Object>> results = service.search("仓库");

        assertEquals(2, results.size());
        assertEquals("h", results.get(0).get("id"));
        assertTrue(((Number) results.get(0).get("score")).doubleValue()
                > ((Number) results.get(1).get("score")).doubleValue());
    }

    private static KnowledgeCategory category(String id, String name) {
        KnowledgeCategory category = new KnowledgeCategory();
        category.setId(id);
        category.setName(name);
        category.setIcon("◎");
        return category;
    }

    private static KnowledgeItem item(String id, String question, String tags, KnowledgeCategory category,
                                      List<KnowledgeBlock> blocks) {
        KnowledgeItem item = new KnowledgeItem();
        item.setId(id);
        item.setQuestion(question);
        item.setTags(tags);
        item.setCategory(category);
        item.setBlocks(blocks);
        return item;
    }

    private static KnowledgeBlock block(String type, String content) {
        KnowledgeBlock block = new KnowledgeBlock();
        block.setBlockType(type);
        block.setContent(content);
        block.setSortOrder(1);
        return block;
    }
}
