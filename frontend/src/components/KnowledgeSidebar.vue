<template>
  <el-aside class="knowledge-sidebar" width="280px" aria-label="知识库导航">
    <div class="sidebar-heading">
      <div><div class="panel-kicker">知识库</div><div class="panel-count" data-testid="sidebar-count">{{ total }} 条问答</div></div>
      <el-button text type="primary" size="small" @click="toggleAll">{{ allOpen ? '全部收起' : '全部展开' }}</el-button>
    </div>
    <el-input v-model="keyword" data-testid="knowledge-search" clearable placeholder="搜索问题，如：PR / 上传 / 冲突…" class="sidebar-search"><template #prefix>⌕</template></el-input>
    <div class="sidebar-scroll">
      <el-empty v-if="!ordered.length" description="没有匹配的问题" :image-size="68" />
      <el-collapse v-else v-model="opened" class="knowledge-collapse">
        <template v-for="(category, index) in ordered" :key="category.id">
          <div v-if="category.auto && (index === 0 || !ordered[index - 1].auto)" class="auto-divider" data-testid="auto-divider">自动采集</div>
          <el-collapse-item :name="category.id" :class="{ 'auto-category': category.auto }">
            <template #title>
              <span class="category-icon">{{ category.icon }}</span>
              <span class="category-title-wrap"><span class="category-index">{{ String(index + 1).padStart(2, '0') }}</span><strong>{{ category.name }}</strong><small>{{ category.desc }}</small></span>
              <el-tag size="small" effect="plain" round>{{ category.questions.length }} 问</el-tag>
            </template>
            <div class="question-list">
              <el-button v-for="question in category.questions" :key="question.id" data-testid="question-item" text class="question-button" @click="$emit('ask', question.q)">
                <span class="question-dot" /><span class="question-text">{{ question.q }}</span><el-tag v-if="question.tags?.length" size="small" effect="plain">{{ question.tags[0] }}</el-tag>
              </el-button>
            </div>
          </el-collapse-item>
        </template>
      </el-collapse>
    </div>
  </el-aside>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({ categories: { type: Array, default: () => [] } })
defineEmits(['ask'])
const keyword = ref('')
const allOpen = ref(false)
const opened = ref([])
// 首次加载后默认展开第一个分类；用户手动收起后不再强制展开。
let seededOpen = false
const total = computed(() => props.categories.reduce((sum, category) => sum + category.questions.length, 0))
const filtered = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  return props.categories.map((category) => ({ ...category, questions: category.questions.filter((question) => !query || `${category.name} ${question.q} ${(question.tags || []).join(' ')}`.toLowerCase().includes(query)) })).filter((category) => category.questions.length)
})
// 展示顺序：人工分类在前，自动采集分类在后并以分隔线区分（统计口径与底栏一致）。
const ordered = computed(() => [
  ...filtered.value.filter((category) => !category.auto),
  ...filtered.value.filter((category) => category.auto),
])
function toggleAll() { allOpen.value = !allOpen.value; opened.value = allOpen.value ? ordered.value.map((category) => category.id) : [] }
watch(() => props.categories, (categories) => {
  if (allOpen.value) {
    opened.value = ordered.value.map((category) => category.id)
  } else if (!seededOpen && categories.length) {
    opened.value = [categories[0].id]
    seededOpen = true
  }
}, { immediate: true })
watch(keyword, (query) => {
  if (query.trim()) opened.value = ordered.value.map((category) => category.id)
})
</script>
