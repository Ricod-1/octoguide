package com.octoguide.service;

import com.octoguide.ai.LlmProperties;
import com.octoguide.dto.CategoryResponse;
import com.octoguide.dto.StatusResponse;
import com.octoguide.dto.TermResponse;
import com.octoguide.knowledge.domain.KnowledgeCategory;
import com.octoguide.knowledge.domain.Term;
import com.octoguide.knowledge.mapper.KnowledgeItemMapper;
import com.octoguide.knowledge.mapper.TermMapper;
import com.octoguide.knowledge.service.AnswerService;
import com.octoguide.mapper.KnowledgeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KnowledgeService 单元测试。
 *
 * <p>按《阿里巴巴Java开发手册》单元测试规约，覆盖全部公共方法，
 * 使用 mock 隔离 Mapper 依赖，不依赖真实数据库与网络。</p>
 */
public class KnowledgeServiceTest {

    private AnswerService answerService;
    private KnowledgeItemMapper itemMapper;
    private TermMapper termMapper;
    private KnowledgeMapper knowledgeMapper;
    private LlmProperties llmProperties;
    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        answerService = mock(AnswerService.class);
        itemMapper = mock(KnowledgeItemMapper.class);
        termMapper = mock(TermMapper.class);
        knowledgeMapper = mock(KnowledgeMapper.class);
        llmProperties = new LlmProperties();
        service = new KnowledgeService(answerService, itemMapper, termMapper, knowledgeMapper, llmProperties, true);
    }

    /** categories() 应委托 AnswerService 并转换为 CategoryResponse 列表。 */
    @Test
    void categoriesConvertsDomainToResponse() {
        KnowledgeCategory category = category("intro", "入门");
        when(answerService.categories()).thenReturn(Collections.singletonList(category));
        when(knowledgeMapper.toCategoryResponse(category))
                .thenReturn(new CategoryResponse("intro", "入门", "◎", "说明", false, Collections.emptyList()));

        List<CategoryResponse> result = service.categories();

        assertEquals(1, result.size());
        assertEquals("intro", result.get(0).getId());
    }

    /** terms() 应委托 TermMapper 并转换为 TermResponse 列表。 */
    @Test
    void termsConvertsDomainToResponse() {
        Term term = new Term();
        term.setEnglish("PR");
        term.setChinese("合并请求");
        term.setDescription("说明");
        when(termMapper.findAllOrdered()).thenReturn(Collections.singletonList(term));
        when(knowledgeMapper.toTermResponse(term)).thenReturn(new TermResponse("PR", "合并请求", "说明"));

        List<TermResponse> result = service.terms();

        assertEquals(1, result.size());
        assertEquals("PR", result.get(0).getEn());
    }

    /** ask() 应原样透传 AnswerService 的检索结果。 */
    @Test
    void askDelegatesToAnswerService() {
        Map<String, Object> answer = new LinkedHashMap<String, Object>();
        answer.put("found", true);
        when(answerService.ask("什么是 PR？")).thenReturn(answer);

        Map<String, Object> result = service.ask("什么是 PR？");

        assertEquals(answer, result);
    }

    /** autoCount() 应委托 itemMapper 统计自动条目数量。 */
    @Test
    void autoCountDelegatesToItemMapper() {
        when(itemMapper.countByAutoEntry(true)).thenReturn(5L);

        assertEquals(5L, service.autoCount());
    }

    /** status() 应正确汇总手工/自动条目数与来源分布，未匹配来源归入 other。 */
    @Test
    void statusAggregatesCountsAndSourceBreakdown() {
        when(itemMapper.countByAutoEntry(false)).thenReturn(19L);
        when(itemMapper.countByAutoEntry(true)).thenReturn(5L);
        when(itemMapper.countByAutoEntryAndSourceType(true, "docs")).thenReturn(2L);
        when(itemMapper.countByAutoEntryAndSourceType(true, "trending")).thenReturn(1L);
        when(itemMapper.countByAutoEntryAndSourceType(true, "rss")).thenReturn(0L);
        when(termMapper.count()).thenReturn(18L);
        llmProperties.setApiKey("test-key");
        llmProperties.setModel("gpt-4o-mini");

        StatusResponse status = service.status();

        assertEquals(5L, status.getAutoCount());
        assertEquals(19L, status.getManualCount());
        assertEquals(24L, status.getTotalCount());
        assertEquals(18L, status.getTermCount());
        assertTrue(status.isAiConfigured());
        // 已知来源 2+1+0=3，自动条目 5 条，其余 2 条归入 other
        assertEquals(2L, status.getSourceBreakdown().get("other").longValue());
        assertEquals("gpt-4o-mini", status.getAiModel());
    }

    /** status() 应按 is_auto 拆分分类计数，供前端统一统计口径。 */
    @Test
    void statusSplitsCategoryCountsByAutoFlag() {
        when(itemMapper.countByAutoEntry(false)).thenReturn(19L);
        when(itemMapper.countByAutoEntry(true)).thenReturn(1L);
        when(itemMapper.countByAutoEntryAndSourceType(true, "docs")).thenReturn(0L);
        when(itemMapper.countByAutoEntryAndSourceType(true, "trending")).thenReturn(0L);
        when(itemMapper.countByAutoEntryAndSourceType(true, "rss")).thenReturn(0L);
        when(termMapper.count()).thenReturn(18L);
        when(answerService.manualCategoryCount()).thenReturn(7L);
        when(answerService.autoCategoryCount()).thenReturn(3L);

        StatusResponse status = service.status();

        assertEquals(7L, status.getManualCategoryCount());
        assertEquals(3L, status.getAutoCategoryCount());
    }

    /** status() 在 AI 未配置时应正确反映 aiConfigured=false。 */
    @Test
    void statusReportsAiNotConfigured() {
        when(itemMapper.countByAutoEntry(false)).thenReturn(0L);
        when(itemMapper.countByAutoEntry(true)).thenReturn(0L);
        when(itemMapper.countByAutoEntryAndSourceType(true, "docs")).thenReturn(0L);
        when(itemMapper.countByAutoEntryAndSourceType(true, "trending")).thenReturn(0L);
        when(itemMapper.countByAutoEntryAndSourceType(true, "rss")).thenReturn(0L);
        when(termMapper.count()).thenReturn(0L);
        // 未设置 apiKey，视为未配置
        llmProperties.setApiKey("  ");

        StatusResponse status = service.status();

        assertFalse(status.isAiConfigured());
    }

    /** AI 已配置时 status() 应反映联网研究可用，而不是恒为 false。 */
    @Test
    void statusReportsWebResearchEnabledWhenAiConfigured() {
        when(itemMapper.countByAutoEntry(false)).thenReturn(0L);
        when(itemMapper.countByAutoEntry(true)).thenReturn(0L);
        when(itemMapper.countByAutoEntryAndSourceType(true, "docs")).thenReturn(0L);
        when(itemMapper.countByAutoEntryAndSourceType(true, "trending")).thenReturn(0L);
        when(itemMapper.countByAutoEntryAndSourceType(true, "rss")).thenReturn(0L);
        when(termMapper.count()).thenReturn(0L);
        llmProperties.setApiKey("test-key");

        StatusResponse status = service.status();

        assertTrue(status.isWebResearchEnabled());
    }

    /** 构造带分类的测试分类对象。 */
    private static KnowledgeCategory category(String id, String name) {
        KnowledgeCategory category = new KnowledgeCategory();
        category.setId(id);
        category.setName(name);
        return category;
    }
}
