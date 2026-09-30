package com.octoguide.knowledge.service;

import com.octoguide.knowledge.domain.KnowledgeBlock;
import com.octoguide.knowledge.domain.KnowledgeCategory;
import com.octoguide.knowledge.domain.KnowledgeItem;
import com.octoguide.knowledge.mapper.KnowledgeBlockMapper;
import com.octoguide.knowledge.mapper.KnowledgeCategoryMapper;
import com.octoguide.knowledge.mapper.KnowledgeItemMapper;
import com.octoguide.knowledge.mapper.KnowledgeSourceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识更新任务入口：采集 → 转化 → 合并 → 保存。
 *
 * <p>与 Node 版 {@code lib/knowledge-updater.js} 的 {@code runKnowledgeUpdate()} 行为对齐。
 * 每次运行时，启用的数据源会被逐个抓取并生成知识条目；本次涉及的数据源按源重建
 * （删除旧条目后插入新条目），未涉及的源保持不变。统计新增、更新和移除数量。</p>
 *
 * <p>抓取与入库分离：数据源的网络抓取在事务外完成（单源失败不影响其它源且不占用
 * 数据库连接），确认要写入的条目后，再在单个事务内按源删除并用批量 INSERT 入库。</p>
 */
@Service
public class KnowledgeUpdater {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeUpdater.class);

    /** 未匹配到分类时使用的默认分类名称。 */
    private static final String DEFAULT_CATEGORY = "自动采集";

    private final KnowledgeSourceMapper sourceMapper;
    private final KnowledgeItemMapper itemMapper;
    private final KnowledgeBlockMapper blockMapper;
    private final KnowledgeCategoryMapper categoryMapper;
    private final ObjectProvider<PlatformTransactionManager> transactionManagerProvider;

    private volatile LocalDateTime lastRun;

    public KnowledgeUpdater(KnowledgeSourceMapper sourceMapper, KnowledgeItemMapper itemMapper,
                            KnowledgeBlockMapper blockMapper, KnowledgeCategoryMapper categoryMapper,
                            ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
        this.sourceMapper = sourceMapper;
        this.itemMapper = itemMapper;
        this.blockMapper = blockMapper;
        this.categoryMapper = categoryMapper;
        this.transactionManagerProvider = transactionManagerProvider;
    }

    /** 每周日 18:00（北京时间）自动触发一次更新任务。 */
    @Scheduled(cron = "0 0 18 ? * SUN", zone = "Asia/Shanghai")
    public void scheduledUpdate() {
        run();
    }

    /**
     * 手动或定时触发知识更新，使用默认 HTTP 抓取器。
     *
     * @return 更新结果统计（ok / added / updated / removed / total / updatedAt）
     */
    public Map<String, Object> run() {
        return runWithFetcher(KnowledgeCollector::fetchPage);
    }

    /**
     * 使用可注入的页面抓取器执行知识更新，便于测试时注入内存 HTML。
     *
     * <p>抓取和条目计算在事务外进行，只有确认后的删除/批量写入放在事务内，
     * 避免长时间的网络 I/O 占用数据库连接。</p>
     *
     * @param fetcher 页面抓取函数
     * @return 更新结果统计
     */
    public Map<String, Object> runWithFetcher(KnowledgeCollector.PageFetcher fetcher) {
        lastRun = LocalDateTime.now();
        log.info("[update] === 开始知识更新 ===");

        // 1. 加载启用的数据源并转换为采集器输入格式
        List<com.octoguide.knowledge.domain.KnowledgeSource> dbSources = sourceMapper.findEnabledSources();
        List<KnowledgeSource> collectSources = new ArrayList<KnowledgeSource>();
        Map<String, String> categoryNameById = loadCategoryNameMap();
        for (com.octoguide.knowledge.domain.KnowledgeSource dbSource : dbSources) {
            KnowledgeSource source = new KnowledgeSource();
            source.setId(dbSource.getId());
            source.setTitle(dbSource.getTitle());
            source.setUrl(dbSource.getUrl());
            source.setType(dbSource.getSourceType());
            // category_id → 分类名称（采集器按名称回退分类）
            String categoryName = categoryNameById.get(dbSource.getCategoryId());
            source.setCategory(categoryName == null ? DEFAULT_CATEGORY : categoryName);
            source.setTags(dbSource.getTagsList());
            source.setSiteTitle(dbSource.getSiteTitle());
            collectSources.add(source);
        }

        // 2. 采集 + 转化（每个源按类型生成条目，含来源字段）。网络抓取在事务外执行。
        List<KnowledgeEntry> allItems = dedupeById(KnowledgeCollector.collect(collectSources, fetcher,
                msg -> log.info(msg)));

        // 3. 在同一事务内完成统计与按源重建，保证“读取旧数据 → 删除 → 批量写入”原子一致。
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManagerProvider.getObject());
        Map<String, Object> result = transactionTemplate.execute(status -> {
            Map<String, String> categoryIdByName = loadCategoryIdMap();

            // 3.1 加载现有自动条目，构建 ID 集合和按源分组，计算新增/更新/移除统计。
            List<KnowledgeItem> existingAuto = itemMapper.findAllAutoEntries();
            Set<String> existingIds = new HashSet<String>();
            Map<String, List<String>> existingIdsBySource = new HashMap<String, List<String>>();
            for (KnowledgeItem item : existingAuto) {
                existingIds.add(item.getId());
                String sid = item.getSourceId();
                if (sid != null) {
                    existingIdsBySource.computeIfAbsent(sid, k -> new ArrayList<String>()).add(item.getId());
                }
            }

            int added = 0;
            int updated = 0;
            Set<String> involvedSourceIds = new HashSet<String>();
            Set<String> newIds = new HashSet<String>();
            for (KnowledgeEntry entry : allItems) {
                if (existingIds.contains(entry.getId())) {
                    updated++;
                } else {
                    added++;
                }
                if (entry.getSourceId() != null) {
                    involvedSourceIds.add(entry.getSourceId());
                }
                newIds.add(entry.getId());
            }

            // 3.2 统计移除数量：本次涉及源中、不再出现于新条目集合的旧自动条目。
            int removed = 0;
            for (String sourceId : involvedSourceIds) {
                List<String> sourceItemIds = existingIdsBySource.get(sourceId);
                if (sourceItemIds != null) {
                    for (String itemId : sourceItemIds) {
                        if (!newIds.contains(itemId)) {
                            removed++;
                        }
                    }
                }
            }

            // 3.3 按源重建 + 批量写入（答案块由外键 CASCADE 自动删除）。
            List<KnowledgeItem> itemsToInsert = new ArrayList<KnowledgeItem>();
            List<KnowledgeBlock> blocksToInsert = new ArrayList<KnowledgeBlock>();
            for (KnowledgeEntry entry : allItems) {
                itemsToInsert.add(toItem(entry, categoryIdByName));
                blocksToInsert.addAll(toBlocks(entry));
            }
            for (String sourceId : involvedSourceIds) {
                itemMapper.deleteBySourceId(sourceId);
            }
            if (!itemsToInsert.isEmpty()) {
                itemMapper.insertBatch(itemsToInsert);
            }
            if (!blocksToInsert.isEmpty()) {
                blockMapper.insertBatch(blocksToInsert);
            }
            long total = itemMapper.countByAutoEntry(true);
            log.info("[update] 新增 {} 条，更新 {} 条，移除陈旧 {} 条，总数 {}", added, updated,
                    removed, total);

            Map<String, Object> updateResult = new LinkedHashMap<String, Object>();
            updateResult.put("ok", true);
            updateResult.put("added", added);
            updateResult.put("updated", updated);
            updateResult.put("removed", removed);
            updateResult.put("total", total);
            updateResult.put("updatedAt", lastRun.toString());
            return updateResult;
        });
        return result == null ? new LinkedHashMap<String, Object>() : result;
    }

    /** 返回最近一次任务触发时间。 */
    public LocalDateTime getLastRun() {
        return lastRun;
    }

    /**
     * 同批次条目按 id 去重，保留先采集到的条目。
     *
     * <p>条目 id 仅由章节标题哈希生成，不同页面或不同数据源可能出现同名章节；若不去重，
     * 空库首次写入时重复 id 的第二条 INSERT 会因主键冲突导致整个更新事务回滚。</p>
     *
     * @param items 采集生成的知识条目
     * @return 按 id 去重后的条目列表
     */
    private List<KnowledgeEntry> dedupeById(List<KnowledgeEntry> items) {
        Map<String, KnowledgeEntry> unique = new LinkedHashMap<String, KnowledgeEntry>();
        for (KnowledgeEntry entry : items) {
            KnowledgeEntry previous = unique.put(entry.getId(), entry);
            if (previous != null) {
                log.warn("[update] 同批次出现重复条目 id {}，保留先采集到的版本", entry.getId());
            }
        }
        return new ArrayList<KnowledgeEntry>(unique.values());
    }

    /** 将采集生成的条目转换为数据库实体（分类按名称解析，找不到时用默认分类）。 */
    private KnowledgeItem toItem(KnowledgeEntry entry, Map<String, String> categoryIdByName) {
        KnowledgeItem item = new KnowledgeItem();
        item.setId(entry.getId());
        item.setQuestion(entry.getQuestion());
        item.setTags(String.join(",", entry.getTags()));
        // 来源类型来自采集器写入的数据源类型（docs / trending / rss），缺失时回退 auto
        item.setSourceType(entry.getSource() == null || entry.getSource().trim().isEmpty()
                ? "auto" : entry.getSource());
        item.setSourceUrl(entry.getSourceUrl());
        item.setSourceTitle(entry.getSourceTitle());
        item.setSourceId(entry.getSourceId());
        item.setAutoEntry(true);
        item.setEnabled(true);

        // 分类名称 → category_id；找不到时使用默认分类
        String categoryId = categoryIdByName.get(entry.getCategory());
        if (categoryId == null) {
            categoryId = categoryIdByName.get(DEFAULT_CATEGORY);
        }
        KnowledgeCategory category = new KnowledgeCategory();
        category.setId(categoryId);
        item.setCategory(category);
        return item;
    }

    /** 将采集条目中的答案块转换为数据库实体列表。 */
    private List<KnowledgeBlock> toBlocks(KnowledgeEntry entry) {
        List<KnowledgeBlock> blocks = new ArrayList<KnowledgeBlock>();
        List<KnowledgeEntry.AnswerBlock> answer = entry.getAnswer();
        if (answer != null) {
            int sortOrder = 1;
            for (KnowledgeEntry.AnswerBlock block : answer) {
                KnowledgeBlock kb = new KnowledgeBlock();
                kb.setItemId(entry.getId());
                kb.setBlockType(block.getType());
                kb.setContent(block.getContent());
                kb.setSortOrder(sortOrder++);
                blocks.add(kb);
            }
        }
        return blocks;
    }

    /** 加载分类 ID 到名称的映射。 */
    private Map<String, String> loadCategoryNameMap() {
        List<KnowledgeCategory> categories = categoryMapper.findEnabledCategories();
        Map<String, String> map = new HashMap<String, String>();
        for (KnowledgeCategory category : categories) {
            map.put(category.getId(), category.getName());
        }
        return map;
    }

    /** 加载分类名称到 ID 的映射。 */
    private Map<String, String> loadCategoryIdMap() {
        List<KnowledgeCategory> categories = categoryMapper.findEnabledCategories();
        Map<String, String> map = new HashMap<String, String>();
        for (KnowledgeCategory category : categories) {
            map.put(category.getName(), category.getId());
        }
        return map;
    }
}
