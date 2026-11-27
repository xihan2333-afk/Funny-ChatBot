<template>
  <el-select
      :model-value="encodedValue"
      :placeholder="placeholder"
      clearable
      filterable
      style="min-width: 260px"
      @change="handleChange"
  >
    <el-option
        v-for="g in groups"
        :key="encode(g)"
        :label="`${g.groupName || g.groupId} (${g.platform}/${g.botId})`"
        :value="encode(g)"
    />
  </el-select>
</template>

<script setup lang="ts">
import {computed, onMounted, ref} from 'vue'
import {http, type PageResult} from '@/api/http'
import type {GroupTriple} from '@/stores/ui'

interface GroupItem extends GroupTriple {
  groupName?: string
  lifecycleState?: string
}

const props = withDefaults(
    defineProps<{
      modelValue: GroupTriple | null
      placeholder?: string
    }>(),
    {
      placeholder: '选择服务群 (platform/botId/groupId)',
    },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: GroupTriple | null): void
  (e: 'change', value: GroupTriple | null): void
}>()

const groups = ref<GroupItem[]>([])

function encode(g: GroupTriple): string {
  return `${g.platform}::${g.botId}::${g.groupId}`
}

const encodedValue = computed(() => (props.modelValue ? encode(props.modelValue) : ''))

function handleChange(val: string) {
  if (!val) {
    emit('update:modelValue', null)
    emit('change', null)
    return
  }
  const [platform = '', botId = '', groupId = ''] = val.split('::')
  const triple: GroupTriple = {platform, botId, groupId}
  emit('update:modelValue', triple)
  emit('change', triple)
}

onMounted(async () => {
  try {
    const res = await http.get<PageResult<GroupItem>>('/api/management/groups?page=1&pageSize=200', {
      silent: true,
    })
    groups.value = res.items
  } catch {
    groups.value = []
  }
})
</script>
