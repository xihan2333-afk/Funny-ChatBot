<template>
  <el-row :gutter="16">
    <el-col :span="7">
      <el-card shadow="never">
        <template #header>
          <div class="header-bar">
            <span>人格库</span>
            <el-button type="primary" size="small" @click="createNew">新建人格</el-button>
          </div>
        </template>
        <el-table
            :data="personas"
            highlight-current-row
            border
            @current-change="selectPersona"
        >
          <el-table-column prop="name" label="名称"/>
          <el-table-column prop="boundGroupCount" label="绑定群" width="70"/>
          <el-table-column prop="status" label="状态" width="85"/>
        </el-table>
      </el-card>
    </el-col>

    <el-col :span="17">
      <el-card v-if="currentId !== null || creating" shadow="never">
        <template #header>
          <div class="header-bar">
            <span>{{ creating ? '新建人格草稿' : `人格详情：${detailForm.name} (v${detailForm.versionNo})` }}</span>
            <el-space v-if="!creating && currentId">
              <el-button type="primary" size="small" @click="saveDraft">保存草稿</el-button>
              <el-button type="success" size="small" @click="publishVisible = true">发布</el-button>
              <el-button size="small" @click="openVersions">历史版本</el-button>
            </el-space>
            <el-button v-else type="primary" size="small" @click="saveDraft">创建草稿</el-button>
          </div>
        </template>

        <el-tabs v-model="activeTab">
          <el-tab-pane label="七组字段定义" name="definition">
            <el-form label-width="100px">
              <el-form-item label="人格名称">
                <el-input v-model="detailForm.name"/>
              </el-form-item>
              <el-form-item label="默认人格">
                <el-switch v-model="detailForm.isDefault"/>
              </el-form-item>
              <el-form-item label="七组定义 JSON">
                <el-input v-model="detailForm.definitionJson" type="textarea" :rows="14"/>
              </el-form-item>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="绑定群与切换历史" name="bindings" :disabled="creating">
            <div class="sub-title">当前绑定群</div>
            <el-table :data="bindings.boundGroups" size="small" border style="margin-bottom: 14px">
              <el-table-column prop="groupName" label="群名称"/>
              <el-table-column prop="groupId" label="群号"/>
              <el-table-column prop="lifecycle" label="状态" width="100"/>
            </el-table>
            <div class="sub-title">切换历史</div>
            <el-table :data="bindings.histories" size="small" border>
              <el-table-column prop="switchedAt" label="时间" width="170"/>
              <el-table-column prop="groupId" label="群号" width="120"/>
              <el-table-column prop="operator" label="操作人" width="100"/>
              <el-table-column prop="reason" label="原因"/>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="演进提案与人工反馈" name="evolution" :disabled="creating">
            <div class="toolbar">
              <el-button type="warning" size="small" @click="triggerEvo">手动触发演进分析</el-button>
              <el-button type="primary" size="small" @click="feedbackDialog = true">录入人工反馈</el-button>
            </div>
            <el-table :data="proposals" border size="small" style="margin-bottom: 14px">
              <el-table-column prop="id" label="ID" width="90"/>
              <el-table-column prop="status" label="状态" width="120"/>
              <el-table-column prop="proposalItemsJson" label="建议变更" min-width="200" show-overflow-tooltip/>
              <el-table-column label="审阅" width="220">
                <template #default="{ row }">
                  <template v-if="row.status === 'pending_review'">
                    <el-button link type="success" @click="review(row.id, 'accept')">接受</el-button>
                    <el-button link type="primary" @click="openEditAccept(row)">修改后接受</el-button>
                    <el-button link type="danger" @click="rejectProp(row.id)">驳回</el-button>
                  </template>
                  <span v-else>{{ row.reviewRemark || row.status }}</span>
                </template>
              </el-table-column>
            </el-table>

            <div class="sub-title">管理员人工反馈（第五信号源）</div>
            <el-table :data="feedbacks" border size="small">
              <el-table-column prop="tag" label="标签" width="140"/>
              <el-table-column prop="comment" label="说明"/>
              <el-table-column prop="operator" label="操作人" width="100"/>
              <el-table-column prop="createdAt" label="时间" width="170"/>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </el-col>

    <!-- 人工反馈弹窗 -->
    <el-dialog v-model="feedbackDialog" title="录入管理员人工反馈" width="460px">
      <el-form label-width="90px">
        <el-form-item label="标签">
          <el-select v-model="feedbackForm.tag" style="width: 100%">
            <el-option label="tone_drift (语气漂移)" value="tone_drift"/>
            <el-option label="too_verbose (过于啰嗦)" value="too_verbose"/>
            <el-option label="boundary_issue (边界问题)" value="boundary_issue"/>
            <el-option label="great_reply (优质回复)" value="great_reply"/>
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="feedbackForm.comment" type="textarea" :rows="3"/>
        </el-form-item>
        <el-form-item label="关联 TraceId">
          <el-input v-model="feedbackForm.traceId" placeholder="可选"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="feedbackDialog = false">取消</el-button>
        <el-button type="primary" @click="submitFeedback">提交</el-button>
      </template>
    </el-dialog>

    <!-- 修改后接受弹窗 -->
    <el-dialog v-model="editAcceptDialog.visible" title="修改后接受演进提案" width="520px">
      <el-input v-model="editAcceptDialog.editedItemsJson" type="textarea" :rows="8"/>
      <template #footer>
        <el-button @click="editAcceptDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmEditAccept">确认接受</el-button>
      </template>
    </el-dialog>

    <!-- 版本历史 -->
    <el-drawer v-model="versionDrawer" title="人格历史版本" size="460px">
      <el-table :data="versionList" border size="small">
        <el-table-column prop="versionNo" label="版本" width="70"/>
        <el-table-column prop="createdAt" label="时间" width="170"/>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="warning" @click="rollbackTo(row.versionNo)">回滚</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <VersionPublishDialog
        v-if="currentId"
        v-model:visible="publishVisible"
        entity-kind="persona"
        :entity-key="currentId"
        :publish-executor="executePublish"
        @published="load"
    />
  </el-row>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import VersionPublishDialog from '@/components/VersionPublishDialog.vue'
