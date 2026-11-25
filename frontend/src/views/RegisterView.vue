<template>
  <div class="register-page">
    <div class="register-card">
      <h2 class="register-title">注册账号</h2>
      <p class="register-sub">注册后是「学生」身份：能提问、能评价、能看自己的对话历史</p>

      <el-form :model="form" label-position="top" @submit.prevent>
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="3~20 位，字母/数字/下划线" maxlength="20" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="6~32 位" maxlength="32" />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input v-model="form.confirm" type="password" show-password placeholder="再输一次" maxlength="32" />
        </el-form-item>
        <el-form-item label="显示名（可选）">
          <el-input v-model="form.displayName" placeholder="比如：张三" maxlength="20" />
        </el-form-item>

        <el-button type="primary" class="register-btn" :loading="submitting" @click="submit">
          注册并进入
        </el-button>
      </el-form>

      <div class="register-foot">
        已经有账号？<router-link to="/login">返回登录</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { register } from '../store/auth'

const router = useRouter()
const submitting = ref(false)
const form = reactive({ username: '', password: '', confirm: '', displayName: '' })

/**
 * 前端只做"明显错误"的拦截，真正的规则在后端（用户名格式、重名、密码长度）。
 *
 * 为什么还要前端校验：重名要往返一次网络，输错密码确认也白跑一趟——
 * 能本地拦的就别浪费一次请求。
 */
async function submit() {
  const username = form.username.trim()
  if (!/^[A-Za-z0-9_]{3,20}$/.test(username)) {
    ElMessage.warning('用户名要 3~20 位，只能用字母、数字、下划线')
    return
  }
  if (!form.password || form.password.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  if (form.password !== form.confirm) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }

  submitting.value = true
  try {
    await register(username, form.password, form.displayName.trim())
    ElMessage.success('注册成功，已自动登录')
    router.replace('/qa')
  } catch (e) {
    // http.js 的拦截器已经弹过错误提示，这里只兜底
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.register-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(160deg, #f5f8ff 0%, #eef3fb 40%, #f7f9fc 100%);
}

.register-card {
  width: 380px;
  padding: 30px 32px 24px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 12px 40px rgba(23, 51, 102, 0.1);
}

.register-title {
  margin: 0 0 6px;
  font-size: 21px;
  color: #1f2d3d;
}

.register-sub {
  margin: 0 0 18px;
  font-size: 12px;
  color: #8a94a6;
  line-height: 1.6;
}

.register-btn {
  width: 100%;
  margin-top: 6px;
}

.register-foot {
  margin-top: 14px;
  font-size: 12px;
  color: #8a94a6;
  text-align: center;
}

.register-foot a {
  color: #409eff;
  text-decoration: none;
}
</style>
