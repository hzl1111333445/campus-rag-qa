<script setup>
import { computed, ref } from 'vue'
import http from '../api/http'

/**
 * 智能问答页（第 2 步）。
 *
 * 这一页最重要的设计不是"输入框 + 显示答案"，而是把 RAG 的三个诚实性信号直接摆给用户看：
 *   ① 引用校验 —— 回答里写的 [1][2] 是不是资料里真实存在的编号
 *   ② 接地校验 —— 回答里的数字/期限/处分名能不能在被引用的原文里找到
 *   ③ 是否拒答 —— 检索分太低时系统在检索层就拒答，没花大模型的钱
 * 学生最怕的是"AI 一本正经胡说"，所以出处卡片必须点得开、看得见原文。
 */
const question = ref('')
const topK = ref(3)
const loading = ref(false)
const result = ref(null)
const errorText = ref('')
/** 页面内的历史（后端还没有会话表，所以只存在内存里，刷新即清空） */
const history = ref([])

const canAsk = computed(() => question.value.trim().length > 0 && !loading.value)

async function ask() {
  const q = question.value.trim()
  if (!q) return
  loading.value = true
  errorText.value = ''
  result.value = null
  try {
    const data = await http.post('/qa', { question: q, topK: topK.value })
    result.value = data
    history.value = [{ id: Date.now(), question: q, gated: data.gated, wrong: data.invalidCitations?.length > 0 }, ...history.value].slice(0, 8)
  } catch (e) {
    errorText.value = e.message
  } finally {
    loading.value = false
  }
}

function reuse(item) {
  question.value = item.question
}

/** 回答按换行拆段，保留它的分条结构（模型输出不带 Markdown） */
const answerParagraphs = computed(() => {
  if (!result.value?.answer) return []
  return result.value.answer.split(/\n+/).map((s) => s.trim()).filter(Boolean)
})

/** 接地校验的三态：通过 / 有对不上的事实 / 这段回答里没有可核对的硬事实 */
const groundedState = computed(() => {
  const r = result.value
  if (!r) return null
  if (!r.grounded) return { type: 'danger', text: '接地校验未通过' }
  if (r.factCount === 0) return { type: 'info', text: '无可核对硬事实' }
  return { type: 'success', text: `接地校验通过（核了 ${r.factCount} 条）` }
})

function scoreText(v) {
  if (v === null || v === undefined) return '-'
  return Number(v).toFixed(4)
}
</script>

