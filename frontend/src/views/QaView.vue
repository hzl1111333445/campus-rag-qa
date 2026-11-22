<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import http from '../api/http'

const route = useRoute()

/**
 * 智能问答页（第 2 步·对话式 UI）。
 *
 * 为什么做成聊天式而不是表单式？
 * 学生问规章是"一句接一句"的：先问"旷课多少学时处分"，接着问"那留校察看呢"。
 * 表单式每次都要清空重填，聊天式能自然承接上下文（后端还没有会话表，
 * 所以现在只做到"看得见历史对话"，真正的多轮理解放在第 5 步后端补）。
 *
 * 但 RAG 的诚实性信息不能因为做成聊天就丢掉，所以每条回答下面都保留：
 *   引用编号 chips（点开看原文）· 引用校验 · 接地校验（核了几条硬事实）· 是否拒答
 */
const examples = [
  '旷课多少学时会被处分？',
  '宿舍楼晚上几点关门？',
  '转专业一年可以申请几次？',
  '国家助学金一年多少钱？'
]

const messages = ref([])
const input = ref('')
const topK = ref(3)
const sending = ref(false)
const scrollBox = ref(null)
/** 每条回答的出处面板是否展开：{ [messageId]: true } */
const refsOpen = ref({})

let seq = 0

function nextId() {
  seq += 1
  return `m${seq}`
}

