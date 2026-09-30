package com.octoguide.knowledge.mapper;

import com.octoguide.knowledge.domain.KnowledgeItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 知识条目的 MyBatis 数据访问接口。 */
@Mapper
public interface KnowledgeItemMapper {

    /** 查询启用条目，并关联其所属分类。 */
    List<KnowledgeItem> findEnabledItems();

    /** 按主键查询知识条目。 */
    KnowledgeItem findById(String id);

    /** 按问题精确查询已有条目 ID，用于知识记忆去重。 */
    String findIdByQuestion(String question);

    /** 统计指定自动采集标志的条目数量。 */
    long countByAutoEntry(boolean autoEntry);

    /** 按自动采集标志和来源类型统计条目数量。 */
    long countByAutoEntryAndSourceType(boolean autoEntry, String sourceType);

    /** 查询所有自动采集条目，用于合并时构建现有 ID 集合。 */
    List<KnowledgeItem> findAllAutoEntries();

    /** 插入知识条目，返回受影响行数。 */
    int insert(KnowledgeItem item);

    /** 批量插入知识条目（供知识更新在同一事务内一次写入多条）。 */
    int insertBatch(@Param("items") List<KnowledgeItem> items);

    /** 原子插入知识条目；问题唯一键冲突时返回 0，不抛出重复键异常。 */
    int insertIgnore(KnowledgeItem item);

    /** 按数据源 ID 删除该源的所有自动条目（含答案块级联），用于按源重建。 */
    int deleteBySourceId(String sourceId);
}
