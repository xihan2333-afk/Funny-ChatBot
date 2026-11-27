<template>
  <el-card shadow="never">
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span>注入对抗用例与安全测试</span>
        <div>
          <el-button size="small" type="warning" :loading="running" @click="onRunAll">全量运行对抗测试</el-button>
          <el-button size="small" type="primary" @click="openCreate">新增对抗用例</el-button>
        </div>
      </div>
    </template>

    <el-form :inline="true" class="mb-3">
      <el-form-item label="攻击通道">
        <el-select v-model="channel" clearable style="width: 150px" @change="load">
          <el-option v-for="ch in channels" :key="ch" :label="ch" :value="ch"/>
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load">查询</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="rows" v-loading="loading" border stripe size="small">
      <el-table-column prop="id" label="ID" width="70"/>
      <el-table-column prop="channel" label="通道" width="100"/>
      <el-table-column prop="title" label="标题" width="200"/>
      <el-table-column prop="payload" label="攻击载荷" show-overflow-tooltip/>
      <el-table-column prop="lastVerdict" label="最近结论" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.lastVerdict" :type="row.lastVerdict === 'pass' ? 'success' : 'danger'" size="small">
            {{ row.lastVerdict }}
          </el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-switch :model-value="row.enabled" @change="(val: boolean) => onToggle(row.id, val)"/>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" link type="danger" @click="onDelete(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑对抗用例' : '新增对抗用例'" width="580px">
      <el-form label-width="90px">
        <el-form-item label="通道">
          <el-select v-model="form.channel" style="width: 100%">
            <el-option v-for="ch in channels" :key="ch" :label="ch" :value="ch"/>
          </el-select>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="form.title"/>
        </el-form-item>
        <el-form-item label="载荷">
          <el-input v-model="form.payload" type="textarea" :rows="3"/>
        </el-form-item>
        <el-form-item label="Scenario">
          <el-input v-model="form.scenario" type="textarea" :rows="2"/>
        </el-form-item>
        <el-form-item label="Expectation">
          <el-input v-model="form.expectation" type="textarea" :rows="2"/>
        </el-form-item>
        <el-form-item label="Assertion">
          <el-input v-model="form.assertion" type="textarea" :rows="2"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveCase">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from 'vue'
import {ElMessage} from 'element-plus'
import {
  type AttackCaseView,
  createAttackCase,
  deleteAttackCase,
  listAttackCases,
  runAttackCases,
  toggleAttackCase,
  updateAttackCase,
} from '@/api/eval'

const channels = ['message', 'nickname', 'image', 'web', 'memory', 'card', 'http', 'emoji']
const loading = ref(false)
const running = ref(false)
const rows = ref<AttackCaseView[]>([])
const channel = ref('')
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({
  channel: 'message',
  title: '',
  payload: '',
  scenario: '{"priorScript":[]}',
  expectation: '{"safe":true}',
  assertion: '{"code":["noUnauthorizedTask","noDeliveredViolation","noSandboxEscape"]}',
})

async function load() {
  loading.value = true
  try {
    rows.value = await listAttackCases(channel.value || undefined)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, {
    channel: 'message',
    title: '',
    payload: '',
    scenario: '{"priorScript":[]}',
    expectation: '{"safe":true}',
    assertion: '{"code":["noUnauthorizedTask","noDeliveredViolation","noSandboxEscape"]}',
  })
  dialogVisible.value = true
}

function openEdit(row: AttackCaseView) {
  editingId.value = row.id
  Object.assign(form, {
    channel: row.channel,
    title: row.title,
    payload: row.payload,
    scenario: row.scenario,
    expectation: row.expectation,
    assertion: row.assertion,
  })
  dialogVisible.value = true
}

async function saveCase() {
  if (editingId.value) {
    await updateAttackCase(editingId.value, {...form})
  } else {
    await createAttackCase({...form})
  }
  ElMessage.success('已保存')
  dialogVisible.value = false
  await load()
}

async function onToggle(id: number, enabled: boolean) {
  await toggleAttackCase(id, enabled)
  await load()
}

async function onDelete(id: number) {
  await deleteAttackCase(id)
  ElMessage.success('已删除')
  await load()
}

async function onRunAll() {
  running.value = true
  try {
    const report = await runAttackCases()
    ElMessage.success(`对抗测试完成：通过 ${report.passed}/${report.total}，硬防线击穿 ${report.hardBreaches}`)
    await load()
  } finally {
    running.value = false
  }
}

onMounted(load)
</script>
