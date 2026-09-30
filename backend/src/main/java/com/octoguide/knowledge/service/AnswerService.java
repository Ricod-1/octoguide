package com.octoguide.knowledge.service;

import com.octoguide.knowledge.domain.KnowledgeCategory;
import com.octoguide.knowledge.domain.KnowledgeBlock;
import com.octoguide.knowledge.domain.KnowledgeItem;
import com.octoguide.knowledge.mapper.KnowledgeBlockMapper;
import com.octoguide.knowledge.mapper.KnowledgeCategoryMapper;
import com.octoguide.knowledge.mapper.KnowledgeItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库检索与回答服务。
 *
 * <p>检索流程为：文本归一化、关键词提取、IDF 加权排序，最后将最高分条目转换为 Node 兼容的响应结构。</p>
 */
@Service
public class AnswerService {

    /** 不参与检索排序的常见虚词。 */
    private static final Set<String> STOPWORDS = new HashSet<String>(Arrays.asList(
            "怎么", "如何", "什么", "为什么", "吗", "呢", "请", "问", "一下", "告诉我", "帮我",
            "我", "你", "的", "了", "是", "在", "到", "把", "被", "和", "与", "或",
            "github", "git", "how", "what", "why", "the", "a", "to", "of"));

    /** 将用户常用说法映射到知识库统一概念。 */
    private static final Map<String, String> SYNONYMS = createSynonyms();

    private final KnowledgeCategoryMapper categoryMapper;
    private final KnowledgeItemMapper itemMapper;
    private final KnowledgeBlockMapper blockMapper;

    /**
     * 创建知识检索服务。
     *
     * @param categoryMapper 分类查询 Mapper
     * @param itemMapper 条目查询 Mapper
     * @param blockMapper 答案块查询 Mapper
     */
    public AnswerService(KnowledgeCategoryMapper categoryMapper,
                         KnowledgeItemMapper itemMapper,
                         KnowledgeBlockMapper blockMapper) {
        this.categoryMapper = categoryMapper;
        this.itemMapper = itemMapper;
        this.blockMapper = blockMapper;
    }

    /** 查询启用的知识分类及其问题。 */
    @Transactional(readOnly = true)
    public List<KnowledgeCategory> categories() {
        List<KnowledgeCategory> categories = categoryMapper.findEnabledCategories();
        List<KnowledgeItem> items = items();
        Map<String, List<KnowledgeItem>> itemsByCategory = items.stream()
                .filter(item -> item.getCategory() != null)
                .collect(Collectors.groupingBy(item -> item.getCategory().getId()));
        for (KnowledgeCategory category : categories) {
            category.setItems(itemsByCategory.containsKey(category.getId())
                    ? itemsByCategory.get(category.getId()) : Collections.<KnowledgeItem>emptyList());
        }
        return categories;
    }

    /** 统计启用的自动采集分类数量。 */
    @Transactional(readOnly = true)
    public long autoCategoryCount() {
        return categoryMapper.countByAuto(true);
    }

    /** 统计启用的人工维护分类数量。 */
    @Transactional(readOnly = true)
    public long manualCategoryCount() {
        return categoryMapper.countByAuto(false);
    }

    /** 查询所有启用的知识条目，供检索器计算词频。 */
    public List<KnowledgeItem> items() {
        List<KnowledgeItem> items = itemMapper.findEnabledItems();
        attachBlocks(items);
        return items;
    }

    /**
     * 对问题进行关键词检索并按相关性降序返回。
     *
     * <p>IDF 权重用于降低高频词对结果的影响；完整短语命中会额外加分。</p>
     */
    public List<Map<String, Object>> search(String query) {
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> queryWords = keywords(normalizedQuery);
        List<KnowledgeItem> allItems = items();
        Map<String, Integer> documentFrequency = documentFrequency(allItems);
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();

        for (KnowledgeItem item : allItems) {
            String searchableText = normalize(item.getQuestion() + " " + String.join(" ", item.getTags()));
            Set<String> itemWords = keywords(searchableText);
            double score = score(queryWords, itemWords, searchableText, normalizedQuery,
                    documentFrequency, allItems.size());
            if (score > 0) {
                Map<String, Object> row = itemMap(item);
                row.put("score", score);
                results.add(row);
            }
        }

        results.sort((left, right) -> Double.compare(
                ((Number) right.get("score")).doubleValue(),
                ((Number) left.get("score")).doubleValue()));
        return results;
    }

    /** 将检索结果拼接为提供给大模型的 RAG 上下文。 */
    public String ragContext(String query, int limit) {
        return ragContextFrom(search(query), limit);
    }

