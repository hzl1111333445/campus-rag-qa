<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { getToken } from '../api/http'
import { isAdmin } from '../store/auth'

/**
 * 智能问答页（第 2 步·对话式 UI + 会话管理）。
 *
 * 为什么做成聊天式而不是表单式？
 * 学生问规章是"一句接一句"的：先问"旷课多少学时处分"，接着问"那留校察看呢"。
 *
 * 会话从哪来？后端 qa_session / qa_message 两张表（第 3 步加的）：
 *   - 每次提问自动落库，响应头 X-Session-Id 告诉前端这次属于哪个会话
 *   - 左侧栏列出历史会话，点开加载历史消息（引用快照一起还原，不用重新检索）
 *
 * RAG 的诚实性信息不能因为做成聊天就丢掉，所以每条回答下面仍保留：
 *   引用校验 · 接地校验（核了几条硬事实）· 是否拒答 · 可展开的原文出处
 */
const route = useRoute()

const examples = [
  '旷课多少学时会被处分？',
  '宿舍楼晚上几点关门？',
  '转专业一年可以申请几次？',
  '国家助学金一年多少钱？'
]

/** 消息列表：[{ id, role:'user'|'assistant', text?, data?, loading?, error? }] */
const messages = ref([])
const sessions = ref([])
const sessionId = ref(null)
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

/* ==================== 会话管理 ==================== */

async function loadSessions() {
  try {
    const data = await http.get('/session', { params: { limit: 50 } })
    sessions.value = data.items || []
  } catch {
    /* 列表拉不到不影响提问，http 拦截器已经弹过提示 */
  }
}

async function newSession() {
  sessionId.value = null
  messages.value = []
  refsOpen.value = {}
  input.value = ''
}

/** 打开历史会话：把存下来的消息还原成当前的消息数组（含引用快照）。 */
async function openSession(s) {
  if (sending.value) return
  try {
    const data = await http.get(`/session/${s.id}/messages`)
    sessionId.value = s.id
    refsOpen.value = {}
    messages.value = (data.messages || []).map((m) => {
      if (m.role === 'user') {
        return { id: nextId(), role: 'user', text: m.content }
      }
      return { id: nextId(), role: 'assistant', data: toAnswer(m) }
    })
    scrollToBottom()
  } catch {
    /* 已在拦截器提示 */
  }
}

/** 数据库一行 → 页面用的回答结构（字段名从 snake_case 转 camelCase）。 */
function toAnswer(m) {
  let refs = []
  if (m.references_json) {
    try {
      refs = JSON.parse(m.references_json)
    } catch {
      refs = []
    }
  }
  return {
    answer: m.content,
    gated: !!m.gated,
    citationOk: m.citation_ok !== null && m.citation_ok !== undefined ? !!m.citation_ok : true,
    grounded: m.grounded !== null && m.grounded !== undefined ? !!m.grounded : true,
    factCount: m.fact_count || 0,
    topScore: m.top_score,
    elapsedMs: m.elapsed_ms,
    retrievedCount: refs.length,
    references: refs,
    missingFacts: m.missing_facts ? String(m.missing_facts).split('、') : [],
    invalidCitations: []
  }
}

async function renameSession(s) {
  try {
    const { value } = await ElMessageBox.prompt('给这个对话起个名字', '重命名', {
      inputValue: s.title,
      inputValidator: (v) => (v && v.trim() ? true : '标题不能为空'),
      confirmButtonText: '保存',
      cancelButtonText: '取消'
    })
    await http.patch(`/session/${s.id}`, { title: value.trim() })
    await loadSessions()
  } catch {
    /* 用户点了取消 */
  }
}

