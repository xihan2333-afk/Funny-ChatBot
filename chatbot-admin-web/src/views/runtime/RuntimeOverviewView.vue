<template>
  <div v-loading="loading">
    <el-row :gutter="16" style="margin-bottom: 16px">
      <el-col :span="8">
        <el-card shadow="never">
          <template #header>全局熔断状态</template>
          <el-tag :type="overview?.globalBreakerTripped ? 'danger' : 'success'" size="large">
            {{ overview?.globalBreakerTripped ? '已熔断' : '正常' }}
          </el-tag>
          <div style="margin-top: 10px; color: #4e5969; font-size: 13px">
            当日消费：${{ (overview?.todaySpendUsd ?? 0).toFixed(4) }} / 限额
            ${{ (overview?.dailyLimitUsd ?? 0).toFixed(2) }}
          </div>
        </el-card>
      </el-col>
      <el-col :span="16">
        <el-card shadow="never">
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center">
              <span>全群运行态总览</span>
              <el-button size="small" @click="load">刷新</el-button>
            </div>
          </template>
          <el-table :data="overview?.groups || []" border size="small">
            <el-table-column prop="groupName" label="群名称">
              <template #default="{ row }">{{ row.groupName || row.groupId }}</template>
            </el-table-column>
            <el-table-column prop="lifecycle" label="状态" width="100"/>
            <el-table-column prop="runningTurns" label="运行中 Turn" width="110"/>
            <el-table-column prop="runningTasks" label="运行中 Task" width="110"/>
            <el-table-column prop="activeWorkers" label="活跃 Worker" width="110"/>
            <el-table-column label="熔断" width="80">
              <template #default="{ row }">
                <el-tag :type="row.breakerTripped ? 'danger' : 'success'" size="small">
                  {{ row.breakerTripped ? '是' : '否' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {runtimeApi, type RuntimeOverview} from '@/api/runtime'

const loading = ref(false)
const overview = ref<RuntimeOverview | null>(null)

async function load() {
  loading.value = true
  try {
    overview.value = await runtimeApi.overview()
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>