    /** 将已排好序的检索结果拼接为 RAG 上下文；单请求内检索一次后多次复用，避免重复全量扫描。 */
    public String ragContextFrom(List<Map<String, Object>> matches, int limit) {
        StringBuilder context = new StringBuilder();
        int end = Math.min(Math.max(0, limit), matches == null ? 0 : matches.size());

        for (int index = 0; index < end; index++) {
            Map<String, Object> item = matches.get(index);
            if (index > 0) {
                context.append("\n\n");
            }
            context.append("[资料").append(index + 1).append("] 问：")
                    .append(item.get("question")).append("\n答：");
            appendAnswerBlocks(context, item.get("answer"));
            if (item.get("sourceTitle") != null) {
                context.append("（来源：").append(item.get("sourceTitle")).append("）");
            }
        }
        return context.toString();
    }

    /** 提取检索结果中的来源链接，供聊天完成事件使用。 */
    public List<Map<String, Object>> sources(String query, int limit) {
        return sourcesFrom(search(query), limit);
    }

    /** 从已排好序的检索结果中提取来源链接；单请求内检索一次后多次复用，避免重复全量扫描。 */
    public List<Map<String, Object>> sourcesFrom(List<Map<String, Object>> matches, int limit) {
        List<Map<String, Object>> sources = new ArrayList<Map<String, Object>>();
        if (matches == null) {
            return sources;
        }
        for (Map<String, Object> item : matches) {
            Object url = item.get("sourceUrl");
            if (url == null || String.valueOf(url).trim().isEmpty()) {
                continue;
            }

            Map<String, Object> source = new LinkedHashMap<String, Object>();
            source.put("title", item.get("sourceTitle") == null
                    ? item.get("question") : item.get("sourceTitle"));
            source.put("url", url);
            sources.add(source);
            if (sources.size() >= limit) {
                break;
            }
        }
        return sources;
    }

    /**
     * 执行同步知识库问答。
     *
     * <p>低于最低命中分数时不猜测答案，而是返回提示和推荐问题。</p>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> ask(String query) {
        String input = query == null ? "" : query.trim();
        return askFrom(input, search(input));
    }

    /**
     * 基于已排好序的检索结果构造问答响应。
     *
     * <p>供同一请求内“先检索一次、再派生出问答/上下文/来源”的调用方使用，
     * 避免 {@link #ask} 内部再次触发全量 {@link #search}。空输入不触发检索。</p>
     */
    public Map<String, Object> askFrom(String query, List<Map<String, Object>> matches) {
        String input = query == null ? "" : query.trim();
        if (input.isEmpty()) {
            return response(false, null, "", "", Collections.singletonList(block(
                    "text", "请先输入你想问的问题，例如“什么是 PR？”或“怎么上传代码？”")),
                    Collections.emptyList(), 0);
        }

        if (matches == null || matches.isEmpty() || scoreOf(matches.get(0)) < 1) {
            return unknownAnswer();
        }

        Map<String, Object> top = matches.get(0);
        List<Map<String, Object>> suggestions = matches.subList(1, Math.min(3, matches.size()))
                .stream().map(this::suggestionFromMap).collect(Collectors.toList());
        Map<String, Object> result = response(true, top.get("id"), (String) top.get("question"),
                (String) top.get("category"), castAnswer(top.get("answer")), suggestions, scoreOf(top));
        result.put("categoryId", top.get("categoryId"));
        result.put("icon", top.get("icon"));
        if (hasText(top.get("sourceUrl"))) {
            result.put("sourceUrl", top.get("sourceUrl"));
            result.put("sourceTitle", top.get("sourceTitle"));
        }
        return result;
    }

    private Map<String, Integer> documentFrequency(List<KnowledgeItem> items) {
        Map<String, Integer> frequency = new HashMap<String, Integer>();
        for (KnowledgeItem item : items) {
            for (String word : keywords(item.getQuestion() + " " + String.join(" ", item.getTags()))) {
                frequency.put(word, frequency.containsKey(word) ? frequency.get(word) + 1 : 1);
            }
        }
        return frequency;
    }

    private double score(Set<String> queryWords, Set<String> itemWords, String searchableText,
                         String normalizedQuery, Map<String, Integer> frequency, int itemCount) {
        double score = 0;
        for (String word : queryWords) {
            if (itemWords.contains(word) || searchableText.contains(word)) {
                int documentCount = frequency.containsKey(word) ? frequency.get(word) : 1;
                score += 2 * (1 + Math.log((double) Math.max(1, itemCount) / Math.max(1, documentCount)));
            }
        }
        if (searchableText.contains(normalizedQuery)) {
            score += 5;
        }
        return score;
    }

    private void appendAnswerBlocks(StringBuilder context, Object answer) {
        if (!(answer instanceof List)) {
            return;
        }
        for (Object block : (List<?>) answer) {
            if (block instanceof Map) {
                context.append(String.valueOf(((Map<?, ?>) block).get("c"))).append("\n");
            }
        }
    }

