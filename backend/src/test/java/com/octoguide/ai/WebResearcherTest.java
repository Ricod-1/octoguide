package com.octoguide.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.octoguide.knowledge.service.KnowledgeCollector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 联网研究核心测试：mock 大模型客户端，网络抓取用内存 HTML 注入。 */
class WebResearcherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmClient llmClient = mock(LlmClient.class);
    private final WebResearcher researcher = new WebResearcher(llmClient);

    /** 中文时效词子串命中。 */
    @Test
    void detectsChineseTimeSensitiveKeyword() {
        assertTrue(researcher.isTimeSensitive("GitHub 今天有什么热门仓库？"));
        assertTrue(researcher.isTimeSensitive("最近流行的前端框架"));
    }

    /** 英文时效词按词边界命中。 */
    @Test
    void detectsEnglishTimeSensitiveKeywordWithWordBoundary() {
        assertTrue(researcher.isTimeSensitive("Show me the latest GitHub features"));
        assertFalse(researcher.isTimeSensitive("How does a latesttopic work"));
    }

    /** 普通问题不触发联网。 */
    @Test
    void ordinaryQuestionIsNotTimeSensitive() {
        assertFalse(researcher.isTimeSensitive("什么是 Pull Request？"));
    }

    /** AI 返回网址数组时按顺序返回候选。 */
    @Test
    void suggestUrlsParsesAiUrlList() throws Exception {
        when(llmClient.answerJson(anyList())).thenReturn(mapper.readTree(
                "[\"https://docs.github.com/zh/rest\", \"https://github.com/trending\"]"));

        List<String> urls = researcher.suggestUrls("GitHub 热门仓库", null);

        assertEquals(2, urls.size());
        assertEquals("https://docs.github.com/zh/rest", urls.get(0));
    }

    /** 模型返回非 GitHub 官方地址时必须过滤，避免服务端请求任意站点。 */
    @Test
    void suggestUrlsFiltersUntrustedHosts() throws Exception {
        when(llmClient.answerJson(anyList())).thenReturn(mapper.readTree(
                "[\"https://docs.github.com/zh/rest\", \"http://169.254.169.254/latest/meta-data\", "
                        + "\"https://evil.example/steal\"]"));

        List<String> urls = researcher.suggestUrls("问题", null);

        assertEquals(Collections.singletonList("https://docs.github.com/zh/rest"), urls);
    }

    /** 直接抓取内网地址时必须拒绝。 */
    @Test
    void fetchPageRejectsPrivateAddress() {
        WebResearcher.ResearchOptions options = new WebResearcher.ResearchOptions();
        options.setFetcher(url -> "<html><main><p>敏感内容</p></main></html>");
        assertThrows(IOException.class, () -> researcher.fetchPage("http://127.0.0.1:8080/admin", options));
    }

    /** AI 输出非数组或失败时返回空列表。 */
    @Test
    void suggestUrlsReturnsEmptyWhenAiNotArray() throws Exception {
        when(llmClient.answerJson(anyList())).thenReturn(mapper.readTree("{\"error\":\"无法理解\"}"));

        assertTrue(researcher.suggestUrls("问题", null).isEmpty());
    }

    /** 必应 ck/a 重定向链接还原为目标 URL。 */
    @Test
    void decodesBingRedirectUrl() {
        assertEquals("https://docs.github.com/",
                WebResearcher.decodeBingUrl("https://www.bing.com/ck/a?u=aHR0cHM6Ly9kb2NzLmdpdGh1Yi5jb20v&ntb=1"));
        assertEquals("https://github.com/",
                WebResearcher.decodeBingUrl("https://www.bing.com/ck/a?a=1&u=aHR0cHM6Ly9naXRodWIuY29tLw%3d%3d"));
    }

    /** 必应搜索解析 b_algo 结果并排除必应/微软自身页面。 */
    @Test
    void bingSearchExtractsAlgoLinksAndSkipsBingDomains() throws Exception {
        String bingHtml = "<html><body>"
                + "<li class=\"b_algo\"><h2><a href=\"https://www.bing.com/ck/a?u=aHR0cHM6Ly9kb2NzLmdpdGh1Yi5jb20v\">创建仓库</a></h2></li>"
                + "<li class=\"b_algo\"><h2><a href=\"https://www.bing.com/ck/a?u=aHR0cHM6Ly9naXRodWIuY29tLw%3d%3d\">GitHub</a></h2></li>"
                + "<li class=\"b_algo\"><h2><a href=\"https://www.bing.com/search?q=xxx\">必应自身</a></h2></li>"
                + "</body></html>";
        WebResearcher.ResearchOptions options = new WebResearcher.ResearchOptions();
        options.setFetcher(url -> bingHtml);

        List<String> links = researcher.bingSearch("GitHub 创建仓库", options);

        assertEquals(2, links.size());
        assertEquals("https://docs.github.com/", links.get(0));
        assertEquals("https://github.com/", links.get(1));
    }

    /** 必应新版 ck/a 重定向：u 参数为 a1 前缀 + base64url 编码的目标地址。 */
    @Test
    void decodesBingRedirectUrlWithA1Prefix() {
        assertEquals("https://docs.github.com/",
                WebResearcher.decodeBingUrl("https://www.bing.com/ck/a?!&&p=abc123&u=a1aHR0cHM6Ly9kb2NzLmdpdGh1Yi5jb20v&ntb=1"));
        assertEquals("https://github.com/",
                WebResearcher.decodeBingUrl("https://www.bing.com/ck/a?u=a1aHR0cHM6Ly9naXRodWIuY29tLw"));
    }

    /** 必应搜索同时兼容新版 ck/a 重定向与直链两种结果形态。 */
    @Test
    void bingSearchHandlesModernRedirectAndDirectLinks() throws Exception {
        String bingHtml = "<html><body>"
                + "<li class=\"b_algo\"><h2><a href=\"https://www.bing.com/ck/a?!&&p=x1y2&u=a1aHR0cHM6Ly9kb2NzLmdpdGh1Yi5jb20vemgv&ntb=1\">文档</a></h2></li>"
                + "<li class=\"b_algo\"><h2><a href=\"https://github.com/trending\">直链</a></h2></li>"
                + "</body></html>";
        WebResearcher.ResearchOptions options = new WebResearcher.ResearchOptions();
        options.setFetcher(url -> bingHtml);

        List<String> links = researcher.bingSearch("GitHub 文档", options);

        assertEquals(2, links.size());
        assertEquals("https://docs.github.com/zh/", links.get(0));
        assertEquals("https://github.com/trending", links.get(1));
    }

    /** AI 认路成功时直接采用抓取结果，不再额外让模型判断"够不够"（省掉一次完整往返）。 */
    @Test
    void findRelevantPagesUsesAiSuggestedUrlWithoutExtraJudgement() throws Exception {
        when(llmClient.answerJson(anyList()))
                .thenReturn(mapper.readTree("[\"https://docs.github.com/zh/repositories\"]"));
        WebResearcher.ResearchOptions options = new WebResearcher.ResearchOptions();
        options.setFetcher(url -> githubDocHtml());

        WebResearcher.ResearchResult result = researcher.findRelevantPages("怎么创建仓库？", options);

        assertEquals("ai", result.getVia());
        assertEquals(1, result.getPages().size());
        assertEquals("https://docs.github.com/zh/repositories", result.getPages().get(0).getUrl());
        assertFalse(result.getPages().get(0).getText().isEmpty());
        // 只调用一次模型（suggestUrls）；"充足性判断"已移除，不应再有第二次调用
        verify(llmClient, times(1)).answerJson(anyList());
    }

    /**
     * 多页抓取必须并发执行并保持建议顺序，单页失败不影响其它页。
     *
     * <p>用 CountDownLatch 做确定性判据：两个抓取任务都进入 fetcher 后才会放行，
     * 串行实现会在第一个任务处超时，从而暴露"没有并发"。</p>
     */
    @Test
    void findRelevantPagesFetchesInParallelKeepingOrder() throws Exception {
        when(llmClient.answerJson(anyList())).thenReturn(mapper.readTree(
                "[\"https://docs.github.com/a\", \"https://github.com/b\", \"https://github.blog/c\"]"));
        final CountDownLatch bothStarted = new CountDownLatch(2);
        WebResearcher.ResearchOptions options = new WebResearcher.ResearchOptions();
        options.setFetcher(url -> {
            if (url.endsWith("/b")) {
                throw new IOException("模拟单页抓取失败");
            }
            bothStarted.countDown();
            try {
                if (!bothStarted.await(3, TimeUnit.SECONDS)) {
                    throw new IOException("抓取未并发执行");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("抓取被中断", interrupted);
            }
            return "<html><head><title>" + url + "</title></head><body><main><p>"
                    + url + " 的正文内容</p></main></body></html>";
        });

        WebResearcher.ResearchResult result = researcher.findRelevantPages("怎么创建仓库？", options);

        assertEquals("ai", result.getVia());
        assertEquals(2, result.getPages().size());
        // 顺序与建议顺序一致：失败的 /b 被跳过，a 仍在 c 之前
        assertEquals("https://docs.github.com/a", result.getPages().get(0).getUrl());
        assertEquals("https://github.blog/c", result.getPages().get(1).getUrl());
    }

    /** AI 认路为空时回退到必应兜底。 */
    @Test
    void findRelevantPagesFallsBackToBingWhenAiSuggestsNothing() throws Exception {
        when(llmClient.answerJson(anyList())).thenReturn(mapper.readTree("{\"no\":\"urls\"}"));
        WebResearcher.ResearchOptions options = new WebResearcher.ResearchOptions();
        options.setFetcher(url -> url.contains("bing.com")
                ? bingSearchHtml() : githubDocHtml());

        WebResearcher.ResearchResult result = researcher.findRelevantPages("怎么创建仓库？", options);

        assertEquals("bing", result.getVia());
        assertEquals(1, result.getPages().size());
        assertEquals("https://docs.github.com/", result.getPages().get(0).getUrl());
    }

    /** 所有途径都失败时返回空结果（via=none）。 */
    @Test
    void findRelevantPagesReturnsEmptyWhenAllFail() throws Exception {
        when(llmClient.answerJson(anyList())).thenReturn(mapper.readTree("{\"no\":\"urls\"}"));
        WebResearcher.ResearchOptions options = new WebResearcher.ResearchOptions();
        options.setFetcher(url -> "<html><body><p>没有搜索结果</p></body></html>");

        WebResearcher.ResearchResult result = researcher.findRelevantPages("怎么创建仓库？", options);

        assertEquals("none", result.getVia());
        assertTrue(result.getPages().isEmpty());
    }

    /** 抓取页面提取标题与正文。 */
    @Test
    void fetchPageExtractsTitleAndText() throws Exception {
        WebResearcher.ResearchOptions options = new WebResearcher.ResearchOptions();
        options.setFetcher(url -> githubDocHtml());

        WebResearcher.WebPage page = researcher.fetchPage("https://docs.github.com/zh", options);

        assertEquals("创建仓库 - GitHub 文档", page.getTitle());
        assertTrue(page.getText().contains("创建仓库"));
    }

    private String githubDocHtml() {
        return "<html><head><title>创建仓库 - GitHub 文档</title></head>"
                + "<body><main><h1>创建仓库</h1><p>在 GitHub 上，你可以通过多种方式创建仓库。</p></main></body></html>";
    }

    private String bingSearchHtml() {
        return "<html><body><li class=\"b_algo\"><h2><a href=\"https://www.bing.com/ck/a?u=aHR0cHM6Ly9kb2NzLmdpdGh1Yi5jb20v\">创建仓库</a></h2></li></body></html>";
    }
}
