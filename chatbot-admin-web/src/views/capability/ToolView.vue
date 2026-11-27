<template>
  <el-card shadow="never">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="内置工具" name="builtin">
        <el-table :data="builtinTools" border stripe>
          <el-table-column prop="name" label="工具名" width="160"/>
          <el-table-column prop="description" label="描述（只读 Descriptor）" min-width="220" show-overflow-tooltip/>
          <el-table-column prop="speed" label="快慢" width="90"/>
          <el-table-column label="全局启用" width="90">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="100"/>
          <el-table-column prop="versionNo" label="版本" width="70"/>
          <el-table-column label="操作" width="220">
            <template #default="{ row }">
              <el-button link type="primary" @click="openBuiltinEdit(row)">编辑草稿</el-button>
              <el-button link type="success" @click="openPublish('tool_config', row.name)">发布</el-button>
              <el-button link @click="openVersions('tool_config', row.name)">历史</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="HTTP 泛化工具" name="http">
        <div class="toolbar">
          <el-button type="primary" size="small" @click="openHttpEdit()">新增 HTTP 工具</el-button>
        </div>
        <el-table :data="httpTools" border stripe>
          <el-table-column prop="toolName" label="工具名" width="150"/>
          <el-table-column prop="method" label="方法" width="80"/>
          <el-table-column prop="urlTemplate" label="URL 模板" min-width="220" show-overflow-tooltip/>
          <el-table-column prop="speed" label="快慢" width="80"/>
          <el-table-column prop="status" label="状态" width="95"/>
          <el-table-column prop="versionNo" label="版本" width="70"/>
          <el-table-column label="操作" width="260">
            <template #default="{ row }">
              <el-button link type="primary" @click="openHttpEdit(row)">编辑</el-button>
              <el-button link type="success" @click="openPublish('http_tool_config', row.toolName)">发布</el-button>
              <el-button link @click="openVersions('http_tool_config', row.toolName)">历史</el-button>
              <el-button link type="danger" @click="removeHttp(row.toolName)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 内置工具草稿弹窗 -->
    <el-dialog v-model="builtinDialog" :title="`编辑内置工具：${builtinForm.name}`" width="520px">
      <el-form label-width="100px">
        <el-form-item label="Schema(只读)">
          <el-input :model-value="builtinForm.parametersJson" type="textarea" :rows="3" disabled/>
        </el-form-item>
        <el-form-item label="全局启用">
          <el-switch v-model="builtinForm.enabled"/>
        </el-form-item>
        <el-form-item label="运行参数 JSON">
          <el-input v-model="builtinForm.paramsJson" type="textarea" :rows="4"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="builtinDialog = false">取消</el-button>
        <el-button type="primary" @click="saveBuiltinDraft">保存草稿</el-button>
      </template>
    </el-dialog>

    <!-- HTTP 工具五组字段弹窗 -->
    <el-dialog v-model="httpDialog" :title="httpIsNew ? '新增 HTTP 工具' : `编辑 HTTP 工具：${httpForm.toolName}`"
               width="640px">
      <el-alert
          v-for="(w, i) in httpWarnings"
          :key="i"
          type="warning"
          :title="w"
          show-icon
          :closable="false"
          style="margin-bottom: 10px"
      />
      <el-form :model="httpForm" label-width="120px">
        <el-form-item label="工具名">
          <el-input v-model="httpForm.toolName" :disabled="!httpIsNew"/>
        </el-form-item>
        <el-form-item label="模型可见描述">
          <el-input v-model="httpForm.description"/>
        </el-form-item>
        <el-form-item label="参数 Schema">
          <el-input v-model="httpForm.parametersJson" type="textarea" :rows="3"/>
        </el-form-item>
        <el-form-item label="请求方法">
          <el-select v-model="httpForm.method" style="width: 120px">
            <el-option label="GET" value="GET"/>
            <el-option label="POST" value="POST"/>
          </el-select>
        </el-form-item>
        <el-form-item label="URL 模板">
          <el-input v-model="httpForm.urlTemplate" placeholder="https://api.example.com/q={{query}}"/>
        </el-form-item>
        <el-form-item label="请求体模板">
          <el-input v-model="httpForm.bodyTemplate" type="textarea" :rows="2"/>
        </el-form-item>
        <el-form-item label="响应提取路径">
          <el-input v-model="httpForm.extractPath" placeholder="$.data.result"/>
        </el-form-item>
        <el-form-item label="保护配置">
          <el-space>
            <el-input-number v-model="httpForm.timeoutMillis" :min="500" :step="500"/>
            ms
            <el-select v-model="httpForm.speed" style="width: 110px">
              <el-option label="FAST" value="FAST"/>
              <el-option label="SLOW" value="SLOW"/>
            </el-select>
            <el-switch v-model="httpForm.enabled"/>
          </el-space>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="httpDialog = false">取消</el-button>
        <el-button type="primary" @click="saveHttpDraft">保存草稿</el-button>
      </template>
    </el-dialog>

    <!-- 版本历史抽屉 -->
    <el-drawer v-model="versionDrawer" title="历史版本列表" size="500px">
      <el-table :data="versionList" border size="small">
        <el-table-column prop="versionNo" label="版本" width="70"/>
        <el-table-column prop="createdAt" label="发布时间" width="170"/>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="warning" @click="rollbackTo(row.versionNo)">回滚</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <VersionPublishDialog
        v-model:visible="publishState.visible"
        :entity-kind="publishState.kind"
        :entity-key="publishState.key"
        :publish-executor="executePublish"
        @published="load"
    />
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from 'vue'
import {ElMessage} from 'element-plus'
import VersionPublishDialog from '@/components/VersionPublishDialog.vue'
import {type BuiltinToolItem, type HttpToolItem, toolApi, type VersionItem} from '@/api/capability'

