package com.octoguide.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LlmClient 测试：URL 规范化、非流式/流式回答、SSE 跨块切分、JSON 结构化输出与异常路径。
 *
 * <p>合并自原 LlmClientTest 与 LlmClientDetailedTest，全部通过注入内存 WebClient 模拟上游，
 * 不发起真实网络请求。</p>
 */
class LlmClientTest {

    // ---------- URL 规范化与端点 ----------

    @Test
    void rootBaseUrlUsesV1ChatCompletionsEndpoint() {
        LlmProperties properties = properties();
        properties.setBaseUrl("http://llm.test");
        AtomicReference<String> requestUrl = new AtomicReference<String>();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    requestUrl.set(request.url().toString());
                    return Mono.just(response(HttpStatus.OK,
                            "{\"choices\":[{\"message\":{\"content\":\"回答\"}}]}"));
                })
                .build();
        LlmClient llm = new LlmClient(properties, new ObjectMapper(), client);

        assertEquals("回答", llm.answer("问题", "资料"));
        assertEquals("http://llm.test/v1/chat/completions", requestUrl.get());
    }

    @Test
    void v1OrFullEndpointDoesNotDuplicatePath() {
        LlmProperties properties = properties();
        properties.setBaseUrl("http://llm.test/v1/chat/completions");
        AtomicReference<String> requestUrl = new AtomicReference<String>();
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> {
                    requestUrl.set(request.url().toString());
                    return Mono.just(response(HttpStatus.OK,
                            "{\"choices\":[{\"message\":{\"content\":\"回答\"}}]}"));
                })
                .build();
        LlmClient llm = new LlmClient(properties, new ObjectMapper(), client);

        assertEquals("回答", llm.answer("问题", "资料"));
        assertEquals("http://llm.test/v1/chat/completions", requestUrl.get());
    }

    // ---------- 非流式 answer ----------

    @Test
    void answerParsesNonStreamingContent() {
        LlmProperties properties = properties();
        WebClient.Builder client = webClient(Mono.just(response(HttpStatus.OK,
                "{\"choices\":[{\"message\":{\"content\":\"完整回答\"}}]}")));
        LlmClient llm = new LlmClient(properties, new ObjectMapper(), client);

        String result = llm.answer("问题", "资料");

        assertEquals("完整回答", result);
    }

    @Test
    void answerReturnsNullWhenNoContent() {
        LlmProperties properties = properties();
        WebClient.Builder client = webClient(Mono.just(response(HttpStatus.OK,
                "{\"choices\":[{}]}")));
        LlmClient llm = new LlmClient(properties, new ObjectMapper(), client);

        assertNull(llm.answer("问题", "资料"));
    }

    @Test
    void answerReturnsNullWhenNotConfigured() {
        LlmProperties properties = new LlmProperties(); // 未设置 apiKey
        LlmClient llm = new LlmClient(properties, new ObjectMapper(),
                WebClient.builder().baseUrl("http://llm.test/v1"));

        assertNull(llm.answer("问题", "资料"));
    }

    @Test
    void answerThrowsOnMalformedResponse() {
        LlmProperties properties = properties();
        WebClient.Builder client = webClient(Mono.just(response(HttpStatus.OK, "{not valid json")));
        LlmClient llm = new LlmClient(properties, new ObjectMapper(), client);

        assertThrows(AiCallException.class, () -> llm.answer("问题", "资料"));
    }

    // ---------- 流式 answer / SSE ----------

    @Test
    void streamsEveryNonEmptySseDeltaAndStopsAtDone() {
        LlmProperties properties = properties();
        WebClient.Builder client = webClient(Mono.just(response(HttpStatus.OK,
                "data: {\"choices\":[{\"delta\":{\"content\":\"第一段\"}}]}\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"第二段\"}}]}\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"\"}}]}\n"
                        + "data: [DONE]\n")));
        LlmClient llm = new LlmClient(properties, new ObjectMapper(), client);
        List<String> deltas = new ArrayList<String>();

        String result = llm.streamAnswer("问题", "资料", Collections.<Map<String, Object>>emptyList(), deltas::add);

        assertEquals(Arrays.asList("第一段", "第二段"), deltas);
        assertEquals("第一段第二段", result);
    }

    @Test
    void streamAnswerHandlesChunkBoundarySplits() {
        LlmProperties properties = properties();
        // 第一个块在行中间切断，第二个块补齐并结束
        WebClient.Builder client = webClient(Flux.just(
                buffer("data: {\"choices\":[{\"delta\":{\"content\":\"第一"),
                buffer("段\"}}]}\ndata: [DONE]\n")));
        LlmClient llm = new LlmClient(properties, new ObjectMapper(), client);
        List<String> deltas = new ArrayList<String>();

        String result = llm.streamAnswer("问题", "资料", Collections.<Map<String, Object>>emptyList(), deltas::add);

        assertEquals(Arrays.asList("第一段"), deltas);
        assertEquals("第一段", result);
    }

    @Test
    void streamAnswerDecodesMultiByteCharsSplitAcrossChunks() {
        LlmProperties properties = properties();
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"你好世界\"}}]}\ndata: [DONE]\n";
        byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
        // 切分点落在“你”的 UTF-8 编码（3 字节）中间
        int split = sse.indexOf('你') + 1;
        WebClient.Builder client = webClient(Flux.just(
                buffer(Arrays.copyOfRange(bytes, 0, split)),
                buffer(Arrays.copyOfRange(bytes, split, bytes.length))));
        LlmClient llm = new LlmClient(properties, new ObjectMapper(), client);
        List<String> deltas = new ArrayList<String>();

        String result = llm.streamAnswer("问题", "资料", Collections.<Map<String, Object>>emptyList(), deltas::add);

        assertEquals(Collections.singletonList("你好世界"), deltas);
        assertEquals("你好世界", result);
    }

    @Test
    void propagatesUpstreamHttpFailure() {
        LlmProperties properties = properties();
        WebClient.Builder client = webClient(Mono.just(response(HttpStatus.SERVICE_UNAVAILABLE, "upstream down")));
        LlmClient llm = new LlmClient(properties, new ObjectMapper(), client);

        AiCallException error = assertThrows(AiCallException.class, () ->
                llm.streamAnswer("问题", "", Collections.<Map<String, Object>>emptyList(), text -> { }));

        assertTrue(error.getMessage().contains("503"));
    }

    // ---------- 消息组装 ----------

    @Test
    void buildsRecentValidHistoryOnly() {
        LlmProperties properties = properties();
        properties.setHistoryLimit(2);
        List<Map<String, Object>> history = Arrays.asList(
                message("user", "旧问题"),
                message("system", "不允许"),
                message("assistant", "旧回答"),
                message("user", ""),
                message("user", "新问题"),
                message("assistant", "新回答"));

        List<Map<String, Object>> messages = LlmClient.buildMessages("当前问题", "资料", history, properties);

        assertEquals(4, messages.size());
        assertEquals("system", messages.get(0).get("role"));
        assertEquals("user", messages.get(1).get("role"));
        assertEquals("新问题", messages.get(1).get("content"));
        assertEquals("assistant", messages.get(2).get("role"));
        assertEquals("新回答", messages.get(2).get("content"));
        assertEquals("当前问题", messages.get(3).get("content"));
    }

    @Test
    void buildMessagesTruncatesHistoryByLimit() {
        LlmProperties properties = properties();
        properties.setHistoryLimit(3);
        List<Map<String, Object>> history = Arrays.asList(
                message("user", "第1轮"), message("assistant", "第2轮"), message("user", "第3轮"),
                message("assistant", "第4轮"), message("user", "第5轮"), message("assistant", "第6轮"));

        List<Map<String, Object>> messages = LlmClient.buildMessages("当前问题", "资料", history, properties);

        // system + 最后 3 条历史 + 当前问题
        assertEquals(5, messages.size());
        assertEquals("第4轮", messages.get(1).get("content"));
        assertEquals("当前问题", messages.get(4).get("content"));
    }

    // ---------- answerJson / 结构化输出 ----------

    @Test
    void answerJsonParsesPlainJsonArray() {
        LlmProperties properties = properties();
        WebClient.Builder client = webClient(Mono.just(response(HttpStatus.OK,
                "{\"choices\":[{\"message\":{\"content\":"
                        + "\"[\\\"https://docs.github.com/zh/rest\\\", \\\"https://github.com/trending\\\"]\"}}]}")));
        LlmClient llm = new LlmClient(properties, new ObjectMapper(), client);

        JsonNode data = llm.answerJson(Collections.<Map<String, Object>>singletonList(message("user", "问题")));

        assertNotNull(data);
        assertTrue(data.isArray());
        assertEquals(2, data.size());
        assertEquals("https://docs.github.com/zh/rest", data.get(0).asText());
        assertEquals("https://github.com/trending", data.get(1).asText());
    }

    @Test
    void answerJsonParsesFencedJsonWithSurroundingText() {
        LlmProperties properties = properties();
        WebClient.Builder client = webClient(Mono.just(response(HttpStatus.OK,
                "{\"choices\":[{\"message\":{\"content\":"
                        + "\"好的，以下是判断结果：\\n```json\\n{\\\"sufficient\\\": true}\\n```\\n希望对你有帮助。\"}}]}")));
        LlmClient llm = new LlmClient(properties, new ObjectMapper(), client);

        JsonNode data = llm.answerJson(Collections.<Map<String, Object>>singletonList(message("user", "问题")));

        assertNotNull(data);
        assertTrue(data.path("sufficient").asBoolean());
    }

    @Test
    void answerJsonFallsBackToNestedScan() {
        LlmProperties properties = properties();
        WebClient.Builder client = webClient(Mono.just(response(HttpStatus.OK,
                "{\"choices\":[{\"message\":{\"content\":"
                        + "\"结果如下：{\\\"data\\\":{\\\"sufficient\\\":false,\\\"list\\\":[1,2]}} 仅供参考。\"}}]}")));
        LlmClient llm = new LlmClient(properties, new ObjectMapper(), client);

        JsonNode data = llm.answerJson(Collections.<Map<String, Object>>emptyList());

        assertTrue(data.path("data").path("sufficient").asBoolean() == false);
        assertEquals(2, data.path("data").path("list").size());
    }

    @Test
    void answerJsonReturnsNullWhenUpstreamIsNotJson() {
        LlmProperties properties = properties();
        WebClient.Builder client = webClient(Mono.just(response(HttpStatus.OK,
                "{\"choices\":[{\"message\":{\"content\":\"抱歉，我无法理解你的请求。\"}}]}")));
        LlmClient llm = new LlmClient(properties, new ObjectMapper(), client);

        JsonNode data = llm.answerJson(Collections.<Map<String, Object>>singletonList(message("user", "问题")));

        assertNull(data);
    }

    // ---------- 流式超时语义：空闲超时 + 总时长上限 ----------

    /**
     * 回归：数据在持续到达时，慢速流不应被"总时长"预算切断。
     *
     * <p>修复前 {@code blockLast(timeout())} 把 timeout-seconds 当成整段回答的总预算，
     * 只要累计超过就报超时——线上表现为 delta 已经流出、随后又补一条 ai_service error。</p>
     */
    @Test
    void streamAnswerToleratesSlowButSteadyStream() {
        LlmProperties properties = properties();
        // 非流式预算只有 1 秒，但流式回答不应受它约束
        properties.setTimeoutSeconds(1);
        properties.setStreamIdleTimeoutSeconds(2);
        properties.setStreamTotalTimeoutSeconds(10);
        // 每 500ms 到一段数据，总耗时约 1.5s：超过非流式预算，但每段间隔远小于空闲超时
        Flux<DataBuffer> body = Flux.interval(Duration.ofMillis(500))
                .take(3)
                .map(index -> index < 2
                        ? buffer("data: {\"choices\":[{\"delta\":{\"content\":\"段" + (index + 1) + "\"}}]}\n")
                        : buffer("data: [DONE]\n"));
        LlmClient llm = new LlmClient(properties, new ObjectMapper(), webClient(body));
        List<String> deltas = new ArrayList<String>();

        String result = llm.streamAnswer("问题", "资料", Collections.<Map<String, Object>>emptyList(), deltas::add);

        assertEquals(Arrays.asList("段1", "段2"), deltas);
        assertEquals("段1段2", result);
    }

    /** 上游真正卡死（长时间没有新数据）时应中止，并给出可读的中文超时说明。 */
    @Test
    void streamAnswerReportsReadableErrorWhenIdleTooLong() {
        LlmProperties properties = properties();
        properties.setStreamIdleTimeoutSeconds(1);
        properties.setStreamTotalTimeoutSeconds(30);
        Flux<DataBuffer> body = Flux.concat(
                Flux.just(buffer("data: {\"choices\":[{\"delta\":{\"content\":\"前半段\"}}]}\n")),
                Flux.<DataBuffer>never());
        LlmClient llm = new LlmClient(properties, new ObjectMapper(), webClient(body));

        AiCallException error = assertThrows(AiCallException.class, () ->
                llm.streamAnswer("问题", "资料", Collections.<Map<String, Object>>emptyList(), text -> { }));

        assertTrue(error.getMessage().contains("未收到新数据"),
                "应说明是等待新数据超时，实际：" + error.getMessage());
    }

    /** 总时长上限仍然生效：即使数据按时到达，整段回答超过上限也要中止。 */
    @Test
    void streamAnswerStopsWhenTotalDurationExceeded() {
        LlmProperties properties = properties();
        properties.setStreamIdleTimeoutSeconds(5);
        properties.setStreamTotalTimeoutSeconds(1);
        // 每 300ms 一段，共 10 段（约 3s），空闲不触发但总时长超过 1s 上限
        Flux<DataBuffer> body = Flux.interval(Duration.ofMillis(300))
                .take(10)
                .map(index -> buffer("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n"));
        LlmClient llm = new LlmClient(properties, new ObjectMapper(), webClient(body));

        AiCallException error = assertThrows(AiCallException.class, () ->
                llm.streamAnswer("问题", "资料", Collections.<Map<String, Object>>emptyList(), text -> { }));

        assertTrue(error.getMessage().contains("整段回答超过"),
                "应说明是整段回答超出总上限，实际：" + error.getMessage());
    }

    /** 推理模型先输出 reasoning_content：应只回调一次"思考"，正文照常解析。 */
    @Test
    void streamAnswerNotifiesThinkingOnceAndStillStreamsContent() {
        LlmProperties properties = properties();
        WebClient.Builder client = webClient(Mono.just(response(HttpStatus.OK,
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先想想\"}}]}\n"
                        + "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"再想想\"}}]}\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"答案\"}}]}\n"
                        + "data: [DONE]\n")));
        LlmClient llm = new LlmClient(properties, new ObjectMapper(), client);
        List<String> deltas = new ArrayList<String>();
        List<String> thinking = new ArrayList<String>();

        String result = llm.streamAnswer("问题", "资料", Collections.<Map<String, Object>>emptyList(),
                deltas::add, thinking::add);

        assertEquals(1, thinking.size(), "思考回调只应触发一次，实际 " + thinking);
        assertEquals("先想想", thinking.get(0));
        assertEquals(Collections.singletonList("答案"), deltas);
        assertEquals("答案", result);
    }

    // ---------- 工具方法 ----------

    private LlmProperties properties() {
        LlmProperties properties = new LlmProperties();
        properties.setBaseUrl("http://llm.test/v1");
        properties.setApiKey("test-key");
        properties.setModel("test-model");
        return properties;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private WebClient.Builder webClient(Flux<DataBuffer> body) {
        return WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .body(body)
                        .build())).baseUrl("http://llm.test/v1");
    }

    private WebClient.Builder webClient(Mono<ClientResponse> response) {
        return WebClient.builder().exchangeFunction(request -> response).baseUrl("http://llm.test/v1");
    }

    private ClientResponse response(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(body.getBytes(StandardCharsets.UTF_8))))
                .build();
    }

    private DataBuffer buffer(String text) {
        return DefaultDataBufferFactory.sharedInstance.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    private DataBuffer buffer(byte[] bytes) {
        return DefaultDataBufferFactory.sharedInstance.wrap(bytes);
    }
}
