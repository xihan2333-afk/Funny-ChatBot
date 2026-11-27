<template>
  <div v-loading="loading" class="group-config-page">
    <div class="top-bar">
      <div class="title-wrap">
        <el-button size="small" @click="router.push('/access/groups')">返回群列表</el-button>
        <span class="group-title">
          {{ config?.groupName || groupId }}（{{ platform }}/{{ botId }}/{{ groupId }}）
        </span>
      </div>
      <div class="actions-wrap">
        <el-button @click="historyDrawer = true">人格切换历史</el-button>
        <el-button type="primary" :loading="saving" @click="handleSaveWithPrecheck">
          保存全页配置
        </el-button>
      </div>
    </div>

    <el-row :gutter="16">
      <el-col :span="12">
        <el-card shadow="never" class="block-card">
          <template #header>1. 人格绑定</template>
          <el-form label-width="110px">
            <el-form-item label="绑定人格">
              <el-select v-model="form.personaId" clearable placeholder="跟随默认人格" style="width: 100%">
                <el-option
                    v-for="p in personas"
                    :key="p.id"
                    :label="p.name"
                    :value="p.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="切换说明">
              <el-input v-model="form.personaSwitchReason" placeholder="切换人格时记录至历史的原因（可选）"/>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card shadow="never" class="block-card">
          <template #header>2. Mandate 任务授权允许清单</template>
          <el-form label-width="140px">
            <el-form-item label="member 类允许类别">
              <el-select
                  v-model="form.allowedMemberTaskCategories"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  placeholder="输入任务类别后回车"
                  style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="autonomous 允许类别">
              <el-select
                  v-model="form.allowedAutonomousCategories"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  placeholder="输入自主类别后回车"
                  style="width: 100%"
              />
            </el-form-item>
          </el-form>
        </el-card>

        <el-card shadow="never" class="block-card">
          <template #header>3. 工具与 Skill 群级开关</template>
          <el-form label-width="120px">
            <el-form-item label="禁用工具列表">
              <el-select
                  v-model="form.disabledTools"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  placeholder="输入禁用的工具名"
                  style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="工具参数覆盖 JSON">
              <el-input
                  v-model="toolParamOverridesJson"
                  type="textarea"
                  :rows="3"
                  placeholder='{"webSearch": {"maxResults": 5}}'
              />
            </el-form-item>
            <el-form-item label="禁用 Skill 列表">
              <el-select
                  v-model="form.disabledSkills"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  placeholder="输入禁用的 Skill 名"
                  style="width: 100%"
              />
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never" class="block-card">
          <template #header>4. 主动行为参数覆盖</template>
          <el-form label-width="140px">
            <el-form-item label="破冰静默阈值(分)">
              <el-input-number v-model="form.proactiveParams.silenceBreakMinutes" :min="1"/>
            </el-form-item>
            <el-form-item label="最小间隔(小时)">
              <el-input-number v-model="form.proactiveParams.minIntervalHours" :min="0"/>
            </el-form-item>
            <el-form-item label="每日主动预算(次)">
              <el-input-number v-model="form.proactiveParams.dailyBudget" :min="0"/>
            </el-form-item>
            <el-form-item label="活跃时段覆盖">
              <el-input v-model="form.proactiveParams.activeHoursOverride" placeholder="如 09:00-23:00"/>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card shadow="never" class="block-card">
          <template #header>5. 群级用户黑名单</template>
          <el-select
              v-model="form.userBlacklist"
              multiple
              filterable
              allow-create
              default-first-option
              placeholder="输入需拉黑的 userId 后回车"
              style="width: 100%"
          />
        </el-card>

        <el-card shadow="never" class="block-card">
          <template #header>6. 群规与销毁沉淀策略</template>
          <el-form label-width="120px">
            <el-form-item label="群规文本">
              <el-input v-model="form.groupRules" type="textarea" :rows="4" placeholder="群上下文层参考群规"/>
            </el-form-item>
            <el-form-item label="销毁沉淀策略">
              <el-radio-group v-model="form.transcriptRetainPolicy">
                <el-radio value="keep">keep（沉淀保留）</el-radio>
                <el-radio value="discard">discard（丢弃）</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="precheckDialog.visible" title="Mandate 收紧影响预检" width="540px">
      <el-alert
          type="warning"
          :closable="false"
          show-icon
          :title="`保存本次配置将导致 ${precheckDialog.result?.affectedTaskCount || 0} 个在途任务失格并被自动取消：`"
          style="margin-bottom: 12px"
      />
      <el-table :data="precheckDialog.result?.affectedTasks || []" size="small" border>
        <el-table-column prop="taskId" label="Task ID" width="110"/>
        <el-table-column prop="taskKind" label="类型" width="100"/>
        <el-table-column prop="category" label="类别"/>
        <el-table-column prop="status" label="当前状态" width="100"/>
      </el-table>
      <template #footer>
        <el-button @click="precheckDialog.visible = false">取消保存</el-button>
        <el-button type="danger" :loading="saving" @click="executeSave">
          确认保存并取消上述任务
        </el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="historyDrawer" title="群人格切换历史" size="520px">
      <el-table :data="config?.recentPersonaHistory || []" border size="small">
        <el-table-column prop="switchedAt" label="切换时间" width="170"/>
        <el-table-column label="变更">
          <template #default="{ row }">
            {{ row.fromPersonaName || '未绑定' }} → {{ row.toPersonaName }}
          </template>
        </el-table-column>
        <el-table-column prop="operator" label="操作人" width="90"/>
        <el-table-column prop="reason" label="原因"/>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {http} from '@/api/http'
