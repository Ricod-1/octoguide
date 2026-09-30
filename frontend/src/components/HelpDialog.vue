<template>
  <el-dialog v-model="visible" data-testid="help-dialog" title="使用帮助" width="560px" class="help-dialog">
    <!-- 核心首屏信息：用户价值导向 -->
    <section class="help-section">
      <h4>OctoGuide 能帮你做什么</h4>
      <ul class="help-capabilities">
        <li v-for="capability in CAPABILITIES" :key="capability.title">
          <span class="help-capability-icon">{{ capability.icon }}</span>
          <div>
            <strong>{{ capability.title }}</strong>
            <p>{{ capability.desc }}</p>
          </div>
        </li>
      </ul>
    </section>

    <!-- 次要信息：联网知识机制（schedule 文案来自后端，消除前端硬编码） -->
    <section class="help-section">
      <h4>联网知识是怎么回事</h4>
      <p>除内置问答外，OctoGuide 会自动联网收录 GitHub 官方文档、趋势仓库与更新日志（{{ scheduleText }}），回答底部会附上来源链接。</p>
    </section>

    <!-- 次要信息：AI 配置状态 -->
    <section class="help-section">
      <h4>AI 配置状态</h4>
      <p v-if="status?.aiConfigured">AI 已接入（当前模型：{{ status?.aiModel || '未知' }}），支持联网检索与自由提问。</p>
      <p v-else>当前为<strong>本地知识库模式</strong>，仅能回答内置知识条目。配置环境变量 <code>OCTOGUIDE_LLM_KEY</code>（或在 application-local.yml 的 octoguide.llm.api-key 中）并重启后端后，即可启用 AI 联网回答。</p>
    </section>
  </el-dialog>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  status: { type: Object, default: null },
})
const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

const CAPABILITIES = [
  { icon: '🚀', title: '上手 GitHub 基本操作', desc: '仓库、分支、Pull Request、Issue 等核心概念与操作步骤' },
  { icon: '🛠️', title: '排查报错', desc: '贴上报错原文 + 当前操作步骤 + 期望结果，获得针对性的解决思路' },
  { icon: '🌐', title: '查询 GitHub 最新动态', desc: '趋势仓库、官方文档与更新日志自动联网收录，回答底部附来源链接' },
]

/** 更新机制文案以 /api/status 返回为准。 */
const scheduleText = computed(() => props.status?.schedule || '每周日 18:00 自动更新')
</script>
