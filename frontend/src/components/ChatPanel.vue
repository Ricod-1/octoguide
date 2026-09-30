<template>
  <el-main class="chat-panel" aria-label="对话窗口">
    <div class="chat-header">
      <div class="assistant-avatar">G</div>
      <div class="chat-header-copy">
        <div class="panel-kicker">GITHUB 操作智能助手</div>
        <h1>从第一个仓库开始</h1>
      </div>
      <el-button data-testid="clear-chat" text type="info" @click="$emit('clear')">清空对话</el-button>
    </div>
    <el-scrollbar class="chat-scroll" ref="scrollRef">
      <!-- is-welcome：仅有欢迎语时消息列表垂直居中，消除大面积留白；发出第一条消息后自动回到顶部对齐。 -->
      <div class="chat-messages" :class="{ 'is-welcome': messages.length === 1 }" @click="onBubbleClick">
        <article v-for="(message, index) in messages" :key="message.id" class="message-row" :class="message.role">
          <el-avatar :size="32" :class="['message-avatar', message.role]">{{ message.role === 'bot' ? 'G' : '我' }}</el-avatar>
          <div class="message-content">
            <div class="message-label">{{ message.role === 'bot' ? 'OctoGuide 小助手' : '你' }}</div>
            <div class="message-bubble">
              <!-- 本次回答耗时：回答结束后固定在气泡上方，便于对照"慢在哪一步"。 -->
              <div v-if="message.question && message.finished && message.elapsedMs" class="answer-elapsed" data-testid="answer-elapsed">
                ⏱ 本次回答耗时 {{ formatElapsed(message.elapsedMs) }}
              </div>

              <!-- 处理状态：多阶段步骤 + 进度详情（回答完成前实时更新；正文开始后隐藏进度文字）。 -->
              <div v-if="message.question && !message.finished && !message.cancelled">
                <div v-if="message.stage" class="stage-steps">
                  <template v-for="(step, index) in STAGE_STEPS" :key="step">
                    <span class="stage-step" :class="{ active: index === stageIndex(message), done: index < stageIndex(message) }">
                      <span class="stage-step-num">{{ index < stageIndex(message) ? '✓' : index + 1 }}</span>{{ step }}
                    </span>
                    <span v-if="index < STAGE_STEPS.length - 1" class="stage-step-arrow">→</span>
                  </template>
                </div>
                <div v-if="message.progress && !message.text" class="answer-block progress" :class="{ warning: message.stage === 'fallback' }">{{ message.progress }}</div>
              </div>

              <!-- AI 智能回答：徽章 + 流式 Markdown -->
              <template v-if="message.kind === 'ai'">
                <span class="ai-badge">{{ message.web ? '🤖 AI 联网回答' : '🤖 AI 智能回答' }}</span>
                <div v-if="message.text" class="ai-answer" v-html="message.html"></div>
                <div v-else-if="!message.finished" class="pending-indicator" aria-label="正在生成回答">
                  <div class="typing-dots"><span></span><span></span><span></span></div>
                  <span class="pending-label">正在生成回答…</span>
                </div>
                <div v-else class="answer-block text" :class="{ cancelled: message.cancelled }">{{ message.cancelled ? '🚫 已取消本次回答。' : '抱歉，我暂时没能生成回答，请换个问法试试。' }}</div>

                <!-- 来源链接 -->
                <div v-if="message.sources?.length" class="answer-block source">
                  <a v-for="source in message.sources" :key="source.url" :href="source.url" target="_blank" rel="noopener noreferrer">
                    📄 {{ source.title || '参考来源' }} ↗
                  </a>
                </div>

                <!-- 用户确认沉淀知识：记下 / 不记 -->
                <div v-if="message.learned && !message.remember" class="answer-block remember">
                  <span class="remember-label">📌 这条要记进知识库吗？</span>
                  <el-button size="small" type="primary" plain round @click="$emit('remember', { id: message.id, question: message.question, answer: message.text, source: message.sources?.[0] })">记下</el-button>
                  <el-button size="small" round @click="dismissRemember(index)">不记</el-button>
                </div>
                <div v-else-if="message.remember" class="answer-block remember result">
                  <span class="remember-result" :class="message.remember.state">
                    {{ rememberText(message.remember) }}
                  </span>
                </div>
              </template>

              <!-- 知识库命中：答案块 + 建议 -->
              <template v-else-if="message.kind === 'kb'">
                <div v-for="(block, blockIndex) in message.blocks" :key="blockIndex" :class="['answer-block', block.t]">
                  <button v-if="block.t === 'code'" type="button" class="code-copy" aria-label="复制代码">复制</button>
                  <pre v-if="block.t === 'code'" class="code-lines">{{ block.c }}</pre>
                  <template v-else>{{ block.c }}</template>
                </div>
                <div v-if="message.suggestions?.length" class="answer-block text" style="margin-top:6px">💡 你也可以试试问：</div>
                <div v-if="message.suggestions?.length" class="quick-question-row" style="margin:6px 0 0">
                  <el-button v-for="suggestion in message.suggestions" :key="suggestion.q" size="small" round @click="$emit('ask', suggestion.q)">{{ suggestion.q }}</el-button>
                </div>
              </template>

              <!-- 等待后端响应：占位气泡显示输入动画与文字提示，本地即时出现，避免空白“卡死”观感。 -->
              <div v-else-if="!message.finished && !message.text" class="pending-indicator" aria-label="正在生成回答">
                <div class="typing-dots"><span></span><span></span><span></span></div>
                <span class="pending-label">正在生成回答…</span>
              </div>

              <!-- 普通文本（欢迎语）/ 错误提示：error 事件由 App.vue 映射为 kind='error' -->
              <template v-else>
                <div class="answer-block text" :class="{ error: message.error }">{{ message.text }}</div>
                <el-button v-if="message.error && message.offerRetry" data-testid="retry-connect" class="retry-connect" size="small" type="primary" plain round @click="$emit('retry')">重新连接</el-button>
              </template>

              <!-- 回答进行中：已用时间 + 取消入口 -->
              <div v-if="message.question && !message.finished && !message.cancelled" class="processing-meta">
                <span class="processing-elapsed" data-testid="elapsed">已用时 {{ message.elapsed || 0 }}s</span>
                <el-button data-testid="cancel-answer" class="cancel-answer" size="small" text type="info" @click="$emit('cancel')">取消回答</el-button>
              </div>

              <!-- 取消提示：保留已生成的部分内容，仅追加一行说明 -->
              <div v-if="message.cancelled && message.text && message.kind === 'ai'" class="answer-block cancelled">🚫 已取消本次回答。</div>
            </div>
          </div>
        </article>

        <div v-if="messages.length === 1" class="quick-question-row">
          <el-button v-for="question in quickQuestions" :key="question" data-testid="quick-question" plain round @click="$emit('ask', question)">{{ question }}</el-button>
        </div>
      </div>
    </el-scrollbar>
    <div class="composer-wrap">
      <el-input v-model="input" data-testid="chat-input" size="large" placeholder="描述你的问题，如：git push 时报错怎么解决？" :disabled="busy" @keyup.enter="submit">
        <template #append>
          <el-button data-testid="send-button" type="primary" :loading="busy" :disabled="!input.trim() || busy" @click="submit">{{ busy ? '生成中…' : '发送' }}</el-button>
        </template>
      </el-input>
      <div class="composer-hint">💡 遇到报错？贴上<strong>报错原文 + 当前操作步骤 + 期望结果</strong>，回答会更准确。时效类问题会自动联网查询；新手建议从左侧“认识 GitHub”开始。</div>
    </div>
  </el-main>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  aiConfigured: { type: Boolean, default: false },
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['ask', 'clear', 'remember', 'retry', 'cancel'])

