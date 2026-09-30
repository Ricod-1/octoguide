package com.octoguide.knowledge.domain;

/** 知识条目的答案内容块，例如文本、代码或提示。 */
public class KnowledgeBlock {
    private Long id;
    /** 批量查询时使用所属条目 ID 组装到对应的知识条目。 */
    private String itemId;
    private String blockType;
    private String content;
    private Integer sortOrder;

    /** MyBatis 通过无参构造器创建结果对象。 */
    public KnowledgeBlock() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }
    public String getBlockType() { return blockType; }
    public void setBlockType(String blockType) { this.blockType = blockType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
