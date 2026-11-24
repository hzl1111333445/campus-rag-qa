<template>
  <div class="fb-page">
    <div class="page-head">
      <div>
        <h3>用户反馈</h3>
        <p class="sub">
          按"解决程度"三档统计，不是赞/踩二元：<b>部分解决</b>是"有用但不全"，它既不算好评也不算差评。
          缺陷分布告诉我们下一轮该改检索、提示词还是补语料。
        </p>
      </div>
      <div class="head-actions">
        <el-select v-model="days" style="width: 118px" @change="loadStats">
          <el-option :value="7" label="最近 7 天" />
          <el-option :value="30" label="最近 30 天" />
          <el-option :value="90" label="最近 90 天" />
        </el-select>
        <el-button :loading="loading" @click="loadAll">刷新</el-button>
      </div>
    </div>

    <!-- 四个数 + 三个比率：只报一个"满意度"会把部分解决藏起来 -->
    <div class="stat-row">
      <div class="stat-card">
        <div class="k">总评价</div>
        <div class="v">{{ stats.total || 0 }}</div>
        <div class="s">被评价的回答数</div>
      </div>
      <div class="stat-card up">
        <div class="k">完全解决</div>
        <div class="v">{{ stats.solved || 0 }}</div>
        <div class="s">解决率 {{ pct(stats.solvedRate) }}</div>
      </div>
      <div class="stat-card mid">
        <div class="k">部分解决</div>
        <div class="v">{{ stats.partial || 0 }}</div>
        <div class="s">有帮助率 {{ pct(stats.helpfulRate) }}</div>
      </div>
      <div class="stat-card down">
        <div class="k">未解决</div>
        <div class="v">{{ stats.unsolved || 0 }}</div>
        <div class="s">未解决率 {{ pct(stats.unsolvedRate) }}</div>
      </div>
    </div>

    <div class="panels">
      <!-- 缺陷分布：以当前最大值归一的横条，比饼图好读 -->
      <div class="panel">
        <div class="panel-head">缺陷分布（部分 / 未解决）</div>
        <div v-if="!stats.byProblem || !stats.byProblem.length" class="empty">这段时间还没有登记缺陷</div>
        <div v-for="p in stats.byProblem" :key="p.problem" class="bar-row">
          <span class="bar-name">{{ p.problem }}</span>
          <div class="bar"><div class="bar-in" :style="{ width: barWidth(p.n) }" /></div>
          <span class="bar-count">{{ p.n }}</span>
        </div>
      </div>

      <!-- 趋势：三档共用一把尺子，堆叠看起来才有意义 -->
      <div class="panel">
        <div class="panel-head">按天趋势</div>
        <div v-if="!stats.byDay || !stats.byDay.length" class="empty">这段时间还没有反馈</div>
        <div class="trend">
          <div
            v-for="d in stats.byDay"
            :key="d.day"
            class="trend-col"
            :title="`${d.day}　解决 ${d.solved} / 部分 ${d.partial} / 未解决 ${d.unsolved}`"
          >
            <div class="bars">
              <div class="b up" :style="{ height: h(d.solved) }" />
              <div class="b mid" :style="{ height: h(d.partial) }" />
              <div class="b down" :style="{ height: h(d.unsolved) }" />
            </div>
            <div class="day">{{ shortDay(d.day) }}</div>
          </div>
        </div>
        <div class="legend">
          <span><i class="dot up" />完全解决</span>
          <span><i class="dot mid" />部分解决</span>
          <span><i class="dot down" />未解决</span>
        </div>
      </div>
    </div>

    <div class="panel table-panel">
      <div class="panel-head">
        <span>明细</span>
        <el-radio-group v-model="levelFilter" size="small" @change="loadPage(1)">
          <el-radio-button :value="-1">全部</el-radio-button>
          <el-radio-button :value="2">完全解决</el-radio-button>
          <el-radio-button :value="1">部分解决</el-radio-button>
          <el-radio-button :value="0">未解决</el-radio-button>
        </el-radio-group>
      </div>

      <el-table :data="records" size="small" empty-text="暂无反馈">
        <el-table-column label="时间" width="146">
          <template #default="{ row }">{{ timeText(row.create_time) }}</template>
        </el-table-column>
        <el-table-column label="问题" min-width="210" show-overflow-tooltip>
          <template #default="{ row }">{{ row.question || '—' }}</template>
        </el-table-column>
        <el-table-column label="回答摘要" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">{{ row.answer_snippet || '' }}</template>
        </el-table-column>
        <el-table-column label="程度" width="96">
          <template #default="{ row }">
            <el-tag :type="levelTag(row.level)" size="small" effect="plain">{{ levelText(row.level) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="缺陷" width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.problems || '—' }}</template>
        </el-table-column>
        <el-table-column label="备注" width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.comment || '—' }}</template>
        </el-table-column>
        <el-table-column label="用户" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.display_name || row.username || '匿名' }}</template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          layout="total, prev, pager, next"
          :total="total"
          :page-size="size"
          :current-page="page"
          background
          @current-change="loadPage"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import http from '../api/http'