async function removeSession(s) {
  try {
    await ElMessageBox.confirm(`删除「${s.title}」及其全部对话？`, '删除对话', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
    await http.delete(`/session/${s.id}`)
    if (sessionId.value === s.id) {
      await newSession()
    }
    await loadSessions()
    ElMessage.success('已删除')
  } catch {
    /* 取消 */
  }
}

/* ==================== 提问 ==================== */

async function send(text) {
  const q = (text ?? input.value).trim()
  if (!q || sending.value) return

  messages.value.push({ id: nextId(), role: 'user', text: q })
  const replyId = nextId()
  messages.value.push({
    id: replyId,
    role: 'assistant',
    streaming: true,
    stage: '正在检索规章…',
    streamText: '',
    sources: []
  })
  input.value = ''
  sending.value = true
  scrollToBottom()

  try {
    await streamAsk(q, replyId)
    loadSessions()
  } catch (e) {
    patchMessage(replyId, { streaming: false, error: e.message || String(e) })
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

/** 局部更新某条消息（避免整条替换导致流式时闪烁）。 */
function patchMessage(id, patch) {
  const idx = messages.value.findIndex((m) => m.id === id)
  if (idx >= 0) messages.value[idx] = { ...messages.value[idx], ...patch }
}

/**
 * 流式提问：POST /api/qa/stream，手工解析 SSE。
 *
 * 为什么不用 EventSource？它只发 GET，而我们要 POST 送问题体。
 * 为什么不用 httpRaw？axios 要等完整响应体，拿不到"逐字到达"的过程。
 * 事件协议（后端 QaController）：meta → sources → token×N → done（或 error）。
 */
async function streamAsk(q, replyId) {
  const token = getToken()
  const resp = await fetch('/api/qa/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify({ question: q, topK: topK.value, sessionId: sessionId.value })
  })
  if (!resp.ok || !resp.body) {
    let detail = ''
    try {
      detail = await resp.text()
    } catch {
      /* 忽略 */
    }
    throw new Error(`HTTP ${resp.status} ${detail.slice(0, 200)}`)
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let eventName = ''
  let text = ''
  let lastPaint = 0

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    // SSE 以空行分隔事件；这里按行处理，data 到达即派发
    let idx
    while ((idx = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, idx).replace(/\r$/, '')
      buffer = buffer.slice(idx + 1)
      if (!line.trim()) continue
      if (line.startsWith(':')) continue            // 心跳
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
        continue
      }
      if (!line.startsWith('data:')) continue
      const payload = line.slice(5).trim()
      if (!payload) continue
      let data
      try {
        data = JSON.parse(payload)
      } catch {
        continue
      }

      if (eventName === 'meta') {
        if (data.sessionId && String(data.sessionId) !== String(sessionId.value)) {
          sessionId.value = Number(data.sessionId)
        }
      } else if (eventName === 'sources') {
        patchMessage(replyId, { sources: data.hits || [], stage: '正在组织回答…' })
        scrollToBottom()
      } else if (eventName === 'token') {
        text += data.delta || ''
        const now = Date.now()
        // 每 50ms 才刷一次视图：每个 token 都触发渲染会让长回答掉帧
        if (now - lastPaint > 50) {
          lastPaint = now
          patchMessage(replyId, { streamText: text, stage: '' })
          scrollToBottom()
        }
      } else if (eventName === 'done') {
        patchMessage(replyId, {
          streaming: false,
          streamText: '',
          stage: '',
          ttftMs: data.ttftMs,
          totalMs: data.totalMs,
          // 拒答题没有 token，答案在 done.answer 里
          data: data.answer
        })
        scrollToBottom()
      } else if (eventName === 'error') {
        patchMessage(replyId, { streaming: false, error: data.message || '生成失败' })
      }
    }
  }
  // 流异常结束（没收到 done）时的兜底
  const cur = messages.value.find((m) => m.id === replyId)
  if (cur && cur.streaming) {
    patchMessage(replyId, { streaming: false, streamText: '', stage: '' })
    if (text) patchMessage(replyId, { data: { answer: text, references: [], retrievedCount: 0 } })
  }
}

function onEnter(e) {
  // Enter 发送、Shift+Enter 换行
  if (e.shiftKey) return
  e.preventDefault()
  send()
}

function toggleRefs(id) {
  refsOpen.value = { ...refsOpen.value, [id]: !refsOpen.value[id] }
}

async function copyAnswer(text) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    /* 浏览器不允许剪贴板时静默失败，不弹窗打扰 */
  }
}

/* ==================== 展示工具 ==================== */

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

