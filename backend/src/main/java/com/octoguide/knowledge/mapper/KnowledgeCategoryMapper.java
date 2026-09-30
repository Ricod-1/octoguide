package com.octoguide.knowledge.mapper;

import com.octoguide.knowledge.domain.KnowledgeCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 知识分类的 MyBatis 数据访问接口。 */
@Mapper
public interface KnowledgeCategoryMapper {

    /** 查询启用分类，按配置的显示顺序返回。 */
    List<KnowledgeCategory> findEnabledCategories();

    /** 按分类名称精确查询，用于自动采集时将分类名称映射为分类 ID。 */
    KnowledgeCategory findByName(String name);

    /** 按 is_auto 标记统计启用分类数量（true=自动采集分类，false=人工分类）。 */
    long countByAuto(@Param("isAuto") boolean isAuto);
}
