package com.octoguide.service;

import com.octoguide.ai.AiCallException;
import com.octoguide.ai.LlmClient;
import com.octoguide.knowledge.service.AnswerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatService 测试：覆盖单次检索派生知识视图、AI 配置透传、流式回答委托与异常传播。
 *
 * <p>合并自原 ChatServiceTest 与 ChatServiceDetailedTest：knowledge() 保证一轮对话只触发一次
 * 全量 search，问答结果 / RAG 上下文 / 来源均由同一份检索结果派生。</p>
 */
class ChatServiceTest {

    private AnswerService answerService;
    private LlmClient llmClient;
    private ChatService service;

    @BeforeEach
    void setUp() {
        answerService = mock(AnswerService.class);
        llmClient = mock(LlmClient.class);
        service = new ChatService(answerService, llmClient);
    }

    /** knowledge() 应只检索一次，并基于同一份结果派生问答结果、上下文和来源。 */
    @Test
    void knowledgeDerivesEverythingFromSingleSearch() {
        Map<String, Object> first = new LinkedHashMap<String, Object>();
        first.put("id", "collab-2");
        List<Map<String, Object>> matches = Arrays.asList(first,
                Collections.<String, Object>singletonMap("id", "collab-3"));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("found", true);
        result.put("score", 15);
        when(answerService.search("什么是 PR？")).thenReturn(matches);
        when(answerService.askFrom("什么是 PR？", matches)).thenReturn(result);
        when(answerService.ragContextFrom(matches, 4)).thenReturn("知识库资料");
        when(answerService.sourcesFrom(matches, 2)).thenReturn(Collections.singletonList(
                Collections.<String, Object>singletonMap("url", "https://github.com/a")));

        ChatService.Knowledge knowledge = service.knowledge("什么是 PR？");

        assertEquals(result, knowledge.getResult());
        assertEquals("知识库资料", knowledge.getContext());
        assertEquals(1, knowledge.getSources().size());
        // 检索只发生一次，三个派生方法都复用同一份 matches
        verify(answerService).search("什么是 PR？");
        verify(answerService).askFrom("什么是 PR？", matches);
        verify(answerService).ragContextFrom(matches, 4);
        verify(answerService).sourcesFrom(matches, 2);
    }

    /** 空白问题应走 askFrom 的空输入提示，不抛异常。 */
    @Test
    void knowledgeHandlesBlankQuestion() {
        when(answerService.search("")).thenReturn(Collections.<Map<String, Object>>emptyList());
        when(answerService.ragContextFrom(Collections.<Map<String, Object>>emptyList(), 4)).thenReturn("");
        Map<String, Object> prompt = new LinkedHashMap<String, Object>();
        prompt.put("found", false);
        when(answerService.askFrom("", Collections.<Map<String, Object>>emptyList())).thenReturn(prompt);

        ChatService.Knowledge knowledge = service.knowledge("   ");

        assertEquals(false, knowledge.getResult().get("found"));
        assertEquals("", knowledge.getContext());
        assertTrue(knowledge.getSources().isEmpty());
    }

    /** isAiConfigured() 应透传 LlmClient 的配置状态。 */
    @Test
    void isAiConfiguredDelegatesToLlm() {
        when(llmClient.isConfigured()).thenReturn(true);
        assertTrue(service.isAiConfigured());
    }

    /** AI 未配置时 streamAiAnswer() 应返回 null 且不调用上游。 */
    @Test
    void streamAiAnswerReturnsNullWhenNotConfigured() {
        when(llmClient.isConfigured()).thenReturn(false);

        assertNull(service.streamAiAnswer("问题", "资料", Collections.<Map<String, Object>>emptyList(), text -> { }));
        verify(llmClient, never()).streamAnswer(any(String.class), any(String.class), anyList(), any(Consumer.class));
    }

    /** AI 已配置时 streamAiAnswer() 应原样透传上下文、历史与回调（如联网拼接场景）。 */
    @Test
    void streamAiAnswerPassesContextHistoryAndCallback() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.streamAnswer(eq("问题"), eq("知识库+网页"), anyList(), any(Consumer.class))).thenReturn("联网回答");
        List<Map<String, Object>> history = Collections.emptyList();
        Consumer<String> callback = text -> { };

        String result = service.streamAiAnswer("问题", "知识库+网页", history, callback);

        assertEquals("联网回答", result);
        verify(llmClient).streamAnswer("问题", "知识库+网页", history, callback);
    }

    /** 上游调用失败时 streamAiAnswer() 应向上抛出 AiCallException。 */
    @Test
    void streamAiAnswerPropagatesUpstreamFailure() {
        when(llmClient.isConfigured()).thenReturn(true);
        when(llmClient.streamAnswer(eq("问题"), eq("资料"), anyList(), any(Consumer.class)))
                .thenThrow(new AiCallException("503: upstream down"));

        assertThrows(AiCallException.class, () -> service.streamAiAnswer(
                "问题", "资料", Collections.<Map<String, Object>>emptyList(), text -> { }));
    }
}