// 自由提问三阶段步骤（planning → fetching → reasoning），fallback 停留在检索阶段并以警告样式提示。
const STAGE_STEPS = ['分析问题', '检索资料', '生成回答']
const STAGE_INDEX = { planning: 0, fetching: 1, reasoning: 2, fallback: 1 }

/** 当前消息所处步骤下标（未知阶段回退到第一步）。 */
function stageIndex(message) {
  return STAGE_INDEX[message.stage] ?? 0
}

/** 把毫秒格式化为可读耗时：1 分钟内保留一位小数，超过则用「分 秒」。 */
function formatElapsed(elapsedMs) {
  const ms = Number(elapsedMs) || 0
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} 秒`
  const minutes = Math.floor(ms / 60000)
  const seconds = Math.round((ms % 60000) / 1000)
  return `${minutes} 分 ${String(seconds).padStart(2, '0')} 秒`
}

const input = ref('')
const scrollRef = ref(null)

// 与 Node 版一致的快捷问题。
const quickQuestions = [
  'GitHub 到底是什么？',
  '怎么把自己电脑上的代码上传到 GitHub？',
  '什么是 Pull Request（PR）？',
  '合并时出现 CONFLICT（冲突）怎么办？',
]

/** 发送输入框内容。busy 时直接忽略，作为组件级防重复提交守卫。 */
function submit() {
  const text = input.value.trim()
  if (!text || props.busy) return
  emit('ask', text)
  input.value = ''
}

/** 点击“不记”：直接移除该提示行。 */
function dismissRemember(index) {
  emit('remember', { id: null, dismiss: index })
}

/** 处理气泡内点击事件委托：只响应“复制代码”按钮。 */
function onBubbleClick(event) {
  const button = event.target.closest('.code-copy')
  if (button) copyCode(button)
}

/** 复制代码块内容到剪贴板，并短暂显示反馈。 */
async function copyCode(button) {
  const block = button.closest('.answer-block.code')
  const text = block?.querySelector('.code-lines')?.textContent || ''
  const clipboard = navigator.clipboard
  if (!clipboard) return
  try {
    await clipboard.writeText(text)
    button.textContent = '已复制'
    setTimeout(() => { button.textContent = '复制' }, 1500)
  } catch (error) {
    // 剪贴板偶发不可用时静默失败，不影响阅读。
  }
}

/** 渲染“记下”按钮的处理结果文案。 */
function rememberText(remember) {
  if (remember.state === 'saving') return '⏳ 正在整理保存…'
  if (remember.state === 'duplicate') return 'ℹ️ 知识库已有相关内容，未重复保存。'
  if (remember.state === 'saved') return '✅ 已记入知识库，下次可以直接命中。'
  if (remember.state === 'error') return '😥 ' + (remember.message || '保存失败')
  return ''
}

// 消息内容变化时自动滚动到底部：既监听数量（新消息），也监听流式回答的增长。
watch(() => props.messages, async () => {
  await nextTick()
  scrollRef.value?.setScrollTop(scrollRef.value.wrapRef?.scrollHeight || 0)
}, { deep: true })
</script>
