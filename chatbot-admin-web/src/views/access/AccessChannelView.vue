<template>
  <el-card shadow="never">
    <template #header>
      <div class="header-bar">
        <span>社交平台接入渠道与 Bot 实例</span>
        <el-button type="primary" @click="openCreate">新增接入渠道</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="channels" border stripe>
      <el-table-column prop="platform" label="平台" width="120"/>
      <el-table-column prop="botId" label="Bot ID" width="140"/>
      <el-table-column prop="displayName" label="展示名称" min-width="140"/>
      <el-table-column prop="transportType" label="连接方式" width="120"/>
      <el-table-column prop="endpointUrl" label="协议端地址" min-width="200" show-overflow-tooltip/>
      <el-table-column label="平台凭证（脱敏）" width="180">
        <template #default="{ row }">
          <span v-if="row.maskedCredential">
            <code>{{ row.maskedCredential }}</code>
            <el-tag size="small" style="margin-left: 6px">{{ row.credentialStatus || 'available' }}</el-tag>
          </span>
          <span v-else class="muted">未配置</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" :disabled="row.enabled" @click="handleDelete(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑接入渠道' : '新增接入渠道'" width="520px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="平台标识">
          <el-input v-model="form.platform" :disabled="Boolean(editingId)" placeholder="onebot-v11"/>
        </el-form-item>
        <el-form-item label="Bot ID" required>
          <el-input v-model="form.botId" :disabled="Boolean(editingId)" placeholder="如 10001"/>
        </el-form-item>
        <el-form-item label="展示名称">
          <el-input v-model="form.displayName" placeholder="便于运营识别的 Bot 名称"/>
        </el-form-item>
        <el-form-item label="连接方式">
          <el-select v-model="form.transportType" style="width: 100%">
            <el-option label="ws_client (正向 WebSocket)" value="ws_client"/>
            <el-option label="webhook_server" value="webhook_server"/>
            <el-option label="polling" value="polling"/>
          </el-select>
        </el-form-item>
        <el-form-item label="协议端地址">
          <el-input v-model="form.endpointUrl" placeholder="ws://napcat:3001/"/>
        </el-form-item>
        <el-form-item label="平台凭证密钥">
          <el-input
              v-model="form.credentialSecret"
              type="password"
              show-password
              :placeholder="editingId ? '留空表示不修改现有凭证' : '将加密存入 credential 表'"
          />
        </el-form-item>
        <el-form-item label="是否启用">
          <el-switch v-model="form.enabled"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {type AccessChannel, createChannel, deleteChannel, listChannels, updateChannel,} from '@/api/access'

const loading = ref(false)
const saving = ref(false)
const channels = ref<AccessChannel[]>([])
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)

const form = ref({
  platform: 'onebot-v11',
  botId: '',
  displayName: '',
  transportType: 'ws_client',
  endpointUrl: '',
  credentialSecret: '',
  enabled: true,
})

async function load() {
  loading.value = true
  try {
    channels.value = await listChannels()
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  form.value = {
    platform: 'onebot-v11',
    botId: '',
    displayName: '',
    transportType: 'ws_client',
    endpointUrl: '',
    credentialSecret: '',
    enabled: true,
  }
  dialogVisible.value = true
}

function openEdit(row: AccessChannel) {
  editingId.value = row.id
  form.value = {
    platform: row.platform,
    botId: row.botId,
    displayName: row.displayName,
    transportType: row.transportType,
    endpointUrl: row.endpointUrl || '',
    credentialSecret: '',
    enabled: row.enabled,
  }
  dialogVisible.value = true
}

async function handleSave() {
  saving.value = true
  try {
    if (editingId.value) {
      await updateChannel(editingId.value, {
        displayName: form.value.displayName,
        transportType: form.value.transportType,
        endpointUrl: form.value.endpointUrl,
        credentialSecret: form.value.credentialSecret || undefined,
        enabled: form.value.enabled,
      })
    } else {
      await createChannel({...form.value})
    }
    ElMessage.success('渠道已保存')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function handleDelete(row: AccessChannel) {
  await ElMessageBox.confirm(`确认删除停用渠道 ${row.displayName} (${row.botId}) 吗？`, '删除确认', {
    type: 'warning',
  })
  await deleteChannel(row.id)
  ElMessage.success('渠道已删除')
  await load()
}

onMounted(load)
</script>

<style scoped>
.header-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.muted {
  color: #86909c;
  font-size: 12px;
}
</style>
