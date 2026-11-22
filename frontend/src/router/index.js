import { createRouter, createWebHistory } from 'vue-router'
import HealthView from '../views/HealthView.vue'
import QaView from '../views/QaView.vue'
import DocView from '../views/DocView.vue'
import Placeholder from '../views/Placeholder.vue'

/**
 * 路由表 = 侧边栏菜单的骨架。
 *
 * 现在只有"环境自检"是真页面，其余四个先落占位页。
 * 每做完一个模块，就把对应的 placeholder 换掉，菜单不用动。
 */
const routes = [
  { path: '/', redirect: '/health' },
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
    meta: { title: '文档管理' }
  },
  {
    path: '/debug',
    name: 'debug',
    component: Placeholder,
    meta: { title: '检索调试', todo: '第 4 步：五路检索对比 + 分数 + 改写前后' }
  },
  {
    path: '/eval',
    name: 'eval',
    component: Placeholder,
    meta: { title: '评测看板', todo: '第 5 步：跑评测 / 配置对比表 / 历史结果' }
  }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
