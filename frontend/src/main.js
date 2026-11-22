import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import App from './App.vue'
import router from './router'

/**
 * 前端入口：挂载 App 到 index.html 的 #app。
 *
 * Element Plus 在这里全局注册（main.js 里 use 一次，所有页面都能用 el-button 之类），
 * 语言包用中文，避免日期/分页组件出现英文。
 */
createApp(App)
  .use(ElementPlus, { locale: zhCn })
  .use(router)
  .mount('#app')