import {
  getGroupConfig,
  type GroupConfig,
  type MandatePrecheckResult,
  precheckGroupMandate,
  saveGroupConfig,
} from '@/api/access'

const route = useRoute()
const router = useRouter()

const platform = String(route.params.platform || '')
const botId = String(route.params.botId || '')
const groupId = String(route.params.groupId || '')

const loading = ref(false)
const saving = ref(false)
const historyDrawer = ref(false)
const config = ref<GroupConfig | null>(null)
const personas = ref<Array<{ id: number; name: string }>>([])
const toolParamOverridesJson = ref('{}')

const form = reactive({
  personaId: null as number | null,
  personaSwitchReason: '',
  allowedMemberTaskCategories: [] as string[],
  allowedAutonomousCategories: [] as string[],
  disabledTools: [] as string[],
  disabledSkills: [] as string[],
  proactiveParams: {
    silenceBreakMinutes: null as number | null,
    minIntervalHours: null as number | null,
    dailyBudget: null as number | null,
    activeHoursOverride: '' as string | null,
  },
  userBlacklist: [] as string[],
  groupRules: '',
  transcriptRetainPolicy: 'keep',
})

const precheckDialog = reactive<{
  visible: boolean
  result: MandatePrecheckResult | null
}>({
  visible: false,
  result: null,
})

async function load() {
  loading.value = true
  try {
    const [cfg, personaList] = await Promise.all([
      getGroupConfig(platform, botId, groupId),
      http.get<Array<{ id: number; name: string }>>('/api/management/personas', {silent: true}).catch(() => []),
    ])
    config.value = cfg
    personas.value = personaList
    form.personaId = cfg.personaId ?? null
    form.personaSwitchReason = ''
    form.allowedMemberTaskCategories = [...(cfg.allowedMemberTaskCategories || [])]
    form.allowedAutonomousCategories = [...(cfg.allowedAutonomousCategories || [])]
    form.disabledTools = [...(cfg.disabledTools || [])]
    form.disabledSkills = [...(cfg.disabledSkills || [])]
    form.proactiveParams = {
      silenceBreakMinutes: cfg.proactiveParams?.silenceBreakMinutes ?? null,
      minIntervalHours: cfg.proactiveParams?.minIntervalHours ?? null,
      dailyBudget: cfg.proactiveParams?.dailyBudget ?? null,
      activeHoursOverride: cfg.proactiveParams?.activeHoursOverride ?? '',
    }
    form.userBlacklist = [...(cfg.userBlacklist || [])]
    form.groupRules = cfg.groupRules || ''
    form.transcriptRetainPolicy = cfg.transcriptRetainPolicy || 'keep'
    toolParamOverridesJson.value = JSON.stringify(cfg.toolParamOverrides || {}, null, 2)
  } finally {
    loading.value = false
  }
}

function buildPayload() {
  let parsedOverrides: Record<string, Record<string, unknown>> = {}
  try {
    parsedOverrides = JSON.parse(toolParamOverridesJson.value || '{}')
  } catch {
    throw new Error('工具参数覆盖 JSON 格式不合法')
  }
  return {
    personaId: form.personaId,
    personaSwitchReason: form.personaSwitchReason || null,
    allowedMemberTaskCategories: form.allowedMemberTaskCategories,
    allowedAutonomousCategories: form.allowedAutonomousCategories,
    disabledTools: form.disabledTools,
    toolParamOverrides: parsedOverrides,
    disabledSkills: form.disabledSkills,
    proactiveParams: form.proactiveParams,
    userBlacklist: form.userBlacklist,
    groupRules: form.groupRules,
    transcriptRetainPolicy: form.transcriptRetainPolicy,
  }
}

async function handleSaveWithPrecheck() {
  let payload: Record<string, unknown>
  try {
    payload = buildPayload()
  } catch (e: any) {
    ElMessage.error(e.message)
    return
  }
  const pre = await precheckGroupMandate(platform, botId, groupId, payload)
  if (pre.affectedTaskCount > 0) {
    precheckDialog.result = pre
    precheckDialog.visible = true
    return
  }
  await executeSave()
}

async function executeSave() {
  saving.value = true
  try {
    const payload = buildPayload()
    const res = await saveGroupConfig(platform, botId, groupId, payload)
    precheckDialog.visible = false
    config.value = res.config
    ElMessage.success(
        res.cancelledTaskCount > 0
            ? `配置已保存，并已自动取消 ${res.cancelledTaskCount} 个失格在途任务`
            : '群配置已保存',
    )
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.title-wrap {
  display: flex;
  align-items: center;
  gap: 12px;
}

.group-title {
  font-size: 16px;
  font-weight: 600;
}

.block-card {
  margin-bottom: 16px;
}
</style>
