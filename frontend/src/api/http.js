import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一的请求出口。
 *
 * 四点约定（后面所有页面都照这个来）：
 * 1. baseURL 写 `/api`，开发时由 Vite 代理转给后端，生产时前后端同域，都不用改代码
 * 2. 超时给 60 秒：问答要调大模型，比普通接口慢得多
 * 3. 错误在这里统一弹提示，页面里只关心拿到的数据，不用每个页面写一遍 try/catch
 * 4. 自动带上登录令牌；遇到 401 自动清掉本地登录态并跳登录页
 */
const TOKEN_KEY = 'campus_token'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
  } else {
    localStorage.removeItem(TOKEN_KEY)
  }
}

function authHeader() {
  const token = getToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

function notifyError(error) {
  const status = error.response?.status
  const detail = error.response?.data?.message || error.message
  if (status === 401) {
    // 令牌过期或没登录：清登录态，跳到登录页，并把当前地址带上以便登录后回来
    setToken('')
    localStorage.removeItem('campus_user')
    ElMessage.error('登录已过期，请重新登录')
    const from = encodeURIComponent(window.location.pathname + window.location.search)
    if (!window.location.pathname.startsWith('/login')) {
      window.location.href = `/login?redirect=${from}`
    }
    return
  }
  if (status === 403) {
    ElMessage.error(`权限不足：${detail}`)
    return
  }
  ElMessage.error(status ? `请求失败（${status}）：${detail}` : `网络错误：${detail}`)
}

const http = axios.create({
  baseURL: '/api',
  timeout: 60000
})

http.interceptors.request.use((config) => {
  config.headers = { ...config.headers, ...authHeader() }
  return config
})

http.interceptors.response.use((resp) => resp.data, notifyError)

/**
 * 和 http 一模一样，但**不拆包**：返回完整的 axios 响应（含 headers）。
 *
 * 什么时候需要它？后端把"本次会话 id"放在响应头 X-Session-Id 里回传
 * （这样返回体结构不变、老调用方零改动），前端就得读 header，用这个实例。
 */
export const httpRaw = axios.create({
  baseURL: '/api',
  timeout: 60000
})

httpRaw.interceptors.request.use((config) => {
  config.headers = { ...config.headers, ...authHeader() }
  return config
})

httpRaw.interceptors.response.use((resp) => resp, notifyError)

export default http
