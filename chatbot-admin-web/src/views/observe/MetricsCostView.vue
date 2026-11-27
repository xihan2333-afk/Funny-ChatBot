<template>
  <el-card shadow="never">
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span>指标与成本报表</span>
        <el-button size="small" type="primary" :loading="loading" @click="loadAll">刷新</el-button>
      </div>
    </template>

    <el-form :inline="true" class="mb-3">
      <el-form-item label="聚合维度">
        <el-select v-model="filters.dim" style="width: 130px" @change="loadCost">
          <el-option label="按群" value="GROUP"/>
          <el-option label="按槽位" value="SLOT"/>
          <el-option label="按模型" value="MODEL"/>
          <el-option label="按日" value="DAY"/>
        </el-select>
      </el-form-item>
      <el-form-item label="群组 ID">
        <el-input v-model="filters.groupId" clearable style="width: 140px"/>
      </el-form-item>
      <el-form-item label="槽位">
        <el-input v-model="filters.slotCode" clearable style="width: 130px"/>
      </el-form-item>
      <el-form-item label="模型">
        <el-input v-model="filters.modelId" clearable style="width: 150px"/>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadCost">查询成本</el-button>
      </el-form-item>
    </el-form>

    <div ref="chartRef" style="width: 100%; height: 280px; margin-bottom: 16px"/>

    <el-table :data="costRows" border stripe size="small" class="mb-3">
      <el-table-column prop="dimensionKey" label="维度键"/>
      <el-table-column prop="calls" label="调用次数" width="110"/>
      <el-table-column prop="inputTokens" label="Input Tokens" width="130"/>
      <el-table-column prop="outputTokens" label="Output Tokens" width="130"/>
      <el-table-column prop="cachedTokens" label="Cached Tokens" width="130"/>
      <el-table-column prop="costAmount" label="费用" width="120"/>
    </el-table>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="时延" name="latency">
        <pre v-if="extraMetrics.latency">{{ JSON.stringify(extraMetrics.latency, null, 2) }}</pre>
        <el-empty v-else description="暂无指标数据"/>
      </el-tab-pane>
      <el-tab-pane label="质量" name="quality">
        <pre v-if="extraMetrics.quality">{{ JSON.stringify(extraMetrics.quality, null, 2) }}</pre>
        <el-empty v-else description="暂无指标数据"/>
      </el-tab-pane>
      <el-tab-pane label="上下文" name="context">
        <pre v-if="extraMetrics.context">{{ JSON.stringify(extraMetrics.context, null, 2) }}</pre>
        <el-empty v-else description="暂无指标数据"/>
      </el-tab-pane>
      <el-tab-pane label="社交与反馈" name="social">
        <pre v-if="extraMetrics.social || extraMetrics.feedback">{{
            JSON.stringify({
              social: extraMetrics.social,
              feedback: extraMetrics.feedback
            }, null, 2)
          }}</pre>
        <el-empty v-else description="暂无指标数据"/>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from 'vue'
import * as echarts from 'echarts'
import {
  type CostRow,
  queryContextMetrics,
  queryCost,
  queryFeedbackMetrics,
  queryLatency,
  queryQuality,
  querySocialMetrics,
} from '@/api/observe'

const loading = ref(false)
const activeTab = ref('latency')
const costRows = ref<CostRow[]>([])
const chartRef = ref<HTMLElement | null>(null)
const filters = reactive({
  dim: 'GROUP' as 'GROUP' | 'SLOT' | 'MODEL' | 'DAY',
  groupId: '',
  slotCode: '',
  modelId: '',
})
const extraMetrics = reactive<Record<string, unknown>>({
  latency: null,
  quality: null,
  context: null,
  social: null,
  feedback: null,
})

function renderChart() {
  if (!chartRef.value) return
  const chart = echarts.getInstanceByDom(chartRef.value) || echarts.init(chartRef.value)
  chart.setOption({
    tooltip: {trigger: 'axis'},
    xAxis: {type: 'category', data: costRows.value.map((r) => r.dimensionKey)},
    yAxis: {type: 'value', name: 'Cost'},
    series: [{type: 'bar', data: costRows.value.map((r) => Number(r.costAmount || 0))}],
  })
}

async function loadCost() {
  const report = await queryCost({
    dim: filters.dim,
    groupId: filters.groupId || undefined,
    slotCode: filters.slotCode || undefined,
    modelId: filters.modelId || undefined,
  })
  costRows.value = report.rows || []
  renderChart()
}

async function loadAll() {
  loading.value = true
  try {
    await loadCost()
    const [lat, qual, ctx, soc, fb] = await Promise.all([
      queryLatency().catch(() => null),
      queryQuality().catch(() => null),
      queryContextMetrics().catch(() => null),
      querySocialMetrics().catch(() => null),
      queryFeedbackMetrics().catch(() => null),
    ])
    extraMetrics.latency = lat
    extraMetrics.quality = qual
    extraMetrics.context = ctx
    extraMetrics.social = soc
    extraMetrics.feedback = fb
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)
</script>
