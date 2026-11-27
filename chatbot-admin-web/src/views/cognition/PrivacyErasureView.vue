<template>
  <el-card shadow="never">
    <template #header>个体数据清除（按群三元组 + userId 四路彻底清除）</template>
    <el-form label-width="110px" style="max-width: 520px">
      <el-form-item label="目标群">
        <GroupSelector v-model="selectedGroup"/>
      </el-form-item>
      <el-form-item label="User ID">
        <el-input v-model="userId" placeholder="输入成员 userId"/>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :disabled="!selectedGroup || !userId" @click="doPreview">预览影响范围</el-button>
      </el-form-item>
    </el-form>

    <el-card v-if="report" shadow="never" style="margin-top: 16px; background: #f0f9eb">
      <template #header>清除执行回执</template>
      <pre style="margin: 0">{{ JSON.stringify(report, null, 2) }}</pre>
    </el-card>

    <HighRiskConfirmDialog
        v-model:visible="confirmVisible"
        title="确认执行个体数据清除"
        warning-text="该操作将不可逆地清除该用户在本群的记忆（MySQL+Qdrant）、用户画像、成员关系及会话转录记录。"
        :confirm-keyword="userId"
        :impact-items="impactItems"
        confirm-button-text="确认彻底清除"
        @confirm="executeErase"
    />
  </el-card>
</template>

<script setup lang="ts">
import {computed, ref} from 'vue'
import {ElMessage} from 'element-plus'
import GroupSelector from '@/components/GroupSelector.vue'
import HighRiskConfirmDialog from '@/components/HighRiskConfirmDialog.vue'
import type {GroupTriple} from '@/stores/ui'
import {cognitionApi, type ErasurePreview} from '@/api/cognition'

const selectedGroup = ref<GroupTriple | null>(null)
const userId = ref('')
const previewData = ref<ErasurePreview | null>(null)
const confirmVisible = ref(false)
const report = ref<any>(null)

const impactItems = computed(() => {
  if (!previewData.value) return []
  return [
    {label: '记忆条目 (MySQL + Qdrant)', value: previewData.value.memoryCount},
    {label: '用户画像是否存在', value: previewData.value.userProfileExists ? '是' : '否'},
    {label: '成员关系边数', value: previewData.value.relationCount},
    {label: '会话转录条目数', value: previewData.value.transcriptEntryCount},
  ]
})

async function doPreview() {
  if (!selectedGroup.value || !userId.value.trim()) return
  previewData.value = await cognitionApi.previewErasure(
      selectedGroup.value.platform,
      selectedGroup.value.botId,
      selectedGroup.value.groupId,
      userId.value.trim(),
  )
  confirmVisible.value = true
}

async function executeErase() {
  if (!selectedGroup.value) return
  report.value = await cognitionApi.eraseUser({
    platform: selectedGroup.value.platform,
    botId: selectedGroup.value.botId,
    groupId: selectedGroup.value.groupId,
    userId: userId.value.trim(),
    confirm: true,
  })
  confirmVisible.value = false
  ElMessage.success('个体数据已彻底清除')
}
</script>
