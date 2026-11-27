<template>
  <div>
    <el-card shadow="never" style="margin-bottom: 16px">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span>成本熔断状态</span>
          <el-button type="danger" size="small" @click="openRelease('global')">手动解除全局熔断</el-button>
        </div>
      </template>
      <div v-if="snapshot">
        <div>全局状态：
          <el-tag :type="snapshot.globalTripped ? 'danger' : 'success'">{{
              snapshot.globalTripped ? '已熔断' : '正常'
            }}
          </el-tag>
        </div>
        <div style="margin-top: 8px">当日消费：{{ snapshot.globalUsage }} / 限额 {{ snapshot.globalLimit }}</div>
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>熔断触发与解除历史</template>
      <el-table :data="history" border stripe>
        <el-table-column prop="eventId" label="ID" width="100"/>
        <el-table-column prop="groupId" label="群号" width="130"/>
        <el-table-column prop="detailJson" label="详情" min-width="240" show-overflow-tooltip/>
        <el-table-column label="Trace" width="140">
          <template #default="{ row }">
            <TraceLink :trace-id="row.traceId" short/>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="180"/>
      </el-table>
    </el-card>

    <HighRiskConfirmDialog
        v-model:visible="releaseDialog.visible"
        title="确认解除成本熔断"
        warning-text="解除熔断后系统将恢复重任务与主动行为调用，请确认今日额度风险可控。"
        confirm-keyword="RELEASE"
        confirm-button-text="确认解除"
        @confirm="confirmRelease"
    />
  </div>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from 'vue'
import {ElMessage} from 'element-plus'
import HighRiskConfirmDialog from '@/components/HighRiskConfirmDialog.vue'
import TraceLink from '@/components/TraceLink.vue'
import {runtimeApi} from '@/api/runtime'

const snapshot = ref<any>(null)
const history = ref<any[]>([])
const releaseDialog = reactive({visible: false, scope: 'global'})

async function load() {
  const [snap, hist] = await Promise.all([runtimeApi.breakerStatus(), runtimeApi.breakerHistory()])
  snapshot.value = snap
  history.value = hist
}

function openRelease(scope: string) {
  releaseDialog.scope = scope
  releaseDialog.visible = true
}

async function confirmRelease() {
  await runtimeApi.releaseBreaker(releaseDialog.scope)
  releaseDialog.visible = false
  ElMessage.success('熔断已解除')
  await load()
}

onMounted(load)
</script>
