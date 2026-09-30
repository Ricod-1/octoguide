<template>
  <div class="octoguide-shell">
    <el-container class="page-container">
      <el-header class="topbar">
        <div class="brand-lockup">
          <div class="brand-mark" aria-hidden="true">G</div>
          <div>
            <div class="brand-name">Octo<span>Guide</span> · GitHub 操作助手</div>
            <div class="brand-tagline">给代码新手的中文 GitHub 向导</div>
          </div>
          <el-tag :type="status?.aiConfigured ? 'success' : 'info'" effect="plain" round>
            {{ status?.aiConfigured ? 'AI 已接入' : '本地知识库' }}
          </el-tag>
        </div>
        <div class="topbar-actions">
          <el-button data-testid="help-toggle" plain @click="helpVisible = true">帮助</el-button>
          <el-button data-testid="terms-toggle" plain @click="termsVisible = !termsVisible">{{ termsVisible ? '隐藏术语表' : '术语速查' }}</el-button>
          <el-button tag="a" href="https://docs.github.com/zh" target="_blank" rel="noopener" plain>官方文档 ↗</el-button>
          <el-button tag="a" href="https://github.com" target="_blank" rel="noopener" plain>GitHub 官网 ↗</el-button>
        </div>
      </el-header>

      <el-main class="workspace-main" :class="{ 'terms-collapsed': !termsVisible }">
        <KnowledgeSidebar :categories="categories" @ask="askQuestion" />
        <ChatPanel :messages="messages" :ai-configured="Boolean(status?.aiConfigured)" :busy="busy" @ask="askQuestion" @clear="clearChat" @remember="handleRemember" @retry="init" @cancel="cancelQuestion" />
        <TermsPanel :terms="terms" />
      </el-main>

      <el-footer class="footbar">
        <span><strong>OctoGuide</strong> · GitHub 操作智能助手</span>
        <span>知识库：<strong>{{ categorySummary }}</strong> · <strong>{{ totalCount }} 条问答</strong>（自动收录 {{ autoCount }}）· <strong>{{ terms.length }} 条术语</strong></span>
        <span class="footbar-spacer" />
        <span>{{ status?.aiConfigured ? `AI 回答 · ${status?.aiModel || '已配置'}` : '未配置 API Key，使用本地知识库' }}</span>
      </el-footer>
    </el-container>

    <HelpDialog v-model="helpVisible" :status="status" />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import KnowledgeSidebar from './components/KnowledgeSidebar.vue'
import ChatPanel from './components/ChatPanel.vue'
import TermsPanel from './components/TermsPanel.vue'
import HelpDialog from './components/HelpDialog.vue'
import { getCategories, getStatus, getTerms, remember, streamChat } from './services/api.js'
import { renderMarkdown } from './utils/markdown.js'

const categories = ref([])
const terms = ref([])
const status = ref(null)
const messages = ref([])
const busy = ref(false)
const termsVisible = ref(true)
// 帮助弹窗：承载联网知识机制、AI 配置状态等次要说明信息。
const helpVisible = ref(false)

// 会话历史（多轮记忆，仅内存），并限制条数，避免长对话请求体持续膨胀。
const sessionHistory = []
const HISTORY_LIMIT = 10
let messageSeq = 0

// 进行中的流式请求与计时器：取消/清空对话时中止，避免旧回答写回新会话。
let activeController = null
let activeTicker = null

// 本次中止的原因（'cancel' 用户取消 / 'watchdog' 静默超时），用于区分气泡的最终展示。
let pendingAbortReason = null

// 静默看门狗：区分阶段容忍度——完全无响应 15s；联网检索阶段单页抓取可能较慢放宽到 60s；
// 流式输出开始后又静默（流中断）恢复 15s，避免把"慢"误判为"断"。
const SILENT_TIMEOUT_MS = 15000
const PROGRESS_SILENT_TIMEOUT_MS = 60000

// 后端 error 事件分类码 → 友好提示；未知分类码原样展示 message。
const ERROR_CODE_TEXT = {
  network: '🌐 网络连接异常，请检查网络后重试',
  ai_service: '🤖 AI 服务暂时不可用，请稍后重试',
}

const totalCount = computed(() => Number(status.value?.totalCount ?? categories.value.reduce((sum, category) => sum + category.questions.length, 0)))
const autoCount = computed(() => Number(status.value?.autoCount ?? 0))
// 分类计数：优先用后端 status 的统一口径，接口缺字段时退化为按 categories 的 auto 标记推导。
const manualCategoryCount = computed(() => Number(status.value?.manualCategoryCount ?? categories.value.filter((category) => !category.auto).length))
const autoCategoryCount = computed(() => Number(status.value?.autoCategoryCount ?? categories.value.filter((category) => category.auto).length))
/** 底栏分类文案：自动采集分类数 >0 时才显示补充说明，避免出现“另自动采集 0 类”。 */
const categorySummary = computed(() => autoCategoryCount.value > 0
  ? `${manualCategoryCount.value} 个分类（另自动采集 ${autoCategoryCount.value} 类）`
  : `${manualCategoryCount.value} 个分类`)

