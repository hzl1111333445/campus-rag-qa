<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api/http'

/**
 * 检索调试页（管理员）。
 *
 * 为什么要有这一页？
 * 因为"回答错了"这句话没有信息量 —— 可能是召回没捞到、可能是捞到了但排在后面、
 * 也可能是捞到了排对了但模型答歪了。这一页就是把检索的每一步摊开给你看：
 *
 *   向量召回 → BM25 召回 → RRF 融合 → 精排 → 展开邻居
 *
 * 后端对应接口（都是只检索、不生成，秒级返回）：
 *   GET /api/search/compare?q=&topK=   一次 embedding，五路结果一起回（省时省钱）
 *   GET /api/search?q=&mode=best      线上链路，多返回 topScore / threshold / gated
 *   GET /api/search/weights?q=        本次融合权重与 BM25 信号
 *   GET /api/search/rewrite?q=        改写后的问题（改写关了则为空）
 *   GET /api/search/stats             BM25 索引概况
 *
 * 排版逻辑：左边"怎么理解这个问题"，中间"五路各捞到什么"，右边"线上会不会拒答"。
 */
const route = useRoute()

const examples = [
  '旷课多少学时会被处分？',
  '借的书忘还了怎么算',
  '助学贷款毕业之后要还吗',
  '宿舍能不能用电饭锅'
]

const input = ref('')
const topK = ref(5)
const loading = ref(false)
const data = ref(null)
const stats = ref(null)
const openText = ref({})

/** 默认展开的内容：只在点"看正文"时才显示，避免一屏全是字 */
function toggleText(id) {
  openText.value = { ...openText.value, [id]: !openText.value[id] }
}

const modes = computed(() => data.value?.modes || [])
const best = computed(() => data.value?.best || null)
const gateText = computed(() => {
  if (!best.value) return ''
  if (!best.value.reranked) return '精排未启用，分数为 RRF 融合分，闸门判断仅供参照'
  return best.value.gated ? '分数低于阈值，线上会直接拒答、不调用大模型' : '分数高于阈值，线上会调用大模型作答'
})

function scoreText(v) {
  if (v === null || v === undefined) return '-'
  const n = Number(v)
  return n >= 1 ? n.toFixed(3) : n.toFixed(4)
}

function docTitle(t) {
  return (t || '').replace(/^\s*\d+[-_—\s]+/, '')
}

/** 每路结果的"块 id 集合" */
function idSet(hits) {
  return new Set((hits || []).map((h) => h.chunkId))
}

/**
 * 这一路里"与线上最终结果重合"的几条。
 * 解读：重合多 = 这一路对最终结果贡献大；独有多 = 它捞到的线上没采用（可能被融合/精排刷掉）。
 */
function sharedOf(modeHits) {
  const bestIds = idSet(best.value?.hits)
  const out = {}
  for (const h of modeHits || []) out[h.chunkId] = bestIds.has(h.chunkId)
  return out
}

function sharedCount(modeHits) {
  const bestIds = idSet(best.value?.hits)
  return (modeHits || []).filter((h) => bestIds.has(h.chunkId)).length
}

/** 线上结果里、这一路没捞到的条数 —— 就是这一路"漏掉"的 */
function missCount(modeHits) {
  const mine = idSet(modeHits)
  return (best.value?.hits || []).filter((h) => !mine.has(h.chunkId)).length
}

