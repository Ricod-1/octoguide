package com.octoguide.service;

import com.octoguide.ai.LlmProperties;
import com.octoguide.dto.RememberRequest;
import com.octoguide.dto.RememberResponse;
import com.octoguide.knowledge.mapper.KnowledgeBlockMapper;
import com.octoguide.knowledge.mapper.KnowledgeItemMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证知识记忆服务通过 MyBatis 完成去重和事务写入。 */
public class KnowledgeMemoryServiceTest {

    /** 重复问题只返回已有 ID，不应执行任何插入。 */
    @Test
    void skipsDuplicateQuestion() {
        KnowledgeItemMapper itemMapper = mock(KnowledgeItemMapper.class);
        KnowledgeBlockMapper blockMapper = mock(KnowledgeBlockMapper.class);
        LlmProperties properties = configuredProperties();
        when(itemMapper.findIdByQuestion("问题")).thenReturn("existing-id");

        KnowledgeMemoryService service = new KnowledgeMemoryService(itemMapper, blockMapper, properties);
        RememberResponse response = service.remember(request("问题", "答案"));

        assertTrue(response.isDuplicate());
        verify(itemMapper, never()).insert(any());
        verify(blockMapper, never()).insert(any());
    }

    /** 新问题应先插入条目，再插入对应答案块。 */
    @Test
    void insertsItemBeforeAnswerBlock() {
        KnowledgeItemMapper itemMapper = mock(KnowledgeItemMapper.class);
        KnowledgeBlockMapper blockMapper = mock(KnowledgeBlockMapper.class);
        when(itemMapper.findIdByQuestion("问题")).thenReturn(null);
        when(itemMapper.insertIgnore(any())).thenReturn(1);
        KnowledgeMemoryService service = new KnowledgeMemoryService(itemMapper, blockMapper, configuredProperties());

        RememberResponse response = service.remember(request("问题", "答案"));

        assertTrue(response.isOk());
        InOrder order = inOrder(itemMapper, blockMapper);
        order.verify(itemMapper).insertIgnore(any());
        order.verify(blockMapper).insert(any());
    }

    /** 数据库已存在同题时，原子插入应返回已有条目标识而非抛出异常。 */
    @Test
    void returnsExistingIdWhenAtomicInsertFindsDuplicate() {
        KnowledgeItemMapper itemMapper = mock(KnowledgeItemMapper.class);
        KnowledgeBlockMapper blockMapper = mock(KnowledgeBlockMapper.class);
        when(itemMapper.findIdByQuestion("问题")).thenReturn(null).thenReturn("existing-id");
        when(itemMapper.insertIgnore(any())).thenReturn(0);

        RememberResponse response = new KnowledgeMemoryService(itemMapper, blockMapper, configuredProperties())
                .remember(request("问题", "答案"));

        assertTrue(response.isDuplicate());
        assertEquals("existing-id", response.getExistingId());
        verify(blockMapper, never()).insert(any());
    }

    private LlmProperties configuredProperties() {
        LlmProperties properties = new LlmProperties();
        properties.setApiKey("test-key");
        return properties;
    }

    private RememberRequest request(String question, String answer) {
        RememberRequest request = new RememberRequest();
        request.setQuestion(question);
        request.setAnswer(answer);
        return request;
    }
}
