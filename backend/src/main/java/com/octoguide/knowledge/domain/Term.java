package com.octoguide.knowledge.domain;

/** GitHub 术语领域对象。 */
public class Term {
    private Long id;
    private String english;
    private String chinese;
    private String description;
    private Integer sortOrder;

    /** MyBatis 通过无参构造器创建结果对象。 */
    public Term() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEnglish() { return english; }
    public void setEnglish(String english) { this.english = english; }
    public String getChinese() { return chinese; }
    public void setChinese(String chinese) { this.chinese = chinese; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
