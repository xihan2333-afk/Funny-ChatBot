<template>
  <div class="model-slot-page">
    <el-alert
        v-if="completeness && !completeness.complete"
        type="warning"
        show-icon
        :closable="false"
        :title="`完整性提示：未绑定槽位 [${completeness.unboundSlots.join(', ') || '无'}]，缺凭证供应商 [${completeness.providersMissingCredentials.join(', ') || '无'}]`"
        style="margin-bottom: 16px"
    />

    <el-card v-if="activeReembedJob" shadow="never" style="margin-bottom: 16px">
      <template #header>当前重嵌入迁移任务 #{{ activeReembedJob.id }} ({{ activeReembedJob.status }})</template>
      <el-progress
          :percentage="
          activeReembedJob.totalCount > 0
            ? Math.round((activeReembedJob.processedCount / activeReembedJob.totalCount) * 100)
            : activeReembedJob.status === 'completed'
              ? 100
              : 0
        "
          :status="activeReembedJob.status === 'failed' ? 'exception' : activeReembedJob.status === 'completed' ? 'success' : undefined"
      />
      <div class="reembed-meta">
        已处理 {{ activeReembedJob.processedCount }} / {{ activeReembedJob.totalCount }}（失败
        {{ activeReembedJob.failedCount }}）
      </div>
    </el-card>

    <el-card shadow="never">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="九槽位绑定" name="slots">
          <el-table :data="slots" border stripe>
            <el-table-column prop="slotCode" label="槽位代号" width="160"/>
            <el-table-column prop="providerCode" label="供应商" width="140">
              <template #default="{ row }">{{ row.providerCode || '未绑定' }}</template>
            </el-table-column>
            <el-table-column prop="modelId" label="模型 ID" min-width="180">
              <template #default="{ row }">{{ row.modelId || '-' }}</template>
            </el-table-column>
            <el-table-column prop="genParamsJson" label="生成参数" min-width="200" show-overflow-tooltip/>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                  {{ row.enabled ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button link type="primary" @click="openSlotEdit(row)">配置绑定</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="供应商" name="providers">
          <div class="tab-toolbar">
            <el-button type="primary" size="small" @click="openProviderEdit()">新增供应商</el-button>
          </div>
          <el-table :data="providers" border stripe>
            <el-table-column prop="code" label="Code" width="130"/>
            <el-table-column prop="displayName" label="名称" width="160"/>
            <el-table-column prop="dialect" label="协议方言" width="130"/>
            <el-table-column prop="baseUrl" label="Base URL" min-width="220" show-overflow-tooltip/>
            <el-table-column prop="availableCredentialCount" label="可用凭证数" width="110"/>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{
                    row.enabled ? '启用' : '停用'
                  }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button link type="primary" @click="openProviderEdit(row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="模型清单" name="models">
          <div class="tab-toolbar">
            <el-button type="primary" size="small" @click="openModelEdit()">新增模型</el-button>
          </div>
          <el-table :data="models" border stripe>
            <el-table-column prop="providerCode" label="供应商" width="120"/>
            <el-table-column prop="modelId" label="模型 ID" width="180"/>
            <el-table-column prop="displayName" label="展示名" width="160"/>
            <el-table-column prop="capabilityJson" label="能力声明" min-width="200" show-overflow-tooltip/>
            <el-table-column prop="priceJson" label="单价" min-width="180" show-overflow-tooltip/>
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button link type="primary" @click="openModelEdit(row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="凭证池" name="credentials">
          <div class="tab-toolbar">
            <el-button type="primary" size="small" @click="credDialog = true">新增凭证</el-button>
          </div>
          <el-table :data="credentials" border stripe>
            <el-table-column prop="ownerKind" label="归属类型" width="120"/>
            <el-table-column prop="ownerCode" label="归属标识" width="160"/>
            <el-table-column prop="keyMask" label="脱敏密钥" width="180">
              <template #default="{ row }"><code>{{ row.keyMask }}</code></template>
            </el-table-column>
            <el-table-column label="状态" width="140">
              <template #default="{ row }">
                <el-tag
                    :type="row.status === 'available' ? 'success' : row.status === 'cooldown' ? 'warning' : 'danger'"
                    size="small">
                  {{ row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="cooldownUntil" label="冷却截止" width="180"/>
            <el-table-column label="操作" width="160">
              <template #default="{ row }">
                <el-button
                    link
                    type="primary"
                    @click="toggleCred(row)"
                >
                  {{ row.status === 'disabled' ? '启用' : '停用' }}
                </el-button>
                <el-button
                    link
                    type="danger"
                    :disabled="row.status !== 'disabled'"
                    @click="removeCred(row)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 槽位编辑弹窗 -->
    <el-dialog v-model="slotDialog" :title="`配置槽位：${slotForm.slotCode}`" width="500px">
      <el-form :model="slotForm" label-width="100px">
        <el-form-item label="供应商">
          <el-select v-model="slotForm.providerCode" style="width: 100%">
            <el-option v-for="p in providers" :key="p.code" :label="p.displayName" :value="p.code"/>
          </el-select>
        </el-form-item>
        <el-form-item label="模型">
          <el-select v-model="slotForm.modelId" style="width: 100%">
            <el-option
                v-for="m in models.filter((x) => x.providerCode === slotForm.providerCode)"
                :key="m.modelId"
                :label="m.displayName || m.modelId"
                :value="m.modelId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="生成参数">
          <el-input v-model="slotForm.genParamsJson" type="textarea" :rows="3"/>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="slotForm.enabled"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="slotDialog = false">取消</el-button>
        <el-button type="primary" @click="saveSlot">保存</el-button>
      </template>
    </el-dialog>

    <!-- 供应商弹窗 -->
    <el-dialog v-model="providerDialog" :title="providerForm.id ? '编辑供应商' : '新增供应商'" width="500px">
      <el-form :model="providerForm" label-width="100px">
        <el-form-item label="Code">
          <el-input v-model="providerForm.code" :disabled="Boolean(providerForm.id)"/>
        </el-form-item>
        <el-form-item label="展示名">
          <el-input v-model="providerForm.displayName"/>
        </el-form-item>
        <el-form-item label="方言">
          <el-select v-model="providerForm.dialect" style="width: 100%">
            <el-option label="openai-compat" value="openai-compat"/>
            <el-option label="gemini" value="gemini"/>
          </el-select>
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="providerForm.baseUrl"/>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="providerForm.enabled"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="providerDialog = false">取消</el-button>
        <el-button type="primary" @click="saveProvider">保存</el-button>
      </template>
    </el-dialog>

    <!-- 模型弹窗 -->
    <el-dialog v-model="modelDialog" :title="modelForm.id ? '编辑模型' : '新增模型'" width="520px">
      <el-form :model="modelForm" label-width="100px">
        <el-form-item label="供应商">
          <el-input v-model="modelForm.providerCode" :disabled="Boolean(modelForm.id)"/>
        </el-form-item>
        <el-form-item label="模型 ID">
          <el-input v-model="modelForm.modelId" :disabled="Boolean(modelForm.id)"/>
        </el-form-item>
        <el-form-item label="展示名">
          <el-input v-model="modelForm.displayName"/>
        </el-form-item>
        <el-form-item label="能力 JSON">
          <el-input v-model="modelForm.capabilityJson" type="textarea" :rows="3"/>
        </el-form-item>
        <el-form-item label="单价 JSON">
          <el-input v-model="modelForm.priceJson" type="textarea" :rows="2"/>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="modelForm.enabled"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="modelDialog = false">取消</el-button>
        <el-button type="primary" @click="saveModel">保存</el-button>
      </template>
    </el-dialog>

    <!-- 凭证弹窗 -->
    <el-dialog v-model="credDialog" title="新增凭证（密文落库）" width="460px">
      <el-form :model="credForm" label-width="100px">
        <el-form-item label="归属类型">
          <el-select v-model="credForm.ownerKind" style="width: 100%">
            <el-option label="provider" value="provider"/>
            <el-option label="http_tool" value="http_tool"/>
            <el-option label="platform" value="platform"/>
          </el-select>
        </el-form-item>
        <el-form-item label="归属标识">
          <el-input v-model="credForm.ownerCode"/>
        </el-form-item>
        <el-form-item label="明文密钥">
          <el-input v-model="credForm.secretPlain" type="password" show-password/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="credDialog = false">取消</el-button>
        <el-button type="primary" @click="saveCred">保存</el-button>
      </template>
    </el-dialog>

    <!-- Embedding 维度冲突阻断 + 确认发起重嵌入 -->
    <HighRiskConfirmDialog
        v-model:visible="reembedConfirm.visible"
        title="Embedding 模型维度冲突，需要全量重嵌入迁移"
        warning-text="直接切换不同维度的 Embedding 模型会导致 Qdrant 向量检索失败。确认后将异步创建新集合并全量重嵌入活跃记忆。"
        :impact-items="reembedImpact"
        confirm-button-text="确认发起重嵌入迁移"
        @confirm="confirmStartReembed"
    />
  </div>
</template>

<script setup lang="ts">
import {computed, onMounted, reactive, ref} from 'vue'
import {ElMessage} from 'element-plus'
import {ApiError} from '@/api/http'
import HighRiskConfirmDialog from '@/components/HighRiskConfirmDialog.vue'
import {
  type CompletenessView,
  type CredentialItem,
  llmApi,
  type ModelItem,
  type ProviderItem,
  type ReembedJobItem,
  type SlotItem,
} from '@/api/capability'

const activeTab = ref('slots')
const completeness = ref<CompletenessView | null>(null)
const providers = ref<ProviderItem[]>([])
const models = ref<ModelItem[]>([])
const slots = ref<SlotItem[]>([])
const credentials = ref<CredentialItem[]>([])
const activeReembedJob = ref<ReembedJobItem | null>(null)

const slotDialog = ref(false)
const slotForm = reactive({slotCode: '', providerCode: '', modelId: '', genParamsJson: '{}', enabled: true})

const providerDialog = ref(false)
const providerForm = reactive({id: 0, code: '', displayName: '', dialect: 'openai', baseUrl: '', enabled: true})

const modelDialog = ref(false)
const modelForm = reactive({
  id: 0,
  providerCode: '',
  modelId: '',
  displayName: '',
  capabilityJson: '{}',
  priceJson: '{}',
  enabled: true
})

const credDialog = ref(false)
const credForm = reactive({ownerKind: 'provider', ownerCode: '', secretPlain: ''})

const reembedConfirm = reactive<{
  visible: boolean
  targetModelId: number
  currentDimension: number
  targetDimension: number
  affectedMemoryCount: number
}>({
  visible: false,
  targetModelId: 0,
  currentDimension: 0,
  targetDimension: 0,
  affectedMemoryCount: 0,
})

const reembedImpact = computed(() => [
  {label: '当前集合维度', value: reembedConfirm.currentDimension},
  {label: '目标模型维度', value: reembedConfirm.targetDimension},
  {label: '受影响活跃记忆数', value: reembedConfirm.affectedMemoryCount},
])

async function loadAll() {
  const [comp, prov, mod, sl, cred, jobs] = await Promise.all([
    llmApi.completeness(),
    llmApi.listProviders(),
    llmApi.listModels(),
    llmApi.listSlots(),
    llmApi.listCredentials(),
    llmApi.listReembedJobs(1, 1),
  ])
  completeness.value = comp
  providers.value = prov
  models.value = mod
  slots.value = sl
  credentials.value = cred
  activeReembedJob.value = jobs.items[0] || null
}

function openSlotEdit(row: SlotItem) {
  Object.assign(slotForm, {
    slotCode: row.slotCode,
    providerCode: row.providerCode || providers.value[0]?.code || '',
    modelId: row.modelId || '',
    genParamsJson: row.genParamsJson || '{}',
    enabled: row.enabled,
  })
  slotDialog.value = true
}

async function saveSlot() {
  try {
    await llmApi.updateSlot(slotForm.slotCode, {
      providerCode: slotForm.providerCode,
      modelId: slotForm.modelId,
      genParamsJson: slotForm.genParamsJson,
      enabled: slotForm.enabled,
    })
    ElMessage.success('槽位已更新')
    slotDialog.value = false
    await loadAll()
  } catch (err) {
    if (err instanceof ApiError && err.errorCode === 'llm.embedding_dimension_mismatch') {
      const targetModel = models.value.find(
          (m) => m.providerCode === slotForm.providerCode && m.modelId === slotForm.modelId,
      )
      reembedConfirm.targetModelId = targetModel?.id || 0
      reembedConfirm.currentDimension = Number(err.payload?.currentDimension || 0)
      reembedConfirm.targetDimension = Number(err.payload?.targetDimension || 0)
      reembedConfirm.affectedMemoryCount = Number(err.payload?.affectedMemoryCount || 0)
      reembedConfirm.visible = true
    } else if (err instanceof ApiError) {
      ElMessage.error(err.message)
    }
  }
}

async function confirmStartReembed() {
  if (!reembedConfirm.targetModelId) return
  const job = await llmApi.startReembed(reembedConfirm.targetModelId, true)
  activeReembedJob.value = job
  reembedConfirm.visible = false
  slotDialog.value = false
  ElMessage.success(`重嵌入迁移任务 #${job.id} 已发起`)
  await loadAll()
}

function openProviderEdit(row?: ProviderItem) {
  if (row) {
    Object.assign(providerForm, row)
  } else {
    Object.assign(providerForm, {id: 0, code: '', displayName: '', dialect: 'openai', baseUrl: '', enabled: true})
  }
  providerDialog.value = true
}

async function saveProvider() {
  if (providerForm.id) {
    await llmApi.updateProvider(providerForm.id, providerForm)
  } else {
    await llmApi.createProvider(providerForm)
  }
  ElMessage.success('供应商已保存')
  providerDialog.value = false
  await loadAll()
}

function openModelEdit(row?: ModelItem) {
  if (row) {
    Object.assign(modelForm, row)
  } else {
    Object.assign(modelForm, {
      id: 0,
      providerCode: '',
      modelId: '',
      displayName: '',
      capabilityJson: '{}',
      priceJson: '{}',
      enabled: true
    })
  }
  modelDialog.value = true
}

async function saveModel() {
  if (modelForm.id) {
    await llmApi.updateModel(modelForm.id, modelForm)
  } else {
    await llmApi.createModel(modelForm)
  }
  ElMessage.success('模型已保存')
  modelDialog.value = false
  await loadAll()
}

async function saveCred() {
  await llmApi.addCredential(credForm)
  ElMessage.success('凭证已加密保存')
  credDialog.value = false
  credForm.secretPlain = ''
  await loadAll()
}

async function toggleCred(row: CredentialItem) {
  await llmApi.setCredentialEnabled(row.id, row.status === 'disabled')
  await loadAll()
}

async function removeCred(row: CredentialItem) {
  await llmApi.deleteCredential(row.id)
  await loadAll()
}

onMounted(loadAll)
</script>

<style scoped>
.tab-toolbar {
  margin-bottom: 12px;
  display: flex;
  justify-content: flex-end;
}

.reembed-meta {
  margin-top: 8px;
  font-size: 13px;
  color: #4e5969;
}
</style>