function scrollToBottom() {
  nextTick(() => {
    const el = scrollBox.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

async function send(text) {
  const q = (text ?? input.value).trim()
  if (!q || sending.value) return

  messages.value.push({ id: nextId(), role: 'user', text: q })
  const replyId = nextId()
  messages.value.push({ id: replyId, role: 'assistant', loading: true })
  input.value = ''
  sending.value = true
  scrollToBottom()

  try {
    const data = await http.post('/qa', { question: q, topK: topK.value })
    const idx = messages.value.findIndex((m) => m.id === replyId)
    if (idx >= 0) messages.value[idx] = { id: replyId, role: 'assistant', data }
  } catch (e) {
    const idx = messages.value.findIndex((m) => m.id === replyId)
    if (idx >= 0) messages.value[idx] = { id: replyId, role: 'assistant', error: e.message }
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

function onEnter(e) {
  // Enter 发送、Shift+Enter 换行
  if (e.shiftKey) return
  e.preventDefault()
  send()
}

function reset() {
  messages.value = []
  refsOpen.value = {}
  input.value = ''
}

function toggleRefs(id) {
  refsOpen.value = { ...refsOpen.value, [id]: !refsOpen.value[id] }
}

async function copyAnswer(text) {
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    /* 浏览器不允许剪贴板时静默失败，不弹窗打扰 */
  }
}

function paragraphs(text) {
  if (!text) return []
  return text.split(/\n+/).map((s) => s.trim()).filter(Boolean)
}

function docTitle(t) {
  // 文件名前缀带序号（如「11-广州软件学院学生考勤管理制度」），展示时去掉，避免像编号乱码
  return (t || '').replace(/^\s*\d+[-_—\s]+/, '')
}

function scoreText(v) {
  if (v === null || v === undefined) return '-'
  return Number(v).toFixed(4)
}

function groundedTag(data) {
  if (!data.grounded) return { type: 'danger', text: '接地校验未通过' }
  if (!data.factCount) return { type: 'info', text: '无可核对硬事实' }
  return { type: 'success', text: `接地通过 · 核了 ${data.factCount} 条` }
}

/**
 * 支持 /qa?q=问题 直接提问，用于分享/收藏一个问题，也方便截图与自动化测试。
 * （不模拟假数据：问的仍然是真接口。）
 */
onMounted(() => {
  const q = route.query.q
  if (typeof q === 'string' && q.trim()) send(q)
})
</script>

<template>
  <div class="chat">
    <!-- 顶部工具条 -->
    <div class="chat-top">
      <div class="top-left">
        <span class="top-title">智能问答</span>
        <span class="top-sub">只依据校内规章作答，答案可追溯到原文</span>
      </div>
      <div class="top-right">
        <span class="top-label">资料条数</span>
        <el-select v-model="topK" size="small" style="width: 76px">
          <el-option v-for="n in [3, 4, 5, 8]" :key="n" :label="n" :value="n" />
        </el-select>
        <el-button v-if="messages.length" size="small" text @click="reset">新会话</el-button>
      </div>
    </div>

    <!-- 消息流 -->
    <div ref="scrollBox" class="chat-body">
      <!-- 空态：欢迎 + 示例问题 -->
      <div v-if="!messages.length" class="welcome">
        <div class="welcome-icon">规</div>
        <h2 class="welcome-title">有什么想问的？</h2>
        <p class="welcome-sub">
          课程、学籍、奖惩、宿舍、资助等校内规章都能问。<br />
          系统会先把相关条款检索出来，再让大模型照着条款作答，答不出会直接说"没查到"。
        </p>
        <div class="examples">
          <button v-for="q in examples" :key="q" class="example" @click="send(q)">
            {{ q }}
          </button>
        </div>
      </div>

      <!-- 对话 -->
      <div v-for="m in messages" :key="m.id" class="row" :class="m.role">
        <template v-if="m.role === 'user'">
          <div class="bubble user-bubble">{{ m.text }}</div>
          <div class="avatar user-avatar">我</div>
        </template>

        <template v-else>
          <div class="avatar ai-avatar">AI</div>

          <!-- 生成中 -->
          <div v-if="m.loading" class="bubble ai-bubble">
            <div class="typing">
              <span></span><span></span><span></span>
              <em class="typing-text">正在检索规章并生成回答…</em>
            </div>
          </div>

          <!-- 出错 -->
          <div v-else-if="m.error" class="bubble ai-bubble error-bubble">
            <div class="error-title">请求失败</div>
            <div class="error-msg">{{ m.error }}</div>
          </div>

          <!-- 正常回答 -->
          <div v-else class="bubble ai-bubble">
            <div class="answer-head">
              <el-tag v-if="m.data.gated" type="warning" size="small" effect="dark">未检索到相关资料</el-tag>
              <el-tag :type="m.data.citationOk ? 'success' : 'danger'" size="small">
                {{ m.data.citationOk ? '引用校验通过' : '引用编号异常' }}
              </el-tag>
              <el-tag :type="groundedTag(m.data).type" size="small">{{ groundedTag(m.data).text }}</el-tag>
            </div>

            <div class="answer-body">
              <p v-for="(p, i) in paragraphs(m.data.answer)" :key="i">{{ p }}</p>
            </div>

            <!-- 引用 chips：点开看原文 -->
            <div v-if="m.data.references?.length" class="cites">
              <span class="cites-label">出处</span>
              <button
                v-for="r in m.data.references"
                :key="r.index"
                class="cite"
                :class="{ active: refsOpen[m.id] }"
                :title="`《${r.documentTitle}》第 ${r.chunkIndex} 块`"
                @click="toggleRefs(m.id)"
              >
                [{{ r.index }}] {{ docTitle(r.documentTitle) }}
              </button>
              <button class="cite-toggle" @click="toggleRefs(m.id)">
                {{ refsOpen[m.id] ? '收起原文' : '看原文' }}
              </button>
            </div>

            <!-- 展开的原文卡片 -->
            <div v-if="refsOpen[m.id]" class="ref-list">
              <div v-for="r in m.data.references" :key="r.index" class="ref-card">
                <div class="ref-head">
                  <span class="ref-index">[{{ r.index }}]</span>
                  <span class="ref-doc">《{{ docTitle(r.documentTitle) }}》</span>
                  <span class="ref-chunk">第 {{ r.chunkIndex }} 块</span>
                  <span class="ref-score">相关度 {{ scoreText(r.score) }}</span>
                </div>
                <div class="ref-content">{{ r.content }}</div>
              </div>
            </div>

            <!-- 告警 -->
            <div v-if="m.data.invalidCitations?.length" class="warn warn-danger">
              模型写出了不存在的引用编号：{{ m.data.invalidCitations.join('、') }}（资料只有 {{ m.data.retrievedCount }} 条）
            </div>
            <div v-if="m.data.missingFacts?.length" class="warn warn-warn">
              以下事实在被引用原文中找不到，可能是模型自行补充：{{ m.data.missingFacts.join('、') }}
            </div>

            <div class="answer-foot">
              <span class="foot-meta">
{{ m.data.retrievedCount }} 块 · 最高相关度 {{ scoreText(m.data.topScore) }} · {{ m.data.elapsedMs }} ms
              </span>
              <span class="foot-actions">
                <button class="foot-btn" @click="copyAnswer(m.data.answer)">复制</button>
                <button class="foot-btn" @click="toggleRefs(m.id)">溯源</button>
              </span>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- 输入区（吸底） -->
    <div class="chat-input">
      <div class="input-box">
        <el-input
          v-model="input"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 5 }"
          resize="none"
          maxlength="200"
          placeholder="用大白话问，例如：旷课多少学时会被处分？（Enter 发送 / Shift + Enter 换行）"
          @keydown="onEnter"
        />
        <el-button
          class="send-btn"
          type="primary"
          :disabled="!input.trim() || sending"
          :loading="sending"
          @click="send()"
        >
          发送
        </el-button>
      </div>
      <div class="input-tip">回答只依据校内规章原文，请以官方文件为准；涉及个人情况请咨询对应部门。</div>
    </div>
  </div>
</template>

<style scoped>
.chat {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 92px);
  background: #f7f8fa;
  border-radius: 8px;
  overflow: hidden;
}

/* ---------- 顶部 ---------- */
.chat-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 18px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  flex: none;
}
.top-title {
  font-size: 15px;
  font-weight: 600;
}
.top-sub {
  margin-left: 10px;
  font-size: 12px;
  color: #909399;
}
.top-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.top-label {
  font-size: 12px;
  color: #909399;
}

/* ---------- 消息流 ---------- */
.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px 18px 8px;
  scroll-behavior: smooth;
}
.row {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
  align-items: flex-start;
}
.row.user {
  justify-content: flex-end;
}
.avatar {
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
}
.ai-avatar {
  background: #1f6feb;
  color: #fff;
}
.user-avatar {
  background: #e9edf5;
  color: #57606a;
}
.bubble {
  max-width: 78%;
  padding: 12px 14px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.8;
}
.user-bubble {
  background: #1f6feb;
  color: #fff;
  border-top-right-radius: 2px;
  white-space: pre-wrap;
}
.ai-bubble {
  background: #fff;
  border: 1px solid #ebeef5;
  border-top-left-radius: 2px;
}

