package com.octoguide.knowledge.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识分类器：章节级关键词规则分类 + 自动标签提取。
 *
 * <p>与 Node 版 {@code lib/knowledge-classifier.js} 行为对齐。分类规则表按顺序即平局优先级，
 * 先出现者胜；标签提取融合源标签、中文领域词和英文词元，去重后截断。</p>
 */
public final class KnowledgeClassifier {

    /** 分类命中阈值，标题 + 正文累计总分达到该值才采用规则分类结果。 */
    private static final int THRESHOLD = 2;

    /** 标题命中权重。 */
    private static final int HEADING_WEIGHT = 3;

    /** 正文命中权重。 */
    private static final int BODY_WEIGHT = 1;

    /** 单条条目最多标签数。 */
    private static final int MAX_TAGS = 6;

    /** 常见错误码，作为标签便于检索报错类问题。 */
    private static final String[] ERROR_CODES = {"403", "404"};

    /** 英文词元提取正则：小写字母开头，可含小写字母、数字、连字符。 */
    private static final Pattern WORD_PATTERN = Pattern.compile("[a-z][a-z0-9-]{1,}");

    /** 分类规则表：顺序即平局优先级。 */
    private static final List<CategoryRule> CATEGORY_RULES = buildCategoryRules();

    /** 英文标签停用词：高频但无检索价值。 */
    private static final Set<String> STOP_TAGS = new HashSet<String>(Arrays.asList(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "at", "by", "from",
            "as", "is", "are", "was", "were", "be", "been", "this", "that", "these", "those", "it",
            "its", "you", "your", "we", "our", "how", "what", "why", "when", "where", "which", "who",
            "can", "could", "will", "would", "should", "do", "does", "did", "not", "no", "yes",
            "github", "git", "use", "using", "about", "into", "up", "out", "over", "under", "more",
            "most", "new", "get", "make", "see", "set", "also", "than", "then", "there", "their"));

    /** 中文领域词表：标签提取用，子串匹配。 */
    private static final List<String> CHINESE_TERMS = Arrays.asList(
            "仓库", "分支", "提交", "合并", "冲突", "远程", "克隆", "拉取", "推送", "上传", "下载",
            "密钥", "令牌", "权限", "部署", "自动化", "工作流", "认证", "版本", "历史", "回退",
            "撤销", "协作", "团队", "审查", "议题", "入门", "教程", "学习", "新手", "注册", "账户",
            "配置", "发布", "标签", "星标", "阅读");

    /** 工具类不允许实例化。 */
    private KnowledgeClassifier() {
    }

    /**
     * 章节级分类：按关键词规则打分，高分采用，低分回退源分类。
     *
     * @param heading 章节标题
     * @param blocks 章节内容块（正文打分排除代码块，避免代码示例关键词密集误判）
     * @param fallback 回退分类（数据源配置的 category）
     * @return 分类名称
     */
    public static String classifySection(String heading, List<KnowledgeSection.Block> blocks,
                                         String fallback) {
        String headingText = normalize(heading);
        StringBuilder bodyBuilder = new StringBuilder();
        if (blocks != null) {
            for (KnowledgeSection.Block block : blocks) {
                if (!"code".equals(block.getType())) {
                    bodyBuilder.append(block.getText()).append(" ");
                }
            }
        }
        String bodyText = normalize(bodyBuilder.toString());

        String best = null;
        int bestScore = 0;
        for (CategoryRule rule : CATEGORY_RULES) {
            int score = 0;
            for (String keyword : rule.getKeywords()) {
                String normalizedKeyword = keyword.toLowerCase(Locale.ROOT);
                if (matchKeyword(headingText, normalizedKeyword)) {
                    score += HEADING_WEIGHT;
                } else if (matchKeyword(bodyText, normalizedKeyword)) {
                    score += BODY_WEIGHT;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = rule.getCategory();
            }
        }
        return bestScore >= THRESHOLD ? best : fallback;
    }

    /**
     * 自动标签：源标签 + 中文领域词 + 英文词元，去重并截断。
     *
     * @param heading 章节标题
     * @param blocks 章节内容块
     * @param sourceTags 数据源配置的 tags
     * @return 标签列表（最多 {@value #MAX_TAGS} 个）
     */
    public static List<String> extractTags(String heading, List<KnowledgeSection.Block> blocks,
                                           List<String> sourceTags) {
        StringBuilder raw = new StringBuilder(String.valueOf(heading == null ? "" : heading));
        if (blocks != null) {
            for (KnowledgeSection.Block block : blocks) {
                raw.append(" ").append(block.getText());
            }
        }
        String text = normalize(raw.toString());

        LinkedHashSet<String> tags = new LinkedHashSet<String>();
        if (sourceTags != null) {
            for (String tag : sourceTags) {
                if (tag != null && !tag.trim().isEmpty()) {
                    tags.add(tag.trim());
                }
            }
        }
        for (String term : CHINESE_TERMS) {
            if (text.contains(term)) {
                tags.add(term);
            }
        }
        for (String code : ERROR_CODES) {
            if (Pattern.compile("\\b" + code + "\\b").matcher(text).find()) {
                tags.add(code);
            }
        }
        Matcher matcher = WORD_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (!STOP_TAGS.contains(token)) {
                tags.add(token);
            }
        }

        List<String> result = new ArrayList<String>(tags);
        return result.size() > MAX_TAGS ? new ArrayList<String>(result.subList(0, MAX_TAGS)) : result;
    }

