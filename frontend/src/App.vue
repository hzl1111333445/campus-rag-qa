<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

/**
 * 整个应用的外壳：顶栏 + 左侧菜单 + 右侧内容区。
 *
 * 为什么把布局放在 App.vue 而不是每个页面里各写一遍？
 * 因为切页面时侧边栏要一直亮着、不重绘，体验才连贯。
 */
const route = useRoute()

const menus = [
  { path: '/qa', title: '智能问答', icon: 'ChatDotRound' },
  { path: '/documents', title: '文档管理', icon: 'Folder' },
  { path: '/debug', title: '检索调试', icon: 'Search' },
  { path: '/eval', title: '评测看板', icon: 'DataAnalysis' },
  { path: '/health', title: '环境自检', icon: 'CircleCheck' }
]

const activeMenu = computed(() => route.path)
</script>

<template>
  <el-container class="app">
    <el-header class="app-header">
      <div class="brand">
        <span class="brand-title">校园规章制度智能问答系统</span>
        <span class="brand-sub">Spring Boot 3.5 · RAG · 引用溯源</span>
      </div>
      <el-tag type="info" effect="plain" size="small">开发版 0.1.0</el-tag>
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
