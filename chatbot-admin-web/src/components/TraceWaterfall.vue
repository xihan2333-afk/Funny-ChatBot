<template>
  <div class="trace-waterfall">
    <div v-if="!spans.length" class="empty-tip">暂无 Span 数据</div>
    <div v-else class="span-list">
      <div
          v-for="row in flattenedSpans"
          :key="row.span.spanId"
          class="span-row"
          :class="{ 'is-security': row.hasSecurityEvent, 'is-selected': selectedSpan?.spanId === row.span.spanId }"
          @click="selectSpan(row.span)"
      >
        <div class="span-name-col" :style="{ paddingLeft: `${row.depth * 16 + 8}px` }">
          <span class="span-name" :title="row.span.name">{{ row.span.name }}</span>
          <el-tag v-if="row.hasSecurityEvent" type="danger" size="small" class="sec-tag">
            安全事件
          </el-tag>
        </div>
        <div class="span-bar-col">
          <div class="bar-track">
            <div
                class="bar-fill"
                :class="{ 'bar-security': row.hasSecurityEvent }"
                :style="{ left: `${row.leftPercent}%`, width: `${Math.max(row.widthPercent, 1.5)}%` }"
            />
          </div>
          <span class="duration-text">{{ row.durationMs }} ms</span>
        </div>
      </div>
    </div>

    <el-drawer
        v-model="drawerVisible"
        :title="selectedSpan ? `Span 详情：${selectedSpan.name}` : 'Span 详情'"
        size="460px"
    >
      <template v-if="selectedSpan">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="Span ID">{{ selectedSpan.spanId }}</el-descriptions-item>
          <el-descriptions-item label="Parent Span ID">{{ selectedSpan.parentSpanId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ selectedSpan.startTimeMs }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ selectedSpan.durationMs ?? 0 }} ms</el-descriptions-item>
          <el-descriptions-item label="状态">{{ selectedSpan.status || 'OK' }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="attr-title">属性（Attributes）</h4>
        <el-table :data="attributeRows" size="small" border>
          <el-table-column prop="key" label="Key" width="180"/>
          <el-table-column prop="value" label="Value"/>
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import {computed, ref} from 'vue'

export interface TraceSpanItem {
  spanId: string
  parentSpanId?: string | null
  name: string
  startTimeMs: number
  endTimeMs?: number
  durationMs?: number
  status?: string
  attributes?: Record<string, unknown>
}

interface FlattenedRow {
  span: TraceSpanItem
  depth: number
  leftPercent: number
  widthPercent: number
  durationMs: number
  hasSecurityEvent: boolean
}

const props = defineProps<{
  spans: TraceSpanItem[]
}>()

const selectedSpan = ref<TraceSpanItem | null>(null)
const drawerVisible = ref(false)

function hasSecurityAttribute(span: TraceSpanItem): boolean {
  const attrs = span.attributes || {}
  return Object.keys(attrs).some((k) => k.startsWith('chatbot.security.'))
}

const flattenedSpans = computed<FlattenedRow[]>(() => {
  if (!props.spans.length) return []
  const sorted = [...props.spans].sort((a, b) => a.startTimeMs - b.startTimeMs)
  const minStart = sorted[0]!.startTimeMs
  let maxEnd = minStart + 1
  for (const s of sorted) {
    const dur = s.durationMs ?? (s.endTimeMs !== undefined ? s.endTimeMs - s.startTimeMs : 1)
    const end = s.endTimeMs ?? s.startTimeMs + Math.max(dur, 1)
    if (end > maxEnd) maxEnd = end
  }
  const totalSpan = Math.max(maxEnd - minStart, 1)

  const childrenMap = new Map<string, TraceSpanItem[]>()
  const byId = new Map<string, TraceSpanItem>()
  for (const s of sorted) {
    byId.set(s.spanId, s)
  }
  const roots: TraceSpanItem[] = []
  for (const s of sorted) {
    if (!s.parentSpanId || !byId.has(s.parentSpanId)) {
      roots.push(s)
    } else {
      const list = childrenMap.get(s.parentSpanId) ?? []
      list.push(s)
      childrenMap.set(s.parentSpanId, list)
    }
  }

  const rows: FlattenedRow[] = []

  function walk(node: TraceSpanItem, depth: number) {
    const dur = Math.max(
        node.durationMs ?? (node.endTimeMs !== undefined ? node.endTimeMs - node.startTimeMs : 0),
        0,
    )
    const leftPercent = ((node.startTimeMs - minStart) / totalSpan) * 100
    const widthPercent = (dur / totalSpan) * 100
    rows.push({
      span: node,
      depth,
      leftPercent,
      widthPercent,
      durationMs: dur,
      hasSecurityEvent: hasSecurityAttribute(node),
    })
    const children = childrenMap.get(node.spanId) ?? []
    for (const child of children) {
      walk(child, depth + 1)
    }
  }

  for (const root of roots) {
    walk(root, 0)
  }
  return rows
})

const attributeRows = computed(() => {
  const attrs = selectedSpan.value?.attributes ?? {}
  return Object.entries(attrs).map(([key, value]) => ({
    key,
    value: typeof value === 'string' ? value : JSON.stringify(value),
  }))
})

function selectSpan(span: TraceSpanItem) {
  selectedSpan.value = span
  drawerVisible.value = true
}
</script>

<style scoped>
.trace-waterfall {
  border: 1px solid #e5e6eb;
  border-radius: 6px;
  background: #fff;
}

.empty-tip {
  padding: 24px;
  text-align: center;
  color: #86909c;
}

.span-row {
  display: flex;
  align-items: center;
  border-bottom: 1px solid #f2f3f5;
  padding: 6px 10px;
  cursor: pointer;
}

.span-row:hover {
  background: #f7f8fa;
}

.span-row.is-security {
  background: #fff1f0;
}

.span-row.is-selected {
  background: #e8f3ff;
}

.span-name-col {
  width: 320px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  overflow: hidden;
}

.span-name {
  font-size: 13px;
  color: #1d2129;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sec-tag {
  flex-shrink: 0;
}

.span-bar-col {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 10px;
}

.bar-track {
  position: relative;
  flex: 1;
  height: 14px;
  background: #f2f3f5;
  border-radius: 3px;
  overflow: hidden;
}

.bar-fill {
  position: absolute;
  top: 2px;
  bottom: 2px;
  background: #409eff;
  border-radius: 2px;
}

.bar-fill.bar-security {
  background: #f53f3f;
}

.duration-text {
  width: 72px;
  text-align: right;
  font-size: 12px;
  color: #4e5969;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.attr-title {
  margin: 16px 0 8px;
  font-size: 14px;
  color: #1d2129;
}
</style>