    /**
     * 文本归一化：小写化 + 统一中文/英文标点与空白为空格。
     *
     * @param text 原始文本
     * @return 归一化后的文本
     */
    public static String normalize(String text) {
        return Normalizer.normalize(String.valueOf(text == null ? "" : text).toLowerCase(Locale.ROOT),
                        Normalizer.Form.NFKC)
                .replaceAll("[（）()【】\\[\\]「」『』《》,，。.！!？?；;：:、\\\"'“”‘’\\s]+", " ")
                .trim();
    }

    /**
     * 关键词匹配：中文关键词用子串匹配，拉丁词用词边界匹配。
     *
     * <p>拉丁词使用词边界是为了避免 pr / ci / log / star / tag 误中其它单词（如 topics、shot）。</p>
     *
     * @param text 待匹配文本
     * @param keyword 关键词（已小写化）
     * @return 是否命中
     */
    private static boolean matchKeyword(String text, String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return false;
        }
        if (isCjk(keyword)) {
            return text.contains(keyword);
        }
        return Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b").matcher(text).find();
    }

    /** 判断字符串是否包含中文字符（中文用子串匹配，拉丁词用词边界匹配）。 */
    private static boolean isCjk(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (Character.UnicodeBlock.of(text.charAt(index)) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                return true;
            }
        }
        return false;
    }

    /** 构建分类规则表，顺序即平局优先级。 */
    private static List<CategoryRule> buildCategoryRules() {
        List<CategoryRule> rules = new ArrayList<CategoryRule>();
        rules.add(rule("认识 GitHub",
                "入门", "教程", "学习", "新手", "账户", "注册", "hello world", "开始", "创建账户", "设置"));
        rules.add(rule("仓库操作",
                "仓库", "repository", "远程", "remote", "clone", "克隆", "fork", "star", "星标",
                "下载", "上传", "readme", "重命名", "删除仓库"));
        rules.add(rule("提交与版本",
                "commit", "提交", "推送", "push", "版本", "历史", "log", "回退", "撤销",
                "revert", "reset", "tag", "release", "发布"));
        rules.add(rule("分支",
                "branch", "分支", "切换", "合并", "merge", "rebase", "冲突", "conflict"));
        rules.add(rule("协作",
                "pr", "pull request", "拉取请求", "issue", "议题", "review", "审查", "讨论",
                "团队", "wiki", "项目板", "协作"));
        rules.add(rule("自动化与配置",
                "actions", "workflow", "自动化", "ci", "ssh", "密钥", "token", "令牌",
                "认证", "部署", "pages", "配置", "身份验证"));
        rules.add(rule("常见报错",
                "错误", "报错", "失败", "无法", "权限", "denied", "error", "403", "404", "rejected"));
        return rules;
    }

    /** 便捷构造单条分类规则。 */
    private static CategoryRule rule(String category, String... keywords) {
        return new CategoryRule(category, Arrays.asList(keywords));
    }

    /** 单条分类规则：一组关键词映射到一个分类。 */
    private static final class CategoryRule {

        /** 目标分类。 */
        private final String category;

        /** 触发关键词列表。 */
        private final List<String> keywords;

        private CategoryRule(String category, List<String> keywords) {
            this.category = category;
            this.keywords = keywords;
        }

        public String getCategory() {
            return category;
        }

        public List<String> getKeywords() {
            return keywords;
        }
    }
}