/** 构造一条消息对象。 */
function makeMessage(partial) {
  return { id: ++messageSeq, role: 'bot', kind: 'text', text: '', html: '', progress: '', stage: '', elapsed: 0, elapsedMs: 0, cancelled: false, finished: false, web: false, sources: [], learned: false, question: '', remember: null, blocks: [], suggestions: [], error: false, ...partial }
}

/** 欢迎语：首屏以用户价值（能解决什么问题）为导向，更新机制等技术细节移至帮助弹窗。 */
function seedChat() {
  messages.value = [makeMessage({ text: '你好呀！我是 OctoGuide 🐙 一个 GitHub 操作助手。\n\n我能帮你：\n① 上手 GitHub 基本操作（仓库、PR、Issue…）\n② 排查 git / GitHub 报错（贴上报错原文更准确）\n③ 查询 GitHub 最新动态（自动联网更新）\n\n点左侧问题直接提问，或试试下方快捷问题。更多说明见右上角「帮助」。' })]
}

/** 启动时并行加载分类、术语与系统状态。 */
async function init() {
  try {
    const [categoryData, termData, statusData] = await Promise.all([
      getCategories(), getTerms(), getStatus(),
    ])
    categories.value = categoryData.categories || []
    terms.value = termData.terms || []
    status.value = statusData
    seedChat()
  } catch (error) {
    messages.value = [makeMessage({ error: true, offerRetry: true, text: '😥 连不上知识库服务。请确认后端已启动（README「启动方法」），然后点下方按钮重试。' })]
  }
}

/** 发送问题：走 NDJSON 流式接口，逐事件更新当前回答。 */
async function askQuestion(questionText) {
  const text = String(questionText || '').trim()
  if (!text || busy.value) return

  messages.value.push(makeMessage({ role: 'user', text }))
  messages.value.push(makeMessage({ question: text }))
  // 从响应式数组中取回代理对象：后续对 answer 的字段变更才能触发视图更新。
  // （若直接持有 push 前的原始对象并修改，会绕过 Vue 响应式代理，气泡不刷新）
  const answer = messages.value[messages.value.length - 1]

  busy.value = true
  const controller = new AbortController()
  activeController = controller
  pendingAbortReason = null
  sessionHistory.push({ role: 'user', content: text })
  if (sessionHistory.length > HISTORY_LIMIT) sessionHistory.splice(0, sessionHistory.length - HISTORY_LIMIT)
  let accumulated = ''
  let aiMode = false
  // 后端上报的服务端耗时（done / kb / error 事件携带），缺失时退回前端墙钟时间。
  let backendElapsedMs = 0

  // 已用时间计时器：每秒刷新展示；同时充当静默看门狗，按阶段容忍度判断网络故障。
  const startedAt = Date.now()
  let lastEventAt = startedAt
  let seenEvent = false
  let deltaSeen = false
  const ticker = setInterval(() => {
    answer.elapsed = Math.floor((Date.now() - startedAt) / 1000)
    const limit = seenEvent && !deltaSeen ? PROGRESS_SILENT_TIMEOUT_MS : SILENT_TIMEOUT_MS
    if (!answer.finished && Date.now() - lastEventAt > limit) {
      pendingAbortReason = 'watchdog'
      controller.abort()
    }
  }, 1000)
  activeTicker = ticker

  const applyDelta = () => {
    answer.html = renderMarkdown(accumulated)
    answer.text = accumulated
  }

  // 冻结本次耗时：优先用后端上报的服务端耗时，缺失时退回前端墙钟时间。
  const freezeElapsed = () => {
    answer.elapsedMs = backendElapsedMs || (Date.now() - startedAt)
  }

  const processEvent = (type, event) => {
    lastEventAt = Date.now()
    seenEvent = true
    if (type === 'progress') {
      answer.progress = event.text || ''
      answer.stage = event.stage || ''
    } else if (type === 'kb') {
      const data = event || {}
      answer.kind = 'kb'
      answer.blocks = data.answer || []
      answer.suggestions = data.suggestions || []
      if (data.sourceUrl) answer.sources = [{ title: data.sourceTitle || '原文', url: data.sourceUrl }]
      backendElapsedMs = Number(data.elapsedMs) || 0
      answer.finished = true
      freezeElapsed()
      sessionHistory.push({ role: 'assistant', content: flattenBlocks(answer.blocks) })
    } else if (type === 'start') {
      aiMode = true
      answer.kind = 'ai'
    } else if (type === 'delta') {
      deltaSeen = true
      accumulated += event.text || ''
      applyDelta()
    } else if (type === 'done') {
      answer.finished = true
      answer.web = event.mode === 'ai_web'
      answer.sources = event.sources || []
      answer.learned = Boolean(event.learned)
      backendElapsedMs = Number(event.elapsedMs) || 0
      freezeElapsed()
      if (aiMode) {
        if (!accumulated.trim()) {
          accumulated = '抱歉，我暂时没能生成回答，请换个问法试试。'
          applyDelta()
        }
        if (accumulated.trim()) sessionHistory.push({ role: 'assistant', content: accumulated })
      }
    } else if (type === 'error') {
      const message = event.message || 'AI 服务出错'
      backendElapsedMs = Number(event.elapsedMs) || 0
      const friendly = ERROR_CODE_TEXT[event.code]
      throw new Error(friendly ? `${friendly}（${message}）` : message)
    }
  }

  try {
    // 传给后端的历史不包含当前问题。
    await streamChat(text, processEvent, sessionHistory.slice(0, -1), controller.signal)
  } catch (error) {
    const abortReason = pendingAbortReason
    pendingAbortReason = null
    // 主动中止（取消 / 看门狗 / 清空对话）：不再走通用错误文案。
    if (controller.signal.aborted) {
      answer.finished = true
      if (abortReason === 'cancel') {
        answer.cancelled = true
        // AI 回答无文本时由气泡的取消分支展示提示；普通气泡直接写入取消文案。
        if (!answer.text && answer.kind !== 'ai') {
          answer.kind = 'text'
          answer.text = '🚫 已取消本次回答。'
        }
        // 回滚会话历史中未得到回答的问题，保持多轮问答配对。
        if (sessionHistory.length && sessionHistory[sessionHistory.length - 1].content === text) {
          sessionHistory.pop()
        }
      } else if (abortReason === 'watchdog') {
        answer.kind = 'error'
        answer.error = true
        answer.text = '🌐 网络连接不稳定：已超过 15 秒未收到服务端响应，本次请求已停止。请检查网络后重试。'
      }
      return
    }
    answer.finished = true
    answer.kind = 'error'
    answer.error = true
    answer.text = '😥 ' + (error.message || '网络异常，请确认服务已启动')
  } finally {
    if (activeController === controller) activeController = null
    if (activeTicker === ticker) {
      clearInterval(ticker)
      activeTicker = null
    }
    // 冻结本次耗时（取消 / 看门狗 / 异常路径由这里兜底）。
    freezeElapsed()
    busy.value = false
  }
}

