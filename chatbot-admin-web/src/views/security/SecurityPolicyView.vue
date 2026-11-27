<template>
  <el-card shadow="never">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="黑名单" name="blacklist">
        <div class="toolbar">
          <el-input v-model="newBlack.userId" placeholder="userId" style="width: 160px"/>
          <el-select v-model="newBlack.scope" style="width: 120px">
            <el-option label="global" value="global"/>
            <el-option label="group" value="group"/>
          </el-select>
          <el-input v-model="newBlack.reason" placeholder="原因" style="width: 200px"/>
          <el-button type="primary" @click="addBlack">新增黑名单</el-button>
        </div>
        <el-table :data="blacklist" border stripe>
          <el-table-column prop="scope" label="范围" width="90"/>
          <el-table-column prop="userId" label="User ID" width="150"/>
          <el-table-column prop="groupId" label="Group ID" width="140"/>
          <el-table-column prop="reason" label="原因"/>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="danger" @click="delBlack(row.id)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="分级过滤词库" name="words">
        <div class="toolbar">
          <el-input v-model="newWord.word" placeholder="敏感词" style="width: 180px"/>
          <el-select v-model="newWord.level" style="width: 120px">
            <el-option label="block" value="block"/>
            <el-option label="review" value="review"/>
          </el-select>
          <el-button type="primary" @click="addWord">新增词条</el-button>
          <el-button @click="openCandidates">从拦截/拒发记录转候选词</el-button>
        </div>
        <el-table :data="filterWords" border stripe>
          <el-table-column prop="word" label="词条" width="180"/>
          <el-table-column prop="level" label="级别" width="100"/>
          <el-table-column prop="source" label="来源" width="160"/>
          <el-table-column label="启用" width="90">
            <template #default="{ row }">
              <el-switch :model-value="row.enabled" @change="(v: boolean) => toggleWord(row, v)"/>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="danger" @click="delWord(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="域名黑名单" name="domains">
        <el-form label-width="110px">
          <el-form-item label="禁止域名列表">
            <el-select v-model="domains" multiple filterable allow-create default-first-option style="width: 100%"/>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="saveDomains">保存域名黑名单</el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="频控、指纹与熔断阈值" name="thresholds">
        <el-form label-width="260px">
          <el-form-item v-for="(val, key) in thresholds" :key="key" :label="key">
            <el-input v-model="thresholds[key]" style="width: 240px"/>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="saveThresholds">保存阈值</el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="注入特征规则" name="injection">
        <div class="toolbar">
          <el-input v-model="newPattern" placeholder="输入正则表达式或特征关键词" style="width: 320px"/>
          <el-button type="primary" @click="addRule">新增规则</el-button>
        </div>
        <el-table :data="injectionRules" border stripe>
          <el-table-column prop="id" label="#" width="60"/>
          <el-table-column prop="pattern" label="特征模式"/>
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button link type="danger" @click="delRule(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="candidateDrawer" title="近期快筛拦截/拒发记录（一键转候选词）" size="580px">
      <el-table :data="candidates" border size="small">
        <el-table-column prop="eventId" label="ID" width="80"/>
        <el-table-column prop="detailJson" label="详情" min-width="220" show-overflow-tooltip/>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button link type="primary" @click="promote(row)">转候选词</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {
  type BlacklistItem,
  type FilterCandidateEvent,
  type FilterWordItem,
  type InjectionRuleItem,
  securityApi,
} from '@/api/security'

const activeTab = ref('blacklist')
const blacklist = ref<BlacklistItem[]>([])
const filterWords = ref<FilterWordItem[]>([])
const domains = ref<string[]>([])
const thresholds = reactive<Record<string, string>>({})
const injectionRules = ref<InjectionRuleItem[]>([])

const newBlack = reactive({scope: 'global', userId: '', reason: ''})
const newWord = reactive({word: '', level: 'block'})
const newPattern = ref('')

const candidateDrawer = ref(false)
const candidates = ref<FilterCandidateEvent[]>([])

async function load() {
  const [bl, fw, dom, th, inj] = await Promise.all([
    securityApi.listBlacklist(),
    securityApi.listFilterWords(),
    securityApi.getDomainBlacklist(),
    securityApi.getThresholds(),
    securityApi.listInjectionRules(),
  ])
  blacklist.value = bl
  filterWords.value = fw
  domains.value = dom
  Object.assign(thresholds, th)
  injectionRules.value = inj
}

async function addBlack() {
  await securityApi.addBlacklist(newBlack)
  newBlack.userId = ''
  newBlack.reason = ''
  ElMessage.success('已加入黑名单')
  await load()
}

async function delBlack(id: number) {
  await securityApi.removeBlacklist(id)
  await load()
}

async function addWord() {
  await securityApi.addFilterWord(newWord)
  newWord.word = ''
  ElMessage.success('词条已添加')
  await load()
}

async function toggleWord(row: FilterWordItem, v: boolean) {
  await securityApi.setFilterWordEnabled(row.id, v)
  row.enabled = v
}

async function delWord(id: number) {
  await securityApi.removeFilterWord(id)
  await load()
}

async function saveDomains() {
  await securityApi.updateDomainBlacklist(domains.value)
  ElMessage.success('域名黑名单已刷新')
}

async function saveThresholds() {
  await securityApi.updateThresholds(thresholds)
  ElMessage.success('阈值已热更新')
}

async function addRule() {
  if (!newPattern.value.trim()) return
  await securityApi.addInjectionRule(newPattern.value.trim())
  newPattern.value = ''
  ElMessage.success('规则已添加')
  await load()
}

async function delRule(id: number) {
  await securityApi.deleteInjectionRule(id)
  await load()
}

async function openCandidates() {
  candidates.value = await securityApi.listFilterCandidates(50)
  candidateDrawer.value = true
}

async function promote(row: FilterCandidateEvent) {
  const {value} = await ElMessageBox.prompt('请确认或微调要加入过滤词库的候选词', '一键转候选词', {
    inputValue: '',
  })
  await securityApi.promoteFilterWord(row.eventId, value || undefined, 'block')
  ElMessage.success('已转为过滤词条并刷新词库')
  await load()
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
</style>
