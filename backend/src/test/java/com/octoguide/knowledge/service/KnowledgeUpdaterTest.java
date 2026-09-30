package com.octoguide.knowledge.service;

import com.octoguide.knowledge.domain.KnowledgeItem;
import com.octoguide.knowledge.mapper.KnowledgeBlockMapper;
import com.octoguide.knowledge.mapper.KnowledgeCategoryMapper;
import com.octoguide.knowledge.mapper.KnowledgeItemMapper;
import com.octoguide.knowledge.mapper.KnowledgeSourceMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KnowledgeUpdater 集成测试：验证采集 → 转化 → 合并 → 保存的完整链路。
 *
 * <p>使用 H2 内存数据库和内存 PageFetcher，不依赖网络和本地 MySQL。</p>
 */
@MybatisTest
@Import(KnowledgeUpdater.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.encoding=UTF-8",
        "mybatis.mapper-locations=classpath:/mapper/*.xml"
})
class KnowledgeUpdaterTest {

    @Autowired
    private KnowledgeUpdater updater;

    @Autowired
    private KnowledgeItemMapper itemMapper;

    @Autowired
    private KnowledgeBlockMapper blockMapper;

    @Autowired
    private KnowledgeSourceMapper sourceMapper;

    @Autowired
    private KnowledgeCategoryMapper categoryMapper;

    /** 构造一个包含单个 h2 章节的测试 HTML，用于 docs 类型数据源采集。 */
    private String testHtml(String heading, String paragraph) {
        return "<html><head><title>测试页面</title></head><body>"
                + "<main>"
                + "<h2 id=\"" + heading.toLowerCase().replace(" ", "-") + "\">" + heading + "</h2>"
                + "<p>" + paragraph + "</p>"
                + "</main></body></html>";
    }

    /** 验证基本采集和入库：运行一次更新，自动条目被插入，统计正确。 */
    @Test
    void collectsAndSavesAutoEntries() {
        // 预置条件：data.sql 中已有 1 个手工条目，0 个自动条目
        assertEquals(0, itemMapper.countByAutoEntry(true));

        Map<String, Object> result = updater.runWithFetcher(url ->
                testHtml("什么是仓库", "仓库是存放代码的地方，可以记录版本历史。"));

        assertTrue((Boolean) result.get("ok"));
        // 采集生成 1 个新条目（h2 标题满足最短长度，段落满足最少块数）
        assertEquals(1, result.get("added"));
        assertEquals(0, result.get("updated"));
        assertEquals(0, result.get("removed"));

        // 验证数据库中自动条目数量和内容
        List<KnowledgeItem> autoItems = itemMapper.findAllAutoEntries();
        assertEquals(1, autoItems.size());
        KnowledgeItem item = autoItems.get(0);
        assertTrue(item.getQuestion().contains("仓库"));
        assertEquals("test-docs", item.getSourceId());
        assertEquals("docs", item.getSourceType());
        // 答案块已插入
        assertEquals(1, blockMapper.findByItemIds(
                java.util.Collections.singletonList(item.getId())).size());
    }

    /** 验证幂等性：同样的输入运行两次，第二次只更新不新增，不移除。 */
    @Test
    void reRunWithSameContentIsIdempotent() {
        KnowledgeCollector.PageFetcher fetcher = url ->
                testHtml("什么是分支", "分支是从主线分出的平行开发线。");

        // 第一次运行：新增
        Map<String, Object> first = updater.runWithFetcher(fetcher);
        assertEquals(1, first.get("added"));
        assertEquals(0, first.get("updated"));

        // 第二次运行：同样的内容，条目 ID 相同（基于标题 MD5），所以是更新而非新增
        Map<String, Object> second = updater.runWithFetcher(fetcher);
        assertEquals(0, second.get("added"));
        assertEquals(1, second.get("updated"));
        assertEquals(0, second.get("removed"));

        // 总数不变
        assertEquals(1, itemMapper.countByAutoEntry(true));
    }

