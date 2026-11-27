<template>
  <el-card shadow="never">
    <template #header>表情包指纹映射库（修正描述即时刷新缓存）</template>
    <el-form :inline="true" style="margin-bottom: 12px">
      <el-form-item label="群">
        <GroupSelector v-model="selectedGroup"/>
      </el-form-item>
      <el-form-item label="关键词">
        <el-input v-model="keyword" clearable/>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load(1)">查询</el-button>
      </el-form-item>
    </el-form>
    <PageTable :data="pageData" :loading="loading" @page-change="(p, ps) => load(p, ps)">
      <el-table-column prop="groupId" label="群号" width="120"/>
      <el-table-column prop="fingerprint" label="指纹" width="180" show-overflow-tooltip/>
      <el-table-column prop="description" label="描述文本" min-width="240"/>
      <el-table-column prop="source" label="来源" width="90"/>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button link type="primary" @click="correct(row)">修正描述</el-button>
        </template>
      </el-table-column>
    </PageTable>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import GroupSelector from '@/components/GroupSelector.vue'
import PageTable from '@/components/PageTable.vue'
import type {PageResult} from '@/api/http'
import type {GroupTriple} from '@/stores/ui'
import {cognitionApi, type EmojiMappingItem} from '@/api/cognition'

const loading = ref(false)
const selectedGroup = ref<GroupTriple | null>(null)
const keyword = ref('')
const pageData = ref<PageResult<EmojiMappingItem>>({items: [], total: 0, page: 1, pageSize: 20})

async function load(page = 1, pageSize = pageData.value.pageSize) {
  loading.value = true
  try {
    pageData.value = await cognitionApi.listEmojis({
      platform: selectedGroup.value?.platform,
      botId: selectedGroup.value?.botId,
      groupId: selectedGroup.value?.groupId,
      keyword: keyword.value || undefined,
      page,
      pageSize,
    })
  } finally {
    loading.value = false
  }
}

async function correct(row: EmojiMappingItem) {
  const {value} = await ElMessageBox.prompt('请输入修正后的表情包描述', '修正表情包描述', {
    inputValue: row.description,
  })
  await cognitionApi.correctEmoji(row.id, value)
  ElMessage.success('已修正并即时刷新缓存')
  await load()
}

onMounted(() => load(1))
</script>
