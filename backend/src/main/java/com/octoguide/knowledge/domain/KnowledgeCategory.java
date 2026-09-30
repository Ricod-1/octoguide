package com.octoguide.knowledge.domain;

import java.util.ArrayList;
import java.util.List;

/** 知识库分类领域对象。 */
public class KnowledgeCategory {
    private String id;
    private String name;
    private String icon;
    private String description;
    private Integer sortOrder;
    private Boolean enabled = true;
    /** 是否为自动采集分类：用于前后端统一统计口径（人工分类数 + 自动采集分类数）。 */
    private Boolean auto = false;
    private List<KnowledgeItem> items = new ArrayList<KnowledgeItem>();

    /** MyBatis 通过无参构造器创建结果对象。 */
    public KnowledgeCategory() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Boolean getAuto() { return auto; }
    public void setAuto(Boolean auto) { this.auto = auto; }
    public List<KnowledgeItem> getItems() { return items; }
    public void setItems(List<KnowledgeItem> items) { this.items = items; }
}
