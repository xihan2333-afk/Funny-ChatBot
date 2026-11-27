<template>
  <el-card shadow="never">
    <template #header>全局链接卡片缓存库（30 天自动过期）</template>
    <el-form :inline="true" style="margin-bottom: 12px">
      <el-form-item label="域名">
        <el-input v-model="domain" placeholder="按域名过滤" clearable/>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load(1)">查询</el-button>
      </el-form-item>
    </el-form>
    <PageTable :data="pageData" :loading="loading" @page-change="(p, ps) => load(p, ps)">
      <el-table-column prop="domain" label="域名" width="180">
        <template #default="{ row }">
          <span>{{ row.domain }}</span>
          <el-tag v-if="row.domainBlocked" type="danger" size="small" style="margin-left: 6px">黑名单</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip/>
      <el-table-column prop="normalizedUrl" label="URL" min-width="220" show-overflow-tooltip/>
      <el-table-column prop="expiresAt" label="过期时间" width="180"/>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button link type="danger" @click="del(row.id)">清理</el-button>
        </template>
      </el-table-column>
    </PageTable>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {ElMessage} from 'element-plus'
import PageTable from '@/components/PageTable.vue'
import type {PageResult} from '@/api/http'
import {cognitionApi, type LinkCardItem} from '@/api/cognition'

const loading = ref(false)
const domain = ref('')
const pageData = ref<PageResult<LinkCardItem>>({items: [], total: 0, page: 1, pageSize: 20})

async function load(page = 1, pageSize = pageData.value.pageSize) {
  loading.value = true
  try {
    pageData.value = await cognitionApi.listLinkCards({
      domain: domain.value || undefined,
      page,
      pageSize,
    })
  } finally {
    loading.value = false
  }
}

async function del(id: number) {
  await cognitionApi.deleteLinkCard(id)
  ElMessage.success('缓存已清理')
  await load()
}

onMounted(() => load(1))
</script>
