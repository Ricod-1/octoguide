package com.octoguide.knowledge.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 采集链路中的章节结构。
 *
 * <p>由 {@link KnowledgeCollector#parseHtmlToSections} 从 GitHub 文档页解析得到，
 * 是知识转化器 {@link KnowledgeConverter} 的输入，与数据库实体解耦。</p>
 */
public class KnowledgeSection {

    /** 章节标题。 */
    private String heading;

    /** 章节锚点 id（用于拼接到来源 URL 后）。 */
    private String id;

    /** 章节内容块列表。 */
    private List<Block> blocks = new ArrayList<Block>();

    /** MyBatis 等框架通过无参构造器创建对象。 */
    public KnowledgeSection() {
    }

    public String getHeading() {
        return heading;
    }

    public void setHeading(String heading) {
        this.heading = heading;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<Block> getBlocks() {
        return blocks;
    }

    public void setBlocks(List<Block> blocks) {
        this.blocks = blocks;
    }

    /** 章节内容块：文本、代码或列表项。 */
    public static class Block {

        /** 块类型：p（段落）、code（代码）、li（列表项）。 */
        private String type;

        /** 块文本内容。 */
        private String text;

        /** 无参构造器，供序列化框架使用。 */
        public Block() {
        }

        public Block(String type, String text) {
            this.type = type;
            this.text = text;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }
}