/* 生成中动画 */
.typing {
  display: flex;
  align-items: center;
  gap: 4px;
}
.typing span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #b6c2d2;
  animation: blink 1.2s infinite ease-in-out;
}
.typing span:nth-child(2) {
  animation-delay: 0.2s;
}
.typing span:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes blink {
  0%,
  80%,
  100% {
    opacity: 0.25;
    transform: translateY(0);
  }
  40% {
    opacity: 1;
    transform: translateY(-2px);
  }
}
.typing-text {
  margin-left: 6px;
  font-style: normal;
  font-size: 13px;
  color: #909399;
}

/* 回答 */
.answer-head {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.answer-body p {
  margin: 0 0 6px;
}
.cites {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed #ebeef5;
}
.cites-label {
  font-size: 12px;
  color: #909399;
}
.cite {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 2px 8px;
  border-radius: 12px;
  border: 1px solid #d9e4f5;
  background: #f2f7ff;
  color: #1f6feb;
  font-size: 12px;
  cursor: pointer;
}
.cite:hover,
.cite.active {
  background: #1f6feb;
  color: #fff;
  border-color: #1f6feb;
}
.cite-toggle {
  border: none;
  background: none;
  color: #909399;
  font-size: 12px;
  cursor: pointer;
  text-decoration: underline;
}

/* 原文卡片 */
.ref-list {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ref-card {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  background: #fafbfc;
  padding: 8px 10px;
}
.ref-head {
  display: flex;
  gap: 8px;
  align-items: baseline;
  flex-wrap: wrap;
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}
.ref-index {
  color: #1f6feb;
  font-weight: 600;
}
.ref-doc {
  color: #303133;
}
.ref-content {
  font-size: 13px;
  line-height: 1.75;
  color: #57606a;
  white-space: pre-wrap;
}

/* 告警 */
.warn {
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 12.5px;
  line-height: 1.7;
}
.warn-danger {
  background: #fef0f0;
  color: #c45656;
  border: 1px solid #fbc4c4;
}
.warn-warn {
  background: #fdf6ec;
  color: #b88230;
  border: 1px solid #f5dab1;
}

/* 页脚 */
.answer-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 10px;
  font-size: 12px;
  color: #b6c2d2;
}
.foot-btn {
  border: none;
  background: none;
  color: #909399;
  cursor: pointer;
  font-size: 12px;
  padding: 0 4px;
}
.foot-btn:hover {
  color: #1f6feb;
}

/* 错误 */
.error-bubble {
  background: #fef0f0;
  border-color: #fbc4c4;
}
.error-title {
  font-weight: 600;
  color: #c45656;
  margin-bottom: 4px;
}
.error-msg {
  font-size: 13px;
  color: #a33;
}

/* ---------- 空态 ---------- */
.welcome {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 0 24px;
}
.welcome-icon {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  background: #1f6feb;
  color: #fff;
  font-size: 22px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 14px;
}
.welcome-title {
  margin: 0 0 8px;
  font-size: 20px;
}
.welcome-sub {
  margin: 0 0 20px;
  font-size: 13px;
  line-height: 1.9;
  color: #909399;
  max-width: 520px;
}
.examples {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: center;
  max-width: 640px;
}
.example {
  padding: 8px 14px;
  border-radius: 18px;
  border: 1px solid #e4e7ed;
  background: #fff;
  color: #57606a;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
}
.example:hover {
  border-color: #1f6feb;
  color: #1f6feb;
  background: #f2f7ff;
}

/* ---------- 输入区 ---------- */
.chat-input {
  flex: none;
  padding: 10px 18px 12px;
  background: #fff;
  border-top: 1px solid #ebeef5;
}
.input-box {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  border: 1px solid #dcdfe6;
  border-radius: 10px;
  padding: 8px 10px;
  transition: border-color 0.15s;
}
.input-box:focus-within {
  border-color: #1f6feb;
}
.input-box :deep(.el-textarea__inner) {
  border: none;
  box-shadow: none;
  padding: 4px 2px;
  font-size: 14px;
  line-height: 1.7;
}
.send-btn {
  flex: none;
}
.input-tip {
  margin-top: 6px;
  font-size: 11.5px;
  color: #c0c4cc;
  text-align: center;
}
</style>
