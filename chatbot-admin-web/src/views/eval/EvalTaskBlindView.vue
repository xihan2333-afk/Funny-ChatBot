<template>
  <el-card shadow="never">
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span>评估任务与盲评打分</span>
        <div>
          <el-button size="small" type="primary" @click="onDaily">触发每日抽样</el-button>
          <el-button size="small" @click="loadAll">刷新</el-button>
        </div>
      </div>
    </template>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="评估批次" name="batches">
        <el-table :data="batches" v-loading="loading" border stripe size="small" @row-click="selectBatch">
          <el-table-column prop="id" label="批次 ID" width="90"/>
          <el-table-column prop="batchKind" label="类型" width="110"/>
          <el-table-column prop="status" label="状态" width="100"/>
          <el-table-column prop="createdAt" label="创建时间" width="180"/>
          <el-table-column prop="finishedAt" label="完成时间" width="180"/>
        </el-table>

        <div v-if="summary" style="margin-top: 16px">
          <h4>批次 #{{ summary.batchId }} 汇总（通过率 {{ (summary.passRate * 100).toFixed(1) }}%，均分
            {{ summary.avgTotalScore.toFixed(1) }}）</h4>
          <el-table :data="results" border stripe size="small">
            <el-table-column prop="id" label="结果 ID" width="80"/>
            <el-table-column prop="sourceKind" label="来源" width="90"/>
            <el-table-column prop="decision" label="判定" width="90"/>
            <el-table-column prop="attribution" label="归因" width="110"/>
            <el-table-column label="Trace" width="150">
              <template #default="{ row }">
                <TraceLink :trace-id="row.traceId"/>
              </template>
            </el-table-column>
            <el-table-column prop="explanation" label="说明" show-overflow-tooltip/>
            <el-table-column label="操作" width="220">
              <template #default="{ row }">
                <el-button size="small" link @click="onAttribute(row.id, 'agent')">归因Agent</el-button>
                <el-button v-if="row.decision === 'badcase'" size="small" link type="warning"
                           @click="onPromoteFault(row.id)">沉淀错题
                </el-button>
                <el-button v-if="row.decision === 'pass'" size="small" link type="success"
                           @click="onPromoteGolden(row.id)">升黄金集
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <el-tab-pane label="盲评队列" name="blind">
        <el-table :data="blindQueue" border stripe size="small">
          <el-table-column prop="id" label="结果 ID" width="80"/>
          <el-table-column prop="batchId" label="批次" width="80"/>
          <el-table-column prop="groupId" label="群组" width="120"/>
          <el-table-column label="Trace" width="150">
            <template #default="{ row }">
              <TraceLink :trace-id="row.traceId"/>
            </template>
          </el-table-column>
          <el-table-column prop="explanation" label="机器评语" show-overflow-tooltip/>
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button size="small" type="primary" link @click="openBlindDialog(row)">人工打分</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="blindDialog" title="盲评人工打分" width="440px">
      <el-form label-width="80px">
        <el-form-item label="总分">
          <el-input-number v-model="blindForm.total" :min="0" :max="100"/>
        </el-form-item>
        <el-form-item label="判定">
          <el-select v-model="blindForm.decision">
            <el-option label="pass" value="pass"/>
            <el-option label="badcase" value="badcase"/>
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="blindForm.explanation" type="textarea"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="blindDialog = false">取消</el-button>
        <el-button type="primary" @click="submitBlind">提交</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from 'vue'
import {ElMessage} from 'element-plus'
import TraceLink from '@/components/TraceLink.vue'
import {
  attributeResult,
  type EvalBatchSummaryView,
  type EvalBatchView,
  type EvalResultView,
  getEvalBatchSummary,
  listBlindQueue,
  listEvalBatches,
  listEvalResults,
  promoteFaultCase,
  promoteGoldenCase,
  startDailyEval,
  submitBlindScore,
} from '@/api/eval'

const activeTab = ref('batches')
const loading = ref(false)
const batches = ref<EvalBatchView[]>([])
const summary = ref<EvalBatchSummaryView | null>(null)
const results = ref<EvalResultView[]>([])
const blindQueue = ref<EvalResultView[]>([])
const blindDialog = ref(false)
const blindTargetId = ref<number | null>(null)
const blindForm = reactive({total: 80, decision: 'pass', explanation: ''})

async function loadAll() {
  loading.value = true
  try {
    batches.value = await listEvalBatches({page: 0, size: 20})
    blindQueue.value = await listBlindQueue({page: 0, size: 20})
  } finally {
    loading.value = false
  }
}

async function selectBatch(row: EvalBatchView) {
  summary.value = await getEvalBatchSummary(row.id)
  results.value = await listEvalResults(row.id)
}

async function onDaily() {
  await startDailyEval()
  ElMessage.success('每日抽样批次已触发')
  await loadAll()
}

async function onAttribute(id: number, attr: string) {
  await attributeResult(id, attr)
  ElMessage.success('归因已更新')
  if (summary.value) await selectBatch({id: summary.value.batchId} as EvalBatchView)
}

async function onPromoteFault(id: number) {
  await promoteFaultCase(id)
  ElMessage.success('已沉淀至错题集')
}

async function onPromoteGolden(id: number) {
  await promoteGoldenCase(id)
  ElMessage.success('已升为黄金集')
}

function openBlindDialog(row: EvalResultView) {
  blindTargetId.value = row.id
  blindDialog.value = true
}

async function submitBlind() {
  if (!blindTargetId.value) return
  await submitBlindScore(blindTargetId.value, {...blindForm})
  ElMessage.success('盲评打分已提交')
  blindDialog.value = false
  await loadAll()
}

onMounted(loadAll)
</script>
