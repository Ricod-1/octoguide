package com.octoguide.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.octoguide.ai.AiCallException;
import com.octoguide.ai.WebResearcher;
import com.octoguide.config.AsyncConfig;
import com.octoguide.dto.ChatRequest;
import com.octoguide.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ChatController 测试：单元层面验证 emitter 超时配置，WebMvc 层面验证 NDJSON 事件流兼容性。
 *
 * <p>超时部分验证联网模式最坏链路（多次 LLM 判断 + 多页面抓取 + 流式生成）不会被默认超时中途切断，
 * 且允许通过配置覆盖。事件流部分覆盖 Node 版兼容的 kb/start/delta/done/error 事件顺序。</p>
 */
@WebMvcTest(ChatController.class)
@Import(AsyncConfig.class)
class ChatControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    ChatService chatService;

    @MockBean
    WebResearcher webResearcher;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 默认问题非时效词：知识库强命中时不再询问模型"够不够"，直接走生成。 */
    @BeforeEach
    void setUpWebResearch() {
        when(webResearcher.isTimeSensitive(anyString())).thenReturn(false);
    }

    // ---------- 单元：emitter 超时配置 ----------

    /** 默认 emitter 超时不应低于联网研究最坏链路（300 秒）。 */
    @Test
    void emitterTimeoutDefaultCoversWebResearchWorstCase() {
        when(chatService.knowledge(anyString())).thenReturn(knowledge(answer(true, 15)));
        when(chatService.isAiConfigured()).thenReturn(false);
        ChatController controller = new ChatController(chatService, webResearcher, new ObjectMapper());

        ResponseBodyEmitter emitter = controller.chat(chatRequest("什么是 PR？"), new MockHttpServletResponse());

        assertEquals(Long.valueOf(300_000L), emitter.getTimeout());
    }

    /** emitter 超时应允许通过配置覆盖默认值。 */
    @Test
    void emitterTimeoutIsConfigurable() {
        when(chatService.knowledge(anyString())).thenReturn(knowledge(answer(true, 15)));
        when(chatService.isAiConfigured()).thenReturn(false);
        ChatController controller = new ChatController(chatService, webResearcher, new ObjectMapper(),
                new org.springframework.core.task.SyncTaskExecutor(), 120);

        ResponseBodyEmitter emitter = controller.chat(chatRequest("什么是 PR？"), new MockHttpServletResponse());

        assertEquals(Long.valueOf(120_000L), emitter.getTimeout());
    }

    // ---------- WebMvc：NDJSON 事件流兼容性 ----------

    /** AI 未配置时返回 Node 版 kb 事件，不出现 SSE 信封。 */
    @Test
    void unconfiguredAiReturnsNodeKbEventWithoutSseEnvelope() throws Exception {
        when(chatService.knowledge(anyString())).thenReturn(knowledge(answer(true, 15)));
        when(chatService.isAiConfigured()).thenReturn(false);
        MvcResult initial = mvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("{\"question\":\"什么是 PR？\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"type\":\"kb\""));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("event:"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("\"type\":\"start\""));
    }

    @Test
    void configuredAiReturnsStartDeltaDoneEvents() throws Exception {
        when(chatService.knowledge(anyString())).thenReturn(knowledge(answer(true, 15)));
        when(chatService.isAiConfigured()).thenReturn(true);
        when(chatService.streamAiAnswer(anyString(), anyString(), anyList(), any(Consumer.class), any(Consumer.class))).thenAnswer(invocation -> {
            invocation.<Consumer<String>>getArgument(3).accept("AI 回答");
            return "AI 回答";
        });
        MvcResult initial = mvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("{\"question\":\"怎么使用 PR？\",\"history\":[]}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"type\":\"start\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"type\":\"delta\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"type\":\"done\""));
    }

    @Test
    void configuredAiStreamsMultipleDeltasInOrder() throws Exception {
        when(chatService.knowledge(anyString())).thenReturn(knowledge(answer(true, 15)));
        when(chatService.isAiConfigured()).thenReturn(true);
        when(chatService.streamAiAnswer(anyString(), anyString(), anyList(), any(Consumer.class), any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<String> callback = invocation.getArgument(3);
            callback.accept("片段一");
            callback.accept("片段二");
            return "片段一片段二";
        });

        String body = chatBody("{\"question\":\"问题\",\"history\":[]}");
        String[] lines = body.trim().split("\\R");
        org.junit.jupiter.api.Assertions.assertEquals("start", mapper.readTree(lines[0]).path("type").asText());
        org.junit.jupiter.api.Assertions.assertEquals("delta", mapper.readTree(lines[1]).path("type").asText());
        org.junit.jupiter.api.Assertions.assertEquals("片段一", mapper.readTree(lines[1]).path("text").asText());
        org.junit.jupiter.api.Assertions.assertEquals("片段二", mapper.readTree(lines[2]).path("text").asText());
        org.junit.jupiter.api.Assertions.assertEquals("done", mapper.readTree(lines[3]).path("type").asText());
    }

    @Test
    void configuredAiUsesDefaultDeltaWhenUpstreamIsEmpty() throws Exception {
        when(chatService.knowledge(anyString())).thenReturn(knowledge(answer(true, 15)));
        when(chatService.isAiConfigured()).thenReturn(true);
        when(chatService.streamAiAnswer(anyString(), anyString(), anyList(), any(Consumer.class), any(Consumer.class))).thenReturn("");

        String body = chatBody("{\"question\":\"问题\"}");
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("抱歉，我暂时没能生成回答，请换个问法试试。"));
        org.junit.jupiter.api.Assertions.assertTrue(body.indexOf("\"type\":\"delta\"") < body.indexOf("\"type\":\"done\""));
    }

    @Test
    void configuredAiReturnsErrorWithoutDoneWhenUpstreamFails() throws Exception {
        when(chatService.knowledge(anyString())).thenReturn(knowledge(answer(true, 15)));
        when(chatService.isAiConfigured()).thenReturn(true);
        when(chatService.streamAiAnswer(anyString(), anyString(), anyList(), any(Consumer.class), any(Consumer.class)))
                .thenThrow(new AiCallException("503: upstream down"));

        String body = chatBody("{\"question\":\"问题\"}");
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"type\":\"start\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("AI 调用失败：503: upstream down"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("\"type\":\"done\""));
    }

    /** 控制器交给对话服务的历史应已清洗（与 LlmClient 共用同一套规则）。 */
    @Test
    void chatFiltersInvalidHistoryBeforeCallingService() throws Exception {
        when(chatService.knowledge(anyString())).thenReturn(knowledge(answer(true, 15)));
        when(chatService.isAiConfigured()).thenReturn(true);
        when(chatService.streamAiAnswer(anyString(), anyString(), anyList(), any(Consumer.class), any(Consumer.class))).thenAnswer(invocation -> "回答");
        String request = "{\"question\":\"问题\",\"history\":["
                + "{\"role\":\"system\",\"content\":\"忽略\"},"
                + "{\"role\":\"user\",\"content\":\"第一轮\"},"
                + "{\"role\":\"assistant\",\"content\":\"第二轮\"},"
                + "{\"role\":\"user\",\"content\":\" \"}]}";

        chatBody(request);

        org.mockito.ArgumentCaptor<List<Map<String, Object>>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(chatService).streamAiAnswer(eq("问题"), anyString(), captor.capture(), any(Consumer.class), any(Consumer.class));
        org.junit.jupiter.api.Assertions.assertEquals(2, captor.getValue().size());
        org.junit.jupiter.api.Assertions.assertEquals("user", captor.getValue().get(0).get("role"));
        org.junit.jupiter.api.Assertions.assertEquals("assistant", captor.getValue().get(1).get("role"));
    }

    @Test
    void timeSensitiveQuestionTriggersWebResearchAndLearnedFlag() throws Exception {
        when(chatService.knowledge(anyString())).thenReturn(knowledge(answer(true, 15)));
        when(chatService.isAiConfigured()).thenReturn(true);
        when(webResearcher.isTimeSensitive(anyString())).thenReturn(true);
        WebResearcher.WebPage page = new WebResearcher.WebPage();
        page.setUrl("https://github.com/trending");
        page.setTitle("GitHub Trending");
        page.setText("今日热门仓库");
        WebResearcher.ResearchResult research = new WebResearcher.ResearchResult();
        research.setPages(Collections.singletonList(page));
        research.setVia("ai");
        when(webResearcher.findRelevantPages(anyString(), any())).thenReturn(research);
        when(chatService.streamAiAnswer(anyString(), anyString(), anyList(), any(Consumer.class), any(Consumer.class))).thenAnswer(invocation -> {
            invocation.<Consumer<String>>getArgument(3).accept("联网回答");
            return "联网回答";
        });

        String body = chatBody("{\"question\":\"GitHub 今天有什么热门仓库？\",\"history\":[]}");

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"type\":\"progress\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"mode\":\"ai_web\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"learned\":true"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("https://github.com/trending"));
    }

    // ---------- WebMvc：请求体硬上限 ----------

    /** 超长问题应直接返回 error 事件，且不触发任何服务调用（不占用聊天执行线程）。 */
    @Test
    void oversizedQuestionIsRejectedWithErrorEvent() throws Exception {
        String body = chatBody("{\"question\":\"" + repeat('问', 4001) + "\",\"history\":[]}");

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"type\":\"error\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("问题太长"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("\"type\":\"start\""));
        verifyNoInteractions(chatService);
    }

    /** 历史条数超过硬上限应直接返回 error 事件，不进入聊天执行线程。 */
    @Test
    void tooManyHistoryMessagesAreRejected() throws Exception {
        StringBuilder history = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            if (i > 0) {
                history.append(',');
            }
            history.append("{\"role\":\"user\",\"content\":\"问\"}");
        }
        String body = chatBody("{\"question\":\"问题\",\"history\":[" + history + "]}");

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"type\":\"error\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("历史消息条数超出上限"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("\"type\":\"start\""));
        verifyNoInteractions(chatService);
    }

    /** 单条历史消息超长应直接返回 error 事件，不进入聊天执行线程。 */
    @Test
    void oversizedHistoryMessageIsRejected() throws Exception {
        String body = chatBody("{\"question\":\"问题\",\"history\":[{\"role\":\"user\",\"content\":\""
                + repeat('问', 20001) + "\"}]}");

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"type\":\"error\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("内容过长"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("\"type\":\"start\""));
        verifyNoInteractions(chatService);
    }

    /** 恰好等于上限的合法请求仍正常处理（边界不误伤）。 */
    @Test
    void payloadAtExactLimitsStillProcesses() throws Exception {
        when(chatService.knowledge(anyString())).thenReturn(knowledge(answer(true, 15)));
        when(chatService.isAiConfigured()).thenReturn(false);
        StringBuilder history = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            if (i > 0) {
                history.append(',');
            }
            history.append("{\"role\":\"user\",\"content\":\"问\"}");
        }
        String body = chatBody("{\"question\":\"" + repeat('问', 4000) + "\",\"history\":[" + history + "]}");

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"type\":\"kb\""));
    }

    /** 上限应可通过构造器覆盖，且拒绝时完全不提交执行器。 */
    @Test
    void payloadLimitsAreConfigurableAndRejectionSkipsExecutor() {
        java.util.concurrent.atomic.AtomicInteger executed = new java.util.concurrent.atomic.AtomicInteger();
        org.springframework.core.task.TaskExecutor recorder = task -> executed.incrementAndGet();
        ChatController controller = new ChatController(chatService, webResearcher, new ObjectMapper(),
                recorder, 300, 10, 3, 100);

        // 问题超过 maxQuestionChars=10
        controller.chat(chatRequest(repeat('问', 11)), new MockHttpServletResponse());
        org.junit.jupiter.api.Assertions.assertEquals(0, executed.get());

        // 历史条数超过 maxHistoryMessages=3（带合法 role，避免被历史清洗过滤）
        ChatRequest manyMessages = new ChatRequest();
        manyMessages.setQuestion("问题");
        List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 4; i++) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("role", "user");
            item.put("content", "问");
            history.add(item);
        }
        manyMessages.setHistory(history);
        controller.chat(manyMessages, new MockHttpServletResponse());
        org.junit.jupiter.api.Assertions.assertEquals(0, executed.get());

        verifyNoInteractions(chatService);
    }

    // ---------- 错误事件分类码 ----------

    /** 超限拒绝的 error 事件应携带 payload 分类码，便于前端区分提示。 */
    @Test
    void oversizedQuestionErrorCarriesPayloadCode() throws Exception {
        String body = chatBody("{\"question\":\"" + repeat('问', 4001) + "\",\"history\":[]}");

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"type\":\"error\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"code\":\"payload\""));
    }

    /** AI 未配置的 error 事件应携带 unconfigured 分类码。 */
    @Test
    void unconfiguredAiErrorCarriesUnconfiguredCode() throws Exception {
        when(chatService.knowledge(anyString())).thenReturn(knowledge(answer(false, 0)));
        when(chatService.isAiConfigured()).thenReturn(false);

        String body = chatBody("{\"question\":\"问题\",\"history\":[]}");

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"type\":\"error\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"code\":\"unconfigured\""));
    }

    /** AI 调用失败的 error 事件应携带 ai_service 分类码，且错误文案不能出现重复前缀。 */
    @Test
    void aiFailureErrorCarriesAiServiceCode() throws Exception {
        when(chatService.knowledge(anyString())).thenReturn(knowledge(answer(true, 15)));
        when(chatService.isAiConfigured()).thenReturn(true);
        when(chatService.streamAiAnswer(anyString(), anyString(), anyList(), any(Consumer.class), any(Consumer.class)))
                .thenThrow(new AiCallException("AI 调用失败：503: upstream down"));

        String body = chatBody("{\"question\":\"问题\",\"history\":[]}");

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"code\":\"ai_service\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("AI 调用失败：503: upstream down"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("AI 调用失败：AI 调用失败："),
                "异常信息已带前缀时不应再拼一次，实际：" + body);
    }

    /** done 事件应携带本次回答耗时，供前端在气泡上方展示。 */
    @Test
    void doneEventCarriesElapsedMillis() throws Exception {
        when(chatService.knowledge(anyString())).thenReturn(knowledge(answer(true, 15)));
        when(chatService.isAiConfigured()).thenReturn(true);
        when(chatService.streamAiAnswer(anyString(), anyString(), anyList(), any(Consumer.class), any(Consumer.class)))
                .thenAnswer(invocation -> "回答");

        String body = chatBody("{\"question\":\"问题\",\"history\":[]}");

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"elapsedMs\":"), "done 事件应带 elapsedMs：" + body);
    }

    /** 知识库命中（AI 未配置）时 kb 事件也应携带耗时。 */
    @Test
    void kbEventCarriesElapsedMillis() throws Exception {
        when(chatService.knowledge(anyString())).thenReturn(knowledge(answer(true, 15)));
        when(chatService.isAiConfigured()).thenReturn(false);

        String body = chatBody("{\"question\":\"什么是 PR？\"}");

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"type\":\"kb\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"elapsedMs\":"), "kb 事件应带 elapsedMs：" + body);
    }

    /** 推理模型思考阶段应产生一条"正在思考"进度事件（避免界面看似卡住）。 */
    @Test
    void reasoningContentEmitsThinkingProgress() throws Exception {
        when(chatService.knowledge(anyString())).thenReturn(knowledge(answer(true, 15)));
        when(chatService.isAiConfigured()).thenReturn(true);
        when(chatService.streamAiAnswer(anyString(), anyString(), anyList(), any(Consumer.class), any(Consumer.class)))
                .thenAnswer(invocation -> {
                    invocation.<Consumer<String>>getArgument(4).accept("模型内部思考内容");
                    invocation.<Consumer<String>>getArgument(3).accept("回答");
                    return "回答";
                });

        String body = chatBody("{\"question\":\"问题\",\"history\":[]}");

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("正在思考"), "应有思考进度事件：" + body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"stage\":\"reasoning\""));
    }

    // ---------- 客户端取消（断开 / 超时后停止生成） ----------

    /** 客户端在生成开始前断开：不应触发任何检索与生成调用。 */
    @Test
    void clientDisconnectBeforeStartSkipsGeneration() {
        when(chatService.knowledge(anyString())).thenReturn(knowledge(answer(true, 15)));
        when(chatService.isAiConfigured()).thenReturn(true);
        List<Runnable> tasks = new ArrayList<Runnable>();
        ChatController controller = new ChatController(chatService, webResearcher, new ObjectMapper(),
                tasks::add, 300);

        ResponseBodyEmitter emitter = controller.chat(chatRequest("问题"), new MockHttpServletResponse());
        emitter.complete(); // 模拟客户端断开（请求完成）
        tasks.forEach(Runnable::run);

        verify(chatService, never()).streamAiAnswer(anyString(), anyString(), anyList(), any(Consumer.class), any(Consumer.class));
    }

    /** 客户端在流式生成中途断开：立即停止后续生成，且不向已断开的客户端补发 error 事件。 */
    @Test
    void clientDisconnectDuringDeltaStopsGenerationWithoutErrorEvent() throws Exception {
        when(chatService.knowledge(anyString())).thenReturn(knowledge(answer(true, 15)));
        when(chatService.isAiConfigured()).thenReturn(true);
        final ResponseBodyEmitter[] holder = new ResponseBodyEmitter[1];
        when(chatService.streamAiAnswer(anyString(), anyString(), anyList(), any(Consumer.class), any(Consumer.class)))
                .thenAnswer(invocation -> {
                    Consumer<String> callback = invocation.getArgument(3);
                    callback.accept("第一段");
                    holder[0].complete(); // 模拟客户端在两段输出之间断开
                    callback.accept("第二段");
                    return "第一段";
                });
        ObjectMapper spyMapper = org.mockito.Mockito.spy(new ObjectMapper());
        List<Runnable> tasks = new ArrayList<Runnable>();
        ChatController controller = new ChatController(chatService, webResearcher, spyMapper,
                tasks::add, 300);

        holder[0] = controller.chat(chatRequest("问题"), new MockHttpServletResponse());
        tasks.forEach(Runnable::run);

        verify(chatService).streamAiAnswer(anyString(), anyString(), anyList(), any(Consumer.class), any(Consumer.class));
        verify(spyMapper, never()).writeValueAsString(org.mockito.ArgumentMatchers.argThat(
                value -> value instanceof Map && "error".equals(((Map<?, ?>) value).get("type"))));
    }

    /** 客户端在联网抓取页与页之间断开：取消信号不应被联网失败的 fallback 吞掉，也不继续生成。 */
    @Test
    void clientDisconnectDuringWebResearchSkipsFallbackAndGeneration() throws Exception {
        when(chatService.knowledge(anyString())).thenReturn(knowledge(answer(true, 15)));
        when(chatService.isAiConfigured()).thenReturn(true);
        when(webResearcher.isTimeSensitive(anyString())).thenReturn(true);
        final ResponseBodyEmitter[] holder = new ResponseBodyEmitter[1];
        when(webResearcher.findRelevantPages(anyString(), any())).thenAnswer(invocation -> {
            WebResearcher.ResearchOptions options = invocation.getArgument(1);
            options.getProgress().accept("fetching", "📥 正在抓取第 1 页…");
            holder[0].complete(); // 模拟客户端在两次页面抓取之间断开
            options.getProgress().accept("fetching", "📥 正在抓取第 2 页…");
            return new WebResearcher.ResearchResult();
        });
        ObjectMapper spyMapper = org.mockito.Mockito.spy(new ObjectMapper());
        List<Runnable> tasks = new ArrayList<Runnable>();
        ChatController controller = new ChatController(chatService, webResearcher, spyMapper,
                tasks::add, 300);

        holder[0] = controller.chat(chatRequest("GitHub 今天有什么热门仓库？"), new MockHttpServletResponse());
        tasks.forEach(Runnable::run);

        verify(chatService, never()).streamAiAnswer(anyString(), anyString(), anyList(), any(Consumer.class), any(Consumer.class));
        verify(spyMapper, never()).writeValueAsString(org.mockito.ArgumentMatchers.argThat(
                value -> value instanceof Map && "error".equals(((Map<?, ?>) value).get("type"))));
    }

    // ---------- 工具方法 ----------

    private String repeat(char c, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(c);
        }
        return builder.toString();
    }

    private String chatBody(String requestBody) throws Exception {
        MvcResult initial = mvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mvc.perform(asyncDispatch(initial)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private ChatRequest chatRequest(String question) {
        ChatRequest request = new ChatRequest();
        request.setQuestion(question);
        request.setHistory(Collections.<Map<String, Object>>emptyList());
        return request;
    }

    private Map<String, Object> answer(boolean found, double score) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("found", found);
        result.put("score", score);
        result.put("answer", Collections.singletonList(Collections.singletonMap("t", "text")));
        return result;
    }

    /** 构造 ChatService.knowledge() 的返回值：result 携带 found/score，其余默认空。 */
    private ChatService.Knowledge knowledge(Map<String, Object> result) {
        return new ChatService.Knowledge(result, "", Collections.<Map<String, Object>>emptyList());
    }
}
