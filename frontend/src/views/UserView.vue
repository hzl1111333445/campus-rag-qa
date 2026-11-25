<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2 class="page-title">用户管理</h2>
        <p class="page-sub">
          注册只产生「学生」；管理员由你手工开通。停用会立即失效该账号的令牌。
        </p>
      </div>
      <div class="head-actions">
        <el-input
          v-model="keyword"
          placeholder="按用户名 / 显示名搜索"
          clearable
          class="search-input"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button @click="search">搜索</el-button>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-table :data="rows" v-loading="loading" border stripe class="user-table">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="username" label="用户名" min-width="130" />
      <el-table-column prop="displayName" label="显示名" min-width="120" />
      <el-table-column label="角色" width="100">
        <template #default="{ row }">
          <el-tag :type="row.role === 'ADMIN' ? 'warning' : 'info'" size="small">
            {{ row.role === 'ADMIN' ? '管理员' : '学生' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-switch
            :model-value="row.status === 1"
            :disabled="row.id === myId"
            @change="(v) => toggleStatus(row, v)"
          />
          <span class="status-text">{{ row.status === 1 ? '启用' : '停用' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="最后登录" width="160">
        <template #default="{ row }">{{ row.lastLogin || '—' }}</template>
      </el-table-column>
      <el-table-column label="注册时间" width="160">
        <template #default="{ row }">{{ row.createTime || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openReset(row)">重置密码</el-button>
        </template>
      </el-table-column>
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

    <el-dialog v-model="resetDialog.visible" title="重置密码" width="380px">
      <p class="dialog-tip">
        正在为用户 <b>{{ resetDialog.username }}</b> 设置新密码。告诉他之后请自行登录修改。
      </p>
      <el-input v-model="resetDialog.password" type="password" show-password placeholder="6~32 位新密码" maxlength="32" />
      <template #footer>
        <el-button @click="resetDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="resetDialog.submitting" @click="submitReset">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { authState } from '../store/auth'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const myId = authState.user?.id

const resetDialog = reactive({
  visible: false,
  id: 0,
  username: '',
  password: '',
  submitting: false
})

async function load() {
  loading.value = true
  try {
    const data = await http.get('/user/page', {
      params: { page: page.value, size: size.value, keyword: keyword.value || undefined }
    })
    rows.value = data.records || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function onPageChange(p) {
  page.value = p
  load()
}

/**
 * 启停账号。
 *
 * 注意这里用的是 `:model-value` + `@change` 而不是 `v-model`：
 * 后端可能拒绝（停用自己 / 停用最后一个管理员），被拒时要保持开关原状，
 * 用 v-model 先改本地状态再回滚，看起来会"闪一下"。
 */
async function toggleStatus(row, next) {
  const status = next ? 1 : 0
  const action = status === 1 ? '启用' : '停用'
  try {
    if (status === 0) {
      await ElMessageBox.confirm(
        `停用后「${row.username}」立即无法登录，已登录的会话也会失效。确定吗？`,
        `${action}账号`,
        { type: 'warning', confirmButtonText: '确定停用', cancelButtonText: '取消' }
      )
    }
    await http.patch(`/user/${row.id}/status`, { status })
    ElMessage.success(`已${action}「${row.username}」`)
    load()
  } catch (e) {
    // 取消（'cancel'）和后端拒绝都走这里；后端消息已由拦截器弹出，这里只需不刷新状态
  }
}

function openReset(row) {
  resetDialog.visible = true
  resetDialog.id = row.id
  resetDialog.username = row.username
  resetDialog.password = ''
}

async function submitReset() {
  if (!resetDialog.password || resetDialog.password.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  resetDialog.submitting = true
  try {
    await http.post(`/user/${resetDialog.id}/reset-password`, { password: resetDialog.password })
    ElMessage.success(`已重置「${resetDialog.username}」的密码`)
    resetDialog.visible = false
  } finally {
    resetDialog.submitting = false
  }
}

onMounted(load)
</script>

<style scoped>
.page {
  padding: 18px 22px;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.page-title {
  margin: 0 0 4px;
  font-size: 17px;
  color: #1f2d3d;
}

.page-sub {
  margin: 0;
  font-size: 12px;
  color: #8a94a6;
}

.head-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.search-input {
  width: 220px;
}

.user-table {
  width: 100%;
}

.status-text {
  margin-left: 6px;
  font-size: 12px;
  color: #606266;
}

.pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}

.dialog-tip {
  margin: 0 0 12px;
  font-size: 13px;
  color: #606266;
  line-height: 1.6;
}
</style>