import {
  personaApi,
  type PersonaFeedbackItem,
  type PersonaProposalItem,
  type PersonaSummary,
  type VersionItem,
} from '@/api/capability'

const personas = ref<PersonaSummary[]>([])
const currentId = ref<number | null>(null)
const creating = ref(false)
const activeTab = ref('definition')

const detailForm = reactive({
  name: '',
  isDefault: false,
  versionNo: 1,
  definitionJson: '{}',
})

const bindings = reactive<{ boundGroups: any[]; histories: any[] }>({boundGroups: [], histories: []})
const proposals = ref<PersonaProposalItem[]>([])
const feedbacks = ref<PersonaFeedbackItem[]>([])

const publishVisible = ref(false)
const versionDrawer = ref(false)
const versionList = ref<VersionItem[]>([])

const feedbackDialog = ref(false)
const feedbackForm = reactive({tag: 'tone_drift', comment: '', traceId: ''})

const editAcceptDialog = reactive({visible: false, proposalId: 0, editedItemsJson: '[]'})

async function load() {
  personas.value = await personaApi.list()
  if (currentId.value) {
    await loadDetail(currentId.value)
  } else if (personas.value[0]) {
    await selectPersona(personas.value[0])
  }
}

async function selectPersona(row: PersonaSummary | null) {
  if (!row) return
  creating.value = false
  currentId.value = row.id
  await loadDetail(row.id)
}

