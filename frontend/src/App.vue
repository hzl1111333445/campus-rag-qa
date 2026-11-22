<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authState, isAdmin, logout, refreshMe, roleText } from './store/auth'

/**
 * 整个应用的外壳：顶栏 + 左侧菜单 + 右侧内容区。
 *
 * 为什么把布局放在 App.vue 而不是每个页面里各写一遍？
 * 因为切页面时侧边栏要一直亮着、不重绘，体验才连贯。
 *
 * 登录页是例外：它要占满整屏，所以 meta.public 的页面不套外壳。
 */
const route = useRoute()
const router = useRouter()

const allMenus = [
  { path: '/qa', title: '智能问答', adminOnly: false },
  { path: '/documents', title: '文档管理', adminOnly: true },
  { path: '/debug', title: '检索调试', adminOnly: true },
  { path: '/eval', title: '评测看板', adminOnly: true },
  { path: '/health', title: '环境自检', adminOnly: false }
]

// 学生看不到管理类菜单 —— 不是"藏起来更安全"，而是别让人点了才发现没权限
const menus = computed(() => allMenus.filter((m) => !m.adminOnly || isAdmin()))

const activeMenu = computed(() => route.path)
const isPublicPage = computed(() => !!route.meta.public)

function doLogout() {
  logout()
  ElMessage.success('已退出登录')
  router.replace('/login')
}

onMounted(() => {
  // 刷新页面后向服务端确认令牌是否仍然有效（过期会被清掉并跳登录页）
  refreshMe()
})
</script>

<template>
  <router-view v-if="isPublicPage" />

  <el-container v-else class="app">
    <el-header class="app-header">
      <div class="brand">
        <span class="brand-title">校园规章制度智能问答系统</span>
        <span class="brand-sub">Spring Boot 3.5 · RAG · 引用溯源</span>
      </div>
      <div class="header-right">
        <el-tag v-if="authState.user" size="small" :type="isAdmin() ? 'warning' : 'success'" effect="plain">
          {{ authState.user.displayName || authState.user.username }} · {{ roleText(authState.user.role) }}
        </el-tag>
        <el-button v-if="authState.user" text size="small" @click="doLogout">退出</el-button>
      </div>
    </el-header>

    <el-container>
      <el-aside width="196px" class="app-aside">
        <el-menu :default-active="activeMenu" router class="app-menu">
          <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
            <span>{{ m.title }}</span>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style>
/* 全局基础样式：只做"去默认边距"，页面自己的样式写在各自组件里 */
html,
body,
#app {
  height: 100%;
  margin: 0;
}
body {
  font-family: 'Microsoft YaHei', 'PingFang SC', system-ui, sans-serif;
  background: #f5f7fa;
  color: #303133;
}
</style>

<style scoped>
.app {
  height: 100%;
}
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}
.brand-title {
  font-size: 17px;
  font-weight: 600;
}
.brand-sub {
  margin-left: 12px;
  font-size: 12px;
  color: #909399;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.app-aside {
  background: #fff;
  border-right: 1px solid #e4e7ed;
}
.app-menu {
  border-right: none;
}
.app-main {
  padding: 16px;
  overflow: auto;
}
</style>
