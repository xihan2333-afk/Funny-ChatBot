<template>
  <el-card shadow="never">
    <template #header><span>双流日志检索</span></template>
    <el-form :inline="true" class="mb-3">
      <el-form-item label="群组 ID">
        <el-input v-model="filters.groupId" clearable style="width: 150px"/>
      </el-form-item>
      <el-form-item label="Trace ID">
        <el-input v-model="filters.traceId" clearable style="width: 220px"/>
      </el-form-item>
      <el-form-item label="分类">
        <el-input v-model="filters.category" placeholder="engine / llm / security" clearable style="width: 140px"/>
      </el-form-item>
      <el-form-item label="关键词">
        <el-input v-model="filters.keyword" clearable style="width: 180px"/>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" @click="load(0)">查询</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="rows" v-loading="loading" border stripe size="small">
      <el-table-column prop="timestamp" label="时间" width="180"/>
      <el-table-column prop="level" label="级别" width="90"/>
      <el-table-column prop="category" label="分类" width="120"/>
      <el-table-column prop="groupId" label="群组" width="130"/>
      <el-table-column label="Trace" width="170">
        <template #default="{ row }">
          <TraceLink :trace-id="row.traceId"/>
        </template>
      </el-table-column>
      <el-table-column prop="message" label="日志内容" show-overflow-tooltip/>
    </el-table>

    <el-pagination
        style="margin-top: 12px; justify-content: flex-end"
        background
        layout="total, prev, pager, next"
        :total="total"
        :page-size="50"
        :current-page="page + 1"
        @current-change="(p: number) => load(p - 1)"
    />
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from 'vue'
import TraceLink from '@/components/TraceLink.vue'
import {type LogItem, queryLogs} from '@/api/observe'

const loading = ref(false)
const rows = ref<LogItem[]>([])
const total = ref(0)
const page = ref(0)
const filters = reactive({
  groupId: '',
  traceId: '',
  category: '',
  keyword: '',
})

async function load(nextPage = page.value) {
  page.value = nextPage
  loading.value = true
  try {
    const res = await queryLogs({
      groupId: filters.groupId || undefined,
      traceId: filters.traceId || undefined,
      category: filters.category || undefined,
      keyword: filters.keyword || undefined,
      page: page.value,
      size: 50,
    })
    rows.value = res.items || []
    total.value = res.total || 0
  } finally {
    loading.value = false
  }
}

onMounted(() => load(0))
</script>
