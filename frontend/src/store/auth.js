import { reactive } from 'vue'
import http, { setToken, getToken } from '../api/http'

/**
 * 极简"登录态"存储。
 *
 * 为什么不用 Pinia：这个项目只有一个全局状态（当前用户），
 * 用 reactive 一个对象就够了，少一个依赖也少一层概念。
 *
 * 三个地方共用它：路由守卫（能不能进）、App.vue（菜单显示什么）、登录页（写入）。
 */
const USER_KEY = 'campus_user'

function readUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch (e) {
    return null
  }
}

export const authState = reactive({
  token: getToken(),
  user: readUser()
})

export function isLoggedIn() {
  return !!authState.token
}

export function isAdmin() {
  return authState.user?.role === 'ADMIN'
}

export function roleText(role) {
  return role === 'ADMIN' ? '管理员' : '学生'
}

/** 登录：成功后写进 localStorage —— 刷新页面不用重新登录 */
export async function login(username, password) {
  const data = await http.post('/auth/login', { username, password })
  authState.token = data.token
  authState.user = data.user
  setToken(data.token)
  localStorage.setItem(USER_KEY, JSON.stringify(data.user))
  return data.user
}

/** 注册：后端注册成功会直接返回 {token,user}，所以注册完就等于登录了 */
export async function register(username, password, displayName) {
  const data = await http.post('/auth/register', { username, password, displayName })
  authState.token = data.token
  authState.user = data.user
  setToken(data.token)
  localStorage.setItem(USER_KEY, JSON.stringify(data.user))
  return data.user
}

export function logout() {
  authState.token = ''
  authState.user = null
  setToken('')
  localStorage.removeItem(USER_KEY)
}

/**
 * 刷新页面后向服务端确认一次"我这个令牌还有效吗"。
 * 只靠 localStorage 的副本是不够的：令牌可能已过期、账号可能被禁用。
 */
export async function refreshMe() {
  if (!isLoggedIn()) {
    return null
  }
  try {
    const me = await http.get('/auth/me')
    authState.user = { ...authState.user, ...me }
    localStorage.setItem(USER_KEY, JSON.stringify(authState.user))
    return authState.user
  } catch (e) {
    logout()
    return null
  }
}
