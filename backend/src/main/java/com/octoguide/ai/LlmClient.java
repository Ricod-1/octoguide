package com.octoguide.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI-compatible 大模型客户端。
 *
 * <p>该类只负责上游 HTTP 通信、请求组装和响应解析，不负责决定何时应该联网或如何组织最终答案。
 * 除文本回答外，还提供 JSON 结构化输出能力（{@link #answerJson}），供联网研究等场景让模型输出结构化结果。</p>
 */
@Service
public class LlmClient {

    /** 上游请求超时时间的最小保护值，避免错误配置导致立即超时。 */
    private static final int MIN_TIMEOUT_SECONDS = 1;

    /** 兼容模型输出中 markdown 代码块包裹的 JSON。 */
    private static final Pattern FENCED_JSON = Pattern.compile(
            "```\\s*(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private final LlmProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    /** Spring 运行时使用的构造器。 */
    @Autowired
    public LlmClient(LlmProperties properties, ObjectMapper objectMapper, WebClient.Builder builder) {
        this(properties, objectMapper, builder.baseUrl(normalizeBaseUrl(properties.getBaseUrl())).build());
    }

    /** 测试或特殊适配场景使用的构造器，允许注入自定义 WebClient。 */
    public LlmClient(LlmProperties properties, ObjectMapper objectMapper, WebClient webClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = webClient;
    }

    /**
     * 判断当前是否配置了可用的 API Key。
     *
     * @return 已配置返回 {@code true}，否则返回 {@code false}
     */
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /** 使用非流式接口生成完整回答。 */
    public String answer(String question, String context) {
        return answer(question, context, Collections.<Map<String, Object>>emptyList());
    }

    /**
     * 使用非流式接口生成完整回答。
     *
     * @param question 当前问题
     * @param context 知识库参考资料
     * @param history 历史对话
     * @return 模型回答；上游没有内容时返回 {@code null}
     * @throws AiCallException 上游调用失败或响应无法解析时抛出
     */
    public String answer(String question, String context, List<Map<String, Object>> history) {
        if (!isConfigured()) {
            return null;
        }
        String rawResponse = postChatCompletion(buildMessages(question, context, history, properties), false);
        try {
            JsonNode content = objectMapper.readTree(rawResponse == null ? "" : rawResponse)
                    .path("choices")
                    .path(0)
                    .path("message")
                    .path("content");
            return content.isMissingNode() || content.isNull() ? null : content.asText();
        } catch (Exception exception) {
            throw new AiCallException("AI 调用失败：" + safeMessage(exception), exception);
        }
    }

    /**
     * 使用非流式接口生成回答并解析为 JSON。
     *
     * <p>供“让模型输出结构化结果”的场景使用（如联网研究中的网址建议）。
     * 兼容 markdown 代码块包裹或前后杂文，解析失败返回 {@code null}。</p>
     *
     * @param messages 完整消息列表（可自定义 system prompt）
     * @return 解析后的 JSON；未配置 AI 或解析失败返回 {@code null}
     * @throws AiCallException 上游调用失败时抛出
     */
    public JsonNode answerJson(List<Map<String, Object>> messages) {
        if (!isConfigured()) {
            return null;
        }
        String rawResponse = postChatCompletion(messages, false);
        try {
            // 先取出模型输出正文（choices[0].message.content），再对正文做 JSON 解析
            JsonNode content = objectMapper.readTree(rawResponse == null ? "" : rawResponse)
                    .path("choices")
                    .path(0)
                    .path("message")
                    .path("content");
            String contentText = content.isMissingNode() || content.isNull() ? null : content.asText();
            return extractJson(contentText);
        } catch (Exception exception) {
            throw new AiCallException("AI 调用失败：" + safeMessage(exception), exception);
        }
    }

    /**
     * 使用上游 SSE 接口流式生成回答。
     *
     * <p>上游响应可能在任意位置切分数据，因此这里按字节读取并自行维护行缓冲，不能直接假设一次
     * {@code DataBuffer} 就对应一条 SSE 消息。</p>
     *
     * <p>超时分两层：{@code stream-idle-timeout-seconds} 约束"两段数据之间的间隔"（含首段数据），
     * {@code stream-total-timeout-seconds} 约束整段回答的总时长。这样慢速但持续输出的模型不会被
     * 中途掐断，只有真正卡死或整段超长才会失败。</p>
     *
     * @param question 当前问题
     * @param context 知识库参考资料
     * @param history 历史对话
     * @param onDelta 每收到一段非空文本时执行的回调
     * @return 拼接后的完整回答
     * @throws AiCallException 上游调用失败或超时时抛出
     */
    public String streamAnswer(String question, String context,
                               List<Map<String, Object>> history, Consumer<String> onDelta) {
        return streamAnswer(question, context, history, onDelta, null);
    }

    /**
     * 使用上游 SSE 接口流式生成回答，并额外回调推理内容。
     *
     * <p>推理模型（如 deepseek 系列）会先流式输出 {@code reasoning_content}，这段时间里
     * {@code content} 一直为空。把首次推理内容回调出去，调用方就能在"思考阶段"给用户反馈，
     * 而不是让界面看起来卡住。</p>
     *
     * @param question 当前问题
     * @param context 知识库参考资料
     * @param history 历史对话
     * @param onDelta 每收到一段非空正文时执行的回调
     * @param onThinking 首次收到推理内容时回调一次（可为 null）
     * @return 拼接后的完整回答
     * @throws AiCallException 上游调用失败或超时时抛出
     */
    public String streamAnswer(String question, String context,
                               List<Map<String, Object>> history, Consumer<String> onDelta,
                               Consumer<String> onThinking) {
        if (!isConfigured()) {
            return null;
        }

        StringBuilder fullText = new StringBuilder();
        // 行缓冲按字节维护：网络包可能把多字节 UTF-8 字符从中间切断，
        // 只有完整行才能安全解码，避免产生 U+FFFD 替换字符。
        ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        boolean[] completed = {false};
        boolean[] thinkingNotified = {false};

        try {
            Flux<DataBuffer> responseChunks = webClient.post()
                    .uri(completionUri())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .header(HttpHeaders.ACCEPT, "text/event-stream")
                    .bodyValue(buildRequestBody(question, context, history, true))
                    .retrieve()
                    .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> new AiCallException(
                                    "AI 接口返回 " + response.statusCode().value() + summarize(body))))
                    .bodyToFlux(DataBuffer.class);

            Duration idleTimeout = idleTimeout();
            Duration totalTimeout = totalTimeout();
            Flux<DataBuffer> stream = responseChunks.doOnNext(chunk -> {
                if (completed[0]) {
                    DataBufferUtils.release(chunk);
                    return;
                }

                try {
                    byte[] bytes = new byte[chunk.readableByteCount()];
                    chunk.read(bytes);
                    lineBuffer.write(bytes, 0, bytes.length);
                    processCompleteLines(lineBuffer, fullText, completed, onDelta, onThinking, thinkingNotified);
                } finally {
                    DataBufferUtils.release(chunk);
                }
            })
                    // 空闲超时：只有"距上一段数据超过 idleTimeout"才判定上游卡死。
                    // 不能用总时长预算，否则 token 还在正常输出也会被中途切断。
                    .timeout(idleTimeout)
                    .onErrorMap(TimeoutException.class, exception -> new AiCallException(
                            "AI 响应超时：超过 " + idleTimeout.getSeconds() + " 秒未收到新数据", exception));

            try {
                stream.blockLast(totalTimeout);
            } catch (IllegalStateException exception) {
                // blockLast 超时会抛 Reactor 的原始英文信息，这里换成可读提示。
                if (exception.getMessage() != null
                        && exception.getMessage().startsWith("Timeout on blocking read")) {
                    throw new AiCallException("AI 响应超时：整段回答超过 "
                            + totalTimeout.getSeconds() + " 秒仍未结束", exception);
                }
                throw exception;
            }

            // 有些上游在结束响应前不会补换行，需要处理最后一条残留数据。
            if (!completed[0] && lineBuffer.size() > 0) {
                SseDelta delta = parseSseLine(lineBuffer.toString(StandardCharsets.UTF_8.name()));
                notifyThinking(delta, onThinking, thinkingNotified);
                emitDelta(delta == null ? null : delta.content, fullText, onDelta);
            }
            return fullText.toString();
        } catch (AiCallException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiCallException("AI 调用失败：" + safeMessage(exception), exception);
        }
    }

    /**
     * 过滤非法角色与空消息，返回可安全发给上游的 user/assistant 历史。
     *
     * <p>{@link #buildMessages} 在截断历史前复用同一套清洗规则；{@code ChatController}
     * 也在把历史交给对话服务前调用本方法，确保两条路径的清洗行为一致。</p>
     *
     * @param history 原始历史消息
     * @return 清洗后的历史列表（role/content 均已校验）
     */
    public static List<Map<String, Object>> sanitizeHistory(List<Map<String, Object>> history) {
        List<Map<String, Object>> validHistory = new ArrayList<Map<String, Object>>();
        if (history != null) {
            for (Map<String, Object> item : history) {
                if (item == null || item.get("role") == null || item.get("content") == null) {
                    continue;
                }

                String role = String.valueOf(item.get("role"));
                String content = String.valueOf(item.get("content")).trim();
                if (("user".equals(role) || "assistant".equals(role)) && !content.isEmpty()) {
                    validHistory.add(message(role, content));
                }
            }
        }
        return validHistory;
    }

    /**
     * 组装发送给模型的消息列表，同时过滤非法角色并限制历史消息数量。
     * 测试可调用该方法验证请求边界，但业务代码不应直接依赖它。
     */
    static List<Map<String, Object>> buildMessages(String question, String context,
                                                    List<Map<String, Object>> history,
                                                    LlmProperties properties) {
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(message("system", "你是 OctoGuide，一个面向 GitHub 新手的中文操作助手。请简洁、准确地回答问题。参考资料：\n"
                + (context == null ? "" : context)));

        List<Map<String, Object>> validHistory = sanitizeHistory(history);

        int historyLimit = Math.max(0, properties.getHistoryLimit());
        int startIndex = Math.max(0, validHistory.size() - historyLimit);
        messages.addAll(validHistory.subList(startIndex, validHistory.size()));
        messages.add(message("user", question == null ? "" : question.trim()));
        return messages;
    }

    /** 非流式 POST 调用上游接口并返回原始响应体（含错误状态转换与统一异常包装）。 */
    private String postChatCompletion(List<Map<String, Object>> messages, boolean stream) {
        try {
            return webClient.post()
                    .uri(completionUri())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .bodyValue(buildRequestBody(messages, stream))
                    .retrieve()
                    .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> new AiCallException(
                                    "AI 接口返回 " + response.statusCode().value() + summarize(body))))
                    .bodyToMono(String.class)
                    .block(timeout());
        } catch (AiCallException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiCallException("AI 调用失败：" + safeMessage(exception), exception);
        }
    }

    private Map<String, Object> buildRequestBody(String question, String context,
                                                  List<Map<String, Object>> history,
                                                  boolean stream) {
        return buildRequestBody(buildMessages(question, context, history, properties), stream);
    }

    /** 基于完整消息列表组装上游请求体。 */
    private Map<String, Object> buildRequestBody(List<Map<String, Object>> messages, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", properties.getModel());
        body.put("temperature", 0.2);
        body.put("stream", stream);
        body.put("messages", messages);
        return body;
    }

    /**
     * 从模型输出中提取 JSON。
     *
     * <p>先尝试直接解析；失败时兼容 markdown 代码块包裹（```json ... ```），再退化为从第一个
     * {@code [} 或 {@code \{} 开始按括号深度扫描。</p>
     *
     * @param text 模型原始输出
     * @return 解析后的 JSON；无法解析返回 {@code null}
     */
    private JsonNode extractJson(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return null;
        }
        Matcher fenced = FENCED_JSON.matcher(value);
        String body = fenced.find() ? fenced.group(1).trim() : value;
        try {
            return objectMapper.readTree(body);
        } catch (Exception ignored) {
            // 前后可能有杂文，退化为按括号深度扫描
            return scanJsonObject(body);
        }
    }

    /** 从文本第一个 {@code [} 或 {@code \{} 开始扫描到括号闭合，解析其中的 JSON。 */
    private JsonNode scanJsonObject(String body) {
        int start = -1;
        char opening = 0;
        for (int index = 0; index < body.length(); index++) {
            char ch = body.charAt(index);
            if (ch == '[' || ch == '{') {
                start = index;
                opening = ch;
                break;
            }
        }
        if (start == -1) {
            return null;
        }
        char closing = opening == '[' ? ']' : '}';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < body.length(); index++) {
            char ch = body.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == opening) {
                depth++;
            } else if (ch == closing) {
                depth--;
                if (depth == 0) {
                    try {
                        return objectMapper.readTree(body.substring(start, index + 1));
                    } catch (Exception ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private void processCompleteLines(ByteArrayOutputStream lineBuffer, StringBuilder fullText,
                                      boolean[] completed, Consumer<String> onDelta,
                                      Consumer<String> onThinking, boolean[] thinkingNotified) {
        while (!completed[0]) {
            byte[] buffer = lineBuffer.toByteArray();
            int lineEnd = indexOfNewline(buffer);
            if (lineEnd < 0) {
                return;
            }
            String line = new String(buffer, 0, lineEnd, StandardCharsets.UTF_8);
            lineBuffer.reset();
            lineBuffer.write(buffer, lineEnd + 1, buffer.length - lineEnd - 1);
            String payload = line.trim();
            if (payload.startsWith("data:")) {
                payload = payload.substring(5).trim();
            }
            if ("[DONE]".equals(payload)) {
                completed[0] = true;
                continue;
            }
            SseDelta delta = parseSseLine(line);
            notifyThinking(delta, onThinking, thinkingNotified);
            emitDelta(delta == null ? null : delta.content, fullText, onDelta);
        }
    }

    /** 首次出现推理内容时回调一次，让调用方展示"正在思考"；后续思考增量不再重复通知。 */
    private void notifyThinking(SseDelta delta, Consumer<String> onThinking, boolean[] thinkingNotified) {
        if (onThinking == null || thinkingNotified[0] || delta == null || delta.reasoning == null) {
            return;
        }
        if (!delta.reasoning.trim().isEmpty()) {
            thinkingNotified[0] = true;
            onThinking.accept(delta.reasoning);
        }
    }

    /** 返回缓冲区中第一个换行符下标；无换行返回 -1。 */
    private int indexOfNewline(byte[] buffer) {
        for (int index = 0; index < buffer.length; index++) {
            if (buffer[index] == '\n') {
                return index;
            }
        }
        return -1;
    }

    private void emitDelta(String delta, StringBuilder fullText, Consumer<String> onDelta) {
        if (delta == null) {
            return;
        }
        fullText.append(delta);
        if (onDelta != null) {
            onDelta.accept(delta);
        }
    }

    /** 解析一条 SSE 数据行，同时取出正文与推理内容（推理模型会先输出 reasoning_content）。 */
    private SseDelta parseSseLine(String line) {
        String value = line == null ? "" : line.trim();
        if (!value.startsWith("data:")) {
            return null;
        }

        String payload = value.substring(5).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return null;
        }

        try {
            JsonNode delta = objectMapper.readTree(payload)
                    .path("choices")
                    .path(0)
                    .path("delta");
            String content = textOrNull(delta.path("content"));
            String reasoning = textOrNull(delta.path("reasoning_content"));
            if (content == null && reasoning == null) {
                return null;
            }
            return new SseDelta(content, reasoning);
        } catch (Exception ignored) {
            // 单条 SSE 数据损坏时跳过该条，后续增量仍可继续处理。
            return null;
        }
    }

    /** 取文本节点内容；缺失、非文本或空串返回 null。 */
    private String textOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || !node.isTextual()) {
            return null;
        }
        return node.asText().isEmpty() ? null : node.asText();
    }

    /** 单条 SSE 增量：正文 + 推理内容（两者都可能为 null）。 */
    private static final class SseDelta {

        private final String content;
        private final String reasoning;

        private SseDelta(String content, String reasoning) {
            this.content = content;
            this.reasoning = reasoning;
        }
    }

    /** 非流式调用使用的整次等待上限。 */
    private Duration timeout() {
        return Duration.ofSeconds(Math.max(MIN_TIMEOUT_SECONDS, properties.getTimeoutSeconds()));
    }

    /** 流式回答的空闲超时：两段数据之间的最大间隔（同样约束首段数据的到达）。 */
    private Duration idleTimeout() {
        return Duration.ofSeconds(Math.max(MIN_TIMEOUT_SECONDS, properties.getStreamIdleTimeoutSeconds()));
    }

    /** 流式回答的总时长上限：整段回答从订阅到结束的硬上限。 */
    private Duration totalTimeout() {
        return Duration.ofSeconds(Math.max(MIN_TIMEOUT_SECONDS, properties.getStreamTotalTimeoutSeconds()));
    }

    /**
     * 统一生成 OpenAI 兼容接口地址。
     *
     * <p>兼容三种常见配置：域名根地址、带 {@code /v1} 的基础地址，以及完整的
     * {@code /v1/chat/completions} 地址，避免迁移旧版配置时出现重复或缺失路径。</p>
     */
    private String completionUri() {
        String baseUrl = normalizeBaseUrl(properties.getBaseUrl());
        if (baseUrl.isEmpty()) {
            throw new AiCallException("AI 接口地址未配置");
        }
        return baseUrl + "/chat/completions";
    }

    /** 规范化基础地址，确保最终只保留一个 {@code /v1} 路径。 */
    static String normalizeBaseUrl(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }

        String lowerCase = value.toLowerCase(Locale.ROOT);
        String completionSuffix = "/chat/completions";
        if (lowerCase.endsWith(completionSuffix)) {
            value = value.substring(0, value.length() - completionSuffix.length());
            while (value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }
            lowerCase = value.toLowerCase(Locale.ROOT);
        }
        if (!value.isEmpty() && !lowerCase.endsWith("/v1")) {
            value += "/v1";
        }
        return value;
    }

    private String summarize(String body) {
        if (body == null || body.trim().isEmpty()) {
            return "";
        }
        String value = body.trim();
        return "：" + value.substring(0, Math.min(300, value.length()));
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? "未知错误" : exception.getMessage();
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("role", role);
        result.put("content", content);
        return result;
    }
}
