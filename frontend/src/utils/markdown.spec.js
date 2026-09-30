import { describe, expect, it } from 'vitest'
import { renderMarkdown } from './markdown.js'

describe('renderMarkdown', () => {
  it('escapes raw HTML to prevent injection', () => {
    expect(renderMarkdown('<script>alert(1)</script>')).not.toContain('<script>')
  })

  it('renders fenced code blocks with a copy button and pre wrapper', () => {
    const html = renderMarkdown('```bash\ngit push -u origin main\n```')
    expect(html).toContain('answer-block code')
    expect(html).toContain('class="code-copy"')
    expect(html).toContain('code-lines')
    expect(html).toContain('git push -u origin main')
  })

  it('renders unordered and ordered lists', () => {
    const ul = renderMarkdown('- 第一步\n- 第二步')
    expect(ul).toContain('<ul')
    expect(ul).toContain('<li>第一步</li>')

    const ol = renderMarkdown('1. 打开页面\n2. 点击按钮')
    expect(ol).toContain('<ol')
    expect(ol).toContain('<li>打开页面</li>')
  })

  it('renders --- as a horizontal separator', () => {
    expect(renderMarkdown('上文\n---\n下文')).toContain('<hr')
  })

  it('renders headings as emphasized blocks', () => {
    expect(renderMarkdown('### 操作步骤')).toContain('answer-block h')
    expect(renderMarkdown('### 操作步骤')).toContain('操作步骤')
  })
})