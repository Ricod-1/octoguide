import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ChatPanel from './ChatPanel.vue'

function baseMessage(overrides = {}) {
  return {
    id: 1, role: 'bot', kind: 'text', error: false, text: '', html: '', progress: '',
    finished: false, web: false, sources: [], learned: false, question: '',
    remember: null, blocks: [], suggestions: [], ...overrides,
  }
}

describe('ChatPanel waiting feedback and send button states (P0 UX)', () => {
  it('shows a text hint alongside the typing dots while waiting for a pending answer', () => {
    const wrapper = mount(ChatPanel, {
      props: { messages: [baseMessage()], aiConfigured: false, busy: true },
      global: { plugins: [ElementPlus] },
    })
    expect(wrapper.find('.typing-dots').exists()).toBe(true)
    expect(wrapper.find('.pending-label').text()).toBe('正在生成回答…')
  })

  it('shows the waiting hint for ai-kind pending answers as well', () => {
    const wrapper = mount(ChatPanel, {
      props: { messages: [baseMessage({ kind: 'ai' })], aiConfigured: false, busy: true },
      global: { plugins: [ElementPlus] },
    })
    expect(wrapper.find('.typing-dots').exists()).toBe(true)
    expect(wrapper.find('.pending-label').text()).toBe('正在生成回答…')
  })

  it('renames the send button to 生成中 and disables it while busy', () => {
    const wrapper = mount(ChatPanel, {
      props: { messages: [baseMessage()], aiConfigured: false, busy: true },
      global: { plugins: [ElementPlus] },
    })
    const button = wrapper.get('[data-testid="send-button"]')
    expect(button.text()).toContain('生成中')
    expect(button.attributes('disabled')).toBeDefined()
  })

  it('keeps the send button labelled 发送 and clickable when idle', async () => {
    const wrapper = mount(ChatPanel, {
      props: { messages: [baseMessage()], aiConfigured: false, busy: false },
      global: { plugins: [ElementPlus] },
    })
    const button = wrapper.get('[data-testid="send-button"]')
    expect(button.text()).toContain('发送')

    // 输入内容后按钮应恢复可用并可提交。
    await wrapper.get('[data-testid="chat-input"]').setValue('什么是 PR？')
    expect(button.attributes('disabled')).toBeUndefined()
    await button.trigger('click')
    expect(wrapper.emitted('ask')).toHaveLength(1)
    expect(wrapper.emitted('ask')[0]).toEqual(['什么是 PR？'])
  })

  it('does not emit ask when pressing enter while busy', async () => {
    const wrapper = mount(ChatPanel, {
      props: { messages: [baseMessage()], aiConfigured: false, busy: true },
      global: { plugins: [ElementPlus] },
    })
    await wrapper.get('[data-testid="chat-input"]').setValue('重复提问')
    await wrapper.get('[data-testid="chat-input"]').trigger('keyup.enter')
    expect(wrapper.emitted('ask')).toBeUndefined()
  })
})

describe('ChatPanel composer guidance (P0 UX)', () => {
  it('shows troubleshooting guidance in the composer hint', () => {
    const wrapper = mount(ChatPanel, {
      props: { messages: [baseMessage()], aiConfigured: false, busy: false },
      global: { plugins: [ElementPlus] },
    })
    const hint = wrapper.get('.composer-hint')
    expect(hint.text()).toContain('报错原文')
    expect(hint.text()).toContain('操作步骤')
    expect(hint.text()).toContain('期望结果')
  })
})

