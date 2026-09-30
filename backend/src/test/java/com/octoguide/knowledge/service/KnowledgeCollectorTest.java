package com.octoguide.knowledge.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证知识采集器的 HTML 解析、Trending / RSS 解析与采集分发逻辑。
 *
 * <p>用例使用模拟 GitHub 文档页、趋势页和 RSS 内容，验证与 Node 版
 * {@code lib/knowledge-collector.js} 行为一致。</p>
 */
public class KnowledgeCollectorTest {

    /** 网络抓取器必须拒绝指向本机或内网的地址。 */
    @Test
    void rejectsPrivateNetworkTargets() {
        assertFalse(KnowledgeCollector.isSafeNetworkUrl("http://127.0.0.1:8080/admin"));
        assertFalse(KnowledgeCollector.isSafeNetworkUrl("http://169.254.169.254/latest/meta-data"));
        assertTrue(KnowledgeCollector.isSafeNetworkUrl("https://docs.github.com/zh"));
    }

    /** 模拟 GitHub 文档页：main 内容区 + 带 id 的 h2 章节。 */
    private static final String DOCS_HTML = "<html><head><title>About pull requests - GitHub Docs</title></head>"
            + "<body><main>"
            + "<h2 id=\"about-pull-requests\">About pull requests</h2>"
            + "<p>Pull requests let you tell others about changes you've pushed.</p>"
            + "<h2 id=\"about-branches\">About branches</h2>"
            + "<p>Use a branch to isolate development work.</p>"
            + "</main></body></html>";

    /** 模拟 github.com/trending 页面。 */
    private static final String TRENDING_HTML = "<article class=\"Box-row\">"
            + "<h2><a href=\"/torvalds/linux\">torvalds / linux</a></h2>"
            + "<p class=\"col-9\">The Linux kernel</p>"
            + "<span itemprop=\"programmingLanguage\">C</span>"
            + "<a href=\"/torvalds/linux/stargazers\">190,000</a>"
            + "1,234 stars today"
            + "</article>"
            + "<article class=\"Box-row\">"
            + "<h2><a href=\"/freeCodeCamp/freeCodeCamp\">freeCodeCamp / freeCodeCamp</a></h2>"
            + "<p class=\"col-9\">open source codebase and curriculum</p>"
            + "<span itemprop=\"programmingLanguage\">TypeScript</span>"
            + "<a href=\"/freeCodeCamp/freeCodeCamp/stargazers\">400,000</a>"
            + "567 stars today"
            + "</article>";

    /** 模拟 GitHub Changelog RSS。 */
    private static final String RSS_XML = "<rss><channel>"
            + "<item>"
            + "<title>GitHub Actions: New workflow feature</title>"
            + "<link>https://github.blog/changelog/2024-01-01-actions/</link>"
            + "<pubDate>Mon, 01 Jan 2024 10:00:00 +0000</pubDate>"
            + "<description><![CDATA[<p>New workflow features available now.</p>"
            + "The post GitHub Actions: New workflow feature appeared first on The GitHub Blog.</description>"
            + "</item>"
            + "<item>"
            + "<title>Copilot: Updated model</title>"
            + "<link>https://github.blog/changelog/2024-01-02-copilot/</link>"
            + "<pubDate>Tue, 02 Jan 2024 09:00:00 +0000</pubDate>"
            + "<description><![CDATA[<p>Copilot now uses a newer model.</p>]]></description>"
            + "</item>"
            + "</channel></rss>";

    // ---------- HTML 工具 ----------

    /** 应剔除 script 并转换实体。 */
    @Test
    void stripTagsRemovesScriptsAndEntities() {
        String html = "<script>var x = 1;</script><p>a &amp; b &lt; c</p>";
        assertEquals("a & b < c", KnowledgeCollector.stripTags(html));
    }

    /** 应提取页面标题。 */
    @Test
    void extractPageTitleFindsTitle() {
        assertEquals("About pull requests - GitHub Docs", KnowledgeCollector.extractPageTitle(DOCS_HTML));
    }

    /** 通用正文提取应优先 article 内容区。 */
    @Test
    void extractGenericTextPrefersArticle() {
        String html = "<html><head><title>t</title></head><body>"
                + "<nav>导航</nav><article><p>正文内容</p></article></body></html>";
        assertEquals("正文内容", KnowledgeCollector.extractGenericText(html));
    }

