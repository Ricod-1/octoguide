package com.octoguide.knowledge.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 知识库中的问题及其答案、标签和来源信息。 */
public class KnowledgeItem {
    private String id;
    private KnowledgeCategory category;
    private String question;
    private String tags;
    private String sourceType;
    private String sourceUrl;
    private String sourceTitle;
    /** 自动采集条目的数据源 ID；手工条目为 null。 */
    private String sourceId;
    private Boolean autoEntry;
    private Boolean enabled;
    private LocalDateTime updatedAt;
    private List<KnowledgeBlock> blocks = new ArrayList<KnowledgeBlock>();

    /** MyBatis 通过无参构造器创建结果对象。 */
    public KnowledgeItem() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public KnowledgeCategory getCategory() { return category; }
    public void setCategory(KnowledgeCategory category) { this.category = category; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    /** 将数据库中的逗号分隔标签转换为前端使用的列表。 */
    public List<String> getTags() {
        return tags == null || tags.trim().isEmpty()
                ? Collections.<String>emptyList() : Arrays.asList(tags.split(","));
    }
    /** 返回数据库中的原始标签字符串，供 MyBatis 写入使用。 */
    public String getTagsText() {
        return tags;
    }
    public void setTags(String tags) { this.tags = tags; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getSourceTitle() { return sourceTitle; }
    public void setSourceTitle(String sourceTitle) { this.sourceTitle = sourceTitle; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public Boolean getAutoEntry() { return autoEntry; }
    public void setAutoEntry(Boolean autoEntry) { this.autoEntry = autoEntry; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<KnowledgeBlock> getBlocks() { return blocks; }
    public void setBlocks(List<KnowledgeBlock> blocks) { this.blocks = blocks; }
}
