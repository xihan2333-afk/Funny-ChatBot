import {
  createRouter,
  createWebHistory,
  type NavigationGuardNext,
  type RouteLocationNormalized,
  type RouteRecordRaw
} from 'vue-router'
import AdminLayout from '@/layouts/AdminLayout.vue'
import {useAuthStore} from '@/stores/auth'

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: {title: '管理员登录', public: true},
  },
  {
    path: '/auth/change-password',
    name: 'change-password',
    component: () => import('@/views/auth/ChangePasswordView.vue'),
    meta: {title: '修改管理员口令', allowMustChange: true},
  },
  {
    path: '/',
    component: AdminLayout,
    redirect: '/overview',
    children: [
      {
        path: 'overview',
        name: 'overview',
        component: () => import('@/views/overview/OverviewView.vue'),
        meta: {title: '总览与引导'},
      },
      // 2. 接入与群
      {
        path: 'access/channels',
        name: 'access-channels',
        component: () => import('@/views/access/AccessChannelView.vue'),
        meta: {title: '接入渠道与 Bot'},
      },
      {
        path: 'access/groups',
        name: 'access-groups',
        component: () => import('@/views/access/GroupListView.vue'),
        meta: {title: '服务群管理'},
      },
      {
        path: 'access/groups/:platform/:botId/:groupId/config',
        name: 'access-group-config',
        component: () => import('@/views/access/GroupConfigView.vue'),
        meta: {title: '群配置管理'},
      },
      // 3. 人格与能力
      {
        path: 'capability/personas',
        name: 'capability-personas',
        component: () => import('@/views/capability/PersonaView.vue'),
        meta: {title: '人格库与演进提案'},
      },
      {
        path: 'capability/models',
        name: 'capability-models',
        component: () => import('@/views/capability/ModelSlotView.vue'),
        meta: {title: '模型、槽位与凭证池'},
      },
      {
        path: 'capability/tools',
        name: 'capability-tools',
        component: () => import('@/views/capability/ToolView.vue'),
        meta: {title: '工具管理'},
      },
      {
        path: 'capability/skills',
        name: 'capability-skills',
        component: () => import('@/views/capability/SkillView.vue'),
        meta: {title: 'Skill 管理'},
      },
      {
        path: 'capability/prompts',
        name: 'capability-prompts',
        component: () => import('@/views/capability/PromptView.vue'),
        meta: {title: 'Prompt 片段管理'},
      },
      // 4. 安全与参数
      {
        path: 'security/policies',
        name: 'security-policies',
        component: () => import('@/views/security/SecurityPolicyView.vue'),
        meta: {title: '安全策略'},
      },
      {
        path: 'security/params',
        name: 'security-params',
        component: () => import('@/views/security/SystemParamView.vue'),
        meta: {title: '系统参数'},
      },
      {
        path: 'security/ops',
        name: 'security-ops',
        component: () => import('@/views/security/OpsToolsView.vue'),
        meta: {title: '运维工具'},
      },
      // 5. 运行
      {
        path: 'runtime/overview',
        name: 'runtime-overview',
        component: () => import('@/views/runtime/RuntimeOverviewView.vue'),
        meta: {title: '运行总览'},
      },
      {
        path: 'runtime/turns',
        name: 'runtime-turns',
        component: () => import('@/views/runtime/TurnView.vue'),
        meta: {title: 'Turn 只读查询'},
      },
      {
        path: 'runtime/tasks',
        name: 'runtime-tasks',
        component: () => import('@/views/runtime/TaskView.vue'),
        meta: {title: 'Task 管理'},
      },
      {
        path: 'runtime/decisions',
        name: 'runtime-decisions',
        component: () => import('@/views/runtime/DecisionView.vue'),
        meta: {title: 'Decision 检索'},
      },
      {
        path: 'runtime/emotions',
        name: 'runtime-emotions',
        component: () => import('@/views/runtime/EmotionView.vue'),
        meta: {title: '情绪监控与重置'},
      },
      {
        path: 'runtime/breakers',
        name: 'runtime-breakers',
        component: () => import('@/views/runtime/BreakerView.vue'),
        meta: {title: '熔断监控与解除'},
      },
      // 6. 记忆与画像
      {
        path: 'cognition/memories',
        name: 'cognition-memories',
        component: () => import('@/views/cognition/MemoryView.vue'),
        meta: {title: '记忆管理'},
      },
      {
        path: 'cognition/profiles',
        name: 'cognition-profiles',
        component: () => import('@/views/cognition/ProfileView.vue'),
        meta: {title: '画像与关系'},
      },
      {
        path: 'cognition/emojis',
        name: 'cognition-emojis',
        component: () => import('@/views/cognition/EmojiMappingView.vue'),
        meta: {title: '表情包映射库'},
      },
      {
        path: 'cognition/link-cards',
        name: 'cognition-link-cards',
        component: () => import('@/views/cognition/LinkCardView.vue'),
        meta: {title: '链接卡片库'},
      },
      {
        path: 'cognition/privacy',
        name: 'cognition-privacy',
        component: () => import('@/views/cognition/PrivacyErasureView.vue'),
        meta: {title: '个体数据清除'},
      },
      // 7. 观测
      {
        path: 'observe/traces',
        name: 'observe-traces',
        component: () => import('@/views/observe/TraceView.vue'),
        meta: {title: '链路查询'},
      },
      {
        path: 'observe/logs',
        name: 'observe-logs',
        component: () => import('@/views/observe/LogView.vue'),
        meta: {title: '双流日志'},
      },
      {
        path: 'observe/metrics',
        name: 'observe-metrics',
        component: () => import('@/views/observe/MetricsCostView.vue'),
        meta: {title: '指标与成本'},
      },
      {
        path: 'observe/audit',
        name: 'observe-audit',
        component: () => import('@/views/observe/AuditView.vue'),
        meta: {title: '审计查询'},
      },
      // 8. 告警
      {
        path: 'alert/rules',
        name: 'alert-rules',
        component: () => import('@/views/alert/AlertRuleView.vue'),
        meta: {title: '告警规则'},
      },
      {
        path: 'alert/notifications',
        name: 'alert-notifications',
        component: () => import('@/views/alert/NotificationView.vue'),
        meta: {title: '通知中心'},
      },
      // 9. 评测与安全
      {
        path: 'eval/datasets',
        name: 'eval-datasets',
        component: () => import('@/views/eval/EvalSetView.vue'),
        meta: {title: '评测集管理'},
      },
      {
        path: 'eval/tasks',
        name: 'eval-tasks',
        component: () => import('@/views/eval/EvalTaskBlindView.vue'),
        meta: {title: '评估任务与盲评'},
      },
      {
        path: 'eval/proposals',
        name: 'eval-proposals',
        component: () => import('@/views/eval/AttributionProposalView.vue'),
        meta: {title: '归因与改进提案'},
      },
      {
        path: 'eval/replay',
        name: 'eval-replay',
        component: () => import('@/views/eval/ReplayView.vue'),
        meta: {title: '重放实验'},
      },
      {
        path: 'eval/attack-cases',
        name: 'eval-attack-cases',
        component: () => import('@/views/eval/AttackCaseView.vue'),
        meta: {title: '对抗用例与安全测试'},
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

export function authNavigationGuard(
    to: RouteLocationNormalized,
    _from: RouteLocationNormalized,
    next: NavigationGuardNext,
): void {
  const authStore = useAuthStore()
  if (to.meta.public) {
    if (authStore.isAuthenticated && !authStore.mustChange) {
      next('/overview')
      return
    }
    if (authStore.isAuthenticated && authStore.mustChange) {
      next('/auth/change-password')
      return
    }
    next()
    return
  }

  if (!authStore.isAuthenticated) {
    next({path: '/login', query: {redirect: to.fullPath}})
    return
  }

  if (authStore.mustChange && !to.meta.allowMustChange) {
    next('/auth/change-password')
    return
  }

  next()
}

router.beforeEach(authNavigationGuard)

export default router
