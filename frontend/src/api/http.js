import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一的请求出口。
 *
 * 三点约定（后面所有页面都照这个来）：
 * 1. baseURL 写 `/api`，开发时由 Vite 代理转给后端，生产时前后端同域，都不用改代码
 * 2. 超时给 60 秒：问答要调大模型，比普通接口慢得多
 * 3. 错误在这里统一弹提示，页面里只关心拿到的数据，不用每个页面写一遍 try/catch
 */
const http = axios.create({
  baseURL: '/api',
  timeout: 60000
})

function notifyError(error) {
  const status = error.response?.status
  const detail = error.response?.data?.message || error.message
  ElMessage.error(status ? `请求失败（${status}）：${detail}` : `网络错误：${detail}`)
}

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

httpRaw.interceptors.response.use((resp) => resp, notifyError)

export default http
