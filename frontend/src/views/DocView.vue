<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

/**
 * 文档管理页 = 知识库的"账本"。
 *
 * 为什么要做这一页：
 * 1. 用户能看见库里有几份文件、切成了多少块 —— 这是 RAG 最容易被质疑的黑箱；
 * 2. 上传/删除/重新处理都能在界面上做，不用再敲 curl；
 * 3. "块预览"直接展示切分结果，答辩时能当场点开证明"块长 195 字、带章节头"。
 *
 * 数据全部来自真实接口，没有任何假数据：
 *   GET    /api/document/page?page=&size=   列表 + 总数 + 总块数
 *   POST   /api/document/upload             上传（后端同步做解析→切分→入库）
 *   GET    /api/document/{id}/chunks        某文档切出来的块
 *   POST   /api/document/{id}/reprocess     用磁盘上的原文件重新切一遍
 *   POST   /api/document/{id}/embed         只给这一份文档补向量（新上传的文件用）
 *   DELETE /api/document/{id}               删文档（先删块再删记录）
 */

const route = useRoute()

const loading = ref(false)
const uploading = ref(false)
const uploadName = ref('')

const records = ref([])
const total = ref(0)
const chunkTotal = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')

// 块预览抽屉
const drawer = ref(false)
const drawerTitle = ref('')
const drawerLoading = ref(false)
const chunks = ref([])

/** 前端过滤：33 份文档量级很小，不必为搜索再写一个后端接口 */
const shown = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return records.value
  return records.value.filter((d) =>
    String(d.title || '').toLowerCase().includes(k) ||
    String(d.original_name || '').toLowerCase().includes(k)
  )
})

/** 状态码在后端是数字（0 待处理 / 2 成功 / 3 失败），展示层翻成人话 */
const STATUS = {
  0: { text: '待处理', type: 'warning' },
  1: { text: '处理中', type: 'info' },
  2: { text: '成功', type: 'success' },
  3: { text: '失败', type: 'danger' }
}

function statusOf(s) {
  return STATUS[s] || { text: `未知(${s})`, type: 'info' }
}

/** 文件名里的前缀序号（如 18-）只是入库时的排序编号，展示时去掉 */
function cleanTitle(t) {
  return (t || '').replace(/^\s*\d+[-_—\s]+/, '')
}

