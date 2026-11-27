<template>
  <el-card shadow="never">
    <template #header><span>dry-run 重放与版本对照实验</span></template>
    <el-form :inline="true" class="mb-3">
      <el-form-item label="用例 ID(逗号分隔)">
        <el-input v-model="caseIdsText" placeholder="留空跑 ready 用例" style="width: 180px"/>
      </el-form-item>
      <el-form-item label="Pass@k">
        <el-input-number v-model="kOverride" :min="1" :max="10"/>
      </el-form-item>
      <el-form-item label="版本覆盖">
        <el-input v-model="versionRef" placeholder="prompt:welcome:v2" style="width: 180px"/>
      </el-form-item>
      <el-form-item label="实验 ID">
        <el-input v-model="experimentId" placeholder="exp-001" style="width: 150px"/>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="running" @click="onRun">发起重放</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="batches" v-loading="loading" border stripe size="small">
      <el-table-column prop="id" label="批次 ID" width="90"/>
      <el-table-column prop="status" label="状态" width="100"/>
      <el-table-column prop="scope" label="范围 / 版本 / 实验" show-overflow-tooltip/>
      <el-table-column prop="report" label="报告" show-overflow-tooltip/>
      <el-table-column prop="costStat" label="重放成本" width="180" show-overflow-tooltip/>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {ElMessage} from 'element-plus'
import {type EvalBatchView, listReplayBatches, runReplayBatch} from '@/api/eval'

const loading = ref(false)
const running = ref(false)
const batches = ref<EvalBatchView[]>([])
const caseIdsText = ref('')
const kOverride = ref(3)
const versionRef = ref('')
const experimentId = ref('')

async function load() {
  loading.value = true
  try {
    batches.value = await listReplayBatches({page: 0, size: 20, experimentId: experimentId.value || undefined})
  } finally {
    loading.value = false
  }
}

async function onRun() {
  running.value = true
  try {
    const caseIds = caseIdsText.value
        .split(',')
        .map((s) => Number(s.trim()))
        .filter((n) => !Number.isNaN(n) && n > 0)
    await runReplayBatch({
      caseIds: caseIds.length ? caseIds : undefined,
      kOverride: kOverride.value,
      versionRef: versionRef.value || undefined,
      experimentId: experimentId.value || undefined,
    })
    ElMessage.success('重放实验已完成')
    await load()
  } finally {
    running.value = false
  }
}

onMounted(load)
</script>
