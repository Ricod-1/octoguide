import { afterEach, describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import App from './App.vue'

// 用 mock 模块替代真实 fetch，专注验证组件与后端契约的对接逻辑。
vi.mock('./services/api.js', () => ({
  getCategories: vi.fn(),
  getTerms: vi.fn(),
  getStatus: vi.fn(),
  remember: vi.fn(),
  streamChat: vi.fn(),
}))

import { getCategories, getTerms, getStatus, remember, streamChat } from './services/api.js'

const mockCategories = {
  categories: [
    {
      id: 'intro', name: '认识 GitHub', icon: '📘', desc: '先搞清楚 GitHub 是什么',
      questions: [
        { id: 'intro-1', q: 'GitHub 到底是什么？', tags: ['概念'] },
        { id: 'intro-2', q: 'Git 和 GitHub 有什么区别？', tags: ['概念'] },
      ],
    },
    {
      id: 'errors', name: '常见报错', icon: '⚠️', desc: '推送失败、SSH、冲突',
      questions: [
        { id: 'errors-1', q: '推送失败怎么办？', tags: ['报错'] },
      ],
    },
  ],
  autoCount: 3,
}
const mockTerms = {
  terms: [
    { en: 'Repository', zh: '仓库', desc: '装代码的文件夹。' },
    { en: 'SSH', zh: '安全连接', desc: '免密码连接 GitHub。' },
  ],
}
const mockStatus = { aiConfigured: true, totalCount: 12, autoCount: 3, termCount: 2, aiModel: 'deepseek-v4-flash' }

function mountApp() {
  return mount(App, { global: { plugins: [ElementPlus] } })
}

function stubMatchMedia(matches) {
  vi.stubGlobal('matchMedia', vi.fn().mockImplementation((query) => ({
    matches, media: query, addEventListener: vi.fn(), removeEventListener: vi.fn(),
  })))
}

beforeEach(() => {
  vi.clearAllMocks()
  stubMatchMedia(false)
  getCategories.mockResolvedValue(mockCategories)
  getTerms.mockResolvedValue(mockTerms)
  getStatus.mockResolvedValue(mockStatus)
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('question processing transparency (P1 UX)', () => {
  /** 永不完成的流：仅在中止时拒绝，模拟长耗时生成。 */
  function pendingStreamRespectingAbort() {
    streamChat.mockImplementation((question, onEvent, history, signal) => new Promise((resolve, reject) => {
      if (signal) signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
    }))
  }

  async function submitQuestion(wrapper, text) {
    await wrapper.get('[data-testid="chat-input"]').setValue(text)
    await wrapper.get('[data-testid="send-button"]').trigger('click')
    await flushPromises()
  }

  it('tracks stage steps as progress events arrive', async () => {
    let emit
    streamChat.mockImplementation(async (question, onEvent) => {
      emit = onEvent
      return new Promise(() => {})
    })
    const wrapper = mountApp()
    await flushPromises()
    await submitQuestion(wrapper, '今天有什么热门仓库？')

    emit('progress', { type: 'progress', stage: 'planning', text: '🔍 正在确定要查哪些页面…' })
    await flushPromises()
    expect(wrapper.text()).toContain('分析问题')

    emit('progress', { type: 'progress', stage: 'fetching', text: '📥 正在抓取 github.com/trending…' })
    await flushPromises()
    const steps = wrapper.findAll('.stage-step')
    expect(steps).toHaveLength(3)
    expect(steps[1].classes()).toContain('active')
    expect(wrapper.text()).toContain('正在抓取 github.com/trending')
  })

  it('shows elapsed seconds while waiting for the answer', async () => {
    vi.useFakeTimers()
    pendingStreamRespectingAbort()
    const wrapper = mountApp()
    await flushPromises()
    await submitQuestion(wrapper, '测试问题')

    vi.advanceTimersByTime(2000)
    await flushPromises()
    expect(wrapper.text()).toContain('已用时 2s')
    vi.useRealTimers()
  })

  it('shows the backend-reported duration on the finished bubble', async () => {
    let emit
    streamChat.mockImplementation(async (question, onEvent) => {
      emit = onEvent
      return new Promise(() => {})
    })
    const wrapper = mountApp()
    await flushPromises()
    await submitQuestion(wrapper, '什么是 PR？')

    emit('start', { type: 'start', mode: 'ai' })
    emit('delta', { type: 'delta', text: '回答' })
    emit('done', { type: 'done', mode: 'ai', sources: [], learned: false, elapsedMs: 2425 })
    await flushPromises()

    expect(wrapper.text()).toContain('本次回答耗时 2.4 秒')
  })

  it('cancels the in-flight answer and marks the bubble as cancelled', async () => {
    let capturedSignal
    streamChat.mockImplementation((question, onEvent, history, signal) => {
      capturedSignal = signal
      return new Promise((resolve, reject) => {
        if (signal) signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
      })
    })
    const wrapper = mountApp()
    await flushPromises()
    await submitQuestion(wrapper, '测试问题')
    expect(capturedSignal.aborted).toBe(false)

    await wrapper.get('[data-testid="cancel-answer"]').trigger('click')
    await flushPromises()

    expect(capturedSignal.aborted).toBe(true)
    expect(wrapper.text()).toContain('已取消本次回答')
    // 取消后输入应立即可用，按钮恢复“发送”。
    expect(wrapper.get('[data-testid="send-button"]').text()).toContain('发送')
    expect(wrapper.get('[data-testid="chat-input"]').attributes('disabled')).toBeUndefined()
  })

  it('aborts with a network error when no event arrives within the watchdog window', async () => {
    vi.useFakeTimers()
    let capturedSignal
    streamChat.mockImplementation((question, onEvent, history, signal) => {
      capturedSignal = signal
      return new Promise((resolve, reject) => {
        if (signal) signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
      })
    })
    const wrapper = mountApp()
    await flushPromises()
    await submitQuestion(wrapper, '测试问题')

    vi.advanceTimersByTime(16000)
    await flushPromises()

    expect(capturedSignal.aborted).toBe(true)
    expect(wrapper.text()).toContain('网络连接不稳定')
    vi.useRealTimers()
  })

  it('keeps waiting while backend progress events keep the connection alive', async () => {
    vi.useFakeTimers()
    let emit
    streamChat.mockImplementation((question, onEvent, history, signal) => {
      emit = onEvent
      return new Promise((resolve, reject) => {
        if (signal) signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
      })
    })
    const wrapper = mountApp()
    await flushPromises()
    await submitQuestion(wrapper, '今天有什么热门仓库？')

    emit('progress', { type: 'progress', stage: 'fetching', text: '📥 正在抓取 github.com/trending…' })
    await flushPromises()
    // 联网检索阶段单页抓取可能超过 15s：不应被看门狗误判为网络故障。
    vi.advanceTimersByTime(20000)
    await flushPromises()
    expect(wrapper.text()).not.toContain('网络连接不稳定')

    // 但长时间彻底静默（60s）仍应中止。
    vi.advanceTimersByTime(42000)
    await flushPromises()
    expect(wrapper.text()).toContain('网络连接不稳定')
    vi.useRealTimers()
  })

  it('aborts quickly when the delta stream stalls mid-generation', async () => {
    vi.useFakeTimers()
    let emit
    streamChat.mockImplementation((question, onEvent, history, signal) => {
      emit = onEvent
      return new Promise((resolve, reject) => {
        if (signal) signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
      })
    })
    const wrapper = mountApp()
    await flushPromises()
    await submitQuestion(wrapper, '什么是分支？')

    emit('start', { type: 'start', mode: 'ai' })
    emit('delta', { type: 'delta', text: '部分回答' })
    await flushPromises()
    vi.advanceTimersByTime(16000)
    await flushPromises()

    expect(wrapper.text()).toContain('网络连接不稳定')
    vi.useRealTimers()
  })

  it('maps the ai_service error code to a friendly message with detail', async () => {
    streamChat.mockImplementation(async (question, onEvent) => {
      onEvent('error', { type: 'error', code: 'ai_service', message: 'AI 调用失败：503: upstream down' })
    })
    const wrapper = mountApp()
    await flushPromises()
    await submitQuestion(wrapper, '什么是分支？')

    expect(wrapper.text()).toContain('AI 服务暂时不可用')
    expect(wrapper.text()).toContain('AI 调用失败：503: upstream down')
  })
})

describe('knowledge statistics consistency (P1 UX)', () => {
  it('unifies terminology to 分类 with split auto-collected counts in the footbar', async () => {
    getStatus.mockResolvedValue({ ...mockStatus, manualCategoryCount: 7, autoCategoryCount: 3 })
    const wrapper = mountApp()
    await flushPromises()

    expect(wrapper.text()).toContain('7 个分类（另自动采集 3 类）')
    expect(wrapper.text()).toContain('12 条问答（自动收录 3）')
    expect(wrapper.text()).not.toContain('模块')
  })

  it('derives category counts from sidebar data when status lacks them', async () => {
    getStatus.mockResolvedValue({ ...mockStatus })
    const wrapper = mountApp()
    await flushPromises()

    // mockCategories 有 2 个人工分类、0 个自动采集分类
    expect(wrapper.text()).toContain('2 个分类')
    expect(wrapper.text()).not.toContain('另自动采集')
  })
})

describe('first screen hierarchy and help dialog (P2 UX)', () => {
  it('leads the welcome message with user value instead of update mechanics', async () => {
    getStatus.mockResolvedValue({ ...mockStatus, schedule: '每周日 18:00 自动更新' })
    const wrapper = mountApp()
    await flushPromises()

    expect(wrapper.text()).toContain('我能帮你')
    expect(wrapper.text()).toContain('报错')
    // 更新机制等后台细节不再占据首屏（仅出现在帮助弹窗中）
    expect(wrapper.text()).not.toContain('每周日 18:00')
    expect(wrapper.text()).not.toContain('已自动收录')
  })

  it('opens a help dialog consuming the backend schedule and ai status', async () => {
    getStatus.mockResolvedValue({ ...mockStatus, schedule: '每周日 18:00 自动更新', aiModel: 'deepseek-v4-flash' })
    const wrapper = mountApp()
    await flushPromises()

    await wrapper.get('[data-testid="help-toggle"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('OctoGuide 能帮你做什么')
    expect(wrapper.text()).toContain('每周日 18:00 自动更新')
    expect(wrapper.text()).toContain('当前模型：deepseek-v4-flash')
  })

  it('explains local-only mode in the help dialog when ai is not configured', async () => {
    getStatus.mockResolvedValue({ ...mockStatus, aiConfigured: false, schedule: '每周日 18:00 自动更新' })
    const wrapper = mountApp()
    await flushPromises()

    await wrapper.get('[data-testid="help-toggle"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('本地知识库模式')
    expect(wrapper.text()).toContain('OCTOGUIDE_LLM_KEY')
  })
})

describe('OctoGuide real API shell', () => {
  it('loads categories, terms and status from the backend on mount', async () => {
    const wrapper = mountApp()
    await flushPromises()

    expect(getCategories).toHaveBeenCalled()
    expect(getTerms).toHaveBeenCalled()
    expect(getStatus).toHaveBeenCalled()
    expect(wrapper.text()).toContain('认识 GitHub')
    expect(wrapper.text()).toContain('Repository')
    expect(wrapper.text()).toContain('AI 已接入')
  })

  it('streams AI deltas and renders sources on done', async () => {
    streamChat.mockImplementation(async (question, onEvent) => {
      onEvent('start', { type: 'start', mode: 'ai' })
      onEvent('delta', { type: 'delta', text: '你好，我是' })
      onEvent('delta', { type: 'delta', text: 'AI。' })
      onEvent('done', { type: 'done', mode: 'ai', sources: [{ title: 'GitHub Docs', url: 'https://docs.github.com' }], learned: false })
    })
    const wrapper = mountApp()
    await flushPromises()

    await wrapper.get('[data-testid="chat-input"]').setValue('什么是 PR？')
    await wrapper.get('[data-testid="send-button"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('你好，我是AI。')
    expect(wrapper.text()).toContain('GitHub Docs')
  })

  it('shows remember controls for learned web answers and saves on confirm', async () => {
    streamChat.mockImplementation(async (question, onEvent) => {
      onEvent('start', { type: 'start', mode: 'ai_web' })
      onEvent('progress', { type: 'progress', stage: 'planning', text: '🔍 正在确定要查哪些页面…' })
      onEvent('delta', { type: 'delta', text: '今日热门仓库' })
      onEvent('done', { type: 'done', mode: 'ai_web', sources: [{ title: 'Trending', url: 'https://github.com/trending' }], learned: true })
    })
    remember.mockResolvedValue({ ok: true, duplicate: false, id: 'learned-1' })
    const wrapper = mountApp()
    await flushPromises()

    await wrapper.get('[data-testid="chat-input"]').setValue('GitHub 今天有什么热门仓库？')
    await wrapper.get('[data-testid="send-button"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('这条要记进知识库吗？')

    const rememberButton = wrapper.findAll('button').find((button) => button.text() === '记下')
    expect(rememberButton).toBeTruthy()
    await rememberButton.trigger('click')
    await flushPromises()

    expect(remember).toHaveBeenCalledWith(expect.objectContaining({ question: 'GitHub 今天有什么热门仓库？' }))
    expect(wrapper.text()).toContain('已记入知识库')
  })

  it('shows progress while answering and hides it once finished', async () => {
    let finish
    const gate = new Promise((resolve) => { finish = resolve })
    streamChat.mockImplementation(async (question, onEvent) => {
      onEvent('progress', { type: 'progress', stage: 'planning', text: '🔍 正在确定要查哪些页面…' })
      await gate
      onEvent('start', { type: 'start', mode: 'ai_web' })
      onEvent('delta', { type: 'delta', text: '今日热门仓库' })
      onEvent('done', { type: 'done', mode: 'ai_web', sources: [], learned: true })
    })
    const wrapper = mountApp()
    await flushPromises()

    await wrapper.get('[data-testid="chat-input"]').setValue('今天有什么热门仓库？')
    await wrapper.get('[data-testid="send-button"]').trigger('click')
    expect(wrapper.text()).toContain('正在确定要查哪些页面')

    finish()
    await flushPromises()
    expect(wrapper.text()).not.toContain('正在确定要查哪些页面')
  })

  it('shows the AI status badge only once in the topbar', async () => {
    const wrapper = mountApp()
    await flushPromises()

    expect(wrapper.text()).toContain('AI 已接入')
    expect(wrapper.text()).not.toContain('AI 智能回答已接入')
  })

  it('aborts the in-flight stream when clearing mid-answer', async () => {
    let capturedSignal
    streamChat.mockImplementation(async (question, onEvent, history, signal) => {
      capturedSignal = signal
      return new Promise(() => {})
    })
    const wrapper = mountApp()
    await flushPromises()

    await wrapper.get('[data-testid="chat-input"]').setValue('测试问题')
    await wrapper.get('[data-testid="send-button"]').trigger('click')
    await flushPromises()
    expect(capturedSignal).toBeTruthy()

    await wrapper.get('[data-testid="clear-chat"]').trigger('click')
    await flushPromises()

    expect(capturedSignal.aborted).toBe(true)
    expect(wrapper.text()).toContain('你好呀')
    expect(wrapper.get('[data-testid="chat-input"]').attributes('disabled')).toBeUndefined()
  })

  it('caps the history sent to the backend', async () => {
    const histories = []
    streamChat.mockImplementation(async (question, onEvent, history) => {
      histories.push([...history])
      onEvent('kb', { answer: [], suggestions: [] })
    })
    const wrapper = mountApp()
    await flushPromises()

    for (let index = 0; index < 8; index++) {
      await wrapper.get('[data-testid="chat-input"]').setValue(`问题${index}`)
      await wrapper.get('[data-testid="send-button"]').trigger('click')
      await flushPromises()
    }

    expect(histories).toHaveLength(8)
    expect(histories[7].length).toBeLessThanOrEqual(10)
  })

  it('toggles the terms panel from the topbar', async () => {
    const wrapper = mountApp()
    await flushPromises()

    const main = wrapper.find('.workspace-main')
    expect(main.classes()).not.toContain('terms-collapsed')

    await wrapper.get('[data-testid="terms-toggle"]').trigger('click')
    expect(main.classes()).toContain('terms-collapsed')
  })

  it('collapses the terms panel by default on narrow screens', async () => {
    stubMatchMedia(true)
    const wrapper = mountApp()
    await flushPromises()

    expect(wrapper.find('.workspace-main').classes()).toContain('terms-collapsed')
  })

  it('shows a retry option when the backend is unreachable', async () => {
    getStatus.mockRejectedValue(new Error('服务未启动'))
    const wrapper = mountApp()
    await flushPromises()

    expect(wrapper.text()).toContain('连不上知识库')
    expect(wrapper.find('[data-testid="retry-connect"]').exists()).toBe(true)
  })

  it('reconnects when the retry button is clicked', async () => {
    getStatus.mockRejectedValueOnce(new Error('服务未启动'))
    const wrapper = mountApp()
    await flushPromises()

    await wrapper.get('[data-testid="retry-connect"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('认识 GitHub')
  })

  it('clears the conversation and resets history', async () => {
    streamChat.mockResolvedValue()
    const wrapper = mountApp()
    await flushPromises()

    await wrapper.get('[data-testid="chat-input"]').setValue('测试问题')
    await wrapper.get('[data-testid="send-button"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('测试问题')

    await wrapper.get('[data-testid="clear-chat"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('测试问题')
  })

  it('renders the error event message from the backend chat stream', async () => {
    // 后端未配置 AI 且知识库弱命中时返回 error 事件，前端应把错误提示渲染出来。
    streamChat.mockImplementation(async (question, onEvent) => {
      onEvent('error', { type: 'error', message: 'AI 未配置：请设置环境变量 OCTOGUIDE_LLM_KEY' })
    })
    const wrapper = mountApp()
    await flushPromises()

    await wrapper.get('[data-testid="chat-input"]').setValue('什么是分支？')
    await wrapper.get('[data-testid="send-button"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('AI 未配置：请设置环境变量 OCTOGUIDE_LLM_KEY')
  })
})
