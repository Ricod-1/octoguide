package com.octoguide.service;

import com.octoguide.ai.LlmClient;
import com.octoguide.knowledge.service.AnswerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 对话应用服务，连接知识库检索和大模型客户端。 */
@Service
public class ChatService {

    private static final int RAG_LIMIT = 4;
    private static final int SOURCE_LIMIT = 2;

    private final AnswerService answerService;
    private final LlmClient llmClient;

    @Autowired
    public ChatService(AnswerService answerService, LlmClient llmClient) {
        this.answerService = answerService;
        this.llmClient = llmClient;
    }

    /** 判断 AI 是否已配置。 */
    public boolean isAiConfigured() {
        return llmClient.isConfigured();
    }

    /**
     * 一次检索派生出一轮对话需要的全部知识库信息：问答结果、RAG 上下文和来源。
     *
     * <p>一次聊天请求在联网判断、上下文拼接和来源展示时会多次用到知识库检索结果，
     * 统一走这一个入口可以避免同一问题触发多次全量 {@code search}。</p>
     *
     * @param question 用户问题
     * @return 单次检索派生出的知识视图
     */
    public Knowledge knowledge(String question) {
        String input = question == null ? "" : question.trim();
        List<Map<String, Object>> matches = answerService.search(input);
        return new Knowledge(
                answerService.askFrom(input, matches),
                answerService.ragContextFrom(matches, RAG_LIMIT),
                answerService.sourcesFrom(matches, SOURCE_LIMIT));
    }

    /**
     * 调用流式模型回答（使用自定义上下文，如联网场景的知识库 + 网页拼接）。
     *
     * @param question 当前问题
     * @param context 参考资料文本（知识库 + 网页）
     * @param history 清洗后的对话历史
     * @param onDelta 收到模型增量时的回调
     * @return 上游拼接后的完整回答
     */
    public String streamAiAnswer(String question, String context, List<Map<String, Object>> history,
                                 Consumer<String> onDelta) {
        if (!llmClient.isConfigured()) {
            return null;
        }
        return llmClient.streamAnswer(question, context, history, onDelta);
    }

    /**
     * 调用流式模型回答，并额外透传"思考内容"回调（推理模型先输出 reasoning_content）。
     *
     * @param question 当前问题
     * @param context 参考资料文本（知识库 + 网页）
     * @param history 清洗后的对话历史
     * @param onDelta 收到模型正文增量时的回调
     * @param onThinking 首次收到推理内容时的回调，可为 null
     * @return 上游拼接后的完整回答
     */
    public String streamAiAnswer(String question, String context, List<Map<String, Object>> history,
                                 Consumer<String> onDelta, Consumer<String> onThinking) {
        if (!llmClient.isConfigured()) {
            return null;
        }
        return llmClient.streamAnswer(question, context, history, onDelta, onThinking);
    }

    /** 单次检索派生出的知识视图：问答结果 + RAG 上下文 + 来源列表。 */
    public static final class Knowledge {

        /** 问答响应（found / questionId / answer / suggestions / score 等）。 */
        private final Map<String, Object> result;

        /** 提供给大模型的 RAG 上下文文本。 */
        private final String context;

        /** 本轮回答附带的来源列表。 */
        private final List<Map<String, Object>> sources;

        public Knowledge(Map<String, Object> result, String context, List<Map<String, Object>> sources) {
            this.result = result;
            this.context = context;
            this.sources = sources == null ? Collections.<Map<String, Object>>emptyList() : sources;
        }

        public Map<String, Object> getResult() {
            return result;
        }

        public String getContext() {
            return context;
        }

        public List<Map<String, Object>> getSources() {
            return sources;
        }
    }
}
