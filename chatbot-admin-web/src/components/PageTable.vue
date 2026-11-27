<template>
  <div class="page-table-wrap">
    <el-table
        v-loading="loading"
        :data="data.items"
        border
        stripe
        style="width: 100%"
        @row-click="(row: any) => emit('row-click', row)"
    >
      <slot/>
      <template #empty>
        <el-empty :description="emptyText" :image-size="64"/>
      </template>
    </el-table>
    <div v-if="showPagination" class="pagination-bar">
      <el-pagination
          background
          layout="total, sizes, prev, pager, next"
          :total="data.total"
          :current-page="data.page"
          :page-size="data.pageSize"
          :page-sizes="[10, 20, 50, 100]"
          @current-change="(p: number) => emit('page-change', p, data.pageSize)"
          @size-change="(s: number) => emit('page-change', 1, s)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import type {PageResult} from '@/api/http'

withDefaults(
    defineProps<{
      data: PageResult<any>
      loading?: boolean
      emptyText?: string
      showPagination?: boolean
    }>(),
    {
      loading: false,
      emptyText: '暂无数据',
      showPagination: true,
    },
)

const emit = defineEmits<{
  (e: 'page-change', page: number, pageSize: number): void
  (e: 'row-click', row: any): void
}>()
</script>

<style scoped>
.pagination-bar {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
