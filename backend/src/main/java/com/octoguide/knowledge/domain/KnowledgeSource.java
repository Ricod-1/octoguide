package com.octoguide.knowledge.domain;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 自动采集的数据源配置实体，映射 knowledge_source 表。
 *
 * <p>区别于采集链路输入值对象 {@link com.octoguide.knowledge.service.KnowledgeSource}，
 * 该对象是数据库持久化结构，category 以 category_id 外键关联分类表。</p>
 */
public class KnowledgeSource {
    private String id;
    private String title;
    private String url;
    private String sourceType;
    private String categoryId;
    private String tags;
    private String siteTitle;
    private Boolean enabled;
    private LocalDateTime updatedAt;

    /** MyBatis 通过无参构造器创建结果对象。 */
    public KnowledgeSource() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }
    /** 将数据库中的逗号分隔标签转换为列表。 */
    public List<String> getTagsList() {
        return tags == null || tags.trim().isEmpty()
                ? Collections.<String>emptyList() : Arrays.asList(tags.split(","));
    }
    /** 返回数据库中的原始标签字符串，供 MyBatis 写入使用。 */
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getSiteTitle() { return siteTitle; }
    public void setSiteTitle(String siteTitle) { this.siteTitle = siteTitle; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
