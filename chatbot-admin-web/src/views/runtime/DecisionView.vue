<template>
  <el-card shadow="never">
    <template #header>Decision 决策检索</template>
    <el-form :inline="true" style="margin-bottom: 12px">
      <el-form-item label="群">
        <GroupSelector v-model="selectedGroup"/>
      </el-form-item>
      <el-form-item label="结果">
        <el-select v-model="decision" clearable placeholder="全部" style="width: 120px">
          <el-option label="accept" value="accept"/>
          <el-option label="ignore" value="ignore"/>
          <el-option label="pending" value="pending"/>
        </el-select>
      </el-form-item>
      <el-form-item label="层级">
        <el-select v-model="decisionLayer" clearable placeholder="全部" style="width: 120px">
          <el-option label="L0" value="L0"/>
          <el-option label="L1" value="L1"/>
          <el-option label="degrade" value="degrade"/>
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load(1)">查询</el-button>
      </el-form-item>
    </el-form>

    <PageTable :data="pageData" :loading="loading" @page-change="(p, ps) => load(p, ps)">
      <el-table-column prop="id" label="Trigger ID" width="120"/>
      <el-table-column prop="groupId" label="群号" width="120"/>
      <el-table-column prop="triggerKind" label="触发源" width="100"/>
      <el-table-column prop="decision" label="决策" width="90"/>
      <el-table-column prop="decisionLayer" label="层级" width="90"/>
      <el-table-column prop="decisionReason" label="理由" min-width="200" show-overflow-tooltip/>
      <el-table-column prop="batchDigest" label="批次摘要" min-width="180" show-overflow-tooltip/>
      <el-table-column prop="turnId" label="Turn ID" width="100"/>
    </PageTable>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import GroupSelector from '@/components/GroupSelector.vue'
import PageTable from '@/components/PageTable.vue'
import type {PageResult} from '@/api/http'
import type {GroupTriple} from '@/stores/ui'
import {type DecisionItem, runtimeApi} from '@/api/runtime'

const loading = ref(false)
const selectedGroup = ref<GroupTriple | null>(null)
const decision = ref('')
const decisionLayer = ref('')
const pageData = ref<PageResult<DecisionItem>>({items: [], total: 0, page: 1, pageSize: 20})

async function load(page = 1, pageSize = pageData.value.pageSize) {
  loading.value = true
  try {
    pageData.value = await runtimeApi.listDecisions({
      platform: selectedGroup.value?.platform,
      botId: selectedGroup.value?.botId,
      groupId: selectedGroup.value?.groupId,
      decision: decision.value || undefined,
      decisionLayer: decisionLayer.value || undefined,
      page,
      pageSize,
    })
  } finally {
    loading.value = false
  }
}

onMounted(() => load(1))
</script>
