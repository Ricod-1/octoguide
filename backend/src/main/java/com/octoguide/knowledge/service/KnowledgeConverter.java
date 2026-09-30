package com.octoguide.knowledge.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 知识转化器：把采集到的章节结构转化为知识条目。
 *
 * <p>与 Node 版 {@code lib/knowledge-converter.js} 行为对齐。负责标题清洗、自然问题构造、
 * 稳定 id 生成、答案块合并，以及调用 {@link KnowledgeClassifier} 完成章节级智能分类和自动标签。</p>
 */
public final class KnowledgeConverter {

    /** 标题最短长度（少于该长度不生成条目）。 */
    private static final int MIN_HEADING_LEN = 4;

    /** 至少需要的有效内容块数。 */
    private static final int MIN_BLOCKS = 1;

    /** 每个条目最多块数（防止答案过长）。 */
    private static final int MAX_BLOCKS_PER_ITEM = 6;

    /** 单次采集最多生成条目数（防膨胀）。 */
    private static final int MAX_ITEMS_PER_RUN = 60;

    /** 标题中的步骤前缀 / 序号正则，用于让问题更自然。 */
    private static final Pattern STEP_PREFIX_PATTERN = Pattern.compile(
            "^\\s*(?:第\\s*[一二三四五六七八九十\\d]+\\s*步\\s*[:：]|步骤\\s*\\d+\\s*[:：]|\\d+[.、-])");

    /** 标题已以问号结尾时不再追加问号。 */
    private static final Pattern QUESTION_END_PATTERN = Pattern.compile("[？?]$");

    /** 标题已是自然问句时的开头前缀。 */
    private static final Pattern NATURAL_QUESTION_PATTERN =
            Pattern.compile("^(什么是|怎么|如何|为什么|怎样)");

    /** 工具类不允许实例化。 */
    private KnowledgeConverter() {
    }

    /**
     * 去掉标题中的步骤前缀 / 序号，让问题更自然。
     *
     * @param heading 原始章节标题
     * @return 清洗后的标题
     */
    public static String cleanHeading(String heading) {
        String cleaned = String.valueOf(heading == null ? "" : heading);
        cleaned = cleaned.replaceFirst("^\\s*第\\s*[一二三四五六七八九十\\d]+\\s*步\\s*[:：]\\s*", "")
                .replaceFirst("^\\s*步骤\\s*\\d+\\s*[:：]\\s*", "")
                .replaceFirst("^\\s*[\\d.、-]+\\s*", "")
                .trim();
        return cleaned;
    }

    /**
     * 生成稳定的条目 id：基于清洗后的标题计算 MD5。
     *
     * <p>category 参数仅为调用兼容保留，不再参与哈希，避免同一标题因分类不同生成重复条目。</p>
     *
     * @param category 分类（不参与哈希）
     * @param heading 章节标题
     * @return 形如 {@code auto-<12位hex>} 的稳定 id
     */
    public static String makeId(String category, String heading) {
        String clean = cleanHeading(heading);
        String hex = md5Hex(clean);
        return "auto-" + (hex.length() > 12 ? hex.substring(0, 12) : hex);
    }

    /**
     * 把章节标题转成自然的提问句式。
     *
     * @param heading 章节标题
     * @return 自然问题；标题为空时返回空串
     */
    public static String headingToQuestion(String heading) {
        String clean = cleanHeading(heading);
        if (clean.isEmpty()) {
            return "";
        }
        if (QUESTION_END_PATTERN.matcher(clean).find()) {
            return clean;
        }
        if (NATURAL_QUESTION_PATTERN.matcher(clean).find()) {
            return clean + "？";
        }
        return "什么是「" + clean + "」？";
    }