function sizeText(bytes) {
  if (!bytes && bytes !== 0) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

function timeText(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').slice(0, 16)
}

async function load() {
  loading.value = true
  try {
    const data = await http.get('/document/page', { params: { page: page.value, size: size.value } })
    records.value = data.records || []
    total.value = data.total || 0
    chunkTotal.value = data.chunkTotal || 0
  } finally {
    loading.value = false
  }
}

function onPageChange(p) {
  page.value = p
  load()
}

/**
 * 覆盖 el-upload 的默认上传行为：走我们自己的 http 实例，
 * 这样错误提示、代理、超时都跟其它页面一致。
 */
async function doUpload(option) {
  const file = option.file
  uploading.value = true
  uploadName.value = file.name
  const fd = new FormData()
  fd.append('file', file)
  try {
    const data = await http.post('/document/upload', fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    if (data.status === 2) {
      ElMessage.success(
        `上传成功：切出 ${data.chunkCount} 块、向量化 ${data.embeddedThisRun} 块，耗时 ${data.elapsedMs} ms`
      )
      if (data.embedError) ElMessage.warning(`有块向量化失败：${data.embedError}，可点「补向量化」重试`)
    } else {
      ElMessage.warning(`上传了但切分失败（${data.failReason}），这份文件检索不到内容`)
    }
    page.value = 1
    await load()
  } finally {
    uploading.value = false
    uploadName.value = ''
  }
}

async function openChunks(row) {
  drawerTitle.value = cleanTitle(row.title)
  drawer.value = true
  drawerLoading.value = true
  chunks.value = []
  try {
    const data = await http.get(`/document/${row.id}/chunks`, { params: { limit: 50 } })
    chunks.value = data.chunks || []
  } finally {
    drawerLoading.value = false
  }
}

/**
 * 补向量化：只处理 embedding 为空的块。
 * 什么时候需要它？换 embedding 模型、或上传过程中接口失败过，会留下"有块没向量"的文档，
 * 这种文档只有 BM25 能搜到，向量检索看不见。
 */
async function embedPending() {
  const data = await http.post('/dev/embed-all', null, { params: { maxChunks: 1000 } })
  if (data.embeddedThisRun > 0) {
    ElMessage.success(`补齐 ${data.embeddedThisRun} 块向量，耗时 ${data.elapsedMs} ms`)
  } else {
    ElMessage.info('没有待向量化的块，库里已是最新')
  }
  if (data.error) ElMessage.error(`中途失败：${data.error}`)
  await load()
}

/**
 * 单份文档补向量：只算这份里 embedding 为空的块。
 *
 * 为什么要有"按行"的按钮，而不是只留工具栏那个全局的？
 * 上传后如果某份文件向量化失败，管理员需要知道"是哪一份出问题"，
 * 并且能就地补一次 —— 全局按钮会把整个库扫一遍，看不出问题出在哪份。
 */
async function embedRow(row) {
  const data = await http.post(`/document/${row.id}/embed`)
  if (data.embeddedThisRun > 0) {
    ElMessage.success(
      `「${cleanTitle(row.title)}」补齐 ${data.embeddedThisRun} 块向量，耗时 ${data.elapsedMs} ms`
    )
  } else {
    ElMessage.info('这份文档没有待向量化的块')
  }
  if (data.error) ElMessage.error(`向量化失败：${data.error}`)
  await load()
}

async function reprocess(row) {
  await ElMessageBox.confirm(
    `用磁盘上的原文件重新切分「${cleanTitle(row.title)}」？块会被整份替换。`,
    '重新处理',
    { type: 'warning', confirmButtonText: '重新处理', cancelButtonText: '取消' }
  )
  const data = await http.post(`/document/${row.id}/reprocess`)
  if (data.status === 2) {
    ElMessage.success(
      `重新切分完成：${data.chunkCount} 块、向量化 ${data.embeddedThisRun} 块，耗时 ${data.elapsedMs} ms`
    )
  } else {
    ElMessage.error('重新切分失败，详情见 kb_document.fail_reason')
  }
  await load()
}

async function remove(row) {
  await ElMessageBox.confirm(
    `删除「${cleanTitle(row.title)}」会同时删掉它的 ${row.chunk_count} 个块和磁盘文件，且无法恢复。`,
    '删除文档',
    { type: 'error', confirmButtonText: '确认删除', cancelButtonText: '取消' }
  )
  const data = await http.delete(`/document/${row.id}`)
  ElMessage.success(`已删除，清理块 ${data.deleted} 条${data.fileRemoved ? '，磁盘文件已删' : ''}`)
  await load()
}

/**
 * 支持 /documents?id=46 直接打开某份文档的块预览。
 * 用途：答辩或演示时可以直接把一个链接发给别人，点开就是"切分结果"这一屏。
 */
onMounted(async () => {
  await load()
  const id = Number(route.query.id)
  if (!id) return
  const row = records.value.find((r) => r.id === id)
  await openChunks(row || { id, title: `文档 #${id}`, chunk_count: 0 })
})
</script>

<template>
  <div class="doc-page">
    <el-card shadow="never">
      <div class="head">
        <div class="head-left">
          <span class="head-title">文档管理</span>
          <span class="head-sub">上传后自动解析 → 切分 → 入库，这里能看到每一份文件和它切出的块</span>
        </div>
        <div class="head-right">
          <el-tag effect="plain" size="small">文档 {{ total }} 份</el-tag>
          <el-tag effect="plain" size="small" type="success">块 {{ chunkTotal }}</el-tag>
        </div>
      </div>

      <div class="toolbar">
        <el-input v-model="keyword" placeholder="按标题过滤" clearable class="search" />
        <div class="toolbar-right">
          <el-button :loading="loading" @click="load">刷新</el-button>
          <el-button @click="embedPending">补向量化</el-button>
          <el-upload
            :show-file-list="false"
            :http-request="doUpload"
            accept=".pdf,.docx,.doc,.txt"
          >
            <el-button type="primary" :loading="uploading">
              {{ uploading ? `上传中：${uploadName}` : '上传文档' }}
            </el-button>
          </el-upload>
        </div>
      </div>

      <el-table v-loading="loading" :data="shown" stripe style="width: 100%">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="标题" min-width="260">
          <template #default="{ row }">
            <span :title="row.original_name">{{ cleanTitle(row.title) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="file_type" label="类型" width="80" />
        <el-table-column label="大小" width="92">
          <template #default="{ row }">{{ sizeText(row.file_size) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusOf(row.status).type" size="small" effect="light">
              {{ statusOf(row.status).text }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="chunk_count" label="块数" width="80" />
        <el-table-column label="向量" width="88">
          <template #default="{ row }">
            <el-tag v-if="!row.pending_chunks" type="success" size="small" effect="plain">已完整</el-tag>
            <el-tag v-else type="warning" size="small" effect="light">缺 {{ row.pending_chunks }} 块</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="入库时间" width="150">
          <template #default="{ row }">{{ timeText(row.create_time) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openChunks(row)">看块</el-button>
            <el-button
              link
              type="primary"
              :disabled="!row.pending_chunks"
              :title="row.pending_chunks ? '给这份文档补算向量' : '这份文档的块都已向量化'"
              @click="embedRow(row)"
            >
              向量化
            </el-button>
            <el-button link type="primary" @click="reprocess(row)">重处理</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty">还没有文档，点右上角「上传文档」开始</div>
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          background
          layout="total, prev, pager, next"
          :total="total"
          :page-size="size"
          :current-page="page"
          @current-change="onPageChange"
        />
      </div>
    </el-card>

    <el-drawer v-model="drawer" :title="`切分结果：${drawerTitle}`" size="560px" class="chunk-drawer">
      <div v-loading="drawerLoading" class="drawer-body">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="这些块就是检索的最小单位：回答问题时，系统会先在这些块里找最相关的几条。"
        />
        <div v-for="c in chunks" :key="c.id" class="chunk">
          <div class="chunk-head">
            <span class="chunk-idx">第 {{ c.chunk_index }} 块</span>
            <span class="chunk-meta">{{ c.char_count }} 字</span>
          </div>
          <div class="chunk-text">{{ c.content }}</div>
        </div>
        <div v-if="!drawerLoading && !chunks.length" class="empty">这份文档还没有块（可能解析失败）</div>
        <div v-if="chunks.length >= 50" class="empty">只显示前 50 块（完整内容见数据库 kb_chunk）</div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.doc-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding-bottom: 10px;
  border-bottom: 1px solid #f0f2f5;
}
.head-title {
  font-size: 16px;
  font-weight: 600;
}
.head-sub {
  margin-left: 10px;
  font-size: 12px;
  color: #909399;
}
.head-right {
  display: flex;
  gap: 8px;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 12px 0;
  gap: 12px;
}
.search {
  max-width: 260px;
}
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
.empty {
  padding: 16px;
  color: #909399;
  font-size: 13px;
  text-align: center;
}
/** 抽屉内容区统一给内边距，避免长段落贴到窗口边缘（看起来像被裁掉） */
:deep(.el-drawer__body) {
  padding: 16px 16px 24px;
}
.drawer-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  /* 块正文可能是很长的无空格中文，允许在任意位置换行，避免把抽屉撑宽 */
  min-width: 0;
  overflow-x: hidden;
}
.chunk {
  /* flex 容器里的子项默认 min-width:auto，长中文会把卡片撑得比抽屉还宽 → 显式清零 */
  min-width: 0;
  overflow: hidden;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px 12px;
  background: #fafafa;
}
.chunk-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 6px;
}
.chunk-idx {
  font-size: 12px;
  font-weight: 600;
  color: #409eff;
}
.chunk-meta {
  font-size: 12px;
  color: #909399;
}
.chunk-text {
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
  color: #303133;
}
</style>
