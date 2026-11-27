<template>
  <el-card shadow="never">
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span>各群情绪状态（含懒衰减有效强度与基线对照）</span>
        <el-button size="small" @click="load">刷新</el-button>
      </div>
    </template>
    <el-table v-loading="loading" :data="items" border stripe>
      <el-table-column prop="groupName" label="群名称" width="150">
        <template #default="{ row }">{{ row.groupName || row.groupId }}</template>
      </el-table-column>
      <el-table-column prop="label" label="当前情绪" width="110"/>
      <el-table-column prop="rawIntensity" label="原始强度" width="90"/>
      <el-table-column prop="effectiveIntensity" label="懒衰减有效强度" width="130"/>
      <el-table-column label="基线对照" width="140">
        <template #default="{ row }">{{ row.baselineLabel }} ({{ row.baselineIntensity }})</template>
      </el-table-column>
      <el-table-column prop="cause" label="最近起因" min-width="180" show-overflow-tooltip/>
      <el-table-column label="操作" width="130">
        <template #default="{ row }">
          <el-button link type="warning" @click="reset(row)">重置回基线</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {ElMessage} from 'element-plus'
import {type GroupEmotionItem, runtimeApi} from '@/api/runtime'

const loading = ref(false)
const items = ref<GroupEmotionItem[]>([])

async function load() {
  loading.value = true
  try {
    items.value = await runtimeApi.listEmotions()
  } finally {
    loading.value = false
  }
}

async function reset(row: GroupEmotionItem) {
  await runtimeApi.resetEmotion(row.platform, row.botId, row.groupId)
  ElMessage.success('已重置回人格基线情绪')
  await load()
}

onMounted(load)
</script>