    /** 验证源内容变化时旧条目被移除：同一源采集不同内容，旧条目被删除。 */
    @Test
    void sourceContentChangeRemovesStaleEntries() {
        // 第一次采集：生成"什么是提交"条目
        updater.runWithFetcher(url ->
                testHtml("什么是提交", "提交是一次代码版本的存档。"));
        assertEquals(1, itemMapper.countByAutoEntry(true));

        // 第二次采集：同一源，不同内容（"什么是合并"），旧条目应该被移除
        Map<String, Object> result = updater.runWithFetcher(url ->
                testHtml("什么是合并", "合并是把分支或改动合到一起。"));

        // 新条目是新增，旧条目被移除
        assertEquals(1, result.get("added"));
        assertEquals(0, result.get("updated"));
        assertEquals(1, result.get("removed"));

        // 总数仍为 1（旧的被删，新的被插）
        assertEquals(1, itemMapper.countByAutoEntry(true));
        // 验证新条目的问题包含"合并"
        List<KnowledgeItem> items = itemMapper.findAllAutoEntries();
        assertEquals(1, items.size());
        assertTrue(items.get(0).getQuestion().contains("合并"));
    }

    /** 验证手工条目不受自动更新影响。 */
    @Test
    void manualEntriesAreNotAffected() {
        // 预置条件：data.sql 中有 1 个手工条目 intro-1
        assertNotNull(itemMapper.findById("intro-1"));

        updater.runWithFetcher(url ->
                testHtml("什么是拉取", "拉取是把远程更新下载到本地。"));

        // 手工条目仍然存在
        assertNotNull(itemMapper.findById("intro-1"));
        // 自动条目 1 个，手工条目不受影响
        assertEquals(1, itemMapper.countByAutoEntry(true));
        assertEquals(1, itemMapper.countByAutoEntry(false));
    }

    /**
     * 自动条目的 source_type 应记录数据源类型（docs / trending / rss），
     * 供 /api/status 的来源统计使用；全部写成 "auto" 会导致来源分布恒为 0。
     */
    @Test
    void autoEntriesRecordSourceTypeFromSource() {
        updater.runWithFetcher(url ->
                testHtml("什么是标签", "标签用于标记某个提交的版本。"));

        List<KnowledgeItem> items = itemMapper.findAllAutoEntries();
        assertEquals(1, items.size());
        // data.sql 中的 test-docs 源类型为 docs
        assertEquals("docs", items.get(0).getSourceType());
    }

    /**
     * 验证同批次出现重复条目 id 时空库写入不失败。
     *
     * <p>条目 id 仅由章节标题哈希生成：同一页面出现两个同名 h2（或不同数据源存在同名章节）
     * 会生成相同 id。空库首次写入时，若不去重，第二条 INSERT 会因主键冲突导致整个事务回滚。</p>
     */
    @Test
    void duplicateIdsWithinBatchAreDeduplicated() {
        String html = "<html><head><title>测试页面</title></head><body><main>"
                + "<h2 id=\"troubleshoot-a\">故障排除指南</h2>"
                + "<p>第一种情况的排错说明，介绍如何排查认证失败的问题。</p>"
                + "<h2 id=\"troubleshoot-b\">故障排除指南</h2>"
                + "<p>第二种情况的排错说明，介绍另一个场景的处理办法。</p>"
                + "</main></body></html>";

        Map<String, Object> result = updater.runWithFetcher(url -> html);

        assertTrue((Boolean) result.get("ok"));
        // 同名章节只保留一个条目
        assertEquals(1, result.get("added"));
        assertEquals(1, itemMapper.countByAutoEntry(true));
        List<KnowledgeItem> items = itemMapper.findAllAutoEntries();
        assertEquals(1, items.size());
        assertTrue(items.get(0).getQuestion().contains("故障排除指南"));
        // 保留的条目带有自己的答案块
        assertEquals(1, blockMapper.findByItemIds(
                java.util.Collections.singletonList(items.get(0).getId())).size());
    }
}
