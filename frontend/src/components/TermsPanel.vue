<template>
  <el-aside class="terms-panel" width="300px" aria-label="术语表">
    <div class="terms-heading"><div class="panel-kicker">术语速查</div><div class="terms-description">GitHub 常用英文词汇 · 中英对照</div></div>
    <el-input v-model="keyword" data-testid="terms-search" clearable placeholder="搜索术语，如：branch / 分支…" class="terms-search"><template #prefix>⌕</template></el-input>
    <el-scrollbar class="terms-scroll"><el-empty v-if="!filtered.length" description="没有匹配的术语" :image-size="68" /><template v-else><el-card v-for="term in filtered" :key="term.en" class="term-card" shadow="never"><div class="term-title"><span class="term-en">{{ term.en }}</span><el-tag type="success" size="small" effect="plain" round>{{ term.zh }}</el-tag></div><p>{{ term.desc }}</p></el-card></template></el-scrollbar>
  </el-aside>
</template>

<script setup>
import { computed, ref } from 'vue'
const props = defineProps({ terms: { type: Array, default: () => [] } })
const keyword = ref('')
const filtered = computed(() => { const query = keyword.value.trim().toLowerCase(); return props.terms.filter((term) => !query || `${term.en} ${term.zh} ${term.desc}`.toLowerCase().includes(query)) })
</script>
