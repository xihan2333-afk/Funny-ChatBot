<template>
  <el-card shadow="never">
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span>画像与成员关系（全只读展示，支持手动触发重算）</span>
        <el-space>
          <GroupSelector v-model="selectedGroup" @change="load"/>
          <el-button type="primary" :disabled="!selectedGroup" @click="recompute">触发全量重算</el-button>
        </el-space>
      </div>
    </template>
    <el-alert
        type="info"
        :closable="false"
        title="画像与好感度由真实互动信号聚合推导，不提供手工改值入口以防认知失真。"
        style="margin-bottom: 12px"
    />
    <el-tabs v-model="activeTab">
      <el-tab-pane label="用户画像" name="users">
        <PageTable :data="userPage" @page-change="(p, ps) => loadUsers(p, ps)">
          <el-table-column prop="userId" label="User ID" width="130"/>
          <el-table-column prop="callName" label="称呼" width="120"/>
          <el-table-column prop="activityLevel" label="活跃度" width="100"/>
          <el-table-column prop="rawAffinity" label="原始好感" width="90"/>
          <el-table-column prop="effectiveAffinity" label="懒衰减有效好感" width="130"/>
          <el-table-column prop="interestTags" label="兴趣标签" show-overflow-tooltip/>
        </PageTable>
      </el-tab-pane>
      <el-tab-pane label="群体画像" name="group">
        <pre v-if="groupProfile">{{ JSON.stringify(groupProfile, null, 2) }}</pre>
        <el-empty v-else description="请选择群查看群体画像"/>
      </el-tab-pane>
      <el-tab-pane label="成员关系" name="relations">
        <PageTable :data="relationPage" @page-change="(p, ps) => loadRelations(p, ps)">
          <el-table-column prop="userIdA" label="成员 A" width="140"/>
          <el-table-column prop="userIdB" label="成员 B" width="140"/>
          <el-table-column prop="relationKind" label="关系类型" width="140"/>
          <el-table-column prop="strength" label="强度" width="90"/>
        </PageTable>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import {ref} from 'vue'
import {ElMessage} from 'element-plus'
import GroupSelector from '@/components/GroupSelector.vue'
import PageTable from '@/components/PageTable.vue'
import type {PageResult} from '@/api/http'
import type {GroupTriple} from '@/stores/ui'
import {cognitionApi, type UserProfileItem} from '@/api/cognition'

const activeTab = ref('users')
const selectedGroup = ref<GroupTriple | null>(null)
const userPage = ref<PageResult<UserProfileItem>>({items: [], total: 0, page: 1, pageSize: 20})
const relationPage = ref<PageResult<any>>({items: [], total: 0, page: 1, pageSize: 20})
const groupProfile = ref<any>(null)

async function load() {
  if (!selectedGroup.value) return
  await Promise.all([loadUsers(1), loadRelations(1), loadGroup()])
}

async function loadUsers(page = 1, pageSize = 20) {
  if (!selectedGroup.value) return
  userPage.value = await cognitionApi.listUserProfiles(
      selectedGroup.value.platform,
      selectedGroup.value.botId,
      selectedGroup.value.groupId,
      page,
      pageSize,
  )
}

async function loadRelations(page = 1, pageSize = 20) {
  if (!selectedGroup.value) return
  relationPage.value = await cognitionApi.listRelations(
      selectedGroup.value.platform,
      selectedGroup.value.botId,
      selectedGroup.value.groupId,
      page,
      pageSize,
  )
}

async function loadGroup() {
  if (!selectedGroup.value) return
  groupProfile.value = await cognitionApi.getGroupProfile(
      selectedGroup.value.platform,
      selectedGroup.value.botId,
      selectedGroup.value.groupId,
  )
}

async function recompute() {
  if (!selectedGroup.value) return
  await cognitionApi.recomputeProfiles(
      selectedGroup.value.platform,
      selectedGroup.value.botId,
      selectedGroup.value.groupId,
  )
  ElMessage.success('已触发全量画像重算')
  await load()
}
</script>
