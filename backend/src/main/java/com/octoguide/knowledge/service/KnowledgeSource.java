package com.octoguide.knowledge.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动采集的数据源配置。
 *
 * <p>对应原 Node 项目的 {@code data/sources.js}，每个源描述一个需要定期抓取的页面，
 * 按 {@code type} 区分解析方式：docs（GitHub 官方文档页）、trending（热门仓库榜）、rss（订阅源）。</p>
 */
public class KnowledgeSource {

    /** 数据源 id，作为自动条目的 sourceId 存储。 */
    private String id;

    /** 数据源标题。 */
    private String title;

    /** 抓取地址。 */
    private String url;

    /** 解析类型：docs / trending / rss。 */
    private String type;

    /** 数据源配置分类，作为章节分类器低分时的回退值。 */
    private String category;

    /** 附加标签，作为自动标签提取的基底。 */
    private List<String> tags = new ArrayList<String>();

    /** 站点展示标题。 */
    private String siteTitle;

    /** 无参构造器。 */
    public KnowledgeSource() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public String getSiteTitle() {
        return siteTitle;
    }

    public void setSiteTitle(String siteTitle) {
        this.siteTitle = siteTitle;
    }
}
