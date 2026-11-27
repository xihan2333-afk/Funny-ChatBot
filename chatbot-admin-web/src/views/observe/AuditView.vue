<template>
  <el-card shadow="never">
    <template #header><span>安全与操作审计检索</span></template>
    <el-form :inline="true" class="mb-3">
      <el-form-item label="事件类型">
        <el-select v-model="filters.eventKind" clearable style="width: 170px">
          <el-option label="adminOperate" value="adminOperate"/>
          <el-option label="mandateDenied" value="mandateDenied"/>
          <el-option label="gateBlocked" value="gateBlocked"/>
          <el-option label="sandboxAbnormal" value="sandboxAbnormal"/>
          <el-option label="circuitBreak" value="circuitBreak"/>
          <el-option label="injectionBlocked" value="injectionBlocked"/>
          <el-option label="inboundFiltered" value="inboundFiltered"/>
          <el-option label="outboundBlocked" value="outboundBlocked"/>
        </el-select>
      </el-form-item>
      <el-form-item label="Trace ID">
        <el-input v-model="filters.traceId" clearable style="width: 200px"/>
      </el-form-item>
      <el-form-item label="关键词">
        <el-input v-model="filters.keyword" clearable style="width: 180px"/>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" @click="load(1)">检索</el-button>
      </el-form-item>
    </el-form>

    <PageTable
        :data="pageData"
        :loading="loading"
        @page-change="(p: number) => load(p)"
    >
      <el-table-column prop="id" label="ID" width="80"/>
      <el-table-column prop="eventKind" label="事件类型" width="150"/>
      <el-table-column prop="userId" label="操作人/用户" width="130"/>
      <el-table-column prop="groupId" label="群组" width="130"/>
      <el-table-column label="Trace" width="170">
        <template #default="{ row }">
          <TraceLink :trace-id="row.traceId"/>
        </template>
      </el-table-column>
      <el-table-column prop="detail" label="详情" show-overflow-tooltip/>
      <el-table-column prop="occurredAt" label="发生时间" width="180"/>
    </PageTable>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from 'vue'
import type {PageResult} from '@/api/http'
import PageTable from '@/components/PageTable.vue'
import TraceLink from '@/components/TraceLink.vue'
import {type AuditEventView, queryAuditEvents} from '@/api/observe'

const loading = ref(false)
const pageData = ref<PageResult<AuditEventView>>({items: [], total: 0, page: 1, pageSize: 20})
const filters = reactive({
  eventKind: '',
  traceId: '',
  keyword: '',
})

async function load(nextPage = pageData.value.page) {
  loading.value = true
  try {
    pageData.value = await queryAuditEvents({
      eventKind: filters.eventKind || undefined,
      traceId: filters.traceId || undefined,
      keyword: filters.keyword || undefined,
      page: nextPage,
      pageSize: 20,
    })
  } finally {
    loading.value = false
  }
}

onMounted(() => load(1))
</script>