async function run(q) {
  const question = (q ?? input.value ?? '').trim()
  if (!question) {
    ElMessage.warning('先输入一个问题')
    return
  }
  input.value = question
  loading.value = true
  openText.value = {}
  try {
    // compare / best / weights / rewrite 四件事互不依赖，并发发出去
    const [cmp, bestResp, weightResp, rewriteResp] = await Promise.all([
      http.get('/search/compare', { params: { q: question, topK: topK.value } }),
      http.get('/search', { params: { q: question, topK: topK.value, mode: 'best' } }),
      http.get('/search/weights', { params: { q: question } }),
      http.get('/search/rewrite', { params: { q: question } })
    ])
    data.value = { ...cmp, best: bestResp, weights: weightResp, rewrite: rewriteResp }
  } catch (e) {
    /* 拦截器已弹提示 */
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  try {
    stats.value = await http.get('/search/stats')
  } catch (e) {
    stats.value = null
  }
}

onMounted(async () => {
  loadStats()
  const q = route.query.q
  if (typeof q === 'string' && q.trim()) await run(q)
})
</script>

<template>
  <div class="debug">
    <div class="head">
      <div>
        <h2 class="head-title">检索调试</h2>
        <p class="head-sub">
          只检索不生成。用来判断"答错"是检索没捞到、排序不对，还是模型答歪了。
        </p>
      </div>
      <div class="head-right" v-if="stats">
        <el-tag type="info" effect="plain">
          BM25 索引：{{ stats.size ?? '-' }} 块 · 指纹 {{ stats.fingerprint ?? '-' }} · 默认模式 {{ stats.defaultMode }}
        </el-tag>
      </div>
    </div>

    <!-- 查询区 -->
    <div class="query-card">
      <el-input
        v-model="input"
        class="query-input"
        placeholder="输入一个问题，例如：借的书忘还了怎么算"
        size="large"
        clearable
        @keyup.enter="run()"
      />
      <el-select v-model="topK" class="topk" size="large">
        <el-option :value="3" label="每路 3 条" />
        <el-option :value="5" label="每路 5 条" />
        <el-option :value="10" label="每路 10 条" />
      </el-select>
      <el-button type="primary" size="large" :loading="loading" @click="run()">检索</el-button>
    </div>

    <div class="examples" v-if="!data">
      <span class="examples-label">试试：</span>
      <el-button
        v-for="e in examples"
        :key="e"
        size="small"
        round
        plain
        @click="run(e)"
      >{{ e }}</el-button>
    </div>

    <div v-if="loading" class="loading-card">
      <el-skeleton :rows="4" animated />
    </div>

    <template v-if="data && !loading">
      <!-- ① 查询理解 -->
      <div class="card">
        <div class="card-title">① 查询理解</div>
        <div class="kv-grid">
          <div class="kv">
            <span class="k">原句</span>
            <span class="v">{{ data.question }}</span>
          </div>
          <div class="kv">
            <span class="k">改写句</span>
            <span class="v">
              <template v-if="!data.rewrite.enabled">
                <el-tag size="small" type="info" effect="plain">改写器已关闭</el-tag>
              </template>
              <template v-else-if="data.rewrite.changed">
                {{ data.rewrite.rewritten }}
                <el-tag size="small" type="success" effect="plain">触发改写</el-tag>
              </template>
              <template v-else>
                <span class="muted">与原句相同（未触发）</span>
              </template>
            </span>
          </div>
          <div class="kv">
            <span class="k">BM25 信号</span>
            <span class="v">{{ data.weights.signal }}<span class="muted">（{{ data.weights.reason }}）</span></span>
          </div>
          <div class="kv">
            <span class="k">融合权重</span>
            <span class="v">向量 {{ data.weights.wVector }} · BM25 {{ data.weights.wBm25 }}</span>
          </div>
        </div>
      </div>

      <!-- ② 五路召回 -->
      <div class="card">
        <div class="card-title">
          ② 五路召回
          <span class="card-hint">同一问题、同一次 embedding，五种方式各取 Top{{ data.topK }}；绿色=这条也在线上最终结果里（重合），灰色=这一路捞到但线上没采用；"漏"=线上用了而这路没捞到</span>
        </div>
        <div class="modes">
          <div v-for="m in modes" :key="m.mode" class="mode-col">
            <div class="mode-head">
              <span class="mode-name">{{ m.modeLabel }}</span>
              <span class="mode-count">重合 {{ sharedCount(m.hits) }} · 漏 {{ missCount(m.hits) }}</span>
            </div>
            <div v-for="(h, i) in m.hits" :key="h.chunkId" class="hit" :class="{ shared: sharedOf(m.hits)[h.chunkId] }">
              <div class="hit-top">
                <span class="hit-rank">#{{ i + 1 }}</span>
                <span class="hit-score">{{ scoreText(h.score) }}</span>
              </div>
              <div class="hit-doc">《{{ docTitle(h.documentTitle) }}》第 {{ h.chunkIndex }} 块</div>
              <div class="hit-text" v-if="openText[m.mode + h.chunkId]">{{ h.content }}</div>
              <button class="hit-more" @click="toggleText(m.mode + h.chunkId)">
                {{ openText[m.mode + h.chunkId] ? '收起' : '看正文' }}
              </button>
            </div>
            <div v-if="!m.hits.length" class="hit empty">无结果</div>
          </div>
        </div>
      </div>

      <!-- ③ 线上链路 -->
      <div class="card">
        <div class="card-title">③ 线上链路与拒答闸门（混合 → 精排 → 展开邻居）</div>
        <div class="best-row">
          <div class="metric">
            <div class="metric-label">最高分</div>
            <div class="metric-value">{{ scoreText(best?.topScore) }}</div>
          </div>
          <div class="metric">
            <div class="metric-label">阈值</div>
            <div class="metric-value">{{ scoreText(best?.threshold) }}</div>
          </div>
          <div class="metric">
            <div class="metric-label">判定</div>
            <div class="metric-value">
              <el-tag :type="best?.gated ? 'danger' : 'success'" effect="plain">
                {{ best?.gated ? '建议拒答' : '正常作答' }}
              </el-tag>
            </div>
          </div>
          <div class="metric">
            <div class="metric-label">精排</div>
            <div class="metric-value">{{ best?.reranked ? '已启用' : '未启用' }}</div>
          </div>
          <div class="metric">
            <div class="metric-label">耗时</div>
            <div class="metric-value">{{ best?.elapsedMs }} ms</div>
          </div>
        </div>
        <p class="gate-text">{{ gateText }}</p>
        <div class="best-hits">
          <div v-for="(h, i) in best?.hits || []" :key="h.chunkId" class="best-hit">
            <span class="hit-rank">#{{ i + 1 }}</span>
            <span class="hit-score">{{ scoreText(h.score) }}</span>
            <span class="best-doc">《{{ docTitle(h.documentTitle) }}》第 {{ h.chunkIndex }} 块</span>
          </div>
        </div>
      </div>

      <!-- ④ 其他计数 -->
      <div class="card">
        <div class="card-title">④ 索引概况</div>
        <div class="kv-grid" v-if="stats">
          <div class="kv"><span class="k">BM25 块数</span><span class="v">{{ stats.size }}</span></div>
          <div class="kv"><span class="k">索引指纹</span><span class="v">{{ stats.fingerprint ?? '-' }}</span></div>
          <div class="kv"><span class="k">默认模式</span><span class="v">{{ stats.defaultMode }}</span></div>
          <div class="kv"><span class="k">自适应权重</span><span class="v">{{ stats.adaptive ? '开' : '关' }}</span></div>
          <div class="kv"><span class="k">混合向量权重</span><span class="v">{{ stats.hybridWVector }}</span></div>
          <div class="kv"><span class="k">本次耗时</span><span class="v">{{ data.elapsedMs }} ms</span></div>
        </div>
        <p class="muted small" v-else>索引统计未取到（/api/search/stats）</p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.debug {
  padding: 18px 22px 40px;
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.head-title {
  margin: 0 0 4px;
  font-size: 18px;
}

.head-sub {
  margin: 0;
  font-size: 13px;
  color: #909399;
}

.query-card {
  display: flex;
  gap: 10px;
  align-items: center;
  background: #fff;
  padding: 14px;
  border-radius: 8px;
  border: 1px solid #ebeef5;
}

.query-input {
  flex: 1;
}

.topk {
  width: 130px;
}

.examples {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.examples-label {
  font-size: 13px;
  color: #909399;
}

.loading-card {
  margin-top: 14px;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 18px;
}

.card {
  margin-top: 14px;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 16px;
}

.card-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 12px;
}

.card-hint {
  margin-left: 10px;
  font-size: 12px;
  font-weight: 400;
  color: #a8abb2;
}

.kv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 10px 20px;
}

.kv {
  display: flex;
  gap: 10px;
  font-size: 13px;
  align-items: baseline;
}

.k {
  color: #909399;
  flex: 0 0 72px;
}

.v {
  color: #303133;
  word-break: break-word;
}

.muted {
  color: #a8abb2;
}

.small {
  font-size: 12px;
}

.modes {
  display: grid;
  /* 五路并排才好评比；窄屏才让它换行 */
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
}

@media (max-width: 1360px) {
  .modes {
    grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  }
}

.mode-col {
  border: 1px solid #f0f2f5;
  border-radius: 8px;
  padding: 10px;
  background: #fcfcfd;
  min-width: 0;
}

.mode-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 8px;
}

