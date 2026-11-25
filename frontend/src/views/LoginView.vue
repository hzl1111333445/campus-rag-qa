<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '../store/auth'

/**
 * 登录页。
 *
 * 两个默认账号直接列在页面上 —— 这是答辩/演示环境的有意设计：
 * 评委想看"管理员能看到什么"，不该卡在背密码上。上线前当然要删掉这块。
 */
const route = useRoute()
const router = useRouter()

const form = reactive({ username: '', password: '' })
const loading = ref(false)

const accounts = [
  { label: '管理员（能管知识库）', username: 'admin', password: 'admin123' },
  { label: '学生（只能问答）', username: 'student', password: 'student123' }
]

function fill(account) {
  form.username = account.username
  form.password = account.password
}

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const user = await login(form.username.trim(), form.password)
    ElMessage.success(`欢迎，${user.displayName || user.username}`)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/qa'
    router.replace(redirect)
  } catch (e) {
    // 错误提示已由 http 拦截器统一弹出，这里只需要恢复按钮
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <h1 class="title">校园规章制度智能问答系统</h1>
      <p class="subtitle">Spring Boot 3.5 · RAG · 引用溯源</p>

      <el-form class="form" @submit.prevent="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" size="large" clearable />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            size="large"
            show-password
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
          登录
        </el-button>
      </el-form>

      <div class="demo">
        <div class="demo-title">演示账号（点一下自动填入）</div>
        <div class="demo-list">
          <el-button v-for="a in accounts" :key="a.username" text size="small" @click="fill(a)">
            {{ a.label }}
          </el-button>
        </div>
      </div>

      <p class="hint">学生可以问答、看历史；管理员额外能上传文档、调试检索、跑评测。</p>

      <div class="register-line">
        还没有账号？<router-link to="/register" class="register-link">注册一个</router-link>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #eef4ff 0%, #f7f9fc 55%, #eef7f1 100%);
}
.login-card {
  width: 420px;
  padding: 36px 36px 24px;
  background: #fff;
  border-radius: 14px;
  box-shadow: 0 12px 40px rgba(31, 45, 61, 0.1);
}
.title {
  margin: 0 0 6px;
  font-size: 20px;
  text-align: center;
}
.subtitle {
  margin: 0 0 26px;
  font-size: 12px;
  color: #909399;
  text-align: center;
}
.form :deep(.el-form-item) {
  margin-bottom: 16px;
}
.submit {
  width: 100%;
}
.demo {
  margin-top: 22px;
  padding-top: 14px;
  border-top: 1px dashed #e4e7ed;
}
.demo-title {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}
.demo-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.hint {
  margin: 14px 0 0;
  font-size: 12px;
  color: #a8abb2;
  line-height: 1.6;
}
.register-line {
  margin-top: 10px;
  font-size: 12px;
  color: #8a94a6;
  text-align: center;
}

.register-link {
  color: #409eff;
  text-decoration: none;
}
</style>
