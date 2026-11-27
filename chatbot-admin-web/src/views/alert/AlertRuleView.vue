<template>
  <el-card shadow="never">
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span>告警规则管理</span>
        <el-button type="primary" size="small" @click="openCreate">新增规则</el-button>
      </div>
    </template>

    <el-table :data="rules" v-loading="loading" border stripe size="small">
      <el-table-column prop="id" label="ID" width="70"/>
      <el-table-column prop="name" label="规则名称" width="180"/>
      <el-table-column prop="metric" label="指标" width="160"/>
      <el-table-column prop="windowMin" label="窗口(分)" width="90"/>
      <el-table-column prop="threshold" label="阈值" width="120"/>
      <el-table-column prop="level" label="级别" width="90"/>
      <el-table-column label="内置" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.builtin" type="info" size="small">内置</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button
              size="small"
              link
              type="danger"
              :disabled="Boolean(row.builtin)"
              @click="onDelete(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑告警规则' : '新增告警规则'" width="520px">
      <el-form label-width="95px">
        <el-form-item label="名称">
          <el-input v-model="form.name"/>
        </el-form-item>
        <el-form-item label="指标">
          <el-input v-model="form.metric" placeholder="如 error_rate / cost_ratio"/>
        </el-form-item>
        <el-form-item label="窗口(分钟)">
          <el-input-number v-model="form.windowMin" :min="1" :max="1440"/>
        </el-form-item>
        <el-form-item label="阈值">
          <el-input v-model="form.threshold" placeholder="如 0.05"/>
        </el-form-item>
        <el-form-item label="级别">
          <el-select v-model="form.level" style="width: 100%">
            <el-option label="info" value="info"/>
            <el-option label="warn" value="warn"/>
            <el-option label="critical" value="critical"/>
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRule">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {type AlertRuleView, createAlertRule, deleteAlertRule, listAlertRules, updateAlertRule,} from '@/api/alert'

const loading = ref(false)
const rules = ref<AlertRuleView[]>([])
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({
  name: '',
  metric: 'error_rate',
  windowMin: 5,
  threshold: '0.05',
  level: 'warn',
  enabled: true,
})

async function load() {
  loading.value = true
  try {
    rules.value = await listAlertRules()
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, {name: '', metric: 'error_rate', windowMin: 5, threshold: '0.05', level: 'warn', enabled: true})
  dialogVisible.value = true
}

function openEdit(row: AlertRuleView) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    metric: row.metric,
    windowMin: row.windowMin,
    threshold: row.threshold,
    level: row.level,
    enabled: row.enabled,
  })
  dialogVisible.value = true
}

async function saveRule() {
  if (editingId.value) {
    await updateAlertRule(editingId.value, {...form})
  } else {
    await createAlertRule({...form})
  }
  ElMessage.success('已保存')
  dialogVisible.value = false
  await load()
}

async function onDelete(row: AlertRuleView) {
  await ElMessageBox.confirm(`确认删除告警规则 "${row.name}"？`, '删除确认')
  await deleteAlertRule(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>
