<template>
  <el-link
      v-if="traceId"
      type="primary"
      class="trace-link"
      @click.stop="goTrace"
  >
    <el-icon>
      <Link/>
    </el-icon>
    <span>{{ short ? traceId.slice(0, 12) + '…' : traceId }}</span>
  </el-link>
  <span v-else class="trace-empty">-</span>
</template>

<script setup lang="ts">
import {useRouter} from 'vue-router'
import {Link} from '@element-plus/icons-vue'

const props = withDefaults(
    defineProps<{
      traceId?: string | null
      short?: boolean
    }>(),
    {
      traceId: null,
      short: false,
    },
)

const router = useRouter()

function goTrace() {
  if (!props.traceId) return
  router.push({path: '/observe/traces', query: {traceId: props.traceId}})
}
</script>

<style scoped>
.trace-link {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.trace-empty {
  color: #86909c;
}
</style>
