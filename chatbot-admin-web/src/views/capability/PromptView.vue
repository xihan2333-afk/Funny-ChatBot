<template>
  <el-card shadow="never">
    <template #header>
      <div class="header-bar">
        <span>Prompt 片段管理（四层组装 + L1 独立条目）</span>
        <el-button type="primary" size="small" @click="openEdit()">新建自定义片段</el-button>
      </div>
    </template>

    <el-tabs v-model="activeLayer">
      <el-tab-pane v-for="layer in layers" :key="layer.key" :label="layer.label" :name="layer.key">
        <el-table :data="grouped[layer.key] || []" border stripe>
          <el-table-column prop="fragmentKey" label="业务键" width="180"/>
          <el-table-column prop="title" label="标题" width="160"/>
          <el-table-column prop="formFit" label="形态适配" width="120"/>
          <el-table-column label="锁定" width="100">
            <template #default="{ row }">
              <el-tooltip v-if="row.builtinLocked" :content="row.lockReason || '内置锁定'">
                <el-tag type="danger" size="small">只读锁定</el-tag>
              </el-tooltip>
              <el-tag v-else type="success" size="small">可编辑</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="95"/>
          <el-table-column prop="versionNo" label="版本" width="70"/>
          <el-table-column label="操作" width="240">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="row.builtinLocked" @click="openEdit(row)">
                编辑
              </el-button>
              <el-button link type="success" :disabled="row.builtinLocked" @click="openPublish(row.fragmentKey)">
                发布
              </el-button>
              <el-button link @click="openVersions(row.fragmentKey)">历史</el-button>
              <el-button link type="danger" :disabled="row.builtinLocked" @click="removeFragment(row.fragmentKey)">
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="editDialog" :title="isNew ? '新建 Prompt 片段' : `编辑片段：${form.fragmentKey}`" width="660px">
      <el-form label-width="100px">
        <el-form-item label="业务键">
          <el-input v-model="form.fragmentKey" :disabled="!isNew"/>
        </el-form-item>
        <el-form-item label="所属层级">
          <el-select v-model="form.layer" style="width: 100%">
            <el-option v-for="l in layers" :key="l.key" :label="l.label" :value="l.key"/>
          </el-select>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="form.title"/>
        </el-form-item>
        <el-form-item label="形态适配">
          <el-input v-model="form.formFit" placeholder="all / turn / lightTask / worker"/>
        </el-form-item>
        <el-form-item label="声明变量">
          <el-select
              v-model="form.variables"
              multiple
              filterable
              allow-create
              default-first-option
              placeholder="声明模板中允许使用的 {{var}}"
              style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="片段正文">
          <el-input v-model="form.content" type="textarea" :rows="10"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" @click="saveDraft">保存草稿</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="versionDrawer" title="片段历史版本" size="460px">
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
        entity-kind="fragment"
        :entity-key="publishDialog.key"
        :publish-executor="executePublish"
        @published="load"
    />
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from 'vue'
import {ElMessage} from 'element-plus'
import VersionPublishDialog from '@/components/VersionPublishDialog.vue'
import {promptApi, type PromptFragmentItem, type VersionItem} from '@/api/capability'

const layers = [
  {key: 'identity', label: '身份层 (identity)'},
  {key: 'group_context', label: '群上下文层 (group_context)'},
  {key: 'protocol', label: '能力协议层 (protocol)'},
  {key: 'task', label: '任务层 (task)'},
  {key: 'standalone', label: 'L1/独立条目 (standalone)'},
]

const activeLayer = ref('identity')
const grouped = ref<Record<string, PromptFragmentItem[]>>({})

const editDialog = ref(false)
const isNew = ref(true)
const form = reactive({
  fragmentKey: '',
  layer: 'standalone',
  formFit: 'all',
  title: '',
  content: '',
  variables: [] as string[],
})

const versionDrawer = ref(false)
const versionList = ref<VersionItem[]>([])
const versionKey = ref('')

const publishDialog = reactive({visible: false, key: ''})

async function load() {
  grouped.value = await promptApi.listGrouped()
}

function openEdit(row?: PromptFragmentItem) {
  if (row) {
    isNew.value = false
    Object.assign(form, {
      fragmentKey: row.fragmentKey,
      layer: row.layer,
      formFit: row.formFit,
      title: row.title,
      content: row.content,
      variables: [...(row.variables || [])],
    })
  } else {
    isNew.value = true
    Object.assign(form, {
      fragmentKey: '',
      layer: activeLayer.value,
      formFit: 'all',
      title: '',
      content: '',
      variables: [],
    })
  }
  editDialog.value = true
}

async function saveDraft() {
  try {
    if (isNew.value) {
      await promptApi.createDraft(form)
    } else {
      await promptApi.saveDraft(form.fragmentKey, form)
    }
    ElMessage.success('草稿已保存')
    editDialog.value = false
    await load()
  } catch {
    // 错误提示已由 http 客户端统一弹出
  }
}

function openPublish(key: string) {
  publishDialog.key = key
  publishDialog.visible = true
}

async function executePublish(overrideReason: string | null) {
  await promptApi.publish(publishDialog.key, overrideReason)
  ElMessage.success('片段发布成功')
}

async function openVersions(key: string) {
  versionKey.value = key
  versionList.value = await promptApi.versions(key)
  versionDrawer.value = true
}

async function rollbackTo(ver: number) {
  await promptApi.rollback(versionKey.value, ver)
  ElMessage.success(`已回滚至版本 v${ver}`)
  versionDrawer.value = false
  await load()
}

async function removeFragment(key: string) {
  await promptApi.delete(key)
  ElMessage.success('已删除')
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
</style>