    /** 应从 main 内容区解析出两个章节并提取段落块。 */
    @Test
    void parseHtmlToSectionsSplitsByH2() {
        List<KnowledgeSection> sections = KnowledgeCollector.parseHtmlToSections(DOCS_HTML);
        assertEquals(2, sections.size());
        assertEquals("About pull requests", sections.get(0).getHeading());
        assertEquals("about-pull-requests", sections.get(0).getId());
        assertEquals(1, sections.get(0).getBlocks().size());
        assertEquals("p", sections.get(0).getBlocks().get(0).getType());
        assertTrue(sections.get(0).getBlocks().get(0).getText().contains("Pull requests"));
    }

    /** 应剔除“在本文中”等导航性章节。 */
    @Test
    void parseHtmlToSectionsSkipsMiniToc() {
        String html = "<main>"
                + "<h2 id=\"in-this-article\">在本文中</h2><ul><li>概述</li></ul>"
                + "<h2 id=\"real-section\">创建仓库</h2><p>正文</p>"
                + "</main>";
        List<KnowledgeSection> sections = KnowledgeCollector.parseHtmlToSections(html);
        assertEquals(1, sections.size());
        assertEquals("创建仓库", sections.get(0).getHeading());
    }

    /** 代码块内容应保留换行，多行命令不能被压成一行。 */
    @Test
    void codeBlocksPreserveLineBreaks() {
        String html = "<main>"
                + "<h2 id=\"init\">初始化仓库</h2>"
                + "<pre><code>git init\ngit add .\ngit commit -m \"first commit\"</code></pre>"
                + "</main>";

        List<KnowledgeSection> sections = KnowledgeCollector.parseHtmlToSections(html);

        assertEquals(1, sections.size());
        List<KnowledgeSection.Block> blocks = sections.get(0).getBlocks();
        assertEquals(1, blocks.size());
        assertEquals("code", blocks.get(0).getType());
        assertEquals("git init\ngit add .\ngit commit -m \"first commit\"", blocks.get(0).getText());
    }

    // ---------- Trending 解析 ----------

    /** 应从趋势页解析出仓库列表。 */
    @Test
    void parseTrendingExtractsRepos() {
        List<KnowledgeCollector.TrendingRepo> repos = KnowledgeCollector.parseTrending(TRENDING_HTML);
        assertEquals(2, repos.size());
        KnowledgeCollector.TrendingRepo first = repos.get(0);
        assertEquals("torvalds/linux", first.getFullName());
        assertEquals("The Linux kernel", first.getDesc());
        assertEquals("C", first.getLang());
        assertEquals("190,000", first.getStars());
        assertEquals("1,234", first.getToday());
    }

    /** 仓库列表应生成每条仓库条目 + 1 条汇总条目。 */
    @Test
    void buildTrendingItemsCreatesPerRepoAndSummary() {
        KnowledgeSource source = new KnowledgeSource();
        source.setId("github-trending");
        source.setTitle("GitHub Trending 每日热门仓库");
        source.setTags(Arrays.asList("趋势", "热门"));
        source.setSiteTitle("GitHub Trending");

        List<KnowledgeEntry> items = KnowledgeCollector.buildTrendingItems(
                KnowledgeCollector.parseTrending(TRENDING_HTML), source);

        assertEquals(3, items.size(), "2 个仓库 + 1 条汇总");
        KnowledgeEntry first = items.get(0);
        assertEquals("趋势仓库「torvalds/linux」是做什么的？", first.getQuestion());
        assertEquals("趋势仓库", first.getCategory());
        assertEquals("https://github.com/torvalds/linux", first.getSourceUrl());
        KnowledgeEntry summary = items.get(2);
        assertEquals("auto-trending-summary-github-trending", summary.getId());
        assertEquals("GitHub 最近有什么热门仓库？", summary.getQuestion());
    }

    // ---------- RSS 解析 ----------

    /** 应解析 RSS 文章并移除 changelog 底部模板文案。 */
    @Test
    void parseRssExtractsEntries() {
        List<KnowledgeCollector.RssEntry> entries = KnowledgeCollector.parseRss(RSS_XML);
        assertEquals(2, entries.size());
        KnowledgeCollector.RssEntry first = entries.get(0);
        assertEquals("GitHub Actions: New workflow feature", first.getTitle());
        assertEquals("https://github.blog/changelog/2024-01-01-actions/", first.getLink());
        assertFalse(first.getDesc().contains("appeared first on"),
                "应移除 GitHub Blog 底部模板文案");
    }

