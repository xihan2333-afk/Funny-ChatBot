<template>
  <el-card shadow="never">
    <template #header>
      <div class="header-bar">
        <span>Skill 管理（三级渐进披露能力包）</span>
        <el-button type="primary" size="small" @click="openEdit()">新建 Skill</el-button>
      </div>
    </template>

    <PageTable :data="pageData" :loading="loading" @page-change="(p, ps) => load(p, ps)">
      <el-table-column prop="name" label="技能名" width="160"/>
      <el-table-column prop="description" label="目录描述（≤200字）" min-width="220" show-overflow-tooltip/>
      <el-table-column prop="referencedGroupCount" label="引用群数" width="90"/>
      <el-table-column label="启用" width="90">
        <template #default="{ row }">
          <el-switch :model-value="row.enabled" @change="(v: boolean) => toggleEnabled(row, v)"/>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="95"/>
      <el-table-column prop="versionNo" label="版本" width="70"/>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row.name)">编辑</el-button>
          <el-button link @click="openPreview(row.name)">预览</el-button>
          <el-button link type="success" @click="openPublish(row.name)">发布</el-button>
          <el-button link @click="openVersions(row.name)">历史</el-button>
          <el-button link type="danger" :disabled="row.enabled" @click="removeSkill(row.name)">删除</el-button>
        </template>
      </el-table-column>
    </PageTable>

    <el-dialog v-model="editDialog" :title="isNew ? '新建 Skill 草稿' : `编辑 Skill：${form.name}`" width="640px">
      <el-form label-width="100px">
        <el-form-item label="技能名">
          <el-input v-model="form.name" :disabled="!isNew"/>
        </el-form-item>
        <el-form-item label="目录描述">
          <el-input v-model="form.description" maxlength="200" show-word-limit/>
        </el-form-item>
        <el-form-item label="Markdown 正文">
          <el-input v-model="form.content" type="textarea" :rows="10"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" @click="saveDraft">保存草稿</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="previewDrawer" title="Skill 渲染预览" size="520px">
      <div class="preview-block">
        <div class="preview-label">目录行渲染：</div>
        <pre class="preview-pre">{{ previewData.directoryLine }}</pre>
      </div>
      <div class="preview-block">
        <div class="preview-label">readSkill 全文渲染：</div>
        <pre class="preview-pre">{{ previewData.fullContent }}</pre>
      </div>
    </el-drawer>

    <el-drawer v-model="versionDrawer" title="Skill 历史版本" size="460px">
      <el-table :data="versionList" border size="small">
        <el-table-column prop="versionNo" label="版本" width="70"/>
        <el-table-column prop="createdAt" label="发布时间" width="170"/>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="warning" @click="rollbackTo(row.versionNo)">回滚</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <VersionPublishDialog
        v-model:visible="publishDialog.visible"
        entity-kind="skill"
        :entity-key="publishDialog.name"
        :publish-executor="executePublish"
        @published="load()"
    />
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from 'vue'
import {ElMessage} from 'element-plus'
import PageTable from '@/components/PageTable.vue'
import VersionPublishDialog from '@/components/VersionPublishDialog.vue'
import type {PageResult} from '@/api/http'
import {skillApi, type SkillSummary, type VersionItem} from '@/api/capability'

const loading = ref(false)
const pageData = ref<PageResult<SkillSummary>>({items: [], total: 0, page: 1, pageSize: 20})

const editDialog = ref(false)
const isNew = ref(true)
const form = reactive({name: '', description: '', content: '', enabled: true})

const previewDrawer = ref(false)
const previewData = reactive({directoryLine: '', fullContent: ''})

const versionDrawer = ref(false)
const versionList = ref<VersionItem[]>([])
const versionSkillName = ref('')

const publishDialog = reactive({visible: false, name: ''})

async function load(page = pageData.value.page, pageSize = pageData.value.pageSize) {
  loading.value = true
  try {
    pageData.value = await skillApi.list(page, pageSize)
  } finally {
    loading.value = false
  }
}

async function openEdit(name?: string) {
  if (name) {
    isNew.value = false
    const detail = await skillApi.get(name)
    Object.assign(form, {
      name: detail.name,
      description: detail.description,
      content: detail.content,
      enabled: detail.enabled,
    })
  } else {
    isNew.value = true
    Object.assign(form, {name: '', description: '', content: '', enabled: true})
  }
  editDialog.value = true
}

async function saveDraft() {
  if (isNew.value) {
    await skillApi.createDraft(form)
  } else {
    await skillApi.saveDraft(form.name, form)
  }
  ElMessage.success('Skill 草稿已保存')
  editDialog.value = false
  await load()
}

async function openPreview(name: string) {
  const res = await skillApi.preview(name)
  previewData.directoryLine = res.directoryLine
  previewData.fullContent = res.fullContent
  previewDrawer.value = true
}

function openPublish(name: string) {
  publishDialog.name = name
  publishDialog.visible = true
}

async function executePublish(overrideReason: string | null) {
  await skillApi.publish(publishDialog.name, overrideReason)
  ElMessage.success('Skill 发布成功')
}

async function openVersions(name: string) {
  versionSkillName.value = name
  versionList.value = await skillApi.versions(name)
  versionDrawer.value = true
}

async function rollbackTo(ver: number) {
  await skillApi.rollback(versionSkillName.value, ver)
  ElMessage.success(`已回滚至版本 v${ver}`)
  versionDrawer.value = false
  await load()
}

async function toggleEnabled(row: SkillSummary, val: boolean) {
  await skillApi.setEnabled(row.name, val)
  row.enabled = val
}

async function removeSkill(name: string) {
  await skillApi.delete(name)
  ElMessage.success('已删除')
  await load()
}

onMounted(() => load(1))
</script>

<style scoped>
.header-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.preview-block {
  margin-bottom: 16px;
}

.preview-label {
  font-weight: 600;
  margin-bottom: 6px;
}

.preview-pre {
  background: #f7f8fa;
  padding: 12px;
  border-radius: 6px;
  white-space: pre-wrap;
  font-size: 13px;
}
</style>
