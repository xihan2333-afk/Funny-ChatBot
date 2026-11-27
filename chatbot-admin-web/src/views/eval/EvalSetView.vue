<template>
  <el-card shadow="never">
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span>评测集管理（seed / golden / fault）</span>
        <div>
          <el-button size="small" @click="draftDialog = true">AI 生成草稿</el-button>
          <el-button type="primary" size="small" @click="openCreate">人工录入</el-button>
        </div>
      </div>
    </template>

    <el-form :inline="true" class="mb-3">
      <el-form-item label="类型">
        <el-select v-model="caseType" clearable style="width: 130px" @change="load">
          <el-option label="seed" value="seed"/>
          <el-option label="golden" value="golden"/>
          <el-option label="fault" value="fault"/>
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="status" clearable style="width: 130px" @change="load">
          <el-option label="draft" value="draft"/>
          <el-option label="ready" value="ready"/>
          <el-option label="discarded" value="discarded"/>
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load">查询</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="rows" v-loading="loading" border stripe size="small">
      <el-table-column prop="id" label="ID" width="70"/>
      <el-table-column prop="caseType" label="类型" width="90"/>
      <el-table-column prop="origin" label="来源" width="100"/>
      <el-table-column prop="rubricVersion" label="Rubric 版本" width="105"/>
      <el-table-column prop="status" label="状态" width="100"/>
      <el-table-column label="Trace" width="150">
        <template #default="{ row }">
          <TraceLink :trace-id="row.traceRef"/>
        </template>
      </el-table-column>
      <el-table-column prop="scenario" label="场景卡" show-overflow-tooltip/>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openEdit(row)">编辑/定稿</el-button>
          <el-button
              v-if="row.status !== 'discarded'"
              size="small"
              link
              type="danger"
              @click="onDiscard(row.id)"
          >
            废弃
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="editDialog" :title="editingId ? `编辑用例 #${editingId}` : '人工录入评测用例'" width="620px">
      <el-form label-width="95px">
        <el-form-item label="类型">
          <el-select v-model="form.caseType" style="width: 100%">
            <el-option label="seed" value="seed"/>
            <el-option label="golden" value="golden"/>
            <el-option label="fault" value="fault"/>
          </el-select>
        </el-form-item>
        <el-form-item v-if="editingId" label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option label="draft" value="draft"/>
            <el-option label="ready" value="ready"/>
          </el-select>
        </el-form-item>
        <el-form-item label="场景卡 JSON">
          <el-input v-model="form.scenarioJson" type="textarea" :rows="6"/>
        </el-form-item>
        <el-form-item label="Rubric JSON">
          <el-input v-model="form.rubricJson" type="textarea" :rows="5"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" @click="saveCase">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="draftDialog" title="AI 生成场景卡草稿" width="480px">
      <el-form label-width="95px">
        <el-form-item label="互动性质">
          <el-input v-model="draftForm.interactionNature"/>
        </el-form-item>
        <el-form-item label="能力域">
          <el-input v-model="draftForm.capabilityDomain"/>
        </el-form-item>
        <el-form-item label="补充说明">
          <el-input v-model="draftForm.note" type="textarea"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="draftDialog = false">取消</el-button>
        <el-button type="primary" @click="onGenerateDraft">生成草稿</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from 'vue'
import {ElMessage} from 'element-plus'
import TraceLink from '@/components/TraceLink.vue'
import {
  createEvalCase,
  discardEvalCase,
  type EvalCaseView,
  generateDraftCase,
  listEvalCases,
  updateEvalCase,
} from '@/api/eval'

const loading = ref(false)
const rows = ref<EvalCaseView[]>([])
const caseType = ref('')
const status = ref('')
const editDialog = ref(false)
const draftDialog = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({
  caseType: 'seed',
  status: 'ready',
  scenarioJson: '{}',
  rubricJson: '[]',
})
const draftForm = reactive({
  interactionNature: '',
  capabilityDomain: '',
  note: '',
})

async function load() {
  loading.value = true
  try {
    rows.value = await listEvalCases({
      caseType: caseType.value || undefined,
      status: status.value || undefined,
      page: 0,
      size: 50,
    })
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, {caseType: 'seed', status: 'ready', scenarioJson: '{}', rubricJson: '[]'})
  editDialog.value = true
}

function openEdit(row: EvalCaseView) {
  editingId.value = row.id
  Object.assign(form, {
    caseType: row.caseType,
    status: row.status,
    scenarioJson: row.scenario,
    rubricJson: row.rubric,
  })
  editDialog.value = true
}

async function saveCase() {
  if (editingId.value) {
    await updateEvalCase(editingId.value, {...form})
  } else {
    await createEvalCase({
      caseType: form.caseType,
      scenarioJson: form.scenarioJson,
      rubricJson: form.rubricJson,
    })
  }
  ElMessage.success('已保存')
  editDialog.value = false
  await load()
}

async function onGenerateDraft() {
  await generateDraftCase({...draftForm})
  ElMessage.success('草稿已生成')
  draftDialog.value = false
  await load()
}

async function onDiscard(id: number) {
  await discardEvalCase(id)
  ElMessage.success('已废弃')
  await load()
}

onMounted(load)
</script>
