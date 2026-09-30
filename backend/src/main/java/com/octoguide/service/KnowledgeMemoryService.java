package com.octoguide.service;

import com.octoguide.ai.LlmProperties;
import com.octoguide.dto.RememberRequest;
import com.octoguide.dto.RememberResponse;
import com.octoguide.knowledge.domain.KnowledgeBlock;
import com.octoguide.knowledge.domain.KnowledgeCategory;
import com.octoguide.knowledge.domain.KnowledgeItem;
import com.octoguide.knowledge.mapper.KnowledgeBlockMapper;
import com.octoguide.knowledge.mapper.KnowledgeItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** 将用户确认的 AI 回答沉淀为自动知识条目。 */
@Service
public class KnowledgeMemoryService {

    private final KnowledgeItemMapper itemMapper;
    private final KnowledgeBlockMapper blockMapper;
    private final LlmProperties llmProperties;

    /**
     * 创建知识记忆服务。
     *
     * @param itemMapper 知识条目 Mapper
     * @param blockMapper 答案块 Mapper
     * @param llmProperties AI 配置
     */
    public KnowledgeMemoryService(KnowledgeItemMapper itemMapper,
                                  KnowledgeBlockMapper blockMapper,
                                  LlmProperties llmProperties) {
        this.itemMapper = itemMapper;
        this.blockMapper = blockMapper;
        this.llmProperties = llmProperties;
    }

    /**
     * 保存用户确认的知识条目。
     *
     * <p>写入前按问题精确去重，整个条目和答案块写入过程处于同一事务中。</p>
     */
    @Transactional
    public RememberResponse remember(RememberRequest request) {
        if (!llmProperties.isConfigured()) {
            throw new AiNotConfiguredException("AI 未配置，无法整理知识条目");
        }

        String existingId = itemMapper.findIdByQuestion(request.getQuestion());
        if (existingId != null) {
            return new RememberResponse(false, true, null, existingId);
        }

        String itemId = "learned-" + UUID.randomUUID().toString();
        KnowledgeCategory category = new KnowledgeCategory();
        category.setId("intro");
        KnowledgeItem item = new KnowledgeItem();
        item.setId(itemId);
        item.setCategory(category);
        item.setQuestion(request.getQuestion());
        item.setTags("学习,AI");
        item.setSourceType("learned");
        item.setSourceUrl(value(request.getSourceUrl()));
        item.setSourceTitle(value(request.getSourceTitle()));
        item.setAutoEntry(true);
        item.setEnabled(true);
        if (itemMapper.insertIgnore(item) == 0) {
            String duplicateId = itemMapper.findIdByQuestion(request.getQuestion());
            return new RememberResponse(false, true, null, duplicateId);
        }

        KnowledgeBlock block = new KnowledgeBlock();
        block.setItemId(itemId);
        block.setBlockType("text");
        block.setContent(request.getAnswer());
        block.setSortOrder(1);
        blockMapper.insert(block);
        return new RememberResponse(true, false, itemId, null);
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
