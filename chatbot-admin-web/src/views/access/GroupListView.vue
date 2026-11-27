<template>
  <el-card shadow="never">
    <template #header>
      <div class="header-bar">
        <span>服务群列表与生命周期管理（群聊由入事件流自动发现，不提供手动加群）</span>
        <el-button size="small" @click="load()">刷新</el-button>
      </div>
    </template>

    <el-form :inline="true" class="filter-bar">
      <el-form-item label="生命周期">
        <el-select v-model="filters.lifecycle" clearable placeholder="全部" style="width: 140px">
          <el-option label="DISCOVERED" value="DISCOVERED"/>
          <el-option label="ACTIVE" value="ACTIVE"/>
          <el-option label="PAUSED" value="PAUSED"/>
          <el-option label="DESTROYED" value="DESTROYED"/>
        </el-select>
      </el-form-item>
      <el-form-item label="服务名单">
        <el-select v-model="filters.inService" clearable placeholder="全部" style="width: 120px">
          <el-option label="已启用" :value="true"/>
          <el-option label="未启用" :value="false"/>
        </el-select>
      </el-form-item>
      <el-form-item label="关键字">
        <el-input v-model="filters.keyword" placeholder="群号或群名称" clearable/>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load(1)">查询</el-button>
      </el-form-item>
    </el-form>

    <PageTable :data="pageData" :loading="loading" @page-change="(p, ps) => load(p, ps)">
      <el-table-column prop="groupName" label="群名称" min-width="140">
        <template #default="{ row }">{{ row.groupName || row.groupId }}</template>
      </el-table-column>
      <el-table-column label="群三元组" min-width="200">
        <template #default="{ row }">
          <code>{{ row.platform }}/{{ row.botId }}/{{ row.groupId }}</code>
        </template>
      </el-table-column>
      <el-table-column prop="lifecycle" label="生命周期" width="120">
        <template #default="{ row }">
          <el-tag :type="lifecycleTag(row.lifecycle)" size="small">{{ row.lifecycle }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="服务名单" width="100">
        <template #default="{ row }">
          <el-switch
              :model-value="row.inService"
              :disabled="row.lifecycle === 'DESTROYED'"
              @change="(val: boolean) => toggleInService(row, val)"
          />
        </template>
      </el-table-column>
      <el-table-column prop="personaName" label="绑定人格" width="130">
        <template #default="{ row }">{{ row.personaName || '未绑定（默认）' }}</template>
      </el-table-column>
      <el-table-column prop="inFlightTaskCount" label="在途任务" width="90"/>
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="goConfig(row)">群配置</el-button>
          <el-button
              v-if="row.lifecycle === 'ACTIVE'"
              link
              type="warning"
              @click="handlePause(row)"
          >
            暂停
          </el-button>
          <el-button
              v-if="row.lifecycle === 'PAUSED'"
              link
              type="success"
              @click="handleResume(row)"
          >
            恢复
          </el-button>
          <el-button
              v-if="row.lifecycle !== 'DESTROYED'"
              link
              type="danger"
              @click="openDestroy(row)"
          >
            销毁
          </el-button>
        </template>
      </el-table-column>
    </PageTable>

    <HighRiskConfirmDialog
        v-model:visible="destroyDialog.visible"
        title="高危操作：销毁群会话与任务"
        warning-text="销毁将终止该群全部活跃 Worker、取消非终态 Task 并卸载会话。触发器与决策历史将永久保留。"
        :confirm-keyword="destroyDialog.target?.groupId || ''"
        :impact-items="destroyImpact"
        :loading="destroyDialog.loading"
        confirm-button-text="确认销毁群"
        @confirm="confirmDestroy"
    >
      <el-checkbox v-model="destroyDialog.retainTranscriptToMemory" style="margin-top: 6px">
        销毁前将未沉淀会话转录沉淀为长期记忆（keep）
      </el-checkbox>
    </HighRiskConfirmDialog>
  </el-card>
</template>

<script setup lang="ts">
import {computed, onMounted, reactive, ref} from 'vue'
import {useRouter} from 'vue-router'
import {ElMessage, ElMessageBox} from 'element-plus'
import PageTable from '@/components/PageTable.vue'
import HighRiskConfirmDialog from '@/components/HighRiskConfirmDialog.vue'
import type {PageResult} from '@/api/http'
import {
  destroyGroup,
  type GroupLifecyclePrecheck,
  type GroupListItem,
  listGroups,
  pauseGroup,
  precheckGroupLifecycle,
  resumeGroup,
  setGroupInService,
} from '@/api/access'

const router = useRouter()
const loading = ref(false)
const filters = reactive({
  lifecycle: '',
  inService: '' as boolean | '',
  keyword: '',
})
const pageData = ref<PageResult<GroupListItem>>({
  items: [],
  total: 0,
  page: 1,
  pageSize: 20,
})

const destroyDialog = reactive<{
  visible: boolean
  loading: boolean
  target: GroupListItem | null
  precheck: GroupLifecyclePrecheck | null
  retainTranscriptToMemory: boolean
}>({
  visible: false,
  loading: false,
  target: null,
  precheck: null,
  retainTranscriptToMemory: true,
})

const destroyImpact = computed(() => {
  if (!destroyDialog.precheck) return []
  return [
    {label: '在途任务数（将被取消）', value: destroyDialog.precheck.inFlightTaskCount},
    {label: '活跃 Worker 数（将被终止）', value: destroyDialog.precheck.activeWorkerCount},
    {label: '是否存在活跃会话', value: destroyDialog.precheck.hasSession ? '是' : '否'},
  ]
})

function lifecycleTag(s: string) {
  if (s === 'ACTIVE') return 'success'
  if (s === 'PAUSED') return 'warning'
  if (s === 'DESTROYED') return 'danger'
  return 'info'
}

async function load(page = pageData.value.page, pageSize = pageData.value.pageSize) {
  loading.value = true
  try {
    pageData.value = await listGroups({
      lifecycle: filters.lifecycle || undefined,
      inService: filters.inService === '' ? undefined : filters.inService,
      keyword: filters.keyword || undefined,
      page,
      pageSize,
    })
  } finally {
    loading.value = false
  }
}

async function toggleInService(row: GroupListItem, val: boolean) {
  await setGroupInService(row.platform, row.botId, row.groupId, val)
  row.inService = val
  ElMessage.success(val ? '已加入服务名单' : '已移出服务名单')
}

function goConfig(row: GroupListItem) {
  router.push(
      `/access/groups/${encodeURIComponent(row.platform)}/${encodeURIComponent(row.botId)}/${encodeURIComponent(row.groupId)}/config`,
  )
}

async function handlePause(row: GroupListItem) {
  const pre = await precheckGroupLifecycle(row.platform, row.botId, row.groupId)
  await ElMessageBox.confirm(
      `当前有 ${pre.inFlightTaskCount} 个在途任务、${pre.activeWorkerCount} 个活跃 Worker。暂停将冻结管线并顺延计时，确认暂停吗？`,
      '暂停群确认',
      {type: 'warning'},
  )
  await pauseGroup(row.platform, row.botId, row.groupId)
  ElMessage.success('群已暂停')
  await load()
}

async function handleResume(row: GroupListItem) {
  await resumeGroup(row.platform, row.botId, row.groupId)
  ElMessage.success('群已恢复')
  await load()
}

async function openDestroy(row: GroupListItem) {
  const pre = await precheckGroupLifecycle(row.platform, row.botId, row.groupId)
  destroyDialog.target = row
  destroyDialog.precheck = pre
  destroyDialog.retainTranscriptToMemory = pre.transcriptRetainPolicy !== 'discard'
  destroyDialog.visible = true
}

async function confirmDestroy() {
  if (!destroyDialog.target) return
  destroyDialog.loading = true
  try {
    const res = await destroyGroup(
        destroyDialog.target.platform,
        destroyDialog.target.botId,
        destroyDialog.target.groupId,
        destroyDialog.retainTranscriptToMemory,
        destroyDialog.target.groupId,
    )
    ElMessage.success(res.notice)
    destroyDialog.visible = false
    await load()
  } finally {
    destroyDialog.loading = false
  }
}

onMounted(() => load(1))
</script>

<style scoped>
.header-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.filter-bar {
  margin-bottom: 12px;
}
</style>