const days = ref(7)
const loading = ref(false)

const stats = ref({})
const records = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
/** -1 = 全部；2/1/0 = 对应解决程度（后端 level 为空表示不过滤，所以 -1 要转成 undefined） */
const levelFilter = ref(-1)

const LEVEL_TEXT = { 2: '完全解决', 1: '部分解决', 0: '未解决' }
const LEVEL_TAG = { 2: 'success', 1: 'warning', 0: 'danger' }

function levelText(level) {
  return LEVEL_TEXT[level] ?? '—'
}

function levelTag(level) {
  return LEVEL_TAG[level] ?? 'info'
}

/** 后端给的是 0~100 的一位小数（42.9），null 表示分母为 0 */
function pct(v) {
  if (v === null || v === undefined) return '—'
  return `${v}%`
}

/** 横条宽度：以当前最大值为满格，避免小样本下全是细线看不清 */
function barWidth(n) {
  const max = Math.max(...(stats.value.byProblem || []).map((p) => p.n), 1)
  return `${Math.round((n / max) * 100)}%`
}

/** 柱子高度：三档共用同一比例尺，否则"哪一档更多"看不出来 */
function h(n) {
  const all = stats.value.byDay || []
  const max = Math.max(
    ...all.map((d) => Math.max(d.solved || 0, d.partial || 0, d.unsolved || 0)),
    1
  )
  return `${n > 0 ? Math.max(Math.round((n / max) * 54), 3) : 0}px`
}

function shortDay(day) {
  const s = String(day || '')
  return s.length >= 10 ? s.slice(5) : s
}

function timeText(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 16)
}

async function loadStats() {
  try {
    stats.value = await http.get('/feedback/stats', { params: { days: days.value } })
  } catch {
    /* 拦截器已提示 */
  }
}

async function loadPage(p = 1) {
  page.value = p
  try {
    const data = await http.get('/feedback/page', {
      params: {
        page: p,
        size: size.value,
        level: levelFilter.value === -1 ? undefined : levelFilter.value
      }
    })
    records.value = data.records || []
    total.value = data.total || 0
  } catch {
    /* 拦截器已提示 */
  }
}

async function loadAll() {
  loading.value = true
  try {
    await Promise.all([loadStats(), loadPage(page.value)])
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.fb-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 4px 2px 20px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.page-head h3 {
  margin: 0 0 4px;
  font-size: 16px;
  color: #303133;
}
.page-head .sub {
  margin: 0;
  max-width: 780px;
  font-size: 12.5px;
  line-height: 1.7;
  color: #909399;
}
.head-actions {
  display: flex;
  gap: 8px;
  flex: none;
}

.stat-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}
.stat-card {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px 14px;
}
.stat-card .k {
  font-size: 12.5px;
  color: #909399;
}
.stat-card .v {
  margin-top: 6px;
  font-size: 22px;
  font-weight: 600;
  color: #303133;
}
.stat-card .s {
  margin-top: 2px;
  font-size: 11.5px;
  color: #a8abb2;
}
.stat-card.up .v {
  color: #529b2e;
}
.stat-card.mid .v {
  color: #b88230;
}
.stat-card.down .v {
  color: #c45656;
}

.panels {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.panel {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px 14px;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 10px;
}
.empty {
  font-size: 12.5px;
  color: #a8abb2;
  padding: 10px 0;
}

.bar-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.bar-name {
  width: 78px;
  flex: none;
  font-size: 12.5px;
  color: #606266;
}
.bar {
  flex: 1;
  height: 10px;
  background: #f2f3f5;
  border-radius: 5px;
  overflow: hidden;
}
.bar-in {
  height: 100%;
  background: linear-gradient(90deg, #f3d19e, #e6a23c);
}
.bar-count {
  width: 26px;
  text-align: right;
  font-size: 12.5px;
  color: #606266;
  font-variant-numeric: tabular-nums;
}

.trend {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  min-height: 76px;
  overflow-x: auto;
}
.trend-col {
  flex: none;
  width: 34px;
  text-align: center;
}
.trend-col .bars {
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 2px;
  height: 56px;
}
.b {
  width: 7px;
  border-radius: 2px 2px 0 0;
}
.b.up {
  background: #95d475;
}
.b.mid {
  background: #f3d19e;
}
.b.down {
  background: #f89898;
}
.trend-col .day {
  margin-top: 4px;
  font-size: 11px;
  color: #a8abb2;
  white-space: nowrap;   /* 只有一天时 "11-24" 会被挤成两行 */
}
.legend {
  display: flex;
  gap: 14px;
  margin-top: 8px;
  font-size: 11.5px;
  color: #909399;
}
.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 2px;
  margin-right: 4px;
}
.dot.up {
  background: #95d475;
}
.dot.mid {
  background: #f3d19e;
}
.dot.down {
  background: #f89898;
}

.table-panel {
  padding-bottom: 6px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  padding: 10px 0 4px;
}
</style>