/** 取消进行中的回答：中止流式请求并标记气泡为已取消，后端会在检查点停止生成。 */
function cancelQuestion() {
  if (!activeController) return
  pendingAbortReason = 'cancel'
  activeController.abort()
}

/** 把知识库答案块拼接为纯文本（用于写入会话历史）。 */
function flattenBlocks(blocks) {
  return (blocks || []).map((block) => block.c || '').join('\n')
}

/** 清空对话：中止进行中的流式请求并重置会话历史。 */
function clearChat() {
  activeController?.abort()
  if (activeTicker) {
    clearInterval(activeTicker)
    activeTicker = null
  }
  busy.value = false
  sessionHistory.length = 0
  seedChat()
}

/** 处理“记下/不记”：不记直接移除提示行；记下则调知识记忆接口。 */
async function handleRemember(payload) {
  if (payload && payload.dismiss !== null && payload.dismiss !== undefined) {
    const target = messages.value[payload.dismiss]
    if (target) target.learned = false
    return
  }
  const target = messages.value.find((message) => message.id === payload.id)
  if (!target || target.remember) return

  target.remember = { state: 'saving' }
  const source = payload.source || {}
  try {
    const data = await remember({
      question: payload.question,
      answer: payload.answer,
      sourceUrl: source.url || '',
      sourceTitle: source.title || '',
    })
    target.remember = { state: data.duplicate ? 'duplicate' : 'saved' }
    if (!data.duplicate) refreshCategories()
  } catch (error) {
    target.remember = { state: 'error', message: error.message || '保存失败' }
  }
}

/** 重新加载分类（记住新知识后刷新左侧导航）。 */
async function refreshCategories() {
  try {
    const data = await getCategories()
    categories.value = data.categories || []
  } catch (error) {
    // 刷新失败不打断当前对话。
  }
}

/** 窄屏（≤1200px）默认收起术语面板，用户可随时通过顶栏按钮切换。 */
function syncTermsVisibility(event) { termsVisible.value = !event.matches }

onMounted(() => {
  const media = window.matchMedia('(max-width: 1200px)')
  termsVisible.value = !media.matches
  media.addEventListener('change', syncTermsVisibility)
  init()
})

// 组件卸载兜底：中止在途请求并清理计时器，避免内存泄漏。
onBeforeUnmount(() => {
  activeController?.abort()
  if (activeTicker) {
    clearInterval(activeTicker)
    activeTicker = null
  }
})
</script>
