import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import HealthView from '../views/HealthView.vue'
import QaView from '../views/QaView.vue'
import DocView from '../views/DocView.vue'
import LoginView from '../views/LoginView.vue'
import RegisterView from '../views/RegisterView.vue'
import UserView from '../views/UserView.vue'
import DebugView from '../views/DebugView.vue'
import EvalView from '../views/EvalView.vue'
import FeedbackView from '../views/FeedbackView.vue'
import { isAdmin, isLoggedIn } from '../store/auth'

/**
 * 路由表 = 侧边栏菜单的骨架。
 *
 * meta 里三个字段的用途：
 * - title：菜单文字
 * - public：不需要登录（只有登录页）
 * - adminOnly：需要管理员角色；学生点进来会被送回问答页
 */
const routes = [
  { path: '/', redirect: '/qa' },
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { title: '登录', public: true }
  },
  {
    path: '/register',
    name: 'register',
    component: RegisterView,
    meta: { title: '注册', public: true }
  },
  {
    path: '/users',
    name: 'users',
    component: UserView,
    meta: { title: '用户管理', adminOnly: true }
  },
  {
    path: '/health',
    name: 'health',
    component: HealthView,
    meta: { title: '环境自检' }
  },
  {
    path: '/qa',
    name: 'qa',
    component: QaView,
    meta: { title: '智能问答' }
  },
  {
    path: '/documents',
    name: 'documents',
    component: DocView,
    meta: { title: '文档管理', adminOnly: true }
  },
  {
    path: '/debug',
    name: 'debug',
    component: DebugView,
    meta: { title: '检索调试', adminOnly: true }
  },
  {
    path: '/eval',
    name: 'eval',
    component: EvalView,
    meta: { title: '评测看板', adminOnly: true }
  },
  {
    path: '/feedback',
    name: 'feedback',
    component: FeedbackView,
    meta: { title: '用户反馈', adminOnly: true }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 路由守卫：进页面前先判断"能不能进"。
 *
 * 注意这**不是**安全边界 —— 真正的安全在后端拦截器（谁都能改前端代码）。
 * 这里只做体验：没登录别让人看到空白页，学生别点进管理员页再看到 403。
 */
router.beforeEach((to) => {
  if (to.meta.public) {
    return true
  }
  if (!isLoggedIn()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.adminOnly && !isAdmin()) {
    ElMessage.warning('该页面仅管理员可用，已为你返回问答页')
    return { path: '/qa' }
  }
  return true
})

export default router
