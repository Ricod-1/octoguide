package com.octoguide.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.octoguide.ai.LlmClient;
import com.octoguide.ai.WebResearcher;
import com.octoguide.dto.ChatRequest;
import com.octoguide.service.ChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.SyncTaskExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话接口控制器。
 *
 * <p>接口使用 NDJSON 输出，每一行都是一个独立 JSON 事件，便于前端在模型生成过程中逐段渲染回答。
 * AI 已配置时的事件流：{@code progress? -> start -> delta* -> done}；联网场景 mode 为 {@code ai_web}，
 * done 事件附带联网来源与 learned 标记，供前端“记下/不记”沉淀知识。</p>
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    private static final String DEFAULT_ANSWER = "抱歉，我暂时没能生成回答，请换个问法试试。";
    /** 错误文案统一前缀；LlmClient 的异常信息可能已带该前缀，避免重复拼接。 */
    private static final String AI_FAILURE_PREFIX = "AI 调用失败：";
    private static final double KNOWLEDGE_HIT_THRESHOLD = 12D;
    /** 默认流式响应超时（秒）：覆盖联网研究最坏链路（多次 LLM 判断 + 多页面抓取 + 流式生成）。 */
    private static final long DEFAULT_EMITTER_TIMEOUT_SECONDS = 300L;
    /** 请求体硬上限默认值：超限直接返回 error 事件，不提交聊天执行线程（与 application.yml 保持一致）。 */
    private static final int DEFAULT_MAX_QUESTION_CHARS = 4000;
    private static final int DEFAULT_MAX_HISTORY_MESSAGES = 50;
    private static final int DEFAULT_MAX_HISTORY_MESSAGE_CHARS = 20000;
    private static final int WEB_SOURCE_LIMIT = 3;
    private static final int WEB_CONTEXT_CHARS = 1500;

    private final ChatService chatService;
    private final WebResearcher webResearcher;
    private final ObjectMapper objectMapper;
    private final TaskExecutor chatTaskExecutor;
    private final long emitterTimeoutMillis;
    private final int maxQuestionChars;
    private final int maxHistoryMessages;
    private final int maxHistoryMessageChars;

    /** 兼容直接实例化的调用方；Spring 运行时使用有界执行器构造器。 */
    public ChatController(ChatService chatService, WebResearcher webResearcher, ObjectMapper objectMapper) {
        this(chatService, webResearcher, objectMapper, new SyncTaskExecutor(), DEFAULT_EMITTER_TIMEOUT_SECONDS,
                DEFAULT_MAX_QUESTION_CHARS, DEFAULT_MAX_HISTORY_MESSAGES, DEFAULT_MAX_HISTORY_MESSAGE_CHARS);
    }

    /** 兼容仅覆盖 emitter 超时的调用方；请求体上限使用默认值。 */
    public ChatController(ChatService chatService, WebResearcher webResearcher, ObjectMapper objectMapper,
                          TaskExecutor chatTaskExecutor, long emitterTimeoutSeconds) {
        this(chatService, webResearcher, objectMapper, chatTaskExecutor, emitterTimeoutSeconds,
                DEFAULT_MAX_QUESTION_CHARS, DEFAULT_MAX_HISTORY_MESSAGES, DEFAULT_MAX_HISTORY_MESSAGE_CHARS);
    }

    /** Spring 注入有界聊天执行器与请求体上限，避免超长 payload 占用聊天线程。 */
    @Autowired
    public ChatController(ChatService chatService, WebResearcher webResearcher, ObjectMapper objectMapper,
                          @Qualifier("chatTaskExecutor") TaskExecutor chatTaskExecutor,
                          @Value("${octoguide.chat.emitter-timeout-seconds:300}") long emitterTimeoutSeconds,
                          @Value("${octoguide.chat.max-question-chars:4000}") int maxQuestionChars,
                          @Value("${octoguide.chat.max-history-messages:50}") int maxHistoryMessages,
                          @Value("${octoguide.chat.max-history-message-chars:20000}") int maxHistoryMessageChars) {
        this.chatService = chatService;
        this.webResearcher = webResearcher;
        this.objectMapper = objectMapper;
        this.chatTaskExecutor = chatTaskExecutor;
        this.emitterTimeoutMillis = Math.max(1, emitterTimeoutSeconds) * 1000L;
        this.maxQuestionChars = maxQuestionChars;
        this.maxHistoryMessages = maxHistoryMessages;
        this.maxHistoryMessageChars = maxHistoryMessageChars;
    }

    /**
     * 流式回答用户问题。
     *
     * <p>AI 未配置时保留 Node 版本兼容行为：强命中直接返回知识库事件，其他问题返回错误事件。
     * AI 已配置时先判断是否需要联网（时效词 / 弱命中），需要则抓取网页并追加到 RAG 上下文；
     * 事件顺序为 {@code progress? -> start -> delta* -> done}。</p>
     *
     * @param request 问题和多轮对话历史
     * @param response HTTP 响应对象，用于设置 NDJSON 响应头
     * @return 异步响应发射器
     */
    @PostMapping(value = "/chat", produces = "application/x-ndjson")
    public ResponseBodyEmitter chat(@Valid @RequestBody ChatRequest request,
                                    HttpServletResponse response) {
        configureStreamingResponse(response);

        final ResponseBodyEmitter emitter = new ResponseBodyEmitter(emitterTimeoutMillis);
        // 客户端断开 / 超时回调：置取消标记，生成线程在下一个检查点快速终止，避免继续空耗上游资源。
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        final String question = request.getQuestion().trim();
        final List<Map<String, Object>> history = sanitizeHistory(request.getHistory());

        // 提交执行线程前做硬上限校验：超长 payload 直接拒绝，避免长时间占用聊天线程。
        String rejection = validatePayload(question, history);
        if (rejection != null) {
            rejectPayload(emitter, rejection);
            return emitter;
        }

        // 使用独立线程避免阻塞 Servlet 请求线程；上游增量在该线程中即时写回客户端。
        // 计时从请求进入控制器开始，排队等待也计入，反映用户真实等待时长。
        final long startedAtNanos = System.nanoTime();
        chatTaskExecutor.execute(() -> streamAnswer(emitter, question, history, cancelled, startedAtNanos));
        return emitter;
    }

    /**
     * 校验请求体硬上限，超限返回错误文案，否则返回 null 继续正常流程。
     *
     * <p>校验在历史清洗之后进行，只限制真正会传给模型的内容量；前端默认只携带最近 10 条历史，
     * 正常使用不会触达上限。</p>
     */
    private String validatePayload(String question, List<Map<String, Object>> history) {
        if (question.length() > maxQuestionChars) {
            return "问题太长了：请控制在 " + maxQuestionChars + " 字以内（当前 " + question.length() + " 字）";
        }
        if (history.size() > maxHistoryMessages) {
            return "历史消息条数超出上限 " + maxHistoryMessages + " 条：请清空对话后重试";
        }
        for (Map<String, Object> item : history) {
            Object content = item.get("content");
            if (content instanceof String && ((String) content).length() > maxHistoryMessageChars) {
                return "历史消息中有一条内容过长（上限 " + maxHistoryMessageChars + " 字）：请清空对话后重试";
            }
        }
        return null;
    }

    /** 校验失败时在当前线程直接写回 error 事件并完成，不提交聊天执行线程。 */
    private void rejectPayload(ResponseBodyEmitter emitter, String message) {
        try {
            send(emitter, errorEvent("payload", message));
            emitter.complete();
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        }
    }

    private void configureStreamingResponse(HttpServletResponse response) {
        response.setContentType("application/x-ndjson;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
    }

    private void streamAnswer(ResponseBodyEmitter emitter, String question,
                              List<Map<String, Object>> history, AtomicBoolean cancelled,
                              long startedAtNanos) {
        try {
            checkCancelled(cancelled);
            // 一次检索派生出问答结果、RAG 上下文与来源，后续联网判断/上下文拼接/来源展示均复用，
            // 避免同一问题在一轮对话内触发多次全量 search。
            ChatService.Knowledge knowledge = chatService.knowledge(question);
            Map<String, Object> knowledgeResult = knowledge.getResult();
            String kbContext = knowledge.getContext();
            if (!chatService.isAiConfigured()) {
                handleUnconfiguredAi(emitter, knowledgeResult, elapsedMillis(startedAtNanos));
                return;
            }

            // 1. 判断是否需要联网：时效词 / 知识库弱命中。
            //    知识库已强命中（found 且分数达标）时直接采用，不再多花一次模型往返去问"资料够不够"。
            boolean needWeb = webResearcher.isTimeSensitive(question)
                    || !Boolean.TRUE.equals(knowledgeResult.get("found"))
                    || scoreOf(knowledgeResult) < KNOWLEDGE_HIT_THRESHOLD;

            // 2. 联网抓取网页
            List<WebResearcher.WebPage> webPages = Collections.emptyList();
            if (needWeb) {
                sendChecked(emitter, progressEvent("planning", "🔍 正在确定要查哪些页面…"), cancelled);
                try {
                    WebResearcher.ResearchResult research = webResearcher.findRelevantPages(question, researchOptions(emitter, cancelled));
                    webPages = research.getPages() == null
                            ? Collections.<WebResearcher.WebPage>emptyList() : research.getPages();
                    if (!webPages.isEmpty()) {
                        sendChecked(emitter, progressEvent("reasoning", "🧠 正在组织回答…"), cancelled);
                    } else {
                        sendChecked(emitter, progressEvent("fallback", "⚠️ 联网查询未果，先用已有知识回答…"), cancelled);
                    }
                } catch (CancelledException exception) {
                    // 客户端已取消：直接终止，不降级为 fallback 继续。
                    throw exception;
                } catch (Exception exception) {
                    logger.info("[chat] 联网查询失败: {}", safeMessage(exception));
                    sendChecked(emitter, progressEvent("fallback", "⚠️ 联网查询未果，先用已有知识回答…"), cancelled);
                }
            }

            // 3. 构建 RAG 上下文（知识库 + 联网网页）
            String contextText = joinContext(kbContext, buildWebContext(webPages));

            // 4. 流式生成
            boolean webMode = !webPages.isEmpty();
            sendChecked(emitter, modeEvent("start", webMode ? "ai_web" : "ai"), cancelled);
            AtomicBoolean emittedDelta = new AtomicBoolean(false);
            AtomicBoolean thinkingNotified = new AtomicBoolean(false);
            String generated = chatService.streamAiAnswer(question, contextText, history, text -> {
                if (text == null || text.trim().isEmpty()) {
                    return;
                }
                // 发送失败（客户端断开）时抛出取消信号，传播回流式调用方终止上游请求。
                sendChecked(emitter, deltaEvent(text), cancelled);
                emittedDelta.set(true);
            }, reasoning -> {
                // 推理模型先输出 reasoning_content：这段时间没有正文，给前端一条"正在思考"反馈。
                if (thinkingNotified.compareAndSet(false, true)) {
                    sendChecked(emitter, progressEvent("reasoning", "🧠 正在思考…"), cancelled);
                }
            });

            if (!emittedDelta.get() && (generated == null || generated.trim().isEmpty())) {
                sendChecked(emitter, deltaEvent(DEFAULT_ANSWER), cancelled);
            }
            sendChecked(emitter, doneEvent(question, webMode, webPages, knowledge.getSources(),
                    elapsedMillis(startedAtNanos)), cancelled);
            emitter.complete();
        } catch (Exception exception) {
            if (isCancelled(exception)) {
                logger.info("[chat] 客户端已断开或超时，停止生成");
                completeQuietly(emitter);
            } else {
                sendErrorAndComplete(emitter, exception, elapsedMillis(startedAtNanos));
            }
        }
    }

    /** 计算本次请求已耗时（毫秒）。 */
    private long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private void handleUnconfiguredAi(ResponseBodyEmitter emitter, Map<String, Object> knowledgeResult,
                                      long elapsedMs) throws IOException {
        double score = scoreOf(knowledgeResult);
        boolean found = Boolean.TRUE.equals(knowledgeResult.get("found"));
        if (found && score >= KNOWLEDGE_HIT_THRESHOLD) {
            Map<String, Object> event = eventWithData("kb", knowledgeResult);
            event.put("elapsedMs", elapsedMs);
            send(emitter, event);
        } else {
            send(emitter, errorEvent("unconfigured", "AI 未配置：请设置环境变量 OCTOGUIDE_LLM_KEY（或在 application-local.yml 的 octoguide.llm.api-key 中）后重启后端", elapsedMs));
        }
        emitter.complete();
    }

    /** 组装联网研究选项：进度事件写回客户端（含取消检查点），日志打印到控制台。 */
    private WebResearcher.ResearchOptions researchOptions(ResponseBodyEmitter emitter, AtomicBoolean cancelled) {
        WebResearcher.ResearchOptions options = new WebResearcher.ResearchOptions();
        options.setProgress((stage, text) -> sendChecked(emitter, progressEvent(stage, text), cancelled));
        options.setLog(message -> logger.info("[chat] {}", message));
        return options;
    }

    /** 构造 done 事件：附带知识库来源 + 网页来源、learned 标记与本次耗时。 */
    private Map<String, Object> doneEvent(String question, boolean webMode,
                                          List<WebResearcher.WebPage> webPages,
                                          List<Map<String, Object>> knowledgeSources,
                                          long elapsedMs) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("type", "done");
        event.put("mode", webMode ? "ai_web" : "ai");
        event.put("elapsedMs", elapsedMs);

        List<Map<String, Object>> sources = new ArrayList<Map<String, Object>>();
        if (knowledgeSources != null) {
            sources.addAll(knowledgeSources);
        }
        int webCount = 0;
        for (WebResearcher.WebPage page : webPages) {
            if (page == null || page.getUrl() == null || page.getUrl().isEmpty()) {
                continue;
            }
            Map<String, Object> source = new LinkedHashMap<String, Object>();
            String title = page.getTitle() == null || page.getTitle().trim().isEmpty()
                    ? page.getUrl() : page.getTitle();
            source.put("title", title);
            source.put("url", page.getUrl());
            sources.add(source);
            webCount++;
            if (webCount >= WEB_SOURCE_LIMIT) {
                break;
            }
        }
        event.put("sources", sources);
        event.put("learned", webMode);
        return event;
    }

    /** 构造 progress 事件（planning / fetching / reasoning / fallback）。 */
    private Map<String, Object> progressEvent(String stage, String text) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("type", "progress");
        event.put("stage", stage);
        event.put("text", text);
        return event;
    }

    /** 把联网抓到的网页拼成给模型的上下文文本。 */
    private String buildWebContext(List<WebResearcher.WebPage> webPages) {
        if (webPages == null || webPages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (WebResearcher.WebPage page : webPages) {
            if (page == null) {
                continue;
            }
            if (index > 1) {
                builder.append("\n\n");
            }
            builder.append("[网页").append(index).append("] ");
            if (page.getUrl() != null) {
                builder.append(page.getUrl());
            }
            builder.append("\n");
            if (page.getTitle() != null && !page.getTitle().trim().isEmpty()) {
                builder.append("标题：").append(page.getTitle().trim()).append("\n");
            }
            String text = page.getText() == null ? "" : page.getText();
            builder.append(text.length() > WEB_CONTEXT_CHARS ? text.substring(0, WEB_CONTEXT_CHARS) : text);
            index++;
        }
        return builder.toString();
    }

    /** 拼接知识库上下文与网页上下文，供模型参考。 */
    private String joinContext(String knowledgeContext, String webContext) {
        List<String> parts = new ArrayList<String>();
        if (knowledgeContext != null && !knowledgeContext.trim().isEmpty()) {
            parts.add(knowledgeContext);
        }
        if (webContext != null && !webContext.trim().isEmpty()) {
            parts.add("以下是联网抓取的资料：\n" + webContext);
        }
        return String.join("\n\n", parts);
    }

    /** 从知识库检索结果中取命中评分。 */
    private double scoreOf(Map<String, Object> knowledgeResult) {
        return knowledgeResult.get("score") instanceof Number
                ? ((Number) knowledgeResult.get("score")).doubleValue() : 0D;
    }

    private void sendErrorAndComplete(ResponseBodyEmitter emitter, Exception exception, long elapsedMs) {
        try {
            send(emitter, errorEvent(errorCode(exception), failureMessage(exception), elapsedMs));
        } catch (Exception ignored) {
            // 客户端已断开，无法再发送错误事件。
        }
        emitter.complete();
    }

    /** 统一错误文案：异常信息已带「AI 调用失败：」前缀时不再重复拼接（避免出现双前缀）。 */
    private String failureMessage(Exception exception) {
        String message = safeMessage(exception);
        return message.startsWith(AI_FAILURE_PREFIX) ? message : AI_FAILURE_PREFIX + message;
    }

    /** 客户端已断开 / 超时的内部取消信号：生成线程在检查点快速终止。 */
    private static final class CancelledException extends RuntimeException {
        CancelledException() {
            super("客户端已取消本次回答");
        }
    }

    /** 检查点：请求已取消则抛出信号，终止后续生成。 */
    private void checkCancelled(AtomicBoolean cancelled) {
        if (cancelled.get()) {
            throw new CancelledException();
        }
    }

    /**
     * 发送事件并在失败时终止生成：发送失败（客户端断开的 IOException，或 emitter 已完成的
     * IllegalStateException，如超时后）都视为取消，置标记并抛出信号，避免继续空耗上游资源。
     */
    private void sendChecked(ResponseBodyEmitter emitter, Map<String, Object> event, AtomicBoolean cancelled) {
        checkCancelled(cancelled);
        try {
            send(emitter, event);
        } catch (IOException | IllegalStateException exception) {
            cancelled.set(true);
            throw new CancelledException();
        }
    }

    /** 判断异常（含原因链）是否为取消信号：LlmClient 会把回调异常包装为 AiCallException。 */
    private boolean isCancelled(Exception exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof CancelledException) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    /** 取消后静默完成 emitter：客户端已断开，无需也无法再发送任何事件。 */
    private void completeQuietly(ResponseBodyEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 已完成或容器已回收响应，无需处理。
        }
    }

    /** 异常映射为错误分类码：网络类故障与 AI 服务故障分开，供前端分类提示。 */
    private String errorCode(Exception exception) {
        if (exception instanceof IOException) {
            return "network";
        }
        return "ai_service";
    }

    /** 过滤非法角色和空消息，避免把无效内容传递给上游模型（与 LlmClient 共用同一套清洗规则）。 */
    private List<Map<String, Object>> sanitizeHistory(List<Map<String, Object>> input) {
        return LlmClient.sanitizeHistory(input);
    }

    private Map<String, Object> modeEvent(String type, String mode) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("type", type);
        event.put("mode", mode);
        return event;
    }

    private Map<String, Object> deltaEvent(String text) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("type", "delta");
        event.put("text", text);
        return event;
    }

    /** 构造 error 事件：code 为错误分类码（payload / unconfigured / ai_service / network），供前端分类提示。 */
    private Map<String, Object> errorEvent(String code, String message) {
        return errorEvent(code, message, -1L);
    }

    /** 构造 error 事件；elapsedMs 为负表示不携带耗时（如请求体校验失败，耗时可忽略）。 */
    private Map<String, Object> errorEvent(String code, String message, long elapsedMs) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("type", "error");
        event.put("code", code);
        event.put("message", message);
        if (elapsedMs >= 0) {
            event.put("elapsedMs", elapsedMs);
        }
        return event;
    }

    private Map<String, Object> eventWithData(String type, Object data) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("type", type);
        event.put("data", data);
        return event;
    }

    private void send(ResponseBodyEmitter emitter, Map<String, Object> event) throws IOException {
        emitter.send(objectMapper.writeValueAsString(event) + "\n");
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? "未知错误" : exception.getMessage();
    }
}
