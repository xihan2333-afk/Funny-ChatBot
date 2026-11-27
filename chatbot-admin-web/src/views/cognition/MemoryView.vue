<template>
  <el-card shadow="never">
    <el-form :inline="true" style="margin-bottom: 12px">
      <el-form-item label="群">
        <GroupSelector v-model="selectedGroup"/>
      </el-form-item>
      <el-form-item label="类型">
        <el-select v-model="filters.memoryType" clearable placeholder="全部" style="width: 130px">
          <el-option v-for="t in ['episode', 'fact', 'topic', 'preference', 'relationClue']" :key="t" :label="t"
                     :value="t"/>
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="filters.status" clearable placeholder="全部" style="width: 120px">
          <el-option label="active" value="active"/>
          <el-option label="archived" value="archived"/>
          <el-option label="superseded" value="superseded"/>
        </el-select>
      </el-form-item>
      <el-form-item label="关键字">
        <el-input v-model="filters.keyword" clearable/>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load(1)">检索</el-button>
      </el-form-item>
    </el-form>

    <PageTable :data="pageData" :loading="loading" @page-change="(p, ps) => load(p, ps)">
      <el-table-column prop="id" label="ID" width="120"/>
      <el-table-column prop="memoryType" label="类型" width="110"/>
      <el-table-column prop="content" label="正文" min-width="240" show-overflow-tooltip/>
      <el-table-column prop="strength" label="强度" width="80"/>
      <el-table-column prop="status" label="状态" width="100"/>
      <el-table-column label="操作" width="260">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row.id)">溯源/编辑</el-button>
          <el-button v-if="row.status === 'archived'" link type="success" @click="restore(row.id)">恢复</el-button>
          <el-button v-if="row.status === 'superseded'" link type="warning" @click="revoke(row.id)">撤销取代</el-button>
          <el-button link type="danger" @click="del(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </PageTable>

    <el-drawer v-model="drawer" title="记忆详情、溯源链与修正" size="600px">
      <template v-if="detail">
        <el-alert type="info" :closable="false" title="编辑正文保存后将自动重新嵌入并同步更新 Qdrant 向量索引。"
                  style="margin-bottom: 12px"/>
        <el-input v-model="editContent" type="textarea" :rows="4" style="margin-bottom: 10px"/>
        <el-button type="primary" size="small" @click="saveEdit" style="margin-bottom: 16px">保存正文修正</el-button>

        <div v-if="detail.supersededByEntry" style="margin-bottom: 14px">
          <h4>取代时间线</h4>
          <el-tag type="warning">已被记忆 #{{ detail.supersededByEntry.id }} 取代：{{
              detail.supersededByEntry.content
            }}
          </el-tag>
        </div>

        <div v-if="detail.sedimentJob">
          <h4>溯源链：记忆 ← 沉淀工单 #{{ detail.sedimentJob.id }} ← 转录原文</h4>
          <el-table :data="detail.transcriptEntries" border size="small">
            <el-table-column prop="occurredAt" label="时间" width="170"/>
            <el-table-column prop="kind" label="类型" width="80"/>
            <el-table-column prop="payload" label="原文"/>
          </el-table>
        </div>
      </template>
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import GroupSelector from '@/components/GroupSelector.vue'
import PageTable from '@/components/PageTable.vue'
import type {PageResult} from '@/api/http'
import type {GroupTriple} from '@/stores/ui'
import {cognitionApi, type MemoryDetail, type MemorySummary} from '@/api/cognition'

const loading = ref(false)
const selectedGroup = ref<GroupTriple | null>(null)
const filters = reactive({memoryType: '', status: '', keyword: ''})
const pageData = ref<PageResult<MemorySummary>>({items: [], total: 0, page: 1, pageSize: 20})
const drawer = ref(false)
const detail = ref<MemoryDetail | null>(null)
const editContent = ref('')

async function load(page = 1, pageSize = pageData.value.pageSize) {
  loading.value = true
  try {
    pageData.value = await cognitionApi.listMemories({
      platform: selectedGroup.value?.platform,
      botId: selectedGroup.value?.botId,
      groupId: selectedGroup.value?.groupId,
      memoryType: filters.memoryType || undefined,
      status: filters.status || undefined,
      keyword: filters.keyword || undefined,
      page,
      pageSize,
    })
  } finally {
    loading.value = false
  }
}

async function openDetail(id: number) {
  detail.value = await cognitionApi.getMemory(id)
  editContent.value = detail.value.content
  drawer.value = true
}

async function saveEdit() {
  if (!detail.value) return
  await cognitionApi.reviseMemory(detail.value.id, editContent.value)
  ElMessage.success('记忆正文已修正并重嵌入')
  drawer.value = false
  await load()
}

async function restore(id: number) {
  await cognitionApi.restoreMemory(id)
  ElMessage.success('已恢复为 active')
  await load()
}

async function revoke(id: number) {
  await cognitionApi.revokeSupersede(id)
  ElMessage.success('已撤销取代关系')
  await load()
}

async function del(id: number) {
  await ElMessageBox.confirm('确认同时从 MySQL 与 Qdrant 删除该记忆吗？', '删除确认', {type: 'warning'})
  await cognitionApi.deleteMemory(id)
  ElMessage.success('已双库同删')
  await load()
}

onMounted(() => load(1))
</script>
