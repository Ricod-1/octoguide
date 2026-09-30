package com.octoguide.knowledge.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证知识转化器的标题清洗、问题构造、稳定 id 与章节转化逻辑。
 *
 * <p>用例对齐 Node 版 {@code lib/knowledge-converter.js} 的行为。</p>
 */
public class KnowledgeConverterTest {

    private static KnowledgeSection section(String heading, List<KnowledgeSection.Block> blocks) {
        KnowledgeSection section = new KnowledgeSection();
        section.setHeading(heading);
        section.setBlocks(blocks);
        return section;
    }

    /** 去掉中文步骤前缀。 */
    @Test
    void cleanHeadingRemovesStepPrefix() {
        assertEquals("创建仓库", KnowledgeConverter.cleanHeading("第 1 步：创建仓库"));
        assertEquals("创建仓库", KnowledgeConverter.cleanHeading("步骤2：创建仓库"));
        assertEquals("创建仓库", KnowledgeConverter.cleanHeading("3. 创建仓库"));
        assertEquals("创建仓库", KnowledgeConverter.cleanHeading("创建仓库"));
    }

    /** 标题已是问句时原样返回。 */
    @Test
    void headingToQuestionKeepsExistingQuestion() {
        assertEquals("什么是 PR？", KnowledgeConverter.headingToQuestion("什么是 PR？"));
    }

    /** 自然问句前缀追加问号。 */
    @Test
    void headingToQuestionAppendsQuestionMark() {
        assertEquals("如何创建仓库？", KnowledgeConverter.headingToQuestion("如何创建仓库"));
    }

    /** 普通标题包装为“什么是「」？”句式。 */
    @Test
    void headingToQuestionWrapsPlainHeading() {
        assertEquals("什么是「创建仓库」？", KnowledgeConverter.headingToQuestion("创建仓库"));
    }

    /** 相同标题生成的 id 稳定且带 auto- 前缀。 */
    @Test
    void makeIdIsStable() {
        String first = KnowledgeConverter.makeId("仓库操作", "创建仓库");
        String second = KnowledgeConverter.makeId("仓库操作", "创建仓库");
        assertEquals(first, second, "相同标题应生成相同 id");
        assertTrue(first.startsWith("auto-"), "id 应带 auto- 前缀");
    }

    /** 与 Node 版 {@code makeId} 输出对齐（跨语言一致性回归保护）。 */
    @Test
    void makeIdMatchesNodeReferenceValues() {
        assertEquals("auto-28d3d0cf48fc", KnowledgeConverter.makeId("仓库操作", "创建仓库"));
        assertEquals("auto-ed51c6db4f81", KnowledgeConverter.makeId("协作", "About pull requests"));
        assertEquals("auto-73257b80b6bd", KnowledgeConverter.makeId("趋势仓库", "torvalds/linux"));
        assertEquals("auto-1337a9063dbf",
                KnowledgeConverter.makeId("新功能动态", "GitHub Actions: New workflow feature"));
    }

    /** 正常章节应转换为完整条目，相邻 text 块合并。 */
    @Test
    void convertSectionBuildsEntry() {
        KnowledgeSection section = section("创建仓库",
                Arrays.asList(new KnowledgeSection.Block("p", "第一步"), new KnowledgeSection.Block("p", "第二步"),
                        new KnowledgeSection.Block("code", "git init")));
        KnowledgeConverter.SourceOptions options = new KnowledgeConverter.SourceOptions();
        options.setExtraTags(Collections.singletonList("新手"));
        options.setSourceId("quickstart-repos");
        options.setSourceUrl("https://docs.github.com/zh/repositories/quickstart");
        options.setSourceTitle("GitHub 官方中文文档");

        KnowledgeEntry entry = KnowledgeConverter.convertSection(section, "仓库操作", options);

        assertNotNull(entry);
        assertEquals("什么是「创建仓库」？", entry.getQuestion());
        assertEquals("仓库操作", entry.getCategory());
        assertEquals("auto", entry.getSource());
        assertEquals("quickstart-repos", entry.getSourceId());
        assertEquals("新手", entry.getTags().get(0));
        // 相邻 text 块应合并为一个，代码块保留 code 类型
        assertEquals(2, entry.getAnswer().size());
        assertEquals("text", entry.getAnswer().get(0).getType());
        assertEquals("第一步\n第二步", entry.getAnswer().get(0).getContent());
        assertEquals("code", entry.getAnswer().get(1).getType());
    }

    /** 标题过短时应返回 null。 */
    @Test
    void convertSectionRejectsShortHeading() {
        KnowledgeSection section = section("ab",
                Collections.singletonList(new KnowledgeSection.Block("p", "内容")));
        assertNull(KnowledgeConverter.convertSection(section, "分类", null));
    }

    /** 无有效内容块时应返回 null。 */
    @Test
    void convertSectionRejectsEmptyBlocks() {
        KnowledgeSection section = section("创建仓库", Collections.<KnowledgeSection.Block>emptyList());
        assertNull(KnowledgeConverter.convertSection(section, "分类", null));
    }

    /** 批量转化应过滤无效章节并限制总数。 */
    @Test
    void convertAllLimitsTotalItems() {
        List<KnowledgeSection> sections = new ArrayList<KnowledgeSection>();
        // 构造 70 个有效章节，验证最多生成 60 条
        for (int index = 0; index < 70; index++) {
            sections.add(section("章节标题" + index,
                    Collections.singletonList(new KnowledgeSection.Block("p", "内容"))));
        }
        List<KnowledgeEntry> items = KnowledgeConverter.convertAll(sections, "分类", null);
        assertEquals(60, items.size(), "单次采集最多生成 60 条");
    }
}
