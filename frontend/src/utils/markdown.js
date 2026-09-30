/**
 * 极简安全的 Markdown 渲染（对齐 Node 版 public/app.js 的 renderAiMarkdown）。
 * 先整体转义 HTML，再处理 ``` 代码块 / **加粗** / `行内代码` / 列表 / 分隔线，杜绝 XSS。
 */

/** 转义 HTML 特殊字符，防止用户或模型内容注入脚本。 */
function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** 行内标记：加粗与行内代码。 */
function renderInline(text) {
  return text
    .replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
}

/** 渲染普通文本段：拆行后处理标题、分隔线、有序/无序列表与段落。 */
function renderParagraphBlocks(paragraph) {
  const lines = paragraph.split('\n')
  let html = ''
  let openList = ''
  const flushList = () => {
    if (openList) {
      html += '</' + openList + '>'
      openList = ''
    }
  }

  for (const line of lines) {
    const trimmed = line.trim()
    if (!trimmed) {
      flushList()
      continue
    }
    if (/^-{3,}$/.test(trimmed) || /^\*{3,}$/.test(trimmed)) {
      flushList()
      html += '<div class="answer-block hr"><hr></div>'
      continue
    }
    const heading = trimmed.match(/^(#{1,4})\s+(.+)$/)
    if (heading) {
      flushList()
      html += '<div class="answer-block h">' + renderInline(heading[2]) + '</div>'
      continue
    }
    const bullet = trimmed.match(/^[-*]\s+(.+)$/)
    const ordered = trimmed.match(/^\d+\.\s+(.+)$/)
    if (bullet || ordered) {
      const tag = bullet ? 'ul' : 'ol'
      if (openList !== tag) {
        flushList()
        html += '<' + tag + '>'
        openList = tag
      }
      html += '<li>' + renderInline((bullet || ordered)[1]) + '</li>'
      continue
    }
    flushList()
    html += '<div class="answer-block text">' + renderInline(trimmed) + '</div>'
  }
  flushList()
  return html
}

/**
 * 把 AI 的 Markdown 文本渲染为安全的 HTML 片段。
 *
 * @param {string} markdown 模型输出原文
 * @return {string} 可安全用于 v-html 的 HTML
 */
export function renderMarkdown(markdown) {
  const text = escapeHtml(markdown || '')
  // 以 ``` 分割，奇数段是代码块，偶数段是普通文本。
  const parts = text.split('```')
  let html = ''
  for (let index = 0; index < parts.length; index++) {
    if (index % 2 === 1) {
      const lines = parts[index].split('\n')
      // 去掉代码块首行可能存在的语言标识（如 bash / python / json）。
      if (lines.length && /^[a-z0-9_+-]*$/i.test(lines[0].trim())) lines.shift()
      html += '<div class="answer-block code"><button type="button" class="code-copy" aria-label="复制代码">复制</button><pre class="code-lines">' + lines.join('\n') + '</pre></div>'
    } else {
      html += renderParagraphBlocks(parts[index])
    }
  }
  return html
}