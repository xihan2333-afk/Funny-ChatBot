<template>
  <el-dialog
      :model-value="visible"
      :title="title"
      width="500px"
      :close-on-click-modal="false"
      @update:model-value="(v: boolean) => emit('update:visible', v)"
  >
    <el-alert
        type="warning"
        :closable="false"
        show-icon
        :title="warningText"
        style="margin-bottom: 14px"
    />

    <div v-if="impactItems.length > 0" class="impact-box">
      <div class="impact-title">影响范围预检：</div>
      <ul class="impact-list">
        <li v-for="item in impactItems" :key="item.label">
          <span>{{ item.label }}：</span>
          <strong>{{ item.value }}</strong>
        </li>
      </ul>
    </div>

    <slot/>

    <div v-if="confirmKeyword" class="confirm-input-wrap">
      <div class="confirm-hint">
        请输入 <code>{{ confirmKeyword }}</code> 以确认执行此高危操作：
      </div>
      <el-input
          v-model="inputValue"
          :placeholder="`请输入 ${confirmKeyword}`"
          autocomplete="off"
      />
    </div>

    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button
          type="danger"
          :loading="loading"
          :disabled="!canSubmit"
          @click="handleConfirm"
      >
        {{ confirmButtonText }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import {computed, ref, watch} from 'vue'

export interface ImpactItem {
  label: string
  value: string | number
}

const props = withDefaults(
    defineProps<{
      visible: boolean
      title: string
      warningText: string
      confirmKeyword?: string
      confirmButtonText?: string
      impactItems?: ImpactItem[]
      loading?: boolean
    }>(),
    {
      confirmKeyword: '',
      confirmButtonText: '确认执行',
      impactItems: () => [],
      loading: false,
    },
)

const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
  (e: 'confirm'): void
}>()

const inputValue = ref('')

watch(
    () => props.visible,
    (val) => {
      if (val) {
        inputValue.value = ''
      }
    },
)

const canSubmit = computed(() => {
  if (!props.confirmKeyword) return true
  return inputValue.value.trim() === props.confirmKeyword
})

function handleConfirm() {
  if (!canSubmit.value) return
  emit('confirm')
}
</script>

<style scoped>
.impact-box {
  background: #f7f8fa;
  border: 1px solid #e5e6eb;
  border-radius: 6px;
  padding: 10px 14px;
  margin-bottom: 14px;
}

.impact-title {
  font-weight: 600;
  margin-bottom: 6px;
  color: #1d2129;
}

.impact-list {
  margin: 0;
  padding-left: 18px;
  color: #4e5969;
  line-height: 1.7;
}

.confirm-input-wrap {
  margin-top: 12px;
}

.confirm-hint {
  margin-bottom: 8px;
  font-size: 13px;
  color: #4e5969;
}
</style>
