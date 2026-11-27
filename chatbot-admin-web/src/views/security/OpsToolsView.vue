<template>
  <div class="ops-page">
    <el-card shadow="never" style="margin-bottom: 16px">
      <template #header>凭证主密钥轮换（高危运维操作）</template>
      <el-form label-width="180px" style="max-width: 620px">
        <el-form-item label="密钥供给方式">
          <el-radio-group v-model="useEnvStagedKey">
            <el-radio :value="false">手工输入 32 字节 Base64 密钥</el-radio>
            <el-radio :value="true">使用环境变量 CHATBOT_CREDENTIAL_NEW_KEY</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="!useEnvStagedKey" label="新主密钥 (Base64)">
          <el-input v-model="newMasterKeyBase64" type="password" show-password
                    placeholder="32字节 Base64，不落日志不落库"/>
        </el-form-item>
        <el-form-item>
          <el-button type="danger" @click="confirmVisible = true">执行全量主密钥轮换</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" style="margin-bottom: 16px">
      <template #header>主密钥轮换历史</template>
      <PageTable :data="rotationHistory" @page-change="(p, ps) => loadRotation(p, ps)">
        <el-table-column prop="id" label="ID" width="120"/>
        <el-table-column prop="operator" label="操作人" width="100"/>
        <el-table-column prop="status" label="状态" width="100"/>
        <el-table-column prop="rotatedCount" label="重加密数" width="90"/>
        <el-table-column prop="skippedCount" label="跳过数" width="90"/>
        <el-table-column prop="startedAt" label="开始时间" width="180"/>
        <el-table-column prop="finishedAt" label="完成时间" width="180"/>
      </PageTable>
    </el-card>

    <el-card shadow="never">
      <template #header>重嵌入迁移任务历史</template>
      <PageTable :data="reembedHistory" @page-change="(p, ps) => loadReembed(p, ps)">
        <el-table-column prop="id" label="Job ID" width="120"/>
        <el-table-column prop="fromDimension" label="原维度" width="90"/>
        <el-table-column prop="toDimension" label="目标维度" width="90"/>
        <el-table-column prop="status" label="状态" width="100"/>
        <el-table-column prop="processedCount" label="已处理" width="90"/>
        <el-table-column prop="totalCount" label="总数" width="90"/>
        <el-table-column prop="operator" label="操作人" width="100"/>
      </PageTable>
    </el-card>

    <HighRiskConfirmDialog
        v-model:visible="confirmVisible"
        title="确认执行凭证主密钥轮换"
        warning-text="主密钥轮换将重加密 credential 表全部密文，请确保新密钥已安全备份！"
        confirm-keyword="ROTATE_KEY"
        confirm-button-text="确认轮换"
        @confirm="executeRotate"
    />
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {ElMessage} from 'element-plus'
import PageTable from '@/components/PageTable.vue'
import HighRiskConfirmDialog from '@/components/HighRiskConfirmDialog.vue'
import type {PageResult} from '@/api/http'
import {type RotationLogItem, securityApi} from '@/api/security'

const useEnvStagedKey = ref(false)
const newMasterKeyBase64 = ref('')
const confirmVisible = ref(false)

const rotationHistory = ref<PageResult<RotationLogItem>>({items: [], total: 0, page: 1, pageSize: 20})
const reembedHistory = ref<PageResult<any>>({items: [], total: 0, page: 1, pageSize: 20})

async function loadRotation(page = 1, pageSize = 20) {
  rotationHistory.value = await securityApi.listRotationHistory(page, pageSize)
}

async function loadReembed(page = 1, pageSize = 20) {
  reembedHistory.value = await securityApi.listReembedHistory(page, pageSize)
}

async function executeRotate() {
  await securityApi.rotateCredential({
    newMasterKeyBase64: useEnvStagedKey.value ? undefined : newMasterKeyBase64.value,
    useEnvStagedKey: useEnvStagedKey.value,
    confirm: true,
  })
  confirmVisible.value = false
  newMasterKeyBase64.value = ''
  ElMessage.success('主密钥轮换已完成')
  await loadRotation(1)
}

onMounted(() => {
  loadRotation(1)
  loadReembed(1)
})
</script>