describe('ChatPanel processing transparency (P1 UX)', () => {
  function mountProcessing(overrides = {}) {
    return mount(ChatPanel, {
      props: {
        messages: [baseMessage({ question: 'GitHub 今天有什么热门仓库？', ...overrides })],
        aiConfigured: true,
        busy: true,
      },
      global: { plugins: [ElementPlus] },
    })
  }

  it('renders three stage steps with fetching active and planning done', () => {
    const wrapper = mountProcessing({ stage: 'fetching', progress: '📥 正在抓取 github.com/trending…' })
    const steps = wrapper.findAll('.stage-step')
    expect(steps).toHaveLength(3)
    expect(steps[0].classes()).toContain('done')
    expect(steps[1].classes()).toContain('active')
    expect(steps[2].classes()).not.toContain('active')
    expect(wrapper.text()).toContain('正在抓取 github.com/trending')
  })

  it('marks the final step active for reasoning stage', () => {
    const wrapper = mountProcessing({ stage: 'reasoning', progress: '🧠 正在组织回答…' })
    const steps = wrapper.findAll('.stage-step')
    expect(steps[0].classes()).toContain('done')
    expect(steps[1].classes()).toContain('done')
    expect(steps[2].classes()).toContain('active')
  })

  it('styles the fallback stage as a warning', () => {
    const wrapper = mountProcessing({ stage: 'fallback', progress: '⚠️ 联网查询未果，先用已有知识回答…' })
    expect(wrapper.find('.answer-block.progress.warning').exists()).toBe(true)
  })

  it('shows elapsed seconds and a cancel button while processing', () => {
    const wrapper = mountProcessing({ stage: 'planning', elapsed: 12 })
    expect(wrapper.text()).toContain('已用时 12s')
    expect(wrapper.find('[data-testid="cancel-answer"]').exists()).toBe(true)
  })

  it('emits cancel when the cancel button is clicked', async () => {
    const wrapper = mountProcessing({ stage: 'planning' })
    await wrapper.get('[data-testid="cancel-answer"]').trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })

  it('hides the processing panel once the answer is finished', () => {
    const wrapper = mountProcessing({ stage: 'reasoning', finished: true, text: '回答完成', html: '<p>回答完成</p>' })
    expect(wrapper.find('.stage-step').exists()).toBe(false)
    expect(wrapper.find('[data-testid="cancel-answer"]').exists()).toBe(false)
  })

  it('shows the answer duration on the finished bubble', () => {
    const wrapper = mountProcessing({ kind: 'ai', finished: true, text: '回答', html: '<p>回答</p>', elapsedMs: 2425 })
    const elapsed = wrapper.get('[data-testid="answer-elapsed"]')
    expect(elapsed.text()).toContain('本次回答耗时 2.4 秒')
  })

  it('formats durations over one minute as minutes and seconds', () => {
    const wrapper = mountProcessing({ kind: 'ai', finished: true, text: '回答', html: '<p>回答</p>', elapsedMs: 65000 })
    expect(wrapper.get('[data-testid="answer-elapsed"]').text()).toContain('1 分 05 秒')
  })

  it('hides the progress text once the answer text starts streaming', () => {
    const wrapper = mountProcessing({
      kind: 'ai', stage: 'reasoning', progress: '🧠 正在思考…', text: '回答开头', html: '<p>回答开头</p>',
    })
    // 步骤条仍显示当前阶段，但进度文字在正文出现后隐藏，避免与正文内容重复。
    expect(wrapper.findAll('.stage-step')).toHaveLength(3)
    expect(wrapper.text()).not.toContain('正在思考')
    expect(wrapper.text()).toContain('回答开头')
  })

  it('shows a cancelled notice while keeping partial ai text', () => {
    const wrapper = mountProcessing({
      kind: 'ai', finished: true, cancelled: true,
      text: '部分回答内容', html: '<p>部分回答内容</p>',
    })
    expect(wrapper.text()).toContain('部分回答内容')
    expect(wrapper.text()).toContain('已取消本次回答')
  })

  it('shows a plain cancelled notice when cancelled before any text arrived', () => {
    const wrapper = mountProcessing({ kind: 'ai', finished: true, cancelled: true })
    expect(wrapper.text()).toContain('已取消本次回答')
    expect(wrapper.text()).not.toContain('抱歉，我暂时没能生成回答')
  })
})

