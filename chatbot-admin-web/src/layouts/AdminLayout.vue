<template>
  <el-container class="admin-layout">
    <el-aside :width="uiStore.sidebarCollapsed ? '64px' : '228px'" class="admin-aside">
      <div class="brand">
        <span v-if="!uiStore.sidebarCollapsed">Funny-ChatBot 管理台</span>
        <span v-else>CB</span>
      </div>
      <el-scrollbar class="menu-scroll">
        <el-menu
            :default-active="activeMenu"
            :collapse="uiStore.sidebarCollapsed"
            router
            background-color="#1d2129"
            text-color="#c9cdd4"
            active-text-color="#409eff"
        >
          <el-menu-item index="/overview">
            <el-icon>
              <DataAnalysis/>
            </el-icon>
            <template #title>1. 总览</template>
          </el-menu-item>

          <el-sub-menu index="access">
            <template #title>
              <el-icon>
                <Connection/>
              </el-icon>
              <span>2. 接入与群</span>
            </template>
            <el-menu-item index="/access/channels">接入渠道与 Bot</el-menu-item>
            <el-menu-item index="/access/groups">服务群管理</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="capability">
            <template #title>
              <el-icon>
                <Cpu/>
              </el-icon>
              <span>3. 人格与能力</span>
            </template>
            <el-menu-item index="/capability/personas">人格库与演进</el-menu-item>
            <el-menu-item index="/capability/models">模型与槽位</el-menu-item>
            <el-menu-item index="/capability/tools">工具管理</el-menu-item>
            <el-menu-item index="/capability/skills">Skill 管理</el-menu-item>
            <el-menu-item index="/capability/prompts">Prompt 片段</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="security">
            <template #title>
              <el-icon>
                <Lock/>
              </el-icon>
              <span>4. 安全与参数</span>
            </template>
            <el-menu-item index="/security/policies">安全策略</el-menu-item>
            <el-menu-item index="/security/params">系统参数</el-menu-item>
            <el-menu-item index="/security/ops">运维工具</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="runtime">
            <template #title>
              <el-icon>
                <Monitor/>
              </el-icon>
              <span>5. 运行</span>
            </template>
            <el-menu-item index="/runtime/overview">运行总览</el-menu-item>
            <el-menu-item index="/runtime/turns">Turn 查询</el-menu-item>
            <el-menu-item index="/runtime/tasks">Task 管理</el-menu-item>
            <el-menu-item index="/runtime/decisions">Decision 检索</el-menu-item>
            <el-menu-item index="/runtime/emotions">情绪监控</el-menu-item>
            <el-menu-item index="/runtime/breakers">熔断管理</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="cognition">
            <template #title>
              <el-icon>
                <Collection/>
              </el-icon>
              <span>6. 记忆与画像</span>
            </template>
            <el-menu-item index="/cognition/memories">记忆管理</el-menu-item>
            <el-menu-item index="/cognition/profiles">画像与关系</el-menu-item>
            <el-menu-item index="/cognition/emojis">表情包映射</el-menu-item>
            <el-menu-item index="/cognition/link-cards">链接卡片库</el-menu-item>
            <el-menu-item index="/cognition/privacy">个体数据清除</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="observe">
            <template #title>
              <el-icon>
                <Search/>
              </el-icon>
              <span>7. 观测</span>
            </template>
            <el-menu-item index="/observe/traces">链路查询</el-menu-item>
            <el-menu-item index="/observe/logs">双流日志</el-menu-item>
            <el-menu-item index="/observe/metrics">指标与成本</el-menu-item>
            <el-menu-item index="/observe/audit">审计查询</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="alert">
            <template #title>
              <el-icon>
                <Bell/>
              </el-icon>
              <span>
                8. 告警
                <el-badge
                    v-if="uiStore.todoUnreadAlerts > 0"
                    :value="uiStore.todoUnreadAlerts"
                    class="nav-badge"
                />
              </span>
            </template>
            <el-menu-item index="/alert/rules">告警规则</el-menu-item>
            <el-menu-item index="/alert/notifications">通知中心</el-menu-item>
          </el-sub-menu>

          <el-sub-menu index="eval">
            <template #title>
              <el-icon>
                <Flag/>
              </el-icon>
              <span>9. 评测与安全</span>
            </template>
            <el-menu-item index="/eval/datasets">评测集</el-menu-item>
            <el-menu-item index="/eval/tasks">评估任务与盲评</el-menu-item>
            <el-menu-item index="/eval/proposals">归因与提案</el-menu-item>
            <el-menu-item index="/eval/replay">重放实验</el-menu-item>
            <el-menu-item index="/eval/attack-cases">对抗用例与测试</el-menu-item>
          </el-sub-menu>
        </el-menu>
      </el-scrollbar>
    </el-aside>

    <el-container>
      <el-header class="admin-header">
        <div class="header-left">
          <el-button text @click="uiStore.toggleSidebar()">
            <el-icon>
              <Fold v-if="!uiStore.sidebarCollapsed"/>
              <Expand v-else/>
            </el-icon>
          </el-button>
          <span class="page-title">{{ route.meta.title || '管理平台' }}</span>
        </div>
        <div class="header-right">
          <el-tag v-if="totalTodos > 0" type="warning" size="small" class="todo-tag">
            待办 {{ totalTodos }}
          </el-tag>
          <span class="operator-name">{{ authStore.username || 'admin' }}</span>
          <el-button size="small" plain @click="handleLogout">退出登录</el-button>
        </div>
      </el-header>
      <el-main class="admin-main">
        <router-view/>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import {computed} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {
  Bell,
  Collection,
  Connection,
  Cpu,
  DataAnalysis,
  Expand,
  Flag,
  Fold,
  Lock,
  Monitor,
  Search,
} from '@element-plus/icons-vue'
import {useAuthStore} from '@/stores/auth'
import {useUiStore} from '@/stores/ui'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const uiStore = useUiStore()

const activeMenu = computed(() => {
  if (route.path.startsWith('/access/groups/')) {
    return '/access/groups'
  }
  return route.path
})

const totalTodos = computed(
    () => uiStore.todoUnreadAlerts + uiStore.todoPendingBlind + uiStore.todoPendingProposals,
)

async function handleLogout() {
  await authStore.logout()
  await router.push('/login')
}
</script>

<style scoped>
.admin-layout {
  min-height: 100vh;
}

.admin-aside {
  background-color: #1d2129;
  color: #fff;
  display: flex;
  flex-direction: column;
  transition: width 0.2s;
}

.brand {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  font-size: 15px;
  border-bottom: 1px solid #2e3440;
  color: #f2f3f5;
}

.menu-scroll {
  flex: 1;
}

.admin-aside :deep(.el-menu) {
  border-right: none;
}

.nav-badge {
  margin-left: 6px;
}

.admin-header {
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #e5e6eb;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
  color: #1d2129;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.operator-name {
  font-size: 14px;
  color: #4e5969;
}

.admin-main {
  background-color: #f7f8fa;
  padding: 20px;
}
</style>
