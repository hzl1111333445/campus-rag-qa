<script setup>
import { onMounted, ref } from 'vue'
import http from '../api/http'

/**
 * 环境自检页（第 1 步的验收页面）。
 *
 * 它只做一件事：证明"前端能跑起来 + 能穿透 Vite 代理打到后端"。
 * 所以只调一个最简单、不花钱的接口 GET /api/health，把返回原样显示出来。
 */
const loading = ref(true)
const health = ref(null)
const error = ref('')
const checkedAt = ref('')

async function check() {
  loading.value = true
  error.value = ''
  try {
    health.value = await http.get('/health')
    checkedAt.value = new Date().toLocaleString('zh-CN')
  } catch (e) {
    health.value = null
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(check)
</script>

<template>
  <el-card shadow="never" class="card">
    <template #header>
      <div class="card-head">
        <span>后端连通性自检</span>
        <el-button size="small" @click="check" :loading="loading">重新检查</el-button>
      </div>
    </template>

    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      show-icon
      title="连不上后端"
      :description="error + '（检查后端是否启动、端口是否 8081/8090、VITE_API_TARGET 是否配对）'"
    />

    <template v-else-if="health">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="状态">
          <el-tag :type="health.status === 'UP' ? 'success' : 'danger'" size="small">
            {{ health.status }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="应用名">{{ health.appName }}</el-descriptions-item>
        <el-descriptions-item label="Java 版本">{{ health.javaVersion }}</el-descriptions-item>
        <el-descriptions-item label="服务时间">{{ health.time }}</el-descriptions-item>
      </el-descriptions>
      <p class="tip">
        页面上能出现上面这些字段，说明：Vite 跑起来了 → 代理转发通了 → Spring Boot 活着。
        检查时间：{{ checkedAt }}
      </p>
    </template>

    <el-skeleton v-else :rows="3" animated />
  </el-card>
</template>

<style scoped>
.card {
  max-width: 760px;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.tip {
  margin: 12px 0 0;
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
}
</style>