describe('ChatPanel error message rendering', () => {
  it('renders a message whose kind=error from the start', () => {
    const wrapper = mount(ChatPanel, {
      props: {
        messages: [baseMessage({ kind: 'error', error: true, text: '😥 AI 未配置：请设置环境变量 OCTOGUIDE_LLM_KEY' })],
        aiConfigured: false,
        busy: false,
      },
      global: { plugins: [ElementPlus] },
    })
    expect(wrapper.text()).toContain('AI 未配置：请设置环境变量 OCTOGUIDE_LLM_KEY')
  })

  it('re-renders when kind is mutated from text to error via the reactive array', async () => {
    // 通过响应式数组（而非 push 前的原始对象）变更，模拟 App.vue 的错误路径。
    const messages = ref([baseMessage()])
    const wrapper = mount(ChatPanel, {
      props: { messages: messages.value, aiConfigured: false, busy: false },
      global: { plugins: [ElementPlus] },
    })
    const target = messages.value[messages.value.length - 1]
    target.kind = 'error'
    target.error = true
    target.text = '😥 AI 未配置：请设置环境变量 OCTOGUIDE_LLM_KEY'
    await nextTick()
    expect(wrapper.text()).toContain('AI 未配置：请设置环境变量 OCTOGUIDE_LLM_KEY')
  })

  it('shows a waiting indicator for a pending message without content', () => {
    const wrapper = mount(ChatPanel, {
      props: { messages: [baseMessage()], aiConfigured: false, busy: true },
      global: { plugins: [ElementPlus] },
    })
    expect(wrapper.find('.typing-dots').exists()).toBe(true)
  })

  it('shows progress text while an answer is still being generated', () => {
    const wrapper = mount(ChatPanel, {
      props: {
        // 真实流程中回答气泡总是携带 question（App.vue askQuestion 在提交时写入）。
        messages: [baseMessage({ question: '今天有什么热门仓库？', progress: '🔍 正在确定要查哪些页面…' })],
        aiConfigured: false,
        busy: true,
      },
      global: { plugins: [ElementPlus] },
    })
    expect(wrapper.text()).toContain('正在确定要查哪些页面')
  })

  it('hides progress once the answer is finished', () => {
    const wrapper = mount(ChatPanel, {
      props: {
        messages: [baseMessage({ kind: 'ai', finished: true, text: '最终答案。', progress: '🔍 正在确定要查哪些页面…' })],
        aiConfigured: false,
        busy: false,
      },
      global: { plugins: [ElementPlus] },
    })
    expect(wrapper.text()).not.toContain('正在确定要查哪些页面')
  })

  it('scrolls to the bottom as streamed text grows', async () => {
    const messages = ref([baseMessage({ kind: 'ai' })])
    const wrapper = mount(ChatPanel, {
      props: { messages: messages.value, aiConfigured: false, busy: true },
      global: { plugins: [ElementPlus] },
    })
    const setScrollTop = vi.fn()
    wrapper.vm.scrollRef = { wrapRef: { scrollHeight: 640 }, setScrollTop }

    messages.value[0].text = '第一段内容'
    messages.value[0].html = '<div>第一段内容</div>'
    await nextTick()
    await flushPromises()

    expect(setScrollTop).toHaveBeenCalledWith(640)
  })

  it('copies code block content via the copy button', async () => {
    vi.useFakeTimers()
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(window.navigator, 'clipboard', { value: { writeText }, configurable: true })

    const wrapper = mount(ChatPanel, {
      props: {
        messages: [baseMessage({ kind: 'kb', finished: true, blocks: [{ t: 'code', c: 'git push -u origin main' }] })],
        aiConfigured: false,
        busy: false,
      },
      global: { plugins: [ElementPlus] },
    })
    const copyButton = wrapper.find('.code-copy')
    expect(copyButton.exists()).toBe(true)

    await copyButton.trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith('git push -u origin main')
    expect(wrapper.find('.code-copy').text()).toBe('已复制')

    vi.advanceTimersByTime(1500)
    await nextTick()
    expect(wrapper.find('.code-copy').text()).toBe('复制')
    vi.useRealTimers()
  })

  it('emits retry when the reconnection button is clicked', async () => {
    const wrapper = mount(ChatPanel, {
      props: {
        messages: [baseMessage({ error: true, offerRetry: true, text: '😥 连不上知识库服务' })],
        aiConfigured: false,
        busy: false,
      },
      global: { plugins: [ElementPlus] },
    })
    await wrapper.get('[data-testid="retry-connect"]').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })
})

afterEach(() => {
  vi.useRealTimers()
})
