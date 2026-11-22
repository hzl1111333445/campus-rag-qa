<template>
  <div class="eval-page">
    <el-card shadow="never" class="hero">
      <div class="hero-row">
        <div>
          <h2>评测看板</h2>
          <p class="sub">用固定题集把 13 种检索配置跑一遍，比 Recall@3 / MRR。数字高不代表答得好，但检索是回答的上限。</p>
        </div>
        <div class="hero-actions">
          <span class="topk-label">每次取前</span>
          <el-select v-model="topK" size="small" style="width: 84px" :disabled="running">
            <el-option v-for="k in [1, 2, 3, 5, 8, 10]" :key="k" :label="k" :value="k" />
          </el-select>
          <span class="topk-label">条</span>
          <el-button type="primary" :loading="running" :disabled="running || !info" @click="start">
            {{ running ? '正在跑…' : '跑一轮评测' }}
          </el-button>
        </div>
      </div>

      <div v-if="info" class="testset">
        <div class="ts-item">
          <span class="ts-k">题集</span>
          <span class="ts-v">{{ info.questionCount }} 题</span>
        </div>
        <div class="ts-item">
          <span class="ts-k">题型</span>
          <span class="ts-v">
            <el-tag v-for="(n, t) in info.typeCount" :key="t" size="small" class="type-tag" effect="plain">
              {{ t }} {{ n }}
            </el-tag>
          </span>
        </div>
        <div class="ts-item">
          <span class="ts-k">配置</span>
          <span class="ts-v">{{ info.variants.length }} 种</span>
        </div>
        <div class="ts-item">
          <span class="ts-k">来源</span>
          <span class="ts-v mono">{{ info.path }}</span>
        </div>
      </div>

      <div v-if="running" class="progress-box">
        <el-progress :percentage="percent" :stroke-width="14" striped striped-flow />
        <div class="progress-text">
          已完成 <b>{{ done }}</b> / {{ total }} 题 · 已用 <b>{{ (elapsedMs / 1000).toFixed(0) }}</b> 秒
          <span class="hint">（一轮 13 种配置要几分钟，页面可以关掉，回来还能接着看）</span>
        </div>
      </div>

      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="mt8" />
    </el-card>

    <el-card v-if="report" shadow="never" class="mt12">
      <div class="report-head">
        <div class="report-title">
          结果 · 前 {{ report.topK }} 条 · {{ report.questionCount }} 题参与
          <span class="muted">（跳过 {{ report.skippedNoAnswer }} 道"无答案"题：它们考的是拒答，属于生成层）</span>
        </div>
        <div class="report-meta">
          <span>语料 {{ report.chunkTotal }} 块</span>
          <span>耗时 {{ (report.elapsedMs / 1000).toFixed(1) }} 秒</span>
          <span v-if="finishedAt">跑于 {{ finishedAt }}</span>
        </div>
      </div>

      <el-table :data="sortedVariants" size="small" border class="mt8" :row-class-name="rowClass">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand">
              <div class="expand-col">
                <div class="expand-title">按题型</div>
                <table class="mini">
                  <thead>
                    <tr><th>题型</th><th>题数</th><th>命中</th><th>Recall</th><th>MRR</th></tr>
                  </thead>
                  <tbody>
                    <tr v-for="(s, t) in row.byType" :key="t">
                      <td>{{ t }}</td>
                      <td>{{ s.questionCount }}</td>
                      <td>{{ s.hits }}</td>
                      <td>{{ pct(s.recall) }}</td>
                      <td>{{ s.mrr.toFixed(4) }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div class="expand-col">
                <div class="expand-title">
                  按划分 <span class="muted">（test 才是能写进论文的数字）</span>
                </div>
                <table class="mini">
                  <thead>
                    <tr><th>划分</th><th>题数</th><th>命中</th><th>Recall</th><th>MRR</th></tr>
                  </thead>
                  <tbody>
                    <tr v-for="(s, t) in row.bySplit" :key="t">
                      <td>{{ t }}</td>
                      <td>{{ s.questionCount }}</td>
                      <td>{{ s.hits }}</td>
                      <td>{{ pct(s.recall) }}</td>
                      <td>{{ s.mrr.toFixed(4) }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div class="expand-col wide">
                <div class="expand-title">
                  没命中的题（{{ row.misses.length }} 道，rank=0 表示前 {{ report.topK }} 条里根本没有）
                </div>
                <div v-if="!row.misses.length" class="muted">全部命中</div>
                <div v-for="m in row.misses.slice(0, 12)" :key="m.questionId" class="miss">
                  <span class="miss-id">#{{ m.questionId }}</span>
                  <span class="miss-rank" :class="{ zero: m.rank === 0 }">{{ m.rank === 0 ? '未召回' : '第 ' + m.rank + ' 名' }}</span>
                  <span class="miss-q">{{ m.question }}</span>
                </div>
                <div v-if="row.misses.length > 12" class="muted">…还有 {{ row.misses.length - 12 }} 道</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="label" label="检索配置" min-width="230">
          <template #default="{ row }">
            <span>{{ row.label }}</span>
            <el-tag v-if="row.code === 'hybrid_rerank_expand'" size="small" type="success" class="ml6">线上</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="命中" width="100" align="center">
          <template #default="{ row }">{{ row.hits }} / {{ row.questionCount }}</template>
        </el-table-column>
        <el-table-column label="Recall" width="110" align="center">
          <template #default="{ row }">
            <b :class="{ best: row.recall === bestRecall }">{{ pct(row.recall) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="MRR" width="100" align="center">
          <template #default="{ row }">
            <b :class="{ best: row.mrr === bestMrr }">{{ row.mrr.toFixed(4) }}</b>
          </template>
        </el-table-column>
        <el-table-column label="test 划分" width="110" align="center">
          <template #default="{ row }">
            <span :class="{ muted: !row.bySplit?.test }">{{ row.bySplit?.test ? pct(row.bySplit.test.recall) : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="差最多的题型" min-width="150">
          <template #default="{ row }">
            <span class="muted">{{ worstType(row) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="tldr">
        <b>怎么读这张表：</b>
        ① 看 Recall 而不是只看命中数（都是 118 题，命中数就是 Recall 的另一种写法）；
        ② <code>hybrid</code> 之后每一步都是在前一步基础上加东西，掉分说明该改动有害——
        我们就是这么发现"自适应融合权重"和"两段式生成"不划算的；
        ③ 最右一列挑的是这个配置最弱的题型，用来找下一个优化点。
      </div>
    </el-card>

    <el-card v-if="history.length" shadow="never" class="mt12">
      <div class="expand-title">本机历史（只存摘要，存在浏览器里，换机器看不到）</div>
      <div v-for="h in history" :key="h.jobId" class="hist-row">
        <span class="mono">{{ h.at }}</span>
        <span>topK={{ h.topK }}</span>
        <span>最好 {{ h.bestLabel }}：Recall {{ pct(h.bestRecall) }} / MRR {{ h.bestMrr.toFixed(4) }}</span>
        <span class="muted">耗时 {{ (h.elapsedMs / 1000).toFixed(1) }} 秒</span>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import http from '../api/http'

const info = ref(null)
const report = ref(null)
const running = ref(false)
const done = ref(0)
const total = ref(0)
const elapsedMs = ref(0)
const error = ref('')
const finishedAt = ref('')
const topK = ref(3)
const history = ref([])

let jobId = null
let pollTimer = null

const HKEY = 'campus_eval_history'

const percent = computed(() => {
  if (!total.value) return 0
  return Math.min(100, Math.round((done.value / total.value) * 100))
})

/** 按 Recall 从高到低排（同分看 MRR），一眼看到最好的是哪个 */
const sortedVariants = computed(() => {
  const list = [...(report.value?.variants || [])]
  return list.sort((a, b) => b.recall - a.recall || b.mrr - a.mrr)
})

const bestRecall = computed(() => Math.max(...(report.value?.variants || []).map((v) => v.recall), -1))
const bestMrr = computed(() => Math.max(...(report.value?.variants || []).map((v) => v.mrr), -1))

function pct(v) {
  return (v * 100).toFixed(2) + '%'
}

/** 这个配置最弱的题型（题数 < 3 的不算，样本太小没意义） */
function worstType(row) {
  const entries = Object.entries(row.byType || {}).filter(([, s]) => s.questionCount >= 3)
  if (!entries.length) return '—'
  entries.sort((a, b) => a[1].recall - b[1].recall)
  const [type, s] = entries[0]
  return `${type} ${pct(s.recall)}`
}

function rowClass({ row }) {
  return row.code === 'hybrid_rerank_expand' ? 'online-row' : ''
}

async function loadInfo() {
  const resp = await http.get('/eval/testset')
  info.value = resp.data
}

async function start() {
  error.value = ''
  report.value = null
  finishedAt.value = ''
  const resp = await http.post(`/eval/jobs?topK=${topK.value}`)
  jobId = resp.data.jobId
  total.value = resp.data.total
  done.value = 0
  elapsedMs.value = 0
  running.value = true
  localStorage.setItem('campus_eval_job', jobId)
  poll()
}

function poll() {
  clearTimeout(pollTimer)
  pollTimer = setTimeout(async () => {
    try {
      const resp = await http.get(`/eval/jobs/${jobId}`)
      const s = resp.data
      done.value = s.done
      total.value = s.total
      elapsedMs.value = s.elapsedMs
      if (s.status === 'RUNNING') {
        poll()
        return
      }
      running.value = false
      localStorage.removeItem('campus_eval_job')
      if (s.status === 'FAILED') {
        error.value = '评测失败：' + s.error
        return
      }
      report.value = s.report
      finishedAt.value = new Date().toLocaleTimeString()
      saveHistory(s)
    } catch (e) {
      running.value = false
      error.value = '查询进度失败：' + (e.message || e)
    }
  }, 2000)
}

function saveHistory(snapshot) {
  const best = [...snapshot.report.variants].sort((a, b) => b.recall - a.recall || b.mrr - a.mrr)[0]
  const entry = {
    jobId: snapshot.id,
    at: new Date().toLocaleString(),
    topK: snapshot.topK,
    bestLabel: best.label,
    bestRecall: best.recall,
    bestMrr: best.mrr,
    elapsedMs: snapshot.report.elapsedMs,
  }
  history.value = [entry, ...readHistory()].slice(0, 5)
  localStorage.setItem(HKEY, JSON.stringify(history.value))
}

function readHistory() {
  try {
    return JSON.parse(localStorage.getItem(HKEY) || '[]')
  } catch (e) {
    return []
  }
}

/** 刷新页面后如果还有在跑的任务，自动接上进度（不用重新点） */
async function resumeIfRunning() {
  const saved = localStorage.getItem('campus_eval_job')
  if (!saved) return
  try {
    const resp = await http.get(`/eval/jobs/${saved}`)
    const s = resp.data
    if (s.status === 'RUNNING') {
      jobId = saved
      running.value = true
      total.value = s.total
      done.value = s.done
      elapsedMs.value = s.elapsedMs
      poll()
    } else {
      localStorage.removeItem('campus_eval_job')
      if (s.status === 'FINISHED') {
        report.value = s.report
        finishedAt.value = new Date().toLocaleTimeString()
      }
    }
  } catch (e) {
    localStorage.removeItem('campus_eval_job')
  }
}

onMounted(async () => {
  history.value = readHistory()
  try {
    await loadInfo()
  } catch (e) {
    error.value = '题集信息加载失败：' + (e.message || e)
  }
  await resumeIfRunning()
})

onUnmounted(() => clearTimeout(pollTimer))
</script>

<style scoped>
.eval-page { padding: 4px; }
.hero-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.hero h2 { margin: 0 0 6px; font-size: 18px; }
.sub { margin: 0; color: #6b7280; font-size: 13px; line-height: 1.7; max-width: 720px; }
.hero-actions { display: flex; align-items: center; gap: 6px; flex-shrink: 0; }
.topk-label { color: #6b7280; font-size: 13px; }
.testset { display: flex; flex-wrap: wrap; gap: 10px 28px; margin-top: 16px; padding-top: 14px; border-top: 1px dashed #ebeef5; }
.ts-item { display: flex; align-items: baseline; gap: 8px; }
.ts-k { color: #909399; font-size: 12px; }
.ts-v { font-size: 13px; }
.type-tag { margin-right: 6px; }
.mono { font-family: Consolas, monospace; font-size: 12px; color: #606266; }
.progress-box { margin-top: 18px; }
.progress-text { margin-top: 8px; font-size: 13px; color: #4b5563; }
.hint { color: #9ca3af; font-size: 12px; }
.mt8 { margin-top: 8px; }
.mt12 { margin-top: 12px; }
.ml6 { margin-left: 6px; }
.report-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.report-title { font-size: 15px; font-weight: 600; }
.report-meta { display: flex; gap: 16px; color: #909399; font-size: 12px; }
.muted { color: #9ca3af; }
.best { color: #16a34a; }
.expand { display: flex; gap: 26px; flex-wrap: wrap; padding: 6px 10px 10px; }
.expand-col.wide { flex: 1 1 380px; min-width: 0; }
.expand-title { font-size: 13px; font-weight: 600; margin-bottom: 8px; }
table.mini { border-collapse: collapse; font-size: 12px; min-width: 320px; }
table.mini th, table.mini td { border: 1px solid #ebeef5; padding: 4px 9px; text-align: left; }
table.mini th { background: #fafafa; color: #606266; font-weight: 500; }
.miss { display: flex; gap: 8px; align-items: baseline; font-size: 12px; padding: 3px 0; border-bottom: 1px dashed #f2f3f5; }
.miss-id { color: #909399; font-family: Consolas, monospace; }
.miss-rank { color: #d97706; flex-shrink: 0; }
.miss-rank.zero { color: #dc2626; }
.miss-q { color: #4b5563; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tldr { margin-top: 14px; padding: 12px 14px; background: #f8fafc; border-radius: 6px; font-size: 12.5px; line-height: 1.9; color: #4b5563; }
.tldr code { background: #eef2ff; padding: 1px 5px; border-radius: 3px; }
.hist-row { display: flex; gap: 18px; font-size: 12.5px; padding: 6px 0; border-bottom: 1px dashed #f2f3f5; flex-wrap: wrap; }
:deep(.online-row) { background: #f0f9eb; }
</style>
