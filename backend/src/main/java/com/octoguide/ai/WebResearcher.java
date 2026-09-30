package com.octoguide.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.octoguide.knowledge.service.KnowledgeCollector;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网查询核心：AI 认路 → 并行抓取 → 必应兜底 → 搜索 API 预留。
 *
 * <p>与 Node 版 {@code lib/web-researcher.js} 行为对齐。复用 {@link KnowledgeCollector}
 * 的通用 HTML 提取，AI 只负责"该查哪些网址"（{@link LlmClient#answerJson}）。
 * 网络抓取通过可注入的 {@link KnowledgeCollector.PageFetcher} 解耦，便于测试。</p>
 *
 * <p>性能取舍：抓取成功即直接采用，不再额外让模型判断"内容够不够"——判断本身是一次完整的
 * 模型往返（实测约 4 秒），而抓取结果为空/失败时本来就会继续走必应兜底。多个页面并发抓取，
 * 避免把每页耗时相加。</p>
 */
@Service
public class WebResearcher {

    /** 单次联网最多抓取页数。 */
    private static final int MAX_PAGES = 3;

    /** 并行抓取的最大并发数。 */
    private static final int MAX_PARALLEL_FETCHES = 4;

    /** 必应搜索结果最多保留链接数。 */
    private static final int MAX_BING_LINKS = 3;

    /** 中文时效词：子串匹配。 */
    private static final List<String> TIME_KEYWORDS_ZH = Arrays.asList(
            "最火", "最新", "今天", "现在", "排行", "趋势", "热门", "最近", "本周", "本月");

    /** 英文时效词：词边界匹配，避免误命中（如 stop/topics、photo/shot）。 */
    private static final List<Pattern> TIME_KEYWORDS_EN = Arrays.asList(
            Pattern.compile("\\btop\\b"), Pattern.compile("\\bhot\\b"), Pattern.compile("\\blatest\\b"),
            Pattern.compile("\\btrending\\b"), Pattern.compile("\\btoday\\b"));

    /** 必应搜索结果块（b_algo）。 */
    private static final Pattern B_ALGO = Pattern.compile(
            "(?i)<li[^>]*class=\"[^\"]*b_algo[^\"]*\"[\\s\\S]*?</li>");

    /** 搜索结果块中的外链。 */
    private static final Pattern B_ALGO_LINK = Pattern.compile(
            "(?i)<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>");

    /** 必应 / 微软 / msn 自身页面，抓取时需要排除。 */
    private static final Pattern BING_DOMAIN = Pattern.compile(
            "(?i)^https?://(www\\.)?(bing|microsoft|msn)\\.com/");

    /** 必应 ck/a 重定向链接中的目标 URL 参数。 */
    private static final Pattern BING_U = Pattern.compile("[?&]u=([^&]+)");

    private final LlmClient llmClient;

    /**
     * 页面抓取线程池：抓取是纯 I/O 等待，并发执行可避免把多页耗时相加。
     * 线程设为 daemon，随应用退出回收；单例服务生命周期内复用同一池子。
     */
    private final ExecutorService fetchExecutor;

    private static final AtomicInteger FETCH_THREAD_SEQ = new AtomicInteger();

    /**
     * 创建联网研究服务。
     *
     * @param llmClient 大模型客户端（用于网址建议）
     */
    public WebResearcher(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.fetchExecutor = Executors.newFixedThreadPool(MAX_PARALLEL_FETCHES, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "octoguide-web-" + FETCH_THREAD_SEQ.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /**
     * 判断问题是否对时效敏感（命中时效词即认为答案随时间变化，必须联网查最新）。
     *
     * @param question 用户问题
     * @return 命中返回 {@code true}
     */
    public boolean isTimeSensitive(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        for (String keyword : TIME_KEYWORDS_ZH) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        for (Pattern pattern : TIME_KEYWORDS_EN) {
            if (pattern.matcher(normalized).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 让 AI 给出候选网址清单；失败或非数组返回空列表。
     *
     * @param question 用户问题
     * @param options 研究选项（日志回调）
     * @return 候选网址列表（最多 maxUrls 个）
     */
    public List<String> suggestUrls(String question, ResearchOptions options) {
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(message("system",
                "你是网页抓取助手。根据用户的问题，给出 1-3 个最可能包含答案的网址。\n"
                        + "优先 GitHub 官方域名：docs.github.com / github.blog / github.com。\n"
                        + "只输出一个 JSON 数组，例如：[\"https://docs.github.com/zh/...\", "
                        + "\"https://github.com/trending\"]，不要输出任何其他内容。"));
        messages.add(message("user", question == null ? "" : question.trim()));
        try {
            JsonNode data = llmClient.answerJson(messages);
            if (data == null || !data.isArray()) {
                return Collections.emptyList();
            }
            int maxUrls = maxPages(options);
            List<String> urls = new ArrayList<String>();
            for (JsonNode node : data) {
                String url = node.asText().trim();
                if (isAllowedUrl(url)) {
                    urls.add(url);
                    if (urls.size() >= maxUrls) {
                        break;
                    }
                }
            }
            return urls;
        } catch (Exception e) {
            log(options, "[suggestUrls] " + safeMessage(e));
            return Collections.emptyList();
        }
    }

    /**
     * 抓取单个页面并提取标题与正文。
     *
     * @param url 页面地址
     * @param options 研究选项（可注入自定义抓取函数）
     * @return 网页内容（url / title / text）
     * @throws IOException 抓取失败时抛出
     */
    public WebPage fetchPage(String url, ResearchOptions options) throws IOException {
        validateUrl(url);
        String html = fetch(options, url);
        WebPage page = new WebPage();
        page.setUrl(url);
        page.setTitle(KnowledgeCollector.extractPageTitle(html));
        page.setText(KnowledgeCollector.extractGenericText(html));
        return page;
    }

    /**
     * 必应搜索：抓搜索结果页，提取 b_algo 结果链接（还原重定向后，排除必应/微软自身页面）。
     *
     * @param query 搜索关键词
     * @param options 研究选项（可注入自定义抓取函数）
     * @return 目标链接列表（最多 {@value #MAX_BING_LINKS} 个）
     * @throws IOException 抓取失败时抛出
     */
    public List<String> bingSearch(String query, ResearchOptions options) throws IOException {
        String encoded = URLEncoder.encode(query == null ? "" : query, "UTF-8");
        String url = "https://www.bing.com/search?q=" + encoded;
        String html = fetch(options, url);

        List<String> links = new ArrayList<String>();
        Matcher blockMatcher = B_ALGO.matcher(html);
        while (blockMatcher.find()) {
            Matcher linkMatcher = B_ALGO_LINK.matcher(blockMatcher.group());
            if (!linkMatcher.find()) {
                continue;
            }
            String link = decodeBingUrl(linkMatcher.group(1));
            if (BING_DOMAIN.matcher(link).find()) {
                continue;
            }
            links.add(link);
            if (links.size() >= MAX_BING_LINKS) {
                break;
            }
        }
        return links;
    }

    /**
     * 搜索 API 兜底（预留）：未配置 provider / apiKey 时直接返回空数组。
     *
     * <p>后续接入 tavily / bing 等搜索 API 时在此实现。</p>
     *
     * @param query 搜索关键词
     * @return 候选链接列表（当前恒为空）
     */
    public List<String> searchApi(String query) {
        return Collections.emptyList();
    }

    /**
     * 联网查询主入口：AI 认路 → 并行抓取 → 必应兜底 → 搜索 API 预留。
     *
     * @param question 用户问题
     * @param options 研究选项（抓取函数、进度回调、日志回调）
     * @return 研究结果（网页列表 + 命中途径）
     */
    public ResearchResult findRelevantPages(String question, ResearchOptions options) {
        ResearchOptions opts = options == null ? new ResearchOptions() : options;

        // 1) AI 建议网址并抓取
        List<String> urls = suggestUrls(question, opts);
        if (!urls.isEmpty()) {
            log(opts, "[research] AI 建议网址: " + String.join(", ", urls));
        }
        List<WebPage> pages = fetchPages(urls, opts);

        // 2) 抓取成功即采用：不再额外让模型判断"内容够不够"（省掉一次完整模型往返）。
        //    只有一页都没抓到（全部失败或正文为空）才继续走兜底。
        if (!pages.isEmpty()) {
            return result(pages, "ai");
        }

        // 3) 必应兜底
        try {
            List<String> bingUrls = bingSearch(question, opts);
            List<WebPage> bingPages = fetchPages(bingUrls, opts);
            if (!bingPages.isEmpty()) {
                log(opts, "[research] 必应兜底命中 " + bingPages.size() + " 页");
                return result(bingPages, "bing");
            }
        } catch (Exception e) {
            log(opts, "[research] 必应搜索失败: " + safeMessage(e));
        }

        // 4) 搜索 API 兜底（预留）
        try {
            List<String> apiUrls = searchApi(question);
            List<WebPage> apiPages = fetchPages(apiUrls, opts);
            if (!apiPages.isEmpty()) {
                log(opts, "[research] 搜索 API 命中 " + apiPages.size() + " 页");
                return result(apiPages, "api");
            }
        } catch (Exception e) {
            log(opts, "[research] 搜索 API 失败: " + safeMessage(e));
        }

        return result(Collections.<WebPage>emptyList(), "none");
    }

    /**
     * 并行抓取多个页面。
     *
     * <p>并发执行、结果按输入顺序回填（保证上下文里的页面顺序与建议顺序一致），
     * 单页失败只记日志不影响其它页；正文为空的页面被丢弃。</p>
     *
     * @param urls 待抓取地址
     * @param options 研究选项
     * @return 抓取成功的页面列表（保持输入顺序）
     */
    private List<WebPage> fetchPages(List<String> urls, ResearchOptions options) {
        List<String> targets = limit(urls, maxPages(options));
        if (targets.isEmpty()) {
            return Collections.emptyList();
        }

        List<CompletableFuture<WebPage>> futures = new ArrayList<CompletableFuture<WebPage>>(targets.size());
        for (final String url : targets) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                progress(options, "fetching", "📥 正在抓取 " + url + " …");
                try {
                    WebPage page = fetchPage(url, options);
                    if (page.getText() != null && !page.getText().isEmpty()) {
                        log(options, "[research] 抓取成功: " + url + " (" + page.getText().length() + " 字符)");
                        return page;
                    }
                    return null;
                } catch (Exception e) {
                    log(options, "[research] 抓取失败 " + url + ": " + safeMessage(e));
                    return null;
                }
            }, fetchExecutor));
        }

        List<WebPage> pages = new ArrayList<WebPage>(targets.size());
        for (CompletableFuture<WebPage> future : futures) {
            try {
                WebPage page = future.join();
                if (page != null) {
                    pages.add(page);
                }
            } catch (Exception e) {
                log(options, "[research] 抓取任务异常: " + safeMessage(e));
            }
        }
        return pages;
    }

    /** 还原必应 ck/a 重定向：从 u= 参数解出真实目标 URL。 */
    static String decodeBingUrl(String href) {
        if (href == null) {
            return href;
        }
        Matcher matcher = BING_U.matcher(href);
        if (matcher.find()) {
            try {
                // 先还原 URL 编码（%3d -> =，%2B -> +）
                String decoded = java.net.URLDecoder.decode(matcher.group(1), "UTF-8");
                // 新版 ck/a 的 u 参数为 "a1" 前缀 + base64url(目标地址)；旧版为 base64(目标地址) 或明文 URL
                String payload = decoded.matches("(?i)^https?://.*") ? decoded
                        : (decoded.startsWith("a1") ? decoded.substring(2) : decoded);
                // 再做 base64url 兼容替换后解码
                String target = payload.replace('-', '+').replace('_', '/');
                while (target.length() % 4 != 0) {
                    target += "=";
                }
                byte[] decodedBytes = Base64.getDecoder().decode(target);
                String realUrl = new String(decodedBytes, StandardCharsets.UTF_8);
                if (realUrl.matches("(?i)^https?://.*")) {
                    return realUrl;
                }
            } catch (Exception ignored) {
                // 解码失败则原样返回
            }
        }
        return href;
    }

    // ---------- 私有工具 ----------

    /** 按研究选项执行抓取：优先注入的 fetcher，否则使用默认 HTTP 客户端。 */
    private String fetch(ResearchOptions options, String url) throws IOException {
        if (options != null && options.getFetcher() != null) {
            return options.getFetcher().fetch(url);
        }
        return KnowledgeCollector.fetchPage(url);
    }

    /**
     * 校验联网地址，避免模型输出或搜索结果触发 SSRF。
     * 仅允许 GitHub 官方 HTTPS 域名，并拒绝解析到本机/内网的地址。
     */
    static boolean isAllowedUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(scheme) || host == null || uri.getUserInfo() != null) {
                return false;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (!("github.com".equals(normalizedHost) || normalizedHost.endsWith(".github.com")
                    || "github.blog".equals(normalizedHost) || normalizedHost.endsWith(".github.blog")
                    || "docs.github.com".equals(normalizedHost))) {
                return false;
            }
            return !resolvesToPrivateAddress(normalizedHost);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 校验抓取地址；fetchPage 对测试注入的 fetcher 也执行同一安全策略。 */
    private void validateUrl(String url) throws IOException {
        if (!isAllowedUrl(url)) {
            throw new IOException("不允许抓取非 GitHub 官方地址");
        }
    }

    /** 解析域名并拒绝环回、链路本地、站点本地及任意地址。 */
    private static boolean resolvesToPrivateAddress(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                    return true;
                }
            }
            return false;
        } catch (Exception exception) {
            // DNS 解析失败时拒绝请求，避免绕过网络边界检查。
            return true;
        }
    }

    /** 发送研究进度回调。 */
    private void progress(ResearchOptions options, String stage, String text) {
        if (options != null && options.getProgress() != null) {
            options.getProgress().accept(stage, text);
        }
    }

    /** 发送研究日志回调。 */
    private void log(ResearchOptions options, String message) {
        if (options != null && options.getLog() != null) {
            options.getLog().accept(message);
        }
    }

    /** 返回单次最多抓取页数。 */
    private int maxPages(ResearchOptions options) {
        return options != null && options.getMaxUrls() > 0 ? options.getMaxUrls() : MAX_PAGES;
    }

    /** 截取列表前 n 项。 */
    private List<String> limit(List<String> items, int limit) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        return items.size() <= limit ? items : new ArrayList<String>(items.subList(0, limit));
    }

    /** 组装研究结果。 */
    private ResearchResult result(List<WebPage> pages, String via) {
        ResearchResult result = new ResearchResult();
        result.setPages(pages);
        result.setVia(via);
        return result;
    }

    /** 组装消息项。 */
    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("role", role);
        result.put("content", content);
        return result;
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? "未知错误" : exception.getMessage();
    }

    /** 抓取到的网页内容。 */
    public static class WebPage {

        /** 页面地址。 */
        private String url;

        /** 页面标题。 */
        private String title;

        /** 页面正文纯文本。 */
        private String text;

        /** 无参构造器。 */
        public WebPage() {
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }

    /** 联网研究结果：网页列表 + 命中途径。 */
    public static class ResearchResult {

        /** 抓取到的网页列表。 */
        private List<WebPage> pages = new ArrayList<WebPage>();

        /** 命中途径：none / ai / bing / api。 */
        private String via;

        /** 无参构造器。 */
        public ResearchResult() {
        }

        public List<WebPage> getPages() {
            return pages;
        }

        public void setPages(List<WebPage> pages) {
            this.pages = pages;
        }

        public String getVia() {
            return via;
        }

        public void setVia(String via) {
            this.via = via;
        }
    }

    /** 联网研究选项：抓取函数、进度回调与日志回调。 */
    public static class ResearchOptions {

        /** 单次最多抓取页数（0 表示使用默认值 3）。 */
        private int maxUrls;

        /** 自定义抓取函数（测试时可注入内存 HTML）。 */
        private KnowledgeCollector.PageFetcher fetcher;

        /** 研究进度回调：(stage, text)。 */
        private BiConsumer<String, String> progress;

        /** 研究日志回调。 */
        private Consumer<String> log;

        /** 无参构造器。 */
        public ResearchOptions() {
        }

        public int getMaxUrls() {
            return maxUrls;
        }

        public void setMaxUrls(int maxUrls) {
            this.maxUrls = maxUrls;
        }

        public KnowledgeCollector.PageFetcher getFetcher() {
            return fetcher;
        }

        public void setFetcher(KnowledgeCollector.PageFetcher fetcher) {
            this.fetcher = fetcher;
        }

        public BiConsumer<String, String> getProgress() {
            return progress;
        }

        public void setProgress(BiConsumer<String, String> progress) {
            this.progress = progress;
        }

        public Consumer<String> getLog() {
            return log;
        }

        public void setLog(Consumer<String> log) {
            this.log = log;
        }
    }
}