    /** 文章列表应生成每篇条目 + 1 条汇总条目。 */
    @Test
    void buildRssItemsCreatesPerEntryAndSummary() {
        KnowledgeSource source = new KnowledgeSource();
        source.setId("github-changelog");
        source.setTitle("GitHub 官方更新日志（Changelog）");
        source.setTags(Arrays.asList("新功能", "更新"));
        source.setSiteTitle("GitHub Changelog");

        List<KnowledgeEntry> items = KnowledgeCollector.buildRssItems(
                KnowledgeCollector.parseRss(RSS_XML), source);

        assertEquals(3, items.size(), "2 篇文章 + 1 条汇总");
        assertEquals("GitHub Actions: New workflow feature", items.get(0).getQuestion());
        assertEquals("新功能动态", items.get(0).getCategory());
        KnowledgeEntry summary = items.get(2);
        assertEquals("auto-changelog-summary-github-changelog", summary.getId());
        assertEquals("GitHub 最近发布了什么新功能？", summary.getQuestion());
    }

    // ---------- 采集入口 ----------

    /** 采集入口应按类型分发解析并汇总结果。 */
    @Test
    void collectDispatchesBySourceType() throws IOException {
        List<KnowledgeSource> sources = new ArrayList<KnowledgeSource>();
        sources.add(source("docs-source", "https://docs.github.com/example", "docs",
                "认识 GitHub", Collections.singletonList("新手")));
        sources.add(source("trending-source", "https://github.com/trending", "trending",
                "趋势仓库", Arrays.asList("趋势", "热门")));
        sources.add(source("rss-source", "https://github.blog/changelog/feed/", "rss",
                "新功能动态", Arrays.asList("新功能", "更新")));

        // 注入内存 HTML 的抓取函数，避免真实网络请求
        KnowledgeCollector.PageFetcher fetcher = url -> {
            if (url.contains("trending")) {
                return TRENDING_HTML;
            }
            if (url.contains("feed")) {
                return RSS_XML;
            }
            return DOCS_HTML;
        };

        List<KnowledgeEntry> items = KnowledgeCollector.collect(sources, fetcher, null);

        // 文档源 2 个章节 + 趋势 2 仓库 +1 汇总 + RSS 2 篇 +1 汇总 = 8 条
        assertEquals(8, items.size(), "三个源应汇总为 8 条知识条目");
        assertTrue(items.stream().anyMatch(item -> item.getQuestion().contains("pull requests")));
        assertTrue(items.stream().anyMatch(item -> item.getCategory().equals("趋势仓库")));
        assertTrue(items.stream().anyMatch(item -> item.getCategory().equals("新功能动态")));
    }

    /** 单个源抓取失败应跳过而不影响其它源。 */
    @Test
    void collectSkipsFailedSource() throws IOException {
        List<KnowledgeSource> sources = new ArrayList<KnowledgeSource>();
        sources.add(source("bad-source", "https://docs.github.com/bad", "docs",
                "认识 GitHub", Collections.singletonList("新手")));
        sources.add(source("good-source", "https://github.com/trending", "trending",
                "趋势仓库", Arrays.asList("趋势", "热门")));

        KnowledgeCollector.PageFetcher fetcher = url -> {
            if (url.contains("bad")) {
                throw new IOException("HTTP 404");
            }
            return TRENDING_HTML;
        };

        List<KnowledgeEntry> items = KnowledgeCollector.collect(sources, fetcher, null);
        assertEquals(3, items.size(), "失败源跳过，成功源生成 2 仓库 + 1 汇总");
    }

    /** 瞬时网络错误（如 TLS 握手失败）应重试同一数据源，而不是整源丢弃。 */
    @Test
    void collectRetriesTransientFetchFailures() {
        List<KnowledgeSource> sources = new ArrayList<KnowledgeSource>();
        sources.add(source("flaky-source", "https://docs.github.com/flaky", "docs",
                "认识 GitHub", Collections.singletonList("新手")));
        final int[] attempts = {0};
        KnowledgeCollector.PageFetcher fetcher = url -> {
            if (attempts[0]++ < 2) {
                throw new IOException("Remote host closed connection during handshake");
            }
            return DOCS_HTML;
        };

        List<KnowledgeEntry> items = KnowledgeCollector.collect(sources, fetcher, null);

        assertEquals(2, items.size(), "前两次失败后第三次成功，两个章节都应采集");
        assertEquals(3, attempts[0]);
    }

    /** 构造测试用数据源配置。 */
    private static KnowledgeSource source(String id, String url, String type,
                                          String category, List<String> tags) {
        KnowledgeSource source = new KnowledgeSource();
        source.setId(id);
        source.setTitle(id);
        source.setUrl(url);
        source.setType(type);
        source.setCategory(category);
        source.setTags(tags);
        source.setSiteTitle("测试站点");
        return source;
    }
}
