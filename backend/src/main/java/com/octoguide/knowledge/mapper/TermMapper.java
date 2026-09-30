package com.octoguide.knowledge.mapper;

import com.octoguide.knowledge.domain.Term;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** GitHub 术语的 MyBatis 数据访问接口。 */
@Mapper
public interface TermMapper {

    /** 查询全部术语，并按配置的显示顺序返回。 */
    List<Term> findAllOrdered();

    /** 统计术语总数。 */
    long count();
}
