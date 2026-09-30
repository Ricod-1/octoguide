package com.octoguide.knowledge.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识采集器：抓取网页 → 提取内容区 → 按 h2 切分章节 → 分发解析。
 *
 * <p>与 Node 版 {@code lib/knowledge-collector.js} 行为对齐。支持三种数据源类型：
 * docs（GitHub 文档章节级解析）、trending（热门仓库榜）、rss（订阅源）。
 * 解析函数均为纯函数，便于单元测试；网络抓取通过可注入的 {@link PageFetcher} 解耦。</p>
 */
public final class KnowledgeCollector {

    /** 默认请求超时时间（毫秒）。 */
    private static final int TIMEOUT_MS = 15000;

    /** 请求标识，方便站点识别爬虫。 */
    private static final String UA = "OctoGuideBot/0.1 (+https://github.com/octoguide)";

    /** 每个章节最多块数。 */
    private static final int MAX_BLOCKS_PER_SECTION = 12;

    /** 趋势仓库最多条数。 */
    private static final int MAX_TRENDING = 25;

    /** RSS 文章最多条数。 */
    private static final int MAX_RSS = 15;

    /** 汇总条目标题条数（趋势）。 */
    private static final int MAX_TRENDING_SUMMARY = 10;

    /** 汇总条目标题条数（RSS）。 */
    private static final int MAX_RSS_SUMMARY = 8;

    /** 通用正文提取的最大字符数。 */
    private static final int MAX_GENERIC_TEXT = 6000;

    /** 单次网络响应最大字符数，避免异常大响应耗尽堆内存。 */
    private static final int MAX_RESPONSE_CHARS = 2_000_000;

    /** 自动重定向最大次数。 */
    private static final int MAX_REDIRECTS = 5;

    /** 单个数据源抓取尝试次数（首次 + 重试 2 次）。 */
    private static final int MAX_FETCH_ATTEMPTS = 3;

    /** 需要跳过的导航性章节（GitHub 文档的“在本文中”是迷你目录）。 */
    private static final Set<String> SKIP_HEADINGS = new HashSet<String>(Arrays.asList(
            "在本文中", "In this article", "结束语", "后续步骤", "延伸阅读", "其他阅读材料", "进一步阅读"));

    // ---------- HTML 解析正则 ----------

