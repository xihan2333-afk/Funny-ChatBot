<template>
  <div v-loading="store.loading" class="overview-page">
    <template v-if="store.data">
      <!-- 1. 配置完整性告警条 -->
      <el-alert
          v-if="!store.data.configCompleteness.complete"
          type="warning"
          show-icon
          :closable="false"
          class="completeness-alert"
      >
        <template #title>
          <span>系统配置尚有缺项，部分能力可能不可用：</span>
        </template>
        <div class="gap-links">
          <span v-if="store.data.configCompleteness.missingPlatformChannel">
            尚未配置启用的社交平台接入渠道 →
            <el-link type="primary" @click="router.push('/access/channels')">前往配置渠道</el-link>
          </span>
          <span v-if="store.data.configCompleteness.unboundSlots.length > 0">
            未绑定模型槽位：{{ store.data.configCompleteness.unboundSlots.join('、') }} →
            <el-link type="primary" @click="router.push('/capability/models')">前往绑定槽位</el-link>
          </span>
          <span v-if="store.data.configCompleteness.providersMissingCredentials.length > 0">
            缺少可用凭证的供应商：
            {{ store.data.configCompleteness.providersMissingCredentials.map((p) => p.displayName).join('、') }} →
            <el-link type="primary" @click="router.push('/capability/models')">前往补充凭证</el-link>
          </span>
        </div>
      </el-alert>
      <el-alert
          v-else
          type="success"
          show-icon
          :closable="false"
          title="核心模型槽位、供应商凭证与平台接入渠道均已就绪。"
          class="completeness-alert"
      />

      <!-- 2. 5 步初始化引导检查单 -->
      <el-card v-if="!store.data.onboarding.completed" class="section-card" shadow="never">
        <template #header>
          <div class="card-header">
            <span>初始化引导检查单（5 步快速上手）</span>
            <el-tag type="info" size="small">
              已完成 {{ completedStepCount }} / {{ store.data.onboarding.steps.length }}
            </el-tag>
          </div>
        </template>
        <div class="step-list">
          <div
              v-for="(step, idx) in store.data.onboarding.steps"
              :key="step.key"
              class="step-item"
          >
            <div class="step-left">
              <el-tag :type="step.done ? 'success' : 'info'" size="small" round>
                {{ step.done ? '已完成' : `步骤 ${idx + 1}` }}
              </el-tag>
              <span class="step-title" :class="{ 'is-done': step.done }">{{ step.title }}</span>
            </div>
            <el-button
                size="small"
                :type="step.done ? 'default' : 'primary'"
                plain
                @click="router.push(step.route)"
            >
              {{ step.done ? '查看' : '去完成' }}
            </el-button>
          </div>
        </div>
      </el-card>

      <!-- 3. 日常速览与待办队列 -->
      <el-row :gutter="16" class="section-row">
        <el-col :span="8">
          <el-card shadow="never">
            <template #header>当日消费与熔断状态</template>
            <div class="metric-big">${{ store.data.dailySnapshot.todayCostUsd.toFixed(4) }}</div>
            <div class="breaker-row">
              <span>全局熔断：</span>
              <el-tag :type="store.data.dailySnapshot.globalBreakerTripped ? 'danger' : 'success'" size="small">
                {{ store.data.dailySnapshot.globalBreakerTripped ? '已熔断' : '正常' }}
              </el-tag>
              <span style="margin-left: 12px">单群熔断数：{{ store.data.dailySnapshot.trippedGroupCount }}</span>
            </div>
          </el-card>
        </el-col>
        <el-col :span="16">
          <el-card shadow="never">
            <template #header>待办队列</template>
            <el-row :gutter="12">
              <el-col :span="6">
                <div class="todo-box" @click="router.push('/eval/tasks')">
                  <div class="todo-num">{{ store.data.dailySnapshot.todo.pendingBlindReviews }}</div>
                  <div class="todo-label">待盲评样本</div>
                </div>
              </el-col>
              <el-col :span="6">
                <div class="todo-box" @click="router.push('/capability/personas')">
                  <div class="todo-num">{{ store.data.dailySnapshot.todo.pendingPersonaProposals }}</div>
                  <div class="todo-label">待审阅人格提案</div>
                </div>
              </el-col>
              <el-col :span="6">
                <div class="todo-box" @click="router.push('/eval/proposals')">
                  <div class="todo-num">{{ store.data.dailySnapshot.todo.pendingImprovementProposals }}</div>
                  <div class="todo-label">待审阅改进提案</div>
                </div>
              </el-col>
              <el-col :span="6">
                <div class="todo-box" @click="router.push('/alert/notifications')">
                  <div class="todo-num">{{ store.data.dailySnapshot.todo.unreadAlerts }}</div>
                  <div class="todo-label">未读告警</div>
                </div>
              </el-col>
            </el-row>
          </el-card>
        </el-col>
      </el-row>

      <el-card class="section-card" shadow="never">
        <template #header>
          <div class="card-header">
            <span>各群健康状态（近 24 小时 Turn 统计）</span>
            <el-button size="small" @click="store.load()">刷新</el-button>
          </div>
        </template>
        <el-table :data="store.data.dailySnapshot.groupHealth" border stripe>
          <el-table-column prop="groupName" label="群名称" min-width="140">
            <template #default="{ row }">
              {{ row.groupName || row.groupId }}
            </template>
          </el-table-column>
          <el-table-column label="三元组" min-width="200">
            <template #default="{ row }">
              <code>{{ row.platform }}/{{ row.botId }}/{{ row.groupId }}</code>
            </template>
          </el-table-column>
          <el-table-column prop="lifecycle" label="生命周期" width="120">
            <template #default="{ row }">
              <el-tag size="small">{{ row.lifecycle }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="inService" label="服务名单" width="100">
            <template #default="{ row }">
              <el-tag :type="row.inService ? 'success' : 'info'" size="small">
                {{ row.inService ? '已启用' : '未启用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="turnCount24h" label="24h Turn 数" width="120"/>
          <el-table-column label="24h 成功率" width="130">
            <template #default="{ row }">
              {{ (row.turnSuccessRate24h * 100).toFixed(1) }}%
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<script setup lang="ts">
import {computed, onMounted} from 'vue'
import {useRouter} from 'vue-router'
import {useOverviewStore} from '@/stores/overview'

const router = useRouter()
const store = useOverviewStore()

const completedStepCount = computed(
    () => store.data?.onboarding.steps.filter((s) => s.done).length ?? 0,
)

onMounted(() => {
  store.load()
})
</script>

<style scoped>
.completeness-alert {
  margin-bottom: 16px;
}

.gap-links {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 6px;
}

.section-card {
  margin-bottom: 16px;
}

.section-row {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.step-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.step-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: #f7f8fa;
  border-radius: 6px;
}

.step-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.step-title.is-done {
  color: #86909c;
  text-decoration: line-through;
}

.metric-big {
  font-size: 28px;
  font-weight: 700;
  color: #1d2129;
  margin-bottom: 10px;
}

.breaker-row {
  display: flex;
  align-items: center;
  font-size: 13px;
  color: #4e5969;
}

.todo-box {
  background: #f7f8fa;
  border-radius: 6px;
  padding: 14px;
  text-align: center;
  cursor: pointer;
  transition: background 0.15s;
}

.todo-box:hover {
  background: #e8f3ff;
}

.todo-num {
  font-size: 24px;
  font-weight: 700;
  color: #165dff;
}

.todo-label {
  font-size: 12px;
  color: #4e5969;
  margin-top: 4px;
}
</style>
