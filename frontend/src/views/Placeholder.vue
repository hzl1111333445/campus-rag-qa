<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

/**
 * 占位页：给"还没做"的菜单一个正经样子，而不是点进去 404。
 * 好处是：现在就能看到完整导航结构（答辩演示时可以直接讲"这是规划好的五个模块"），
 * 每完成一个模块，路由表里把 component 换成真页面即可。
 */
const route = useRoute()
const title = computed(() => route.meta?.title || '待实现')
const todo = computed(() => route.meta?.todo || '这个模块还没开始做')
</script>

<template>
  <el-card shadow="never" class="card">
    <template #header>{{ title }}</template>
    <el-empty :description="todo" />
  </el-card>
</template>

<style scoped>
.card {
  max-width: 760px;
}
</style>