/**
 * 引用标签要分三种情况，不能只分真假：
 *   - 有编造编号 → 危险（这是真问题）
 *   - 没编造但也没引用（拒答、常识性回答）→ 只是"没引用"，不是错
 *   - 有引用且编号合法 → 通过
 */
function citeTag(data) {
  const bad = (data.invalidCitations || []).length
  const cited = (data.references || []).length
  if (bad) return { type: 'danger', text: `引用编号异常（${bad} 处）` }
  if (!cited) return { type: 'info', text: '未引用资料' }
  return { type: 'success', text: '引用校验通过' }
}

function groundedTag(data) {
  if (!data.grounded) return { type: 'danger', text: '接地校验未通过' }
  if (!data.factCount) return { type: 'info', text: '无可核对硬事实' }
  return { type: 'success', text: `接地通过 · 核了 ${data.factCount} 条` }
}

/** 列表里的时间：今天显示 HH:mm，其余显示 M-D */
function timeText(t) {
  if (!t) return ''
  const d = new Date(String(t).replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return ''
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return sameDay ? `${hh}:${mm}` : `${d.getMonth() + 1}-${d.getDate()}`
}

onMounted(async () => {
  await loadSessions()
  // /qa?session=3 直接打开某个历史会话（方便分享一条对话链接）
  const sid = route.query.session
  if (typeof sid === 'string' && sid.trim()) {
    await openSession({ id: Number(sid) })
    return
  }
  // /qa?q=问题 直接提问（分享/收藏一个问题，也用于截图与自动化测试）
  const q = route.query.q
  if (typeof q === 'string' && q.trim()) send(q)
})
</script>

<template>
  <div class="chat-wrap">
    <!-- 左：会话列表 -->
    <aside class="sessions">
      <div class="sessions-head">
        <span class="sessions-title">对话</span>
        <el-button size="small" type="primary" plain @click="newSession">+ 新对话</el-button>
      </div>
      <div class="sessions-list">
        <div
          v-for="s in sessions"
          :key="s.id"
          class="session-item"
          :class="{ active: s.id === sessionId }"
          @click="openSession(s)"
        >
          <div class="session-title">
            <span class="session-name">{{ s.title }}</span>
            <!-- 匿名会话 = 不带令牌的调用（评测脚本）留下的，只有管理员看得到 -->
            <el-tag v-if="isAdmin() && s.user_id == null" size="small" type="info" effect="plain">匿名</el-tag>
          </div>
          <div class="session-meta">{{ s.message_count }} 条 · {{ timeText(s.update_time) }}</div>
          <div class="session-ops">
            <button class="op" @click.stop="renameSession(s)">改名</button>
            <button class="op danger" @click.stop="removeSession(s)">删除</button>
          </div>
        </div>
        <div v-if="!sessions.length" class="sessions-empty">还没有对话，问一句就有了</div>
      </div>
      <div class="sessions-foot">会话存在数据库里，换设备也能看到</div>
    </aside>

    <!-- 右：对话主区 -->
    <section class="chat-main">
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
        </div>
      </div>

      <div ref="scrollBox" class="chat-body">
        <!-- 空态 -->
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

            <div v-if="m.streaming" class="bubble ai-bubble">
              <div class="answer-head">
                <el-tag v-if="m.stage" type="info" size="small" effect="plain">{{ m.stage }}</el-tag>
                <el-tag v-if="m.sources && m.sources.length" size="small" effect="plain">
                  已检索 {{ m.sources.length }} 条
                </el-tag>
              </div>
              <div v-if="!m.streamText" class="typing">
                <span></span><span></span><span></span>
                <em class="typing-text">{{ m.stage || '正在生成回答…' }}</em>
              </div>
              <div v-else class="answer-body">
                <p>{{ m.streamText }}<span class="caret">▍</span></p>
              </div>
            </div>

            <div v-else-if="m.error" class="bubble ai-bubble error-bubble">
              <div class="error-title">请求失败</div>
              <div class="error-msg">{{ m.error }}</div>
            </div>

            <div v-else class="bubble ai-bubble">
              <div class="answer-head">
                <el-tag v-if="m.data.gated" type="warning" size="small" effect="dark">未检索到相关资料</el-tag>
                <el-tag :type="citeTag(m.data).type" size="small">{{ citeTag(m.data).text }}</el-tag>
                <el-tag :type="groundedTag(m.data).type" size="small">{{ groundedTag(m.data).text }}</el-tag>
              </div>

              <div class="answer-body">
                <p v-for="(p, i) in paragraphs(m.data.answer)" :key="i">{{ p }}</p>
              </div>

              <div v-if="m.data.references?.length" class="cites">
                <span class="cites-label">出处</span>
                <button
                  v-for="r in m.data.references"
                  :key="r.index"
                  class="cite"
                  :class="{ active: refsOpen[m.id] }"
                  :title="`《${docTitle(r.documentTitle)}》第 ${r.chunkIndex} 块`"
                  @click="toggleRefs(m.id)"
                >
                  [{{ r.index }}] {{ docTitle(r.documentTitle) }}
                </button>
                <button class="cite-toggle" @click="toggleRefs(m.id)">
                  {{ refsOpen[m.id] ? '收起原文' : '看原文' }}
                </button>
              </div>

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

              <div v-if="m.data.invalidCitations?.length" class="warn warn-danger">
                模型写出了不存在的引用编号：{{ m.data.invalidCitations.join('、') }}（资料只有 {{ m.data.retrievedCount }} 条）
              </div>
              <div v-if="m.data.missingFacts?.length" class="warn warn-warn">
                以下事实在被引用原文中找不到，可能是模型自行补充：{{ m.data.missingFacts.join('、') }}
              </div>

              <div class="answer-foot">
                <span class="foot-meta">{{ m.data.retrievedCount }} 块 · 最高相关度 {{ scoreText(m.data.topScore) }} · {{ m.data.elapsedMs }} ms<template v-if="m.ttftMs"> · 首字 {{ m.ttftMs }} ms</template></span>
                <span class="foot-actions">
                  <button class="foot-btn" @click="copyAnswer(m.data.answer)">复制</button>
                  <button class="foot-btn" @click="toggleRefs(m.id)">溯源</button>
                </span>
              </div>
            </div>
          </template>
        </div>
      </div>

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
    </section>
  </div>
</template>

<style scoped>
.chat-wrap {
  display: flex;
  height: calc(100vh - 92px);
  background: #f7f8fa;
  border-radius: 8px;
  overflow: hidden;
}

/* ---------- 左侧会话栏 ---------- */
.sessions {
  width: 236px;
  flex: none;
  background: #fff;
  border-right: 1px solid #ebeef5;
  display: flex;
  flex-direction: column;
}
.sessions-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 12px 10px;
  border-bottom: 1px solid #f0f2f5;
}
.sessions-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}
.sessions-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}
.session-item {
  position: relative;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 4px;
}
.session-item:hover {
  background: #f5f7fa;
}
.session-item.active {
  background: #eaf2ff;
}
.session-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-meta {
  margin-top: 3px;
  font-size: 11px;
  color: #a8abb2;
}
.session-ops {
  display: none;
  position: absolute;
  right: 6px;
  top: 6px;
  gap: 4px;
}
.session-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-item:hover .session-ops {
  display: flex;
}
.op {
  border: none;
  background: #fff;
  color: #909399;
  font-size: 11px;
  padding: 1px 5px;
  border-radius: 4px;
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}
.op:hover {
  color: #1f6feb;
}
.op.danger:hover {
  color: #f56c6c;
}
.sessions-empty {
  padding: 16px 8px;
  font-size: 12px;
  color: #c0c4cc;
  text-align: center;
}
.sessions-foot {
  padding: 8px 12px;
  border-top: 1px solid #f0f2f5;
  font-size: 11px;
  color: #c0c4cc;
}

/* ---------- 右侧主区 ---------- */
.chat-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

/* 顶部 */
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

/* 消息流 */
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
.caret {
  color: #409eff;
  animation: blink 1s steps(1) infinite;
}

@keyframes blink {
  50% {
    opacity: 0;
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

/* 空态 */
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

/* 输入区 */
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
