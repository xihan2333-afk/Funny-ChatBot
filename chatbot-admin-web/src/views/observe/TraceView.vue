<template>
  <el-card shadow="never">
    <template #header><span>链路查询（Trace 瀑布图）</span></template>
    <el-form :inline="true" class="mb-3">
      <el-form-item label="群组">
        <GroupSelector v-model="group"/>
      </el-form-item>
      <el-form-item label="槽位">
        <el-input v-model="slotCode" placeholder="如 chat_main" clearable style="width: 140px"/>
      </el-form-item>
      <el-form-item label="Trace ID">
        <el-input v-model="directTraceId" placeholder="直接输入 traceId" clearable style="width: 240px"/>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" @click="doSearch">检索链路</el-button>
        <el-button v-if="directTraceId" @click="loadSpanTree(directTraceId)">查看指定 Trace</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="16">
      <el-col :span="6">
        <el-table :data="traceRows" highlight-current-row size="small" border
                  @row-click="(r: { traceId: string }) => loadSpanTree(r.traceId)">
          <el-table-column prop="traceId" label="Trace ID" show-overflow-tooltip/>
        </el-table>
      </el-col>
      <el-col :span="18">
        <div v-if="activeTraceId" style="margin-bottom: 8px; font-weight: 600">
          当前 Trace：{{ activeTraceId }}
        </div>
        <TraceWaterfall :spans="spans"/>
      </el-col>
    </el-row>
  </el-card>
</template>

<script setup lang="ts">
import {computed, onMounted, ref} from 'vue'
import {useRoute} from 'vue-router'
import GroupSelector from '@/components/GroupSelector.vue'
import TraceWaterfall, {type TraceSpanItem} from '@/components/TraceWaterfall.vue'
import {getTrace, searchTraces} from '@/api/observe'

interface GroupTriple {
  platform: string
  botId: string
  groupId: string
}

const route = useRoute()
const group = ref<GroupTriple | null>(null)
const slotCode = ref('')
const directTraceId = ref('')
const traceIds = ref<string[]>([])
const activeTraceId = ref('')
const spans = ref<TraceSpanItem[]>([])
const loading = ref(false)

const traceRows = computed(() => traceIds.value.map((traceId) => ({traceId})))

async function doSearch() {
  loading.value = true
  try {
    traceIds.value = await searchTraces({
      platform: group.value?.platform,
      botId: group.value?.botId,
      groupId: group.value?.groupId,
      slotCode: slotCode.value || undefined,
    })
    if (traceIds.value.length > 0 && !activeTraceId.value) {
      await loadSpanTree(traceIds.value[0]!)
    }
  } finally {
    loading.value = false
  }
}

async function loadSpanTree(traceId: string) {
  if (!traceId) return
  activeTraceId.value = traceId
  spans.value = await getTrace(traceId)
}

onMounted(() => {
  const qTrace = route.query.traceId as string | undefined
  if (qTrace) {
    directTraceId.value = qTrace
    loadSpanTree(qTrace)
  }
})
</script>
