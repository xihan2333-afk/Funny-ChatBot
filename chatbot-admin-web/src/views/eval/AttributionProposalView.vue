<template>
  <el-card shadow="never">
    <template #header><span>归因与改进提案审阅</span></template>
    <el-form :inline="true" class="mb-3">
      <el-form-item label="状态">
        <el-select v-model="status" clearable style="width: 140px" @change="load">
          <el-option label="pending" value="pending"/>
          <el-option label="accepted" value="accepted"/>
          <el-option label="rejected" value="rejected"/>
          <el-option label="landed" value="landed"/>
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load">刷新</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="rows" v-loading="loading" border stripe size="small">
      <el-table-column prop="id" label="ID" width="70"/>
      <el-table-column prop="sourceKind" label="来源" width="110"/>
      <el-table-column prop="target" label="落点目标" width="200"/>
      <el-table-column prop="attribution" label="归因" width="110"/>
      <el-table-column prop="status" label="状态" width="100"/>
      <el-table-column prop="problemCard" label="问题卡片" show-overflow-tooltip/>
      <el-table-column prop="reviewNote" label="审阅备注" show-overflow-tooltip/>
      <el-table-column label="操作" width="240">
        <template #default="{ row }">
          <el-button size="small" link @click="onAttr(row.id, 'agent')">归因Agent</el-button>
          <el-button size="small" link type="success" @click="onAccept(row.id)">接受</el-button>
          <el-button size="small" link type="danger" @click="onReject(row.id)">拒绝</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {acceptProposal, attributeProposal, listProposals, type ProposalView, rejectProposal,} from '@/api/eval'

const loading = ref(false)
const rows = ref<ProposalView[]>([])
const status = ref('')

async function load() {
  loading.value = true
  try {
    rows.value = await listProposals({status: status.value || undefined, page: 0, size: 20})
  } finally {
    loading.value = false
  }
}

async function onAttr(id: number, attr: string) {
  await attributeProposal(id, attr)
  ElMessage.success('已归因')
  await load()
}

async function onAccept(id: number) {
  await acceptProposal(id)
  ElMessage.success('已接受')
  await load()
}

async function onReject(id: number) {
  const {value} = await ElMessageBox.prompt('请输入拒绝原因', '拒绝提案')
  await rejectProposal(id, value || '不符合预期')
  ElMessage.success('已拒绝')
  await load()
}

onMounted(load)
</script>