    private Map<String, Object> unknownAnswer() {
        List<Map<String, Object>> suggestions = new ArrayList<Map<String, Object>>();
        for (String id : Arrays.asList("intro-1", "repo-3", "collab-2", "errors-3", "branch-1")) {
            KnowledgeItem item = itemMapper.findById(id);
            if (item != null) {
                suggestions.add(mapSuggestion(item));
            }
        }
        return response(false, null, "", "", Collections.singletonList(block(
                "text", "这个问题我暂时还没收录到知识库里。你可以换个说法试试，比如包含“PR”“push”“冲突”“SSH”等关键词。")),
                suggestions, 0);
    }

    private Map<String, Object> itemMap(KnowledgeItem item) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", item.getId());
        result.put("question", item.getQuestion());
        result.put("category", item.getCategory().getName());
        result.put("categoryId", item.getCategory().getId());
        result.put("icon", item.getCategory().getIcon());
        result.put("tags", item.getTags());
        result.put("answer", item.getBlocks().stream()
                .map(block -> block(block.getBlockType(), block.getContent()))
                .collect(Collectors.toList()));
        result.put("sourceUrl", item.getSourceUrl());
        result.put("sourceTitle", item.getSourceTitle());
        return result;
    }

    /** 批量加载答案块并按条目 ID 组装，避免逐条查询造成 N+1。 */
    private void attachBlocks(List<KnowledgeItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<String> itemIds = items.stream().map(KnowledgeItem::getId).collect(Collectors.toList());
        Map<String, List<KnowledgeBlock>> blocksByItem = blockMapper.findByItemIds(itemIds).stream()
                .collect(Collectors.groupingBy(KnowledgeBlock::getItemId));
        for (KnowledgeItem item : items) {
            item.setBlocks(blocksByItem.containsKey(item.getId())
                    ? blocksByItem.get(item.getId()) : Collections.<KnowledgeBlock>emptyList());
        }
    }

    private Map<String, Object> response(boolean found, Object id, String question, String category,
                                         List<Map<String, Object>> answer,
                                         List<Map<String, Object>> suggestions, double score) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("found", found);
        result.put("questionId", id);
        result.put("question", question);
        result.put("category", category);
        result.put("icon", "");
        result.put("answer", answer);
        result.put("suggestions", suggestions);
        result.put("score", score);
        return result;
    }

    private Map<String, Object> mapSuggestion(KnowledgeItem item) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("q", item.getQuestion());
        result.put("id", item.getId());
        return result;
    }

    private Map<String, Object> suggestionFromMap(Map<String, Object> item) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("q", item.get("question"));
        result.put("id", item.get("id"));
        return result;
    }

    private Map<String, Object> block(String type, String content) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("t", type);
        result.put("c", content);
        return result;
    }

    private String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text.toLowerCase(Locale.ROOT), Normalizer.Form.NFKC)
                .replaceAll("[()（）【】\\[\\]「」『』,，。.！!？?；;：:、\\\"“”‘’\\s]+", " ")
                .trim();
    }

    private Set<String> keywords(String text) {
        Set<String> result = new LinkedHashSet<String>();
        String normalized = normalize(text);
        for (String token : normalized.split(" ")) {
            if (token.length() > 1 && !STOPWORDS.contains(token)) {
                result.add(SYNONYMS.containsKey(token) ? SYNONYMS.get(token) : token);
            }
        }
        for (Map.Entry<String, String> entry : SYNONYMS.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                result.add(entry.getValue());
            }
        }
        return result;
    }

    private double scoreOf(Map<String, Object> item) {
        return ((Number) item.get("score")).doubleValue();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castAnswer(Object answer) {
        return (List<Map<String, Object>>) answer;
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).trim().isEmpty();
    }

    private static Map<String, String> createSynonyms() {
        Map<String, String> synonyms = new HashMap<String, String>();
        String[][] pairs = {
                {"pr", "pullrequest"}, {"pull request", "pullrequest"}, {"合并请求", "pullrequest"},
                {"仓库", "repository"}, {"项目", "repository"}, {"上传", "push"}, {"推送", "push"},
                {"下载", "clone"}, {"克隆", "clone"}, {"同步", "pull"}, {"提交", "commit"},
                {"版本", "version"}, {"历史", "log"}, {"回退", "revert"}, {"撤销", "revert"},
                {"分支", "branch"}, {"合并", "merge"}, {"冲突", "conflict"}, {"问题单", "issue"},
                {"收藏", "star"}, {"复制", "fork"}, {"自动化", "actions"}, {"密钥", "ssh"},
                {"配置", "ssh"}, {"说明", "readme"}, {"报错", "error"}, {"失败", "error"},
                {"创建", "create"}, {"初始化", "init"}
        };
        for (String[] pair : pairs) {
            synonyms.put(pair[0], pair[1]);
        }
        return synonyms;
    }
}
