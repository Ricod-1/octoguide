package com.octoguide.knowledge.mapper;

import com.octoguide.knowledge.domain.KnowledgeSource;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 数据源配置的 MyBatis 数据访问接口。 */
@Mapper
public interface KnowledgeSourceMapper {

    /** 查询所有启用的数据源，用于定时采集任务。 */
    List<KnowledgeSource> findEnabledSources();

    /** 按主键查询数据源。 */
    KnowledgeSource findById(String id);
}