<template>
  <el-row :gutter="16">
    <!-- 左：提问 + 回答 -->
    <el-col :xs="24" :md="15">
      <el-card shadow="never" class="card">
        <template #header>
          <div class="head">
            <span>提问</span>
            <div class="head-right">
              <span class="label">资料条数</span>
              <el-select v-model="topK" size="small" style="width: 82px">
                <el-option v-for="n in [3, 4, 5, 8]" :key="n" :label="n" :value="n" />
              </el-select>
            </div>
          </div>
        </template>

        <el-input
          v-model="question"
          type="textarea"
          :rows="3"
          maxlength="200"
          show-word-limit
          placeholder="用大白话问，例如：旷课多少学时会被处分？"
          @keydown.ctrl.enter="ask"
        />
        <div class="actions">
          <span class="hint">Ctrl + Enter 提交</span>
          <el-button type="primary" :disabled="!canAsk" :loading="loading" @click="ask">提问</el-button>
        </div>

        <el-alert v-if="errorText" type="error" :closable="false" show-icon :title="errorText" class="mt" />
      </el-card>

      <el-card v-if="result" shadow="never" class="card">
        <template #header>
          <div class="head">
            <span>回答</span>
            <div class="badges">
              <el-tag v-if="result.gated" type="warning" size="small">检索层拒答（未调用大模型）</el-tag>
              <el-tag :type="result.citationOk ? 'success' : 'danger'" size="small">
                {{ result.citationOk ? '引用校验通过' : '引用编号异常' }}
              </el-tag>
              <el-tag v-if="groundedState" :type="groundedState.type" size="small">{{ groundedState.text }}</el-tag>
            </div>
          </div>
        </template>

        <div class="answer">
          <p v-for="(p, i) in answerParagraphs" :key="i">{{ p }}</p>
        </div>

        <div class="meta">
          <span>模型 {{ result.model }}</span>
          <span>检索 {{ result.retrievedCount }} 块</span>
          <span>最高相关度 {{ scoreText(result.topScore) }}</span>
          <span>耗时 {{ result.elapsedMs }} ms</span>
          <span v-if="result.evidenceCount">两段式证据 {{ result.evidenceCount }} 条</span>
        </div>

        <el-alert
          v-if="result.invalidCitations?.length"
          type="error"
          :closable="false"
          show-icon
          class="mt"
          title="模型编造了不存在的引用编号"
          :description="'编造的编号：' + result.invalidCitations.join('、') + '，资料只有 ' + result.retrievedCount + ' 条。'"
        />
        <el-alert
          v-if="result.missingFacts?.length"
          type="warning"
          :closable="false"
          show-icon
          class="mt"
          title="有事实在被引用原文中找不到（可能是加料）"
          :description="result.missingFacts.join('、')"
        />
      </el-card>

      <el-card v-if="!result && !loading" shadow="never" class="card">
        <el-empty description="还没有提问。问一个试试，比如：宿舍楼晚上几点关门？" />
      </el-card>
    </el-col>

    <!-- 右：出处 + 历史 -->
    <el-col :xs="24" :md="9">
      <el-card shadow="never" class="card">
        <template #header>出处（{{ result?.references?.length || 0 }} 条）</template>
        <template v-if="result?.references?.length">
          <el-collapse>
            <el-collapse-item v-for="r in result.references" :key="r.index" :name="r.index">
              <template #title>
                <span class="ref-title">
                  <el-tag size="small" type="primary" effect="plain">[{{ r.index }}]</el-tag>
                  《{{ r.documentTitle }}》第 {{ r.chunkIndex }} 块
                  <span class="ref-score">{{ scoreText(r.score) }}</span>
                </span>
              </template>
              <div class="ref-body">{{ r.content }}</div>
            </el-collapse-item>
          </el-collapse>
        </template>
        <el-empty v-else description="回答后这里会显示引用的原文块" :image-size="72" />
      </el-card>

      <el-card shadow="never" class="card">
        <template #header>最近提问（仅本页内存，刷新即清）</template>
        <template v-if="history.length">
          <div v-for="h in history" :key="h.id" class="history-item" @click="reuse(h)">
            <el-tag v-if="h.gated" size="small" type="warning">拒</el-tag>
            <el-tag v-else size="small" type="success">答</el-tag>
            <span class="history-q">{{ h.question }}</span>
          </div>
        </template>
        <el-empty v-else description="暂无提问记录" :image-size="72" />
      </el-card>
    </el-col>
  </el-row>
</template>

<style scoped>
.card {
  margin-bottom: 16px;
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}
.head-right {
  display: flex;
  align-items: center;
  gap: 6px;
}
.label {
  font-size: 12px;
  color: #909399;
}
.badges {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}
.hint {
  font-size: 12px;
  color: #c0c4cc;
}
.answer p {
  margin: 0 0 8px;
  line-height: 1.85;
  font-size: 14px;
}
.meta {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  padding-top: 10px;
  border-top: 1px dashed #e4e7ed;
  font-size: 12px;
  color: #909399;
}
.mt {
  margin-top: 12px;
}
.ref-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}
.ref-score {
  color: #909399;
  font-size: 12px;
}
.ref-body {
  white-space: pre-wrap;
  line-height: 1.8;
  font-size: 13px;
  color: #606266;
  background: #fafafa;
  padding: 10px;
  border-radius: 4px;
}
.history-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 4px;
  cursor: pointer;
  border-radius: 4px;
}
.history-item:hover {
  background: #f5f7fa;
}
.history-q {
  font-size: 13px;
  color: #606266;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
