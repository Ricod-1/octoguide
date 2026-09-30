package com.octoguide.mapper;

import com.octoguide.dto.CategoryQuestionResponse;
import com.octoguide.dto.CategoryResponse;
import com.octoguide.dto.TermResponse;
import com.octoguide.knowledge.domain.KnowledgeCategory;
import com.octoguide.knowledge.domain.Term;
import org.springframework.stereotype.Component;
import java.util.stream.Collectors;

/** 将知识领域对象转换为稳定的 API DTO。 */
@Component
public class KnowledgeMapper {
    /** 转换分类及其快捷问题（携带 auto 标记供前端区分自动采集分类）。 */
    public CategoryResponse toCategoryResponse(KnowledgeCategory category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getIcon(), category.getDescription(), Boolean.TRUE.equals(category.getAuto()), category.getItems().stream().map(i -> new CategoryQuestionResponse(i.getId(), i.getQuestion(), i.getTags())).collect(Collectors.toList()));
    }
    /** 转换术语对象。 */
    public TermResponse toTermResponse(Term term) {
        return new TermResponse(term.getEnglish(), term.getChinese(), term.getDescription());
    }
}