.mode-name {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.mode-count {
  font-size: 12px;
  color: #a8abb2;
}

.hit {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 8px;
  margin-bottom: 8px;
  background: #fff;
}

.hit.shared {
  border-color: #b3e19d;
  background: #f0f9eb;
}

.hit.empty {
  color: #c0c4cc;
  font-size: 12px;
  text-align: center;
}

.hit-top {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
}

.hit-rank {
  color: #409eff;
  font-weight: 600;
}

.hit-score {
  color: #909399;
  font-variant-numeric: tabular-nums;
}

.hit-doc {
  font-size: 12px;
  color: #606266;
  margin: 3px 0;
  word-break: break-word;
}

.hit-text {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  max-height: 140px;
  overflow: auto;
  background: #f5f7fa;
  padding: 6px;
  border-radius: 4px;
  margin-top: 4px;
  overflow-wrap: anywhere;
}

.hit-more {
  margin-top: 4px;
  border: none;
  background: none;
  color: #409eff;
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}

.best-row {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.metric-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 2px;
}

.metric-value {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  font-variant-numeric: tabular-nums;
}

.gate-text {
  margin: 4px 0 10px;
  font-size: 13px;
  color: #606266;
}

.best-hits {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.best-hit {
  display: flex;
  gap: 12px;
  font-size: 13px;
  align-items: baseline;
}

.best-doc {
  color: #606266;
  word-break: break-word;
}
</style>
