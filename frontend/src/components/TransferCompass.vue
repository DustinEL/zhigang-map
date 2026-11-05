<script setup lang="ts">
withDefaults(defineProps<{
  compact?: boolean
  reusableSkills?: string[]
  missingSkills?: string[]
  distance?: number
  targetRole?: string
}>(), {
  compact: false,
  reusableSkills: () => ['Java', 'Spring Boot', 'MySQL', 'Redis', '接口与工程化'],
  missingSkills: () => ['大语言模型', 'RAG', 'Prompt Engineering', '工具调用', 'Agent 评测'],
  distance: 58,
  targetRole: 'AI 应用岗位'
})
</script>

<template>
  <section class="transfer-compass" :class="{ compact }">
    <header>
      <div><span>岗位能力罗盘</span><h2>当前基础到目标岗位</h2></div>
      <strong>{{ distance }}%<small>能力差距</small></strong>
    </header>
    <div class="transfer-path"><b>现有基础</b><i>可直接复用</i><b>{{ targetRole }}</b><i>待补齐能力</i><b>目标岗位</b></div>
    <div class="transfer-skills">
      <div><span>可以直接复用</span><p><em v-for="skill in reusableSkills" :key="skill">{{ skill }}</em></p></div>
      <div><span>需要重点补充</span><p><em v-for="skill in missingSkills" :key="skill">{{ skill }}</em></p></div>
    </div>
    <p v-if="!compact" class="transfer-project"><b>建议动作</b> 把目标岗位拆成一个可运行项目，再用代码和测试结果证明能力。</p>
  </section>
</template>
