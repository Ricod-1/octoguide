/** OctoGuide 前端 API 封装：所有请求都经由 Vite 代理转发到后端 8080。 */

/** 请求一个 JSON 接口，非 2xx 时抛出带响应正文的错误。 */
export async function getJson(path, options) {
  const res = await fetch(path, options)
  if (!res.ok) throw new Error((await res.text()) || `HTTP ${res.status}`)
  return res.json()
}

/** 读取左侧分类（含自动知识数量）。 */
export function getCategories() { return getJson('/api/categories') }

/** 读取右侧术语表。 */
export function getTerms() { return getJson('/api/terms') }

/** 读取系统状态（知识库统计 / AI 是否接入）。 */
export function getStatus() { return getJson('/api/status') }

/** 同步知识库问答（非 AI 模式）。 */
export function ask(question) { return getJson('/api/ask', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ question }) }) }

/** 用户确认后把问答沉淀为知识条目。 */
export async function remember(payload) { return getJson('/api/remember', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) }) }

/**
 * 流式聊天：逐行读取 NDJSON 事件并回调 onEvent(type, event)。
 * 同时兼容旧版 SSE（event:/data:）格式，便于本地升级过渡。
 * signal 用于“清空对话”时主动中止请求。
 */
export async function streamChat(question, onEvent, history = [], signal) {
  const response = await fetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question, history }),
    signal,
  })
  if (!response.ok || !response.body) throw new Error('聊天服务不可用')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const consume = (line) => {
    const text = line.trim()
    if (!text) return
    // 主格式：每行一个 JSON 事件。
    if (text.startsWith('{')) {
      const event = JSON.parse(text)
      onEvent(event.type || '', event.type === 'kb' ? event.data : event)
      return
    }
    // 兼容旧版 SSE 事件格式。
    if (text.startsWith('event:')) buffer = text.slice(6).trim()
    else if (text.startsWith('data:')) {
      const event = JSON.parse(text.slice(5).trim())
      onEvent(event.type || buffer, event)
      buffer = ''
    }
  }
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split(/\r?\n/)
    buffer = lines.pop() || ''
    lines.forEach(consume)
  }
  buffer += decoder.decode()
  if (buffer.trim()) consume(buffer)
}