    /**
     * 章节 → 知识条目；不满足条件返回 null。
     *
     * @param section 章节结构
     * @param category 数据源配置分类（作为分类器回退值）
     * @param options 来源上下文（附加标签、来源 id / url / 标题）
     * @return 知识条目；标题过短、无有效块或问题为空时返回 null
     */
    public static KnowledgeEntry convertSection(KnowledgeSection section, String category,
                                                SourceOptions options) {
        String heading = section == null || section.getHeading() == null
                ? "" : section.getHeading().trim();
        if (heading.length() < MIN_HEADING_LEN) {
            return null;
        }
        List<KnowledgeSection.Block> blocks = section.getBlocks() == null
                ? new ArrayList<KnowledgeSection.Block>() : section.getBlocks();
        if (blocks.size() < MIN_BLOCKS) {
            return null;
        }

        // 组装答案块：最多取前 6 块，空块跳过，代码块保留 code 类型
        List<KnowledgeEntry.AnswerBlock> answer = new ArrayList<KnowledgeEntry.AnswerBlock>();
        for (KnowledgeSection.Block block : blocks) {
            String text = block.getText() == null ? "" : block.getText().trim();
            if (text.isEmpty()) {
                continue;
            }
            String type = "code".equals(block.getType()) ? "code" : "text";
            answer.add(new KnowledgeEntry.AnswerBlock(type, text));
            if (answer.size() >= MAX_BLOCKS_PER_ITEM) {
                break;
            }
        }
        if (answer.isEmpty()) {
            return null;
        }

        // 合并相邻 text 块，减少碎片
        List<KnowledgeEntry.AnswerBlock> merged = mergeAdjacentText(answer);

        String question = headingToQuestion(heading);
        if (question.isEmpty()) {
            return null;
        }

        // 章节级智能分类 + 自动标签（传原始章节块，正文参与打分 / 标签提取）
        String finalCategory = KnowledgeClassifier.classifySection(heading, blocks,
                category == null || category.isEmpty() ? "自动采集" : category);
        List<String> tags = KnowledgeClassifier.extractTags(heading, blocks,
                options == null ? null : options.getExtraTags());
        String id = makeId(finalCategory, heading);
        String anchor = section.getId() == null || section.getId().isEmpty()
                ? "" : "#" + section.getId();
        String sourceUrl = (options == null || options.getSourceUrl() == null
                ? "" : options.getSourceUrl()) + anchor;

        KnowledgeEntry entry = new KnowledgeEntry();
        entry.setId(id);
        entry.setQuestion(question);
        entry.setTags(tags);
        entry.setCategory(finalCategory);
        entry.setIcon("🤖");
        entry.setAnswer(merged);
        entry.setSource("auto");
        entry.setSourceId(options == null ? null : options.getSourceId());
        entry.setSourceUrl(sourceUrl);
        entry.setSourceTitle(options == null || options.getSourceTitle() == null
                ? "" : options.getSourceTitle());
        return entry;
    }

    /**
     * 批量转化：按分类处理所有章节，限制总条数。
     *
     * @param sections 章节列表
     * @param category 数据源配置分类（回退值）
     * @param options 来源上下文
     * @return 知识条目列表（最多 {@value #MAX_ITEMS_PER_RUN} 条）
     */
    public static List<KnowledgeEntry> convertAll(List<KnowledgeSection> sections, String category,
                                                  SourceOptions options) {
        List<KnowledgeEntry> items = new ArrayList<KnowledgeEntry>();
        if (sections == null) {
            return items;
        }
        for (KnowledgeSection section : sections) {
            KnowledgeEntry item = convertSection(section, category, options);
            if (item != null) {
                items.add(item);
            }
            if (items.size() >= MAX_ITEMS_PER_RUN) {
                break;
            }
        }
        return items;
    }

    /** 合并相邻的 text 块，减少答案碎片。 */
    private static List<KnowledgeEntry.AnswerBlock> mergeAdjacentText(
            List<KnowledgeEntry.AnswerBlock> answer) {
        List<KnowledgeEntry.AnswerBlock> merged = new ArrayList<KnowledgeEntry.AnswerBlock>();
        for (KnowledgeEntry.AnswerBlock block : answer) {
            if (merged.isEmpty()) {
                merged.add(block);
                continue;
            }
            KnowledgeEntry.AnswerBlock last = merged.get(merged.size() - 1);
            if ("text".equals(last.getType()) && "text".equals(block.getType())) {
                last.setContent(last.getContent() + "\n" + block.getContent());
            } else {
                merged.add(block);
            }
        }
        return merged;
    }

    /** 计算字符串的 MD5 十六进制摘要。 */
    private static String md5Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : bytes) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 在 JDK 中恒存在，这里仅做防御性处理
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }

    /** 转化来源上下文：附加标签与来源字段。 */
    public static class SourceOptions {

        /** 数据源配置的附加标签。 */
        private List<String> extraTags;

        /** 数据源 id。 */
        private String sourceId;

        /** 数据源 URL。 */
        private String sourceUrl;

        /** 数据源站点标题。 */
        private String sourceTitle;

        /** 无参构造器。 */
        public SourceOptions() {
        }

        public List<String> getExtraTags() {
            return extraTags;
        }

        public void setExtraTags(List<String> extraTags) {
            this.extraTags = extraTags;
        }

        public String getSourceId() {
            return sourceId;
        }

        public void setSourceId(String sourceId) {
            this.sourceId = sourceId;
        }

        public String getSourceUrl() {
            return sourceUrl;
        }

        public void setSourceUrl(String sourceUrl) {
            this.sourceUrl = sourceUrl;
        }

        public String getSourceTitle() {
            return sourceTitle;
        }

        public void setSourceTitle(String sourceTitle) {
            this.sourceTitle = sourceTitle;
        }
    }
}