async function loadDetail(id: number) {
  const [detail, bind, props, fbs] = await Promise.all([
    personaApi.get(id),
    personaApi.bindings(id),
    personaApi.listProposals(id),
    personaApi.listFeedbacks(id),
  ])
  detailForm.name = detail.name
  detailForm.isDefault = detail.isDefault
  detailForm.versionNo = detail.versionNo
  detailForm.definitionJson = detail.definitionJson
  bindings.boundGroups = bind.boundGroups
  bindings.histories = bind.histories
  proposals.value = props
  feedbacks.value = fbs
}

function createNew() {
  creating.value = true
  currentId.value = null
  activeTab.value = 'definition'
  detailForm.name = ''
  detailForm.isDefault = false
  detailForm.versionNo = 1
  detailForm.definitionJson = JSON.stringify(
      {
        basicInfo: {},
        selfSetting: '',
        expressionStyle: {variants: []},
        forbiddenPatterns: [],
        examples: [],
        attentionPreference: {talkativity: 50, proactiveness: 50, interjectionTendency: 30},
        groupExperience: {},
        emotionConfig: {baseLabel: 'calm', sensitivity: 50},
      },
      null,
      2,
  )
}

async function saveDraft() {
  if (creating.value) {
    const res = await personaApi.createDraft({
      name: detailForm.name,
      definitionJson: detailForm.definitionJson,
      isDefault: detailForm.isDefault,
    })
    creating.value = false
    currentId.value = res.id
    ElMessage.success('人格草稿已创建')
  } else if (currentId.value) {
    await personaApi.saveDraft(currentId.value, {
      name: detailForm.name,
      definitionJson: detailForm.definitionJson,
      isDefault: detailForm.isDefault,
    })
    ElMessage.success('人格草稿已保存')
  }
  await load()
}

async function executePublish(overrideReason: string | null) {
  if (!currentId.value) return
  await personaApi.publish(currentId.value, overrideReason)
  ElMessage.success('人格发布成功')
}

async function openVersions() {
  if (!currentId.value) return
  versionList.value = await personaApi.versions(currentId.value)
  versionDrawer.value = true
}

async function rollbackTo(ver: number) {
  if (!currentId.value) return
  await personaApi.rollback(currentId.value, ver)
  ElMessage.success(`已回滚至版本 v${ver}`)
  versionDrawer.value = false
  await load()
}

async function triggerEvo() {
  if (!currentId.value) return
  await personaApi.triggerEvolution(currentId.value)
  ElMessage.success('演进分析已执行')
  await loadDetail(currentId.value)
}

async function review(proposalId: number, decision: string) {
  await personaApi.reviewProposal(proposalId, {decision})
  ElMessage.success('审阅完成')
  if (currentId.value) await loadDetail(currentId.value)
}

function openEditAccept(row: PersonaProposalItem) {
  editAcceptDialog.proposalId = row.id
  editAcceptDialog.editedItemsJson = row.proposalItemsJson
  editAcceptDialog.visible = true
}

async function confirmEditAccept() {
  await personaApi.reviewProposal(editAcceptDialog.proposalId, {
    decision: 'edit_and_accept',
    editedItemsJson: editAcceptDialog.editedItemsJson,
  })
  editAcceptDialog.visible = false
  ElMessage.success('已修改并接受提案')
  if (currentId.value) await loadDetail(currentId.value)
}

async function rejectProp(proposalId: number) {
  const {value} = await ElMessageBox.prompt('请输入驳回理由（将留档供下期演进参考）', '驳回提案')
  await personaApi.reviewProposal(proposalId, {decision: 'reject', remark: value})
  ElMessage.success('已驳回')
  if (currentId.value) await loadDetail(currentId.value)
}

async function submitFeedback() {
  if (!currentId.value) return
  await personaApi.addFeedback(currentId.value, feedbackForm)
  feedbackDialog.value = false
  feedbackForm.comment = ''
  ElMessage.success('反馈已记录')
  await loadDetail(currentId.value)
}

onMounted(load)
</script>

<style scoped>
.header-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.sub-title {
  font-weight: 600;
  margin-bottom: 8px;
}
</style>
