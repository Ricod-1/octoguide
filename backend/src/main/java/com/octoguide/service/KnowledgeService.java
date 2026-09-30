package com.octoguide.service;

import com.octoguide.ai.LlmProperties;
import com.octoguide.dto.CategoryResponse;
import com.octoguide.dto.StatusResponse;
import com.octoguide.dto.TermResponse;
import com.octoguide.knowledge.mapper.KnowledgeItemMapper;
import com.octoguide.knowledge.mapper.TermMapper;
import com.octoguide.knowledge.service.AnswerService;
import com.octoguide.mapper.KnowledgeMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 知识库对外应用服务，负责聚合检索、分类、术语和状态数据。 */
@Service
public class KnowledgeService {

    private static final String UPDATE_SCHEDULE = "每周日 18:00 自动更新";

    private final AnswerService answerService;
    private final KnowledgeItemMapper itemMapper;
    private final TermMapper termMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final LlmProperties llmProperties;
    private final boolean webResearchEnabled;

    public KnowledgeService(AnswerService answerService,
                            KnowledgeItemMapper itemMapper,
                            TermMapper termMapper,
                            KnowledgeMapper knowledgeMapper,
                            LlmProperties llmProperties,
                            @Value("${octoguide.web.research-enabled:true}") boolean webResearchEnabled) {
        this.answerService = answerService;
        this.itemMapper = itemMapper;
        this.termMapper = termMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.llmProperties = llmProperties;
        this.webResearchEnabled = webResearchEnabled;
    }

    /** 查询启用分类并转换为前端响应结构。 */
    @Transactional(readOnly = true)
    public List<CategoryResponse> categories() {
        return answerService.categories().stream()
                .map(knowledgeMapper::toCategoryResponse)
                .collect(Collectors.toList());
    }

    /** 查询按显示顺序排列的术语。 */
    @Transactional(readOnly = true)
    public List<TermResponse> terms() {
        return termMapper.findAllOrdered().stream()
                .map(knowledgeMapper::toTermResponse)
                .collect(Collectors.toList());
    }

    /** 执行本地知识库问答。 */
    @Transactional(readOnly = true)
    public Map<String, Object> ask(String question) {
        return answerService.ask(question);
    }

    /** 返回自动采集条目数量。 */
    @Transactional(readOnly = true)
    public long autoCount() {
        return itemMapper.countByAutoEntry(true);
    }

    /**
     * 生成状态页所需统计数据。
     *
     * <p>来源分类统计只覆盖当前已知类型，未匹配类型统一归入 {@code other}。</p>
     */
    @Transactional(readOnly = true)
    public StatusResponse status() {
        long manualCount = itemMapper.countByAutoEntry(false);
        long autoCount = itemMapper.countByAutoEntry(true);
        long totalCount = manualCount + autoCount;

        Map<String, Long> sourceBreakdown = new LinkedHashMap<String, Long>();
        sourceBreakdown.put("docs", itemMapper.countByAutoEntryAndSourceType(true, "docs"));
        sourceBreakdown.put("trending", itemMapper.countByAutoEntryAndSourceType(true, "trending"));
        sourceBreakdown.put("rss", itemMapper.countByAutoEntryAndSourceType(true, "rss"));
        long knownCount = sourceBreakdown.get("docs")
                + sourceBreakdown.get("trending")
                + sourceBreakdown.get("rss");
        sourceBreakdown.put("other", Math.max(0, autoCount - knownCount));

        return new StatusResponse(autoCount, manualCount, totalCount, termMapper.count(),
                llmProperties.isConfigured(), llmProperties.isConfigured() && webResearchEnabled, null,
                UPDATE_SCHEDULE, sourceBreakdown, llmProperties.getModel(),
                answerService.manualCategoryCount(), answerService.autoCategoryCount());
    }
}