const activeTab = ref('builtin')
const builtinTools = ref<BuiltinToolItem[]>([])
const httpTools = ref<HttpToolItem[]>([])

const builtinDialog = ref(false)
const builtinForm = reactive({name: '', parametersJson: '{}', enabled: true, paramsJson: '{}'})

const httpDialog = ref(false)
const httpIsNew = ref(true)
const httpWarnings = ref<string[]>([])
const httpForm = ref<Partial<HttpToolItem>>({
  toolName: '',
  description: '',
  parametersJson: '{"type":"object","properties":{}}',
  method: 'GET',
  urlTemplate: '',
  bodyTemplate: '',
  extractPath: '',
  maxResponseBytes: 4096,
  timeoutMillis: 3000,
  speed: 'FAST',
  enabled: true,
})

const versionDrawer = ref(false)
const versionList = ref<VersionItem[]>([])
const versionTarget = reactive<{ kind: 'tool_config' | 'http_tool_config'; key: string }>({
  kind: 'tool_config',
  key: '',
})

const publishState = ref<{
  visible: boolean
  kind: 'tool_config' | 'http_tool_config'
  key: string
}>({
  visible: false,
  kind: 'tool_config',
  key: '',
})

async function load() {
  const [b, h] = await Promise.all([toolApi.listBuiltin(), toolApi.listHttp()])
  builtinTools.value = b
  httpTools.value = h
}

function openBuiltinEdit(row: BuiltinToolItem) {
  Object.assign(builtinForm, {
    name: row.name,
    parametersJson: row.parametersJson,
    enabled: row.enabled,
    paramsJson: row.paramsJson || '{}',
  })
  builtinDialog.value = true
}

async function saveBuiltinDraft() {
  await toolApi.saveBuiltinDraft(builtinForm.name, {
    enabled: builtinForm.enabled,
    paramsJson: builtinForm.paramsJson,
  })
  ElMessage.success('草稿已保存（需发布门禁后生效）')
  builtinDialog.value = false
  await load()
}

function openHttpEdit(row?: HttpToolItem) {
  httpWarnings.value = []
  if (row) {
    httpIsNew.value = false
    httpForm.value = {...row}
  } else {
    httpIsNew.value = true
    httpForm.value = {
      toolName: '',
      description: '',
      parametersJson: '{"type":"object","properties":{}}',
      method: 'GET',
      urlTemplate: '',
      bodyTemplate: '',
      extractPath: '',
      maxResponseBytes: 4096,
      timeoutMillis: 3000,
      speed: 'FAST',
      enabled: true,
    }
  }
  httpDialog.value = true
}

async function saveHttpDraft() {
  const res = httpIsNew.value
      ? await toolApi.createHttpDraft(httpForm.value)
      : await toolApi.saveHttpDraft(httpForm.value.toolName || '', httpForm.value)
  httpWarnings.value = res.warnings || []
  ElMessage.success(httpWarnings.value.length ? '草稿已保存（请注意 URL 静态提醒）' : '草稿已保存')
  if (!httpWarnings.value.length) {
    httpDialog.value = false
  }
  await load()
}

async function removeHttp(toolName: string) {
  await toolApi.deleteHttp(toolName)
  ElMessage.success('已删除')
  await load()
}

function openPublish(kind: 'tool_config' | 'http_tool_config', key: string) {
  publishState.value.kind = kind
  publishState.value.key = key
  publishState.value.visible = true
}

async function executePublish(overrideReason: string | null) {
  if (publishState.value.kind === 'tool_config') {
    await toolApi.publishBuiltin(publishState.value.key, overrideReason)
  } else {
    await toolApi.publishHttp(publishState.value.key, overrideReason)
  }
  ElMessage.success('发布成功')
}

async function openVersions(kind: 'tool_config' | 'http_tool_config', key: string) {
  versionTarget.kind = kind
  versionTarget.key = key
  versionList.value = kind === 'tool_config'
      ? await toolApi.builtinVersions(key)
      : await toolApi.httpVersions(key)
  versionDrawer.value = true
}

async function rollbackTo(ver: number) {
  if (versionTarget.kind === 'tool_config') {
    await toolApi.rollbackBuiltin(versionTarget.key, ver)
  } else {
    await toolApi.rollbackHttp(versionTarget.key, ver)
  }
  ElMessage.success(`已回滚至版本 v${ver}`)
  versionDrawer.value = false
  await load()
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  margin-bottom: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
