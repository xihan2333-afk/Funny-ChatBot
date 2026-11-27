<template>
  <el-card shadow="never">
    <template #header><span>告警通知中心</span></template>
    <el-form :inline="true" class="mb-3">
      <el-form-item label="级别">
        <el-select v-model="level" clearable style="width: 120px" @change="load">
          <el-option label="info" value="info"/>
          <el-option label="warn" value="warn"/>
          <el-option label="critical" value="critical"/>
        </el-select>
      </el-form-item>
      <el-form-item label="仅未读">
        <el-switch v-model="unreadOnly" @change="load"/>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load">刷新</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="rows" v-loading="loading" border stripe size="small">
      <el-table-column prop="id" label="ID" width="80"/>
      <el-table-column prop="ruleName" label="规则名称" width="180"/>
      <el-table-column prop="level" label="级别" width="100"/>
      <el-table-column prop="summary" label="摘要" show-overflow-tooltip/>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.readFlag ? 'info' : 'danger'" size="small">
            {{ row.readFlag ? '已读' : '未读' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="时间" width="180"/>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button v-if="!row.readFlag" size="small" link type="primary" @click="ack(row.id)">
            标为已读
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {ElMessage} from 'element-plus'
import {acknowledgeNotification, type AlertNotificationView, listNotifications} from '@/api/alert'

const loading = ref(false)
const rows = ref<AlertNotificationView[]>([])
const level = ref('')
const unreadOnly = ref(false)

async function load() {
  loading.value = true
  try {
    rows.value = await listNotifications({
      level: level.value || undefined,
      unreadOnly: unreadOnly.value || undefined,
      page: 0,
      size: 50,
    })
  } finally {
    loading.value = false
  }
}

async function ack(id: number) {
  await acknowledgeNotification(id)
  ElMessage.success('已标记为已读')
  await load()
}

onMounted(load)
</script>
