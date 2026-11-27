<template>
  <el-card shadow="never">
    <template #header>系统参数（五大域热更新，保存即刷新生效）</template>
    <el-tabs v-model="activeDomain">
      <el-tab-pane v-for="d in domains" :key="d.key" :label="d.label" :name="d.key">
        <el-table :data="grouped[d.key] || []" border stripe>
          <el-table-column prop="paramKey" label="参数键" width="280"/>
          <el-table-column prop="description" label="说明" min-width="240"/>
          <el-table-column label="参数值" width="220">
            <template #default="{ row }">
              <el-input v-model="row.paramValue" size="small"/>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button type="primary" size="small" @click="save(row)">保存</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {ElMessage} from 'element-plus'
import {securityApi, type SystemParamItem} from '@/api/security'

const domains = [
  {key: 'context', label: '上下文 (context)'},
  {key: 'memory', label: '记忆 (memory)'},
  {key: 'emotion', label: '情绪 (emotion)'},
  {key: 'scheduler', label: '调度 (scheduler)'},
  {key: 'observability', label: '观测 (observability)'},
]

const activeDomain = ref('context')
const grouped = ref<Record<string, SystemParamItem[]>>({})

async function load() {
  grouped.value = await securityApi.listSystemParams()
}

async function save(row: SystemParamItem) {
  await securityApi.updateSystemParam(row.paramKey, row.paramValue)
  ElMessage.success(`参数 ${row.paramKey} 已热更新`)
}

onMounted(load)
</script>
