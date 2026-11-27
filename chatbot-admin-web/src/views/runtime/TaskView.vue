<template>
  <el-card shadow="never">
    <el-alert
        type="info"
        :closable="false"
        show-icon
        title="受控干预纪律：修改运行中任务请走「取消加重建」，此处仅提供取消在途任务与重试 FAILED 任务。"
        style="margin-bottom: 12px"
    />

    <el-form :inline="true" style="margin-bottom: 12px">
      <el-form-item label="群">
        <GroupSelector v-model="selectedGroup"/>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="status" clearable placeholder="全部" style="width: 140px">
          <el-option v-for="s in ['SCHEDULED', 'RUNNING', 'WAITING', 'DONE', 'FAILED', 'CANCELLED', 'EXPIRED']" :key="s"
                     :label="s" :value="s"/>
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load(1)">查询</el-button>
      </el-form-item>
    </el-form>

    <PageTable :data="pageData" :loading="loading" @page-change="(p, ps) => load(p, ps)">
      <el-table-column prop="id" label="Task ID" width="120"/>
      <el-table-column prop="groupId" label="群号" width="120"/>
      <el-table-column prop="taskKind" label="类型" width="100"/>
      <el-table-column prop="category" label="类别" width="130"/>
      <el-table-column prop="status" label="状态" width="110"/>
      <el-table-column prop="mandateSource" label="授权来源" width="110"/>
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row.id)">详情</el-button>
          <el-button
              v-if="['SCHEDULED', 'RUNNING', 'WAITING'].includes(row.status)"
              link
              type="warning"
              @click="cancel(row.id)"
          >
            取消
          </el-button>
          <el-button
              v-if="row.status === 'FAILED'"
              link
              type="success"
              @click="retry(row.id)"
          >
            重试
          </el-button>
        </template>
      </el-table-column>
    </PageTable>

    <el-drawer v-model="drawer" title="Task 详情" size="560px">
      <template v-if="detail">
        <el-descriptions :column="1" border size="small" style="margin-bottom: 12px">
          <el-descriptions-item label="Task ID">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item label="Mandate">{{ detail.mandateSource }} / {{
              detail.mandateRef
            }}
          </el-descriptions-item>
          <el-descriptions-item label="Worker 存活">{{ detail.workerAlive ? '运行中' : '否' }}</el-descriptions-item>
          <el-descriptions-item label="Snapshot">
            <pre style="margin: 0; white-space: pre-wrap">{{ detail.snapshot }}</pre>
          </el-descriptions-item>
        </el-descriptions>
        <h4>进度日志</h4>
        <el-table :data="detail.progressLogs" border size="small">
          <el-table-column prop="createdAt" label="时间" width="170"/>
          <el-table-column prop="kind" label="类型" width="90"/>
          <el-table-column prop="note" label="摘要"/>
        </el-table>
      </template>
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import GroupSelector from '@/components/GroupSelector.vue'
import PageTable from '@/components/PageTable.vue'
import type {PageResult} from '@/api/http'
import type {GroupTriple} from '@/stores/ui'
import {runtimeApi, type TaskDetail, type TaskSummary} from '@/api/runtime'

const loading = ref(false)
const selectedGroup = ref<GroupTriple | null>(null)
const status = ref('')
const pageData = ref<PageResult<TaskSummary>>({items: [], total: 0, page: 1, pageSize: 20})
const drawer = ref(false)
const detail = ref<TaskDetail | null>(null)

async function load(page = 1, pageSize = pageData.value.pageSize) {
  loading.value = true
  try {
    pageData.value = await runtimeApi.listTasks({
      platform: selectedGroup.value?.platform,
      botId: selectedGroup.value?.botId,
      groupId: selectedGroup.value?.groupId,
      status: status.value || undefined,
      page,
      pageSize,
    })
  } finally {
    loading.value = false
  }
}

async function openDetail(id: number) {
  detail.value = await runtimeApi.getTask(id)
  drawer.value = true
}

async function cancel(id: number) {
  const {value} = await ElMessageBox.prompt('请输入取消原因', '取消任务', {inputValue: '管理员手动取消'})
  await runtimeApi.cancelTask(id, value || '管理员手动取消')
  ElMessage.success('任务已取消')
  await load()
}

async function retry(id: number) {
  await runtimeApi.retryTask(id)
  ElMessage.success('失败任务已重新调度')
  await load()
}

onMounted(() => load(1))
</script>