    private static final Pattern SCRIPT_TAG = Pattern.compile("(?i)<script[\\s\\S]*?</script>");
    private static final Pattern STYLE_TAG = Pattern.compile("(?i)<style[\\s\\S]*?</style>");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern DECIMAL_ENTITY = Pattern.compile("&#(\\d+);");
    private static final Pattern HEX_ENTITY = Pattern.compile("(?i)&#x([0-9a-f]+);");
    private static final Pattern TITLE_TAG = Pattern.compile("(?i)<title[^>]*>([\\s\\S]*?)</title>");
    private static final Pattern ARTICLE_TAG = Pattern.compile("(?i)<article[^>]*>([\\s\\S]*?)</article>");
    private static final Pattern MAIN_TAG = Pattern.compile("(?i)<main[^>]*>([\\s\\S]*?)</main>");
    private static final Pattern MINITOC_NAV = Pattern.compile(
            "(?i)<nav[^>]*data-testid=\"minitoc\"[\\s\\S]*?</nav>");
    private static final Pattern H2_TAG = Pattern.compile(
            "<h2[^>]*id=\"([^\"]*)\"[^>]*>([\\s\\S]*?)</h2>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRE_TAG = Pattern.compile("(?i)<pre[\\s\\S]*?</pre>");
    private static final Pattern P_TAG = Pattern.compile("(?i)<p[^>]*>([\\s\\S]*?)</p>");
    private static final Pattern LI_TAG = Pattern.compile("(?i)<li[^>]*>([\\s\\S]*?)</li>");
    private static final Pattern CHARSET_PATTERN = Pattern.compile("charset=([^;\\s]+)", Pattern.CASE_INSENSITIVE);

    // ---------- Trending 解析正则 ----------

    private static final Pattern TRENDING_ROW = Pattern.compile(
            "(?i)<article[^>]*class=\"[^\"]*Box-row[^\"]*\"[\\s\\S]*?</article>");
    private static final Pattern REPO_NAME = Pattern.compile(
            "(?i)<h2[^>]*>[\\s\\S]*?<a[^>]*href=\"/([^\"/]+/[^\"/]+)\"[^>]*>");
    private static final Pattern REPO_DESC = Pattern.compile(
            "(?i)<p[^>]*class=\"[^\"]*col-9[^\"]*\"[^>]*>([\\s\\S]*?)</p>");
    private static final Pattern REPO_LANG = Pattern.compile(
            "itemprop=\"programmingLanguage\"[^>]*>([\\s\\S]*?)<");
    private static final Pattern REPO_STARS = Pattern.compile(
            "href=\"/[^\"]*/stargazers\"[^>]*>([\\s\\S]*?)</a>");
    private static final Pattern REPO_TODAY = Pattern.compile(
            "([\\d,.kK]+)\\s*stars?\\s*today", Pattern.CASE_INSENSITIVE);

    // ---------- RSS 解析正则 ----------

    private static final Pattern RSS_ITEM = Pattern.compile("<item>[\\s\\S]*?</item>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHANGELOG_FOOTER = Pattern.compile(
            "(?i)\\s*The post[\\s\\S]*?appeared first on\\s+The GitHub Blog[\\s.]*$");

    /** 工具类不允许实例化。 */
    private KnowledgeCollector() {
    }

    // ---------- HTML 工具 ----------

    /**
     * 去除 HTML 标签和实体，保留纯文本。
     *
     * <p>先剔除 script / style 内容，再替换剩余标签与常见实体（含数字实体），最后压缩空白。</p>
     *
     * @param html 原始 HTML
     * @return 纯文本
     */
    public static String stripTags(String html) {
        if (html == null) {
            return "";
        }
        String text = SCRIPT_TAG.matcher(html).replaceAll("");
        text = STYLE_TAG.matcher(text).replaceAll("");
        text = HTML_TAG.matcher(text).replaceAll(" ");
        text = decodeCommonEntities(text);
        text = replaceEntities(text);
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * 代码块专用纯文本提取：保留换行结构，避免把多行命令压成一行。
     *
     * <p>与 {@link #stripTags} 的区别：标签直接移除（不补空格），仅压缩行内空白，
     * 换行符原样保留，行首缩进不受影响。</p>
     *
     * @param html 代码块原始 HTML（通常为 {@code <pre>…</pre>} 内容）
     * @return 保留换行的纯文本
     */
    public static String stripCodeBlock(String html) {
        if (html == null) {
            return "";
        }
        String text = SCRIPT_TAG.matcher(html).replaceAll("");
        text = STYLE_TAG.matcher(text).replaceAll("");
        text = HTML_TAG.matcher(text).replaceAll("");
        text = decodeCommonEntities(text);
        text = replaceEntities(text);
        text = text.replaceAll("\\r\\n?", "\n");
        text = text.replaceAll("[ \\t\\x0B\\f]+", " ");
        text = text.replaceAll(" ?\\n ?", "\n");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }

    /** 替换常见 HTML 命名实体为对应字符。 */
    private static String decodeCommonEntities(String text) {
        return text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    /**
     * 提取页面 {@code <title>} 文本，用于展示来源标题。
     *
     * @param html 原始 HTML
     * @return 页面标题；未找到返回空串
     */
    public static String extractPageTitle(String html) {
        if (html == null) {
            return "";
        }
        Matcher matcher = TITLE_TAG.matcher(html);
        return matcher.find() ? stripTags(matcher.group(1)).trim() : "";
    }

    /**
     * 通用正文提取：优先 article / main 内容区，否则取整页纯文本。
     *
     * <p>用于联网查询抓任意网页（区别于 GitHub 文档的章节级解析）。</p>
     *
     * @param html 原始 HTML
     * @return 正文纯文本（最多 {@value #MAX_GENERIC_TEXT} 字符）
     */
    public static String extractGenericText(String html) {
        if (html == null) {
            return "";
        }
        String zone;
        Matcher article = ARTICLE_TAG.matcher(html);
        Matcher main = MAIN_TAG.matcher(html);
        if (article.find()) {
            zone = article.group(1);
        } else if (main.find()) {
            zone = main.group(1);
        } else {
            zone = html;
        }
        String text = stripTags(zone);
        return text.length() > MAX_GENERIC_TEXT ? text.substring(0, MAX_GENERIC_TEXT) : text;
    }

    /**
     * 提取 main 内容区（找不到则返回整页）。
     *
     * @param html 原始 HTML
     * @return main 内容区 HTML
     */
    public static String extractMain(String html) {
        if (html == null) {
            return "";
        }
        Matcher main = MAIN_TAG.matcher(html);
        return main.find() ? main.group(1) : html;
    }

    /**
     * 把正文按 h2 切分为章节。
     *
     * <p>只匹配带 id 的正文 h2（排除迷你目录导航），并对每个章节调用
     * {@link #extractBlocks} 提取内容块。</p>
     *
     * @param content 内容区 HTML
     * @return 章节列表
     */
    public static List<KnowledgeSection> splitByH2(String content) {
        if (content == null) {
            return Collections.emptyList();
        }
        String cleaned = MINITOC_NAV.matcher(content).replaceAll("");
        Matcher matcher = H2_TAG.matcher(cleaned);
        List<H2Match> matches = new ArrayList<H2Match>();
        while (matcher.find()) {
            matches.add(new H2Match(matcher.start(), matcher.end(),
                    matcher.group(1).trim(), stripTags(matcher.group(2)).trim()));
        }

        List<KnowledgeSection> sections = new ArrayList<KnowledgeSection>();
        for (int index = 0; index < matches.size(); index++) {
            H2Match current = matches.get(index);
            if (current.heading.isEmpty() || SKIP_HEADINGS.contains(current.heading)) {
                continue;
            }
            int bodyStart = current.end;
            int bodyEnd = index + 1 < matches.size() ? matches.get(index + 1).start : cleaned.length();
            String body = cleaned.substring(bodyStart, bodyEnd);
            KnowledgeSection section = new KnowledgeSection();
            section.setHeading(current.heading);
            section.setId(current.id);
            section.setBlocks(extractBlocks(body));
            sections.add(section);
        }
        return sections;
    }

    /**
     * 从章节 body 提取内容块（代码 / 段落 / 列表项）。
     *
     * <p>顺序与 Node 版一致：先代码块，再段落，再列表项，最后截断到最多
     * {@value #MAX_BLOCKS_PER_SECTION} 块。</p>
     *
     * @param body 章节内容 HTML
     * @return 内容块列表
     */
    public static List<KnowledgeSection.Block> extractBlocks(String body) {
        if (body == null) {
            return Collections.emptyList();
        }
        List<KnowledgeSection.Block> blocks = new ArrayList<KnowledgeSection.Block>();

        Matcher codeMatcher = PRE_TAG.matcher(body);
        while (codeMatcher.find()) {
            String text = stripCodeBlock(codeMatcher.group());
            if (!text.isEmpty()) {
                blocks.add(new KnowledgeSection.Block("code", text));
            }
        }

        String textWithoutPre = PRE_TAG.matcher(body).replaceAll("\u0000CODE\u0000");
        Matcher pMatcher = P_TAG.matcher(textWithoutPre);
        while (pMatcher.find()) {
            String text = stripTags(pMatcher.group(1));
            if (!text.trim().isEmpty()) {
                blocks.add(new KnowledgeSection.Block("p", text.trim()));
            }
        }
        Matcher liMatcher = LI_TAG.matcher(textWithoutPre);
        while (liMatcher.find()) {
            String text = stripTags(liMatcher.group(1));
            if (!text.trim().isEmpty()) {
                blocks.add(new KnowledgeSection.Block("li", text.trim()));
            }
        }

        return blocks.size() > MAX_BLOCKS_PER_SECTION
                ? new ArrayList<KnowledgeSection.Block>(blocks.subList(0, MAX_BLOCKS_PER_SECTION)) : blocks;
    }

    /**
     * 解析 HTML → 完整章节数组（不含空章节）。
     *
     * @param html 文档页 HTML
     * @return 章节列表
     */
    public static List<KnowledgeSection> parseHtmlToSections(String html) {
        if (html == null) {
            return Collections.emptyList();
        }
        List<KnowledgeSection> sections = splitByH2(extractMain(html));
        List<KnowledgeSection> result = new ArrayList<KnowledgeSection>();
        for (KnowledgeSection section : sections) {
            if (!section.getBlocks().isEmpty()) {
                result.add(section);
            }
        }
        return result;
    }

    // ---------- Trending 解析 ----------

    /**
     * 解析 github.com/trending 页面 → 仓库列表。
     *
     * @param html 趋势页 HTML
     * @return 仓库列表（最多 {@value #MAX_TRENDING} 条）
     */
    public static List<TrendingRepo> parseTrending(String html) {
        if (html == null) {
            return Collections.emptyList();
        }
        List<TrendingRepo> repos = new ArrayList<TrendingRepo>();
        Matcher rowMatcher = TRENDING_ROW.matcher(html);
        while (rowMatcher.find()) {
            String row = rowMatcher.group();
            Matcher nameMatcher = REPO_NAME.matcher(row);
            if (!nameMatcher.find()) {
                continue;
            }
            TrendingRepo repo = new TrendingRepo();
            repo.setFullName(nameMatcher.group(1).trim());
            repo.setDesc(stripTags(firstGroup(row, REPO_DESC)));
            repo.setLang(stripTags(firstGroup(row, REPO_LANG)));
            repo.setStars(stripTags(firstGroup(row, REPO_STARS)));
            repo.setToday(firstGroup(row, REPO_TODAY));
            repos.add(repo);
            if (repos.size() >= MAX_TRENDING) {
                break;
            }
        }
        return repos;
    }

    /**
     * 仓库列表 → 知识条目（每仓库 1 条 + 1 条汇总）。
     *
     * @param repos 仓库列表
     * @param source 数据源配置
     * @return 知识条目列表
     */
    public static List<KnowledgeEntry> buildTrendingItems(List<TrendingRepo> repos, KnowledgeSource source) {
        List<KnowledgeEntry> items = new ArrayList<KnowledgeEntry>();
        if (repos == null || repos.isEmpty()) {
            return items;
        }
        List<String> tagBase = new ArrayList<String>(source.getTags());

        for (TrendingRepo repo : repos) {
            List<KnowledgeEntry.AnswerBlock> answer = new ArrayList<KnowledgeEntry.AnswerBlock>();
            if (!empty(repo.getDesc())) {
                answer.add(new KnowledgeEntry.AnswerBlock("text", repo.getDesc()));
            }
            StringBuilder meta = new StringBuilder("⭐ ").append(empty(repo.getStars()) ? "?" : repo.getStars()).append(" 星");
            if (!empty(repo.getToday())) {
                meta.append(" · 今日 +").append(repo.getToday());
            }
            if (!empty(repo.getLang())) {
                meta.append(" · 语言：").append(repo.getLang());
            }
            meta.append(" · https://github.com/").append(repo.getFullName());
            answer.add(new KnowledgeEntry.AnswerBlock("text", meta.toString()));

            List<String> combinedTags = new ArrayList<String>(tagBase);
            if (!empty(repo.getLang())) {
                combinedTags.add(repo.getLang());
            }

            KnowledgeEntry entry = new KnowledgeEntry();
            entry.setId(KnowledgeConverter.makeId("趋势仓库", repo.getFullName()));
            entry.setQuestion("趋势仓库「" + repo.getFullName() + "」是做什么的？");
            entry.setTags(KnowledgeClassifier.extractTags(repo.getFullName(), toSectionBlocks(answer), combinedTags));
            entry.setCategory("趋势仓库");
            entry.setIcon("🔥");
            entry.setAnswer(answer);
            entry.setSource("auto");
            entry.setSourceId(source.getId());
            entry.setSourceUrl("https://github.com/" + repo.getFullName());
            entry.setSourceTitle(empty(source.getSiteTitle()) ? source.getTitle() : source.getSiteTitle());
            items.add(entry);
        }

        // 汇总条目
        int summaryCount = Math.min(repos.size(), MAX_TRENDING_SUMMARY);
        StringBuilder lines = new StringBuilder();
        for (int index = 0; index < summaryCount; index++) {
            TrendingRepo repo = repos.get(index);
            if (index > 0) {
                lines.append("\n");
            }
            lines.append("· ").append(repo.getFullName())
                    .append(" ⭐").append(empty(repo.getStars()) ? "?" : repo.getStars());
            if (!empty(repo.getLang())) {
                lines.append("（").append(repo.getLang()).append("）");
            }
            String desc = repo.getDesc() == null ? "" : repo.getDesc();
            lines.append(" — ").append(desc.length() > 60 ? desc.substring(0, 60) : desc);
        }
        List<String> summaryTags = new ArrayList<String>(tagBase);
        summaryTags.add("排行榜");

        KnowledgeEntry summary = new KnowledgeEntry();
        summary.setId("auto-trending-summary-" + source.getId());
        summary.setQuestion("GitHub 最近有什么热门仓库？");
        summary.setTags(new ArrayList<String>(new LinkedHashSet<String>(summaryTags)));
        summary.setCategory("趋势仓库");
        summary.setIcon("🔥");
        summary.setAnswer(Collections.singletonList(new KnowledgeEntry.AnswerBlock("text",
                "今日 GitHub 热门仓库 Top " + summaryCount + "：\n" + lines)));
        summary.setSource("auto");
        summary.setSourceId(source.getId());
        summary.setSourceUrl(source.getUrl());
        summary.setSourceTitle(empty(source.getSiteTitle()) ? source.getTitle() : source.getSiteTitle());
        items.add(summary);
        return items;
    }

    // ---------- RSS 解析 ----------

    /**
     * 提取标签内容（兼容 CDATA）。
     *
     * @param raw 原始条目 XML 片段
     * @param tag 标签名
     * @return 标签文本；未找到返回空串
     */
    public static String tagContent(String raw, String tag) {
        Pattern pattern = Pattern.compile("<" + tag + ">([\\s\\S]*?)</" + tag + ">", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(raw == null ? "" : raw);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).replaceAll("<!\\[CDATA\\[|\\]\\]>", "").trim();
    }

    /**
     * 解析 RSS / Atom 订阅 → 文章条目。
     *
     * @param xml RSS 内容
     * @return 文章列表（最多 {@value #MAX_RSS} 条）
     */
    public static List<RssEntry> parseRss(String xml) {
        if (xml == null) {
            return Collections.emptyList();
        }
        List<RssEntry> entries = new ArrayList<RssEntry>();
        Matcher itemMatcher = RSS_ITEM.matcher(xml);
        while (itemMatcher.find()) {
            String raw = itemMatcher.group();
            String title = tagContent(raw, "title");
            String link = tagContent(raw, "link");
            String date = tagContent(raw, "pubDate");
            String desc = stripTags(tagContent(raw, "description"));
            desc = CHANGELOG_FOOTER.matcher(desc).replaceAll("").trim();
            if (desc.length() > 300) {
                desc = desc.substring(0, 300) + "…";
            }
            if (!title.isEmpty() && !link.isEmpty()) {
                RssEntry entry = new RssEntry();
                entry.setTitle(title);
                entry.setLink(link);
                entry.setDate(date);
                entry.setDesc(desc);
                entries.add(entry);
            }
            if (entries.size() >= MAX_RSS) {
                break;
            }
        }
        return entries;
    }

    /**
     * 文章列表 → 知识条目（每篇 1 条 + 1 条汇总）。
     *
     * @param entries 文章列表
     * @param source 数据源配置
     * @return 知识条目列表
     */
    public static List<KnowledgeEntry> buildRssItems(List<RssEntry> entries, KnowledgeSource source) {
        List<KnowledgeEntry> items = new ArrayList<KnowledgeEntry>();
        if (entries == null || entries.isEmpty()) {
            return items;
        }
        List<String> tagBase = new ArrayList<String>(source.getTags());

        for (RssEntry entry : entries) {
            List<KnowledgeEntry.AnswerBlock> answer = new ArrayList<KnowledgeEntry.AnswerBlock>();
            if (!empty(entry.getDesc())) {
                answer.add(new KnowledgeEntry.AnswerBlock("text", entry.getDesc()));
            }
            answer.add(new KnowledgeEntry.AnswerBlock("text",
                    (empty(entry.getDate()) ? "" : "📅 " + entry.getDate() + " · ") + "原文：" + entry.getLink()));

            KnowledgeEntry item = new KnowledgeEntry();
            item.setId(KnowledgeConverter.makeId("新功能动态", entry.getTitle()));
            item.setQuestion(entry.getTitle());
            item.setTags(KnowledgeClassifier.extractTags(entry.getTitle(), toSectionBlocks(answer), tagBase));
            item.setCategory("新功能动态");
            item.setIcon("📣");
            item.setAnswer(answer);
            item.setSource("auto");
            item.setSourceId(source.getId());
            item.setSourceUrl(entry.getLink());
            item.setSourceTitle(empty(source.getSiteTitle()) ? source.getTitle() : source.getSiteTitle());
            items.add(item);
        }

        // 汇总条目
        int summaryCount = Math.min(entries.size(), MAX_RSS_SUMMARY);
        StringBuilder lines = new StringBuilder();
        for (int index = 0; index < summaryCount; index++) {
            RssEntry entry = entries.get(index);
            if (index > 0) {
                lines.append("\n");
            }
            lines.append("· ").append(entry.getTitle());
            if (!empty(entry.getDate())) {
                String date = entry.getDate();
                lines.append("（").append(date.length() > 16 ? date.substring(0, 16) : date).append("）");
            }
        }
        List<String> summaryTags = new ArrayList<String>(tagBase);
        summaryTags.add("动态");

        KnowledgeEntry summary = new KnowledgeEntry();
        summary.setId("auto-changelog-summary-" + source.getId());
        summary.setQuestion("GitHub 最近发布了什么新功能？");
        summary.setTags(summaryTags);
        summary.setCategory("新功能动态");
        summary.setIcon("📣");
        summary.setAnswer(Collections.singletonList(new KnowledgeEntry.AnswerBlock("text",
                "GitHub 官方最近更新（近 " + summaryCount + " 条）：\n" + lines
                        + "\n\n完整列表见 https://github.blog/changelog/")));
        summary.setSource("auto");
        summary.setSourceId(source.getId());
        summary.setSourceUrl(source.getUrl());
        summary.setSourceTitle(empty(source.getSiteTitle()) ? source.getTitle() : source.getSiteTitle());
        items.add(summary);
        return items;
    }

    // ---------- 抓取与采集入口 ----------

    /**
     * 页面抓取函数抽象，便于测试时注入内存 HTML。
     */
    @FunctionalInterface
    public interface PageFetcher {

        /**
         * 抓取指定 URL 并返回页面内容。
         *
         * @param url 页面地址
         * @return 页面 HTML 文本
         * @throws IOException 抓取失败时抛出
         */
        String fetch(String url) throws IOException;
    }

    /**
     * 抓取单个页面（使用默认 HTTP 客户端，超时 {@value #TIMEOUT_MS} 毫秒）。
     *
     * @param url 页面地址
     * @return 页面 HTML 文本
     * @throws IOException 抓取失败时抛出
     */
    public static String fetchPage(String url) throws IOException {
        return httpGet(url);
    }

    /**
     * 采集入口：按 source.type 分发解析。
     *
     * <p>docs 走章节解析 + 转化，trending 走仓库解析，rss 走文章解析；
     * 单个源失败时跳过并记录日志，不影响其它源。</p>
     *
     * @param sources 数据源配置列表
     * @param fetcher 页面抓取函数
     * @param log 日志回调（可为 null）
     * @return 全部采集到的知识条目
     */
    public static List<KnowledgeEntry> collect(List<KnowledgeSource> sources, PageFetcher fetcher,
                                               Consumer<String> log) {
        List<KnowledgeEntry> allItems = new ArrayList<KnowledgeEntry>();
        if (sources == null) {
            return allItems;
        }
        for (KnowledgeSource source : sources) {
            try {
                String type = empty(source.getType()) ? "docs" : source.getType();
                if (log != null) {
                    log.accept("[collect] 抓取(" + type + "): " + source.getUrl());
                }
                String body = fetchWithRetry(fetcher, source.getUrl(), log);
                List<KnowledgeEntry> sourceItems = new ArrayList<KnowledgeEntry>();
                if ("trending".equals(type)) {
                    List<TrendingRepo> repos = parseTrending(body);
                    sourceItems.addAll(buildTrendingItems(repos, source));
                } else if ("rss".equals(type)) {
                    List<RssEntry> entries = parseRss(body);
                    sourceItems.addAll(buildRssItems(entries, source));
                } else {
                    List<KnowledgeSection> sections = parseHtmlToSections(body);
                    KnowledgeConverter.SourceOptions options = new KnowledgeConverter.SourceOptions();
                    options.setExtraTags(source.getTags());
                    options.setSourceId(source.getId());
                    options.setSourceUrl(source.getUrl());
                    options.setSourceTitle(empty(source.getSiteTitle()) ? source.getTitle() : source.getSiteTitle());
                    sourceItems.addAll(KnowledgeConverter.convertAll(sections, source.getCategory(), options));
                }
                // 数据源类型写入条目（docs / trending / rss），供来源统计使用
                for (KnowledgeEntry entry : sourceItems) {
                    entry.setSource(type);
                }
                allItems.addAll(sourceItems);
            } catch (Exception e) {
                if (log != null) {
                    log.accept("[collect] 跳过（" + e.getMessage() + "）");
                }
            }
        }
        return allItems;
    }

    /**
     * 抓取指定数据源，瞬时网络错误（如 TLS 握手失败）时自动重试。
     *
     * @param fetcher 页面抓取函数
     * @param url 页面地址
     * @param log 日志回调（可为 null）
     * @return 页面内容
     * @throws IOException 全部尝试均失败时抛出最后一次异常
     */
    private static String fetchWithRetry(PageFetcher fetcher, String url, Consumer<String> log) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_FETCH_ATTEMPTS; attempt++) {
            try {
                return fetcher.fetch(url);
            } catch (IOException exception) {
                last = exception;
                if (log != null && attempt < MAX_FETCH_ATTEMPTS) {
                    log.accept("[collect] 第 " + attempt + " 次抓取失败（" + exception.getMessage() + "），重试");
                }
            }
        }
        throw last;
    }

    // ---------- 私有工具 ----------

    /** 使用 HttpURLConnection 执行 GET 请求并读取响应文本。 */
    private static String httpGet(String url) throws IOException {
        String currentUrl = url;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            if (!isSafeNetworkUrl(currentUrl)) {
                throw new IOException("拒绝访问本机或内网地址");
            }

            URL target = new URL(currentUrl);
            HttpURLConnection connection = (HttpURLConnection) target.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            // 手动处理重定向，确保每一个目标都经过私网地址校验。
            connection.setInstanceFollowRedirects(false);
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirect == MAX_REDIRECTS) {
                        throw new IOException("重定向次数超过限制");
                    }
                    currentUrl = new URL(target, location).toExternalForm();
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new IOException("HTTP " + status);
                }
                String contentLength = connection.getHeaderField("Content-Length");
                if (contentLength != null && Long.parseLong(contentLength) > MAX_RESPONSE_CHARS * 4L) {
                    throw new IOException("响应内容过大");
                }
                String charset = charsetFrom(connection.getContentType());
                InputStream input = connection.getInputStream();
                try {
                    return readText(input, charset);
                } finally {
                    input.close();
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("重定向次数超过限制");
    }

    /**
     * 校验网络地址，阻止环回、链路本地、站点本地及未解析的地址。
     * 该方法包级可见，便于用单元测试覆盖安全边界。
     */
    static boolean isSafeNetworkUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || host == null || uri.getUserInfo() != null) {
                return false;
            }
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                    return false;
                }
            }
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    /** 按指定字符集读取输入流全部文本。 */
    private static String readText(InputStream input, String charset) throws IOException {
        StringBuilder builder = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, charset));
        try {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (builder.length() + read > MAX_RESPONSE_CHARS) {
                    throw new IOException("响应内容过大");
                }
                builder.append(buffer, 0, read);
            }
        } finally {
            reader.close();
        }
        return builder.toString();
    }

    /** 从 Content-Type 中解析 charset，缺失时默认 UTF-8。 */
    private static String charsetFrom(String contentType) {
        if (contentType != null) {
            Matcher matcher = CHARSET_PATTERN.matcher(contentType);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "UTF-8";
    }

    /** 替换 HTML 数字实体为对应字符。 */
    private static String replaceEntities(String text) {
        String result = text;
        Matcher decimal = DECIMAL_ENTITY.matcher(result);
        StringBuffer decimalBuffer = new StringBuffer();
        while (decimal.find()) {
            int code = Integer.parseInt(decimal.group(1));
            decimal.appendReplacement(decimalBuffer,
                    Matcher.quoteReplacement(String.valueOf((char) code)));
        }
        decimal.appendTail(decimalBuffer);
        result = decimalBuffer.toString();

        Matcher hex = HEX_ENTITY.matcher(result);
        StringBuffer hexBuffer = new StringBuffer();
        while (hex.find()) {
            int code = Integer.parseInt(hex.group(1), 16);
            hex.appendReplacement(hexBuffer, Matcher.quoteReplacement(String.valueOf((char) code)));
        }
        hex.appendTail(hexBuffer);
        return hexBuffer.toString();
    }

    /** 返回输入中第一个捕获组；无匹配返回空串。 */
    private static String firstGroup(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /** 把答案块转换为章节块（标签提取 / 分类共用结构）。 */
    private static List<KnowledgeSection.Block> toSectionBlocks(List<KnowledgeEntry.AnswerBlock> answer) {
        List<KnowledgeSection.Block> blocks = new ArrayList<KnowledgeSection.Block>();
        for (KnowledgeEntry.AnswerBlock block : answer) {
            blocks.add(new KnowledgeSection.Block(block.getType(), block.getContent()));
        }
        return blocks;
    }

    /** 判断字符串是否为空或仅空白。 */
    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** h2 匹配结果：起止位置 + id + 标题。 */
    private static final class H2Match {

        private final int start;
        private final int end;
        private final String id;
        private final String heading;

        private H2Match(int start, int end, String id, String heading) {
            this.start = start;
            this.end = end;
            this.id = id;
            this.heading = heading;
        }
    }

    /** 趋势仓库解析结果。 */
    public static class TrendingRepo {

        /** 仓库全名（owner/name）。 */
        private String fullName;

        /** 仓库描述。 */
        private String desc;

        /** 主要编程语言。 */
        private String lang;

        /** 总星数展示文本。 */
        private String stars;

        /** 今日新增星数展示文本。 */
        private String today;

        /** 无参构造器。 */
        public TrendingRepo() {
        }

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }

        public String getDesc() {
            return desc;
        }

        public void setDesc(String desc) {
            this.desc = desc;
        }

        public String getLang() {
            return lang;
        }

        public void setLang(String lang) {
            this.lang = lang;
        }

        public String getStars() {
            return stars;
        }

        public void setStars(String stars) {
            this.stars = stars;
        }

        public String getToday() {
            return today;
        }

        public void setToday(String today) {
            this.today = today;
        }
    }

    /** RSS 文章解析结果。 */
    public static class RssEntry {

        /** 文章标题。 */
        private String title;

        /** 文章链接。 */
        private String link;

        /** 发布日期文本。 */
        private String date;

        /** 文章摘要。 */
        private String desc;

        /** 无参构造器。 */
        public RssEntry() {
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getLink() {
            return link;
        }

        public void setLink(String link) {
            this.link = link;
        }

        public String getDate() {
            return date;
        }

        public void setDate(String date) {
            this.date = date;
        }

        public String getDesc() {
            return desc;
        }

        public void setDesc(String desc) {
            this.desc = desc;
        }
    }
}
