<template>
  <el-card shadow="never">
    <template #header>Turn 只读查询（不提供修改或重跑 Turn 接口）</template>
    <el-form :inline="true" style="margin-bottom: 12px">
      <el-form-item label="群">
        <GroupSelector v-model="selectedGroup"/>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="status" clearable placeholder="全部" style="width: 140px">
          <el-option v-for="s in ['ACCEPTED', 'GENERATING', 'SETTLING', 'SETTLED', 'SILENT', 'FAILED', 'DROPPED']"
                     :key="s" :label="s" :value="s"/>
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load(1)">查询</el-button>
      </el-form-item>
    </el-form>

    <PageTable :data="pageData" :loading="loading" @page-change="(p, ps) => load(p, ps)">
      <el-table-column prop="id" label="Turn ID" width="120"/>
      <el-table-column prop="groupId" label="群号" width="130"/>
      <el-table-column prop="status" label="状态" width="110"/>
      <el-table-column prop="silentReason" label="沉默原因" width="140" show-overflow-tooltip/>
      <el-table-column prop="errorCode" label="错误码" width="150"/>
      <el-table-column prop="acceptedAt" label="接收时间" width="180"/>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row.id)">详情</el-button>
        </template>
      </el-table-column>
    </PageTable>

    <el-drawer v-model="drawer" title="Turn 只读详情" size="560px">
      <template v-if="detail">
        <el-descriptions :column="1" border size="small" style="margin-bottom: 14px">
          <el-descriptions-item label="Turn ID">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.status }}</el-descriptions-item>
          <el-descriptions-item label="Trace ID">
            <TraceLink :trace-id="detail.traceId"/>
          </el-descriptions-item>
        </el-descriptions>
        <h4>关联触发器与 Decision</h4>
        <el-table :data="detail.triggers" border size="small" style="margin-bottom: 14px">
          <el-table-column prop="id" label="ID" width="90"/>
          <el-table-column prop="decision" label="决策" width="90"/>
          <el-table-column prop="decisionLayer" label="层级" width="80"/>
          <el-table-column prop="decisionReason" label="理由"/>
        </el-table>
        <h4>发言 Outbox 回执</h4>
        <el-table :data="detail.outboxes" border size="small">
          <el-table-column prop="id" label="ID" width="90"/>
          <el-table-column prop="type" label="类型" width="90"/>
          <el-table-column prop="status" label="状态" width="90"/>
          <el-table-column prop="receiptSummary" label="回执"/>
        </el-table>
      </template>
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import GroupSelector from '@/components/GroupSelector.vue'
import PageTable from '@/components/PageTable.vue'
import TraceLink from '@/components/TraceLink.vue'
import type {PageResult} from '@/api/http'
import type {GroupTriple} from '@/stores/ui'
import {runtimeApi, type TurnDetail, type TurnSummary} from '@/api/runtime'

const loading = ref(false)
const selectedGroup = ref<GroupTriple | null>(null)
const status = ref('')
const pageData = ref<PageResult<TurnSummary>>({items: [], total: 0, page: 1, pageSize: 20})
const drawer = ref(false)
const detail = ref<TurnDetail | null>(null)

async function load(page = 1, pageSize = pageData.value.pageSize) {
  loading.value = true
  try {
    pageData.value = await runtimeApi.listTurns({
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
  detail.value = await runtimeApi.getTurn(id)
  drawer.value = true
}

onMounted(() => load(1))
</script>
