package com.octoguide.knowledge.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证知识分类器的章节级分类与自动标签提取逻辑。
 *
 * <p>用例对齐 Node 版 {@code lib/knowledge-classifier.js} 的行为：
 * 标题命中权重 3、正文命中权重 1、阈值 2，代码块不参与正文打分。</p>
 */
public class KnowledgeClassifierTest {

    private static KnowledgeSection.Block text(String content) {
        return new KnowledgeSection.Block("text", content);
    }

    private static KnowledgeSection.Block code(String content) {
        return new KnowledgeSection.Block("code", content);
    }

    /** 标题命中关键词应直接采用对应分类（标题权重 3 &gt; 阈值 2）。 */
    @Test
    void classifyByHeadingKeyword() {
        String category = KnowledgeClassifier.classifySection("关于拉取请求",
                Collections.singletonList(text("内容")), "默认分类");
        assertEquals("协作", category);
    }

    /** 标题无关键词、正文命中两个同规则关键词时达到阈值（2×1=2）。 */
    @Test
    void classifyByTwoBodyKeywordsReachingThreshold() {
        String category = KnowledgeClassifier.classifySection("介绍",
                Arrays.asList(text("如何创建仓库"), text("使用 clone 命令")), "默认分类");
        assertEquals("仓库操作", category);
    }

    /** 仅正文命中一个关键词（权重 1 &lt; 阈值 2）时应回退到源分类。 */
    @Test
    void classifyFallbackWhenScoreBelowThreshold() {
        String category = KnowledgeClassifier.classifySection("介绍",
                Collections.singletonList(text("这里提到 clone 命令")), "认识 GitHub");
        assertEquals("认识 GitHub", category);
    }

    /** 代码块不参与正文打分：正文仅有代码块含关键词时应回退。 */
    @Test
    void classifyIgnoresCodeBlocksInBodyScoring() {
        String category = KnowledgeClassifier.classifySection("介绍",
                Collections.singletonList(code("git clone https://github.com/octocat/Hello-World.git")),
                "认识 GitHub");
        assertEquals("认识 GitHub", category);
    }

    /** 中文关键词用子串匹配，拉丁词用词边界匹配（branch 不误中 rebranch）。 */
    @Test
    void classifyChineseKeywordBySubstring() {
        String category = KnowledgeClassifier.classifySection("分支管理",
                Collections.singletonList(text("切换")), "默认分类");
        assertEquals("分支", category);
    }

    /** 源标签应保留在提取结果中。 */
    @Test
    void extractTagsKeepsSourceTags() {
        List<String> tags = KnowledgeClassifier.extractTags("创建仓库",
                Collections.singletonList(text("新建一个 GitHub 仓库")),
                Arrays.asList("新手", "入门"));
        assertTrue(tags.contains("新手"), "源标签应被保留");
        assertTrue(tags.contains("入门"), "源标签应被保留");
    }

    /** 文本命中中文领域词时自动补充标签。 */
    @Test
    void extractTagsAddsChineseTerm() {
        List<String> tags = KnowledgeClassifier.extractTags("关于分支",
                Collections.singletonList(text("使用分支进行开发")),
                Collections.<String>emptyList());
        assertTrue(tags.contains("分支"), "中文领域词应被提取");
    }

    /** 文本出现错误码 403 / 404 时应补充为标签。 */
    @Test
    void extractTagsAddsErrorCodes() {
        List<String> tags = KnowledgeClassifier.extractTags("权限被拒",
                Collections.singletonList(text("git push 返回 403 denied")),
                Collections.<String>emptyList());
        assertTrue(tags.contains("403"), "错误码应被提取为标签");
    }

    /** 英文停用词（how / about 等）不应出现在标签中。 */
    @Test
    void extractTagsFiltersEnglishStopWords() {
        List<String> tags = KnowledgeClassifier.extractTags("About pull requests",
                Collections.singletonList(text("How to use pull requests on GitHub")),
                Collections.<String>emptyList());
        assertFalse(tags.contains("about"), "停用词 about 应被过滤");
        assertFalse(tags.contains("how"), "停用词 how 应被过滤");
        assertTrue(tags.contains("pull"), "有检索价值的英文词元应保留");
        assertTrue(tags.contains("requests"), "有检索价值的英文词元应保留");
    }

    /** 标签总数不超过 MAX_TAGS（6 个）。 */
    @Test
    void extractTagsTruncatesToMax() {
        List<String> tags = KnowledgeClassifier.extractTags("仓库与分支冲突",
                Arrays.asList(
                        text("创建仓库 克隆 推送 合并"),
                        text("远程 分支 权限 部署 自动化 工作流 认证 版本 回退 协作")),
                Arrays.asList("新手", "入门", "学习", "教程"));
        assertTrue(tags.size() <= 6, "标签数应不超过 6，实际为 " + tags.size());
    }
}
