import { describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import KnowledgeSidebar from './KnowledgeSidebar.vue'

const categories = [
  { id: 'intro', name: '认识 GitHub', icon: '🐙', desc: '入门', questions: [{ id: 'q1', q: 'GitHub 到底是什么？' }] },
  { id: 'repo', name: '仓库操作', icon: '📦', desc: '仓库', questions: [{ id: 'q2', q: '怎么创建仓库？' }] },
]

describe('KnowledgeSidebar statistics and grouping (P1 UX)', () => {
  it('groups auto-collected categories behind a divider after manual ones', async () => {
    const withAuto = [
      ...categories,
      { id: 'trending', name: '趋势仓库', icon: '🔥', desc: '自动采集', auto: true, questions: [{ id: 'q3', q: '今日热门仓库' }] },
      { id: 'changelog', name: '新功能动态', icon: '📣', desc: '自动采集', auto: true, questions: [{ id: 'q4', q: '最近新功能' }] },
    ]
    const wrapper = mount(KnowledgeSidebar, { props: { categories: withAuto }, global: { plugins: [ElementPlus] } })
    await nextTick()

    expect(wrapper.get('[data-testid="auto-divider"]').text()).toBe('自动采集')
    const items = wrapper.findAll('.el-collapse-item')
    expect(items).toHaveLength(4)
    // 人工分类在前，自动采集分类在后
    expect(items[0].text()).toContain('认识 GitHub')
    expect(items[3].text()).toContain('新功能动态')
    expect(items[3].classes()).toContain('auto-category')
  })

  it('counts all questions including auto ones in the header', async () => {
    const withAuto = [
      ...categories,
      { id: 'trending', name: '趋势仓库', icon: '🔥', desc: '自动采集', auto: true, questions: [{ id: 'q3', q: '今日热门仓库' }] },
    ]
    const wrapper = mount(KnowledgeSidebar, { props: { categories: withAuto }, global: { plugins: [ElementPlus] } })
    await nextTick()

    expect(wrapper.get('[data-testid="sidebar-count"]').text()).toBe('3 条问答')
  })
})

describe('KnowledgeSidebar default expansion', () => {
  it('opens the first category once knowledge loads', async () => {
    const wrapper = mount(KnowledgeSidebar, { props: { categories: [] }, global: { plugins: [ElementPlus] } })
    expect(wrapper.findAll('.el-collapse-item.is-active')).toHaveLength(0)

    await wrapper.setProps({ categories })
    await nextTick()

    expect(wrapper.findAll('.el-collapse-item.is-active')).toHaveLength(1)
    expect(wrapper.text()).toContain('GitHub 到底是什么？')
  })

  it('keeps categories closed after the user collapses them', async () => {
    const wrapper = mount(KnowledgeSidebar, { props: { categories }, global: { plugins: [ElementPlus] } })
    await nextTick()
    expect(wrapper.findAll('.el-collapse-item.is-active')).toHaveLength(1)

    await wrapper.get('.el-collapse-item__header').trigger('click')
    await nextTick()
    expect(wrapper.findAll('.el-collapse-item.is-active')).toHaveLength(0)

    await wrapper.setProps({
      categories: [...categories, { id: 'extra', name: '新分类', icon: '✨', desc: '', questions: [{ id: 'q3', q: '新问题' }] }],
    })
    await nextTick()
    expect(wrapper.findAll('.el-collapse-item.is-active')).toHaveLength(0)
  })
})