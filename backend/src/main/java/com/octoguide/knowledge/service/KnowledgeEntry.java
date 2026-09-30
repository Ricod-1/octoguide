package com.octoguide.knowledge.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 采集链路生成的知识条目值对象。
 *
 * <p>区别于数据库实体 {@link com.octoguide.knowledge.domain.KnowledgeItem}，
 * 该对象是采集 / 转化阶段的内存传输结构，入库时再由上层映射为实体。</p>
 */
public class KnowledgeEntry {

    /** 条目唯一 id（稳定哈希，用于幂等导入）。 */
    private String id;

    /** 自然语言问题。 */
    private String question;

    /** 自动提取的标签列表。 */
    private List<String> tags = new ArrayList<String>();

    /** 分类名称。 */
    private String category;

    /** 分类图标（emoji）。 */
    private String icon;

    /** 答案内容块列表。 */
    private List<AnswerBlock> answer = new ArrayList<AnswerBlock>();

    /** 条目来源：auto（自动采集）/ learned（用户学习）。 */
    private String source;

    /** 数据源 id。 */
    private String sourceId;

    /** 来源 URL（含章节锚点）。 */
    private String sourceUrl;

    /** 来源站点标题。 */
    private String sourceTitle;

    /** 无参构造器。 */
    public KnowledgeEntry() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public List<AnswerBlock> getAnswer() {
        return answer;
    }

    public void setAnswer(List<AnswerBlock> answer) {
        this.answer = answer;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
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

    /** 答案内容块：文本、代码或提示。 */
    public static class AnswerBlock {

        /** 块类型：text（文本）、code（代码）、tip（提示）。 */
        private String type;

        /** 块内容。 */
        private String content;

        /** 无参构造器。 */
        public AnswerBlock() {
        }

        public AnswerBlock(String type, String content) {
            this.type = type;
            this.content = content;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }
}
