package com.octoguide.knowledge.mapper;

import com.octoguide.knowledge.domain.KnowledgeBlock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 知识答案块的 MyBatis 数据访问接口。 */
@Mapper
public interface KnowledgeBlockMapper {

    /** 按知识条目 ID 批量查询答案块，并按条目和显示顺序排列。 */
    List<KnowledgeBlock> findByItemIds(@Param("itemIds") List<String> itemIds);

    /** 插入一个答案块，返回受影响行数。 */
    int insert(KnowledgeBlock block);

    /** 批量插入答案块（供知识更新在同一事务内一次写入多条）。 */
    int insertBatch(@Param("blocks") List<KnowledgeBlock> blocks);
}
