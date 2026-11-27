<template>
  <el-dialog
      :model-value="visible"
      :title="mode === 'rollback' ? `回滚至版本 v${targetVersion}` : '发布门禁检查与确认'"
      width="560px"
      :close-on-click-modal="false"
      @update:model-value="handleClose"
  >
    <div class="gate-header">
      <span>目标实体：</span>
      <el-tag size="small">{{ entityKind }}:{{ entityKey }}</el-tag>
    </div>

    <div v-if="gateStatus" class="gate-status-box">
      <div class="status-row">
        <span>门禁状态：</span>
        <el-tag :type="statusTagType">{{ statusLabel }}</el-tag>
        <span class="case-stat">
          通过 {{ gateStatus.passedCases }} / {{ gateStatus.totalCases }} 题
          <template v-if="gateStatus.emptySubset">（必过子集为空）</template>
        </span>
      </div>

      <el-alert
          v-if="gateStatus.summary"
          :type="alertType"
          :title="gateStatus.summary"
          :closable="false"
          show-icon
          style="margin-top: 10px"
      />

      <ul v-if="gateStatus.experienceWarnings?.length" class="warning-list">
        <li v-for="(w, idx) in gateStatus.experienceWarnings" :key="idx">{{ w }}</li>
      </ul>
    </div>

    <div v-if="isRed" class="override-box">
      <div class="override-label">红牌越过理由（必填，将写入安全审计日志）：</div>
      <el-input
          v-model="overrideReason"
          type="textarea"
          :rows="3"
          placeholder="请说明为何在红线题未通过的情况下仍要强制发布..."
      />
    </div>

    <template #footer>
      <el-button @click="handleClose(false)">取消</el-button>
      <el-button
          v-if="gateStatus && gateStatus.status !== 'running'"
          :loading="triggering"
          @click="triggerGate"
      >
        重新回测
      </el-button>
      <el-button
          type="primary"
          :loading="publishing"
          :disabled="!canPublish"
          @click="confirmPublish"
      >
        {{ mode === 'rollback' ? '确认回滚并发布' : '确认发布' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import {computed, onUnmounted, ref, watch} from 'vue'
import {http} from '@/api/http'

export interface GateStatus {
  intention?: string
  status: 'idle' | 'running' | 'green' | 'yellow' | 'red' | 'passed' | string
  emptySubset: boolean
  totalCases: number
  passedCases: number
  verdicts?: unknown[]
  redLinePassed?: boolean
  experienceWarnings?: string[]
  summary?: string
}

const props = withDefaults(
    defineProps<{
      visible: boolean
      entityKind: 'fragment' | 'persona' | 'skill' | 'tool_config' | 'http_tool_config'
      entityKey: string | number
      mode?: 'publish' | 'rollback'
      targetVersion?: number | null
      publishExecutor: (overrideReason: string | null) => Promise<void>
    }>(),
    {
      mode: 'publish',
      targetVersion: null,
    },
)

const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
  (e: 'published'): void
}>()

const gateStatus = ref<GateStatus | null>(null)
const triggering = ref(false)
const publishing = ref(false)
const overrideReason = ref('')
let pollTimer: ReturnType<typeof setInterval> | null = null

const intention = computed(() => `${props.entityKind}:${props.entityKey}`)
const isRed = computed(() => gateStatus.value?.status === 'red')
const isRunning = computed(() => gateStatus.value?.status === 'running')

const statusTagType = computed(() => {
  const s = gateStatus.value?.status
  if (s === 'red') return 'danger'
  if (s === 'yellow') return 'warning'
  if (s === 'green' || s === 'passed') return 'success'
  return 'info'
})

const alertType = computed(() => {
  const s = gateStatus.value?.status
  if (s === 'red') return 'error'
  if (s === 'yellow') return 'warning'
  if (s === 'green' || s === 'passed') return 'success'
  return 'info'
})

const statusLabel = computed(() => {
  const s = gateStatus.value?.status
  if (s === 'running') return '回测执行中...'
  if (s === 'red') return '红牌（红线未通过）'
  if (s === 'yellow') return '黄牌（体验警告）'
  if (s === 'green' || s === 'passed') return '绿牌（通过）'
  return s || '未知'
})

const canPublish = computed(() => {
  if (!gateStatus.value || isRunning.value) return false
  if (isRed.value) {
    return overrideReason.value.trim().length > 0
  }
  return true
})

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function pollStatus() {
  try {
    const res = await http.get<GateStatus>(
        `/api/management/publish-gate/status?intention=${encodeURIComponent(intention.value)}`,
        {silent: true},
    )
    gateStatus.value = res
    if (res.status !== 'running') {
      stopPolling()
    }
  } catch {
    stopPolling()
  }
}

async function triggerGate() {
  triggering.value = true
  try {
    const res = await http.post<GateStatus>(
        `/api/management/publish-gate/trigger?intention=${encodeURIComponent(intention.value)}`,
    )
    gateStatus.value = res
    if (res.status === 'running') {
      stopPolling()
      pollTimer = setInterval(pollStatus, 1000)
    }
  } finally {
    triggering.value = false
  }
}

async function confirmPublish() {
  if (!canPublish.value) return
  publishing.value = true
  try {
    const reason = isRed.value ? overrideReason.value.trim() : null
    await props.publishExecutor(reason)
    emit('published')
    handleClose(false)
  } finally {
    publishing.value = false
  }
}

function handleClose(v: boolean) {
  if (!v) {
    stopPolling()
  }
  emit('update:visible', v)
}

watch(
    () => props.visible,
    (val) => {
      if (val) {
        overrideReason.value = ''
        gateStatus.value = null
        triggerGate()
      } else {
        stopPolling()
      }
    },
)

onUnmounted(() => {
  stopPolling()
})
</script>

<style scoped>
.gate-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.status-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.case-stat {
  font-size: 13px;
  color: #4e5969;
}

.warning-list {
  margin: 10px 0 0;
  padding-left: 20px;
  color: #d97706;
  font-size: 13px;
}

.override-box {
  margin-top: 14px;
}

.override-label {
  font-size: 13px;
  font-weight: 600;
  color: #cf1322;
  margin-bottom: 6px;
}
</style>
