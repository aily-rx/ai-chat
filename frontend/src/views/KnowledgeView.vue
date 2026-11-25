<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { USE_MOCK } from '../api/config'
import { listDocuments, uploadDocument } from '../api/kb'

const ACCEPTED_FILE_TYPES = '.pdf,.docx,.txt,.md'
const ALLOWED_EXTENSIONS = new Set(['pdf', 'docx', 'txt', 'md'])
const MAX_FILE_SIZE = 20 * 1024 * 1024
const POLL_INTERVAL_MS = 2000

const STATUS_META = {
  PENDING: { label: '排队中', className: 'badge--pending' },
  INDEXING: { label: '处理中', className: 'badge--pending' },
  READY: { label: '可用', className: 'badge--success' },
  FAILED: { label: '失败', className: 'badge--danger' },
}

const mockMode = USE_MOCK
const documents = ref([])
const selectedFile = ref(null)
const fileInput = ref(null)
const loadingList = ref(false)
const uploading = ref(false)
const listError = ref(null)
const fileError = ref('')
const uploadError = ref(null)
const uploadNotice = ref('')

let pollTimer = null
let listAbortController = null
let listRequestInFlight = false
let disposed = false

const hasProcessing = computed(() => documents.value.some((document) => isProcessing(document.status)))
const processingCount = computed(() => documents.value.filter((document) => isProcessing(document.status)).length)

function isProcessing(status) {
  return status === 'PENDING' || status === 'INDEXING'
}

function statusMeta(status) {
  return STATUS_META[status] || { label: status || '未知', className: '' }
}

function extensionOf(fileName) {
  const name = fileName || ''
  const dotIndex = name.lastIndexOf('.')
  return dotIndex >= 0 ? name.slice(dotIndex + 1).toLowerCase() : ''
}

function fileTypeLabel(fileName) {
  const extension = extensionOf(fileName)
  return extension ? extension.toUpperCase() : '文件'
}

function validateFile(file) {
  if (!file) {
    return '请选择要上传的文件'
  }
  if (!ALLOWED_EXTENSIONS.has(extensionOf(file.name))) {
    return '仅支持 PDF、DOCX、TXT、MD 文件'
  }
  if (file.size <= 0) {
    return '文件不能为空'
  }
  if (file.size > MAX_FILE_SIZE) {
    return '文件大小不能超过 20MB'
  }
  return ''
}

function formatFileSize(bytes) {
  if (typeof bytes !== 'number' || Number.isNaN(bytes) || bytes < 0) {
    return '—'
  }
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

function formatDateTime(value) {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  return dateFormatter.format(date)
}

function normalizeError(error, fallback) {
  const code = error?.code ?? ''
  let message = error?.message || fallback

  if (code === 'NETWORK') {
    message = '无法连接后端，请确认后端服务已启动'
  } else if (code === 'MOCK_MODE') {
    message = '知识库需要连接真实后端，请关闭演示模式后重启前端'
  } else if (code === 'INVALID_RESPONSE') {
    message = error?.message || '后端返回数据格式异常'
  } else if (code === 5002 && !message.includes('知识库')) {
    message = `${message}，知识库服务暂不可用`
  }

  return {
    code,
    message,
    traceId: error?.traceId || '',
  }
}

function stopPolling() {
  if (pollTimer !== null) {
    window.clearTimeout(pollTimer)
    pollTimer = null
  }
}

function syncPolling() {
  if (disposed || mockMode || !hasProcessing.value) {
    stopPolling()
    return
  }
  if (pollTimer !== null) {
    return
  }

  pollTimer = window.setTimeout(async () => {
    pollTimer = null
    if (disposed) {
      return
    }
    const ok = await loadDocuments({ silent: true })
    if (ok) {
      syncPolling()
    }
  }, POLL_INTERVAL_MS)
}

async function loadDocuments({ silent = false } = {}) {
  if (listRequestInFlight) {
    return false
  }

  listRequestInFlight = true
  const controller = new AbortController()
  listAbortController = controller
  if (!silent) {
    loadingList.value = true
  }

  try {
    const data = await listDocuments({ signal: controller.signal })
    if (disposed) {
      return false
    }
    documents.value = data
    listError.value = null
    return true
  } catch (error) {
    if (error?.name === 'AbortError' || disposed) {
      return false
    }
    stopPolling()
    listError.value = normalizeError(error, '加载文档列表失败')
    return false
  } finally {
    if (listAbortController === controller) {
      listAbortController = null
    }
    listRequestInFlight = false
    if (!silent) {
      loadingList.value = false
    }
  }
}

async function refreshDocuments() {
  if (listRequestInFlight) {
    return
  }
  stopPolling()
  const ok = await loadDocuments()
  if (ok) {
    syncPolling()
  }
}

function openFilePicker() {
  if (!uploading.value) {
    fileInput.value?.click()
  }
}

function handleFileChange(event) {
  const file = event.target.files?.[0] || null
  fileError.value = ''
  uploadError.value = null
  uploadNotice.value = ''

  if (!file) {
    return
  }

  const message = validateFile(file)
  if (message) {
    selectedFile.value = null
    fileError.value = message
    event.target.value = ''
    return
  }

  selectedFile.value = file
}

function clearSelection() {
  if (uploading.value) {
    return
  }
  selectedFile.value = null
  fileError.value = ''
  uploadError.value = null
  uploadNotice.value = ''
  if (fileInput.value) {
    fileInput.value.value = ''
  }
}

async function handleUpload() {
  if (uploading.value) {
    return
  }

  const file = selectedFile.value
  const message = validateFile(file)
  if (message) {
    fileError.value = message
    return
  }

  uploading.value = true
  fileError.value = ''
  uploadError.value = null
  uploadNotice.value = ''

  try {
    const document = await uploadDocument(file)
    uploadNotice.value = `“${document.fileName || file.name}”已上传，正在建立索引。`
    selectedFile.value = null
    if (fileInput.value) {
      fileInput.value.value = ''
    }

    const ok = await loadDocuments()
    if (ok) {
      syncPolling()
    }
  } catch (error) {
    uploadError.value = normalizeError(error, '上传知识库文档失败')

    if (uploadError.value.code !== 'NETWORK' && uploadError.value.code !== 'MOCK_MODE') {
      const ok = await loadDocuments()
      if (ok) {
        syncPolling()
      }
    }
  } finally {
    uploading.value = false
  }
}

onMounted(async () => {
  if (mockMode) {
    return
  }
  const ok = await loadDocuments()
  if (ok) {
    syncPolling()
  }
})

onBeforeUnmount(() => {
  disposed = true
  stopPolling()
  listAbortController?.abort()
})
</script>

<template>
  <section class="kb-view">
    <div class="kb-toolbar">
      <div>
        <h2>知识库</h2>
        <p class="subtitle">上传公文资料并跟踪索引状态，索引完成后可在知识库中检索使用。</p>
      </div>
      <button
        v-if="!mockMode"
        type="button"
        class="ghost-button"
        :disabled="loadingList"
        @click="refreshDocuments"
      >
        {{ loadingList ? '刷新中…' : '刷新列表' }}
      </button>
    </div>

    <div v-if="mockMode" class="error-card kb-mock-card" role="status">
      <strong>当前为演示模式</strong>
      <p>知识库需要连接真实后端。请将 <code>VITE_USE_MOCK</code> 设为 <code>false</code> 或删除该配置后重启前端，页面不会用 Mock 数据代替真实上传。</p>
    </div>

    <div v-else class="kb-layout">
      <form class="panel kb-upload-panel" @submit.prevent="handleUpload">
        <div class="panel__header">
          <div>
            <h3>上传资料</h3>
            <p class="panel-hint">上传后写入默认知识库，由后端异步建立索引。</p>
          </div>
        </div>

        <input
          ref="fileInput"
          type="file"
          class="kb-file-input"
          :accept="ACCEPTED_FILE_TYPES"
          hidden
          @change="handleFileChange"
        />

        <button
          type="button"
          class="kb-picker"
          :disabled="uploading"
          @click="openFilePicker"
        >
          <span class="kb-picker__mark" aria-hidden="true">+</span>
          <strong>{{ selectedFile ? '重新选择文件' : '选择文件' }}</strong>
          <span>支持 PDF / DOCX / TXT / MD，单个文件不超过 20MB</span>
        </button>

        <div v-if="selectedFile" class="kb-selected-file">
          <div class="kb-selected-file__info">
            <strong>{{ selectedFile.name }}</strong>
            <span>{{ formatFileSize(selectedFile.size) }}</span>
          </div>
          <button
            type="button"
            class="link-button"
            :disabled="uploading"
            @click="clearSelection"
          >
            移除
          </button>
        </div>

        <p v-if="fileError" class="hint-error kb-field-error" role="alert">{{ fileError }}</p>

        <button
          type="submit"
          class="primary-button kb-upload-button"
          :disabled="!selectedFile || uploading"
        >
          {{ uploading ? '上传并提交索引…' : '上传并入库' }}
        </button>

        <p class="panel-hint">上传成功后列表会自动刷新；存在处理中文档时，每 2 秒更新一次状态。</p>

        <div v-if="uploadNotice" class="kb-notice" role="status">
          {{ uploadNotice }}
        </div>

        <div v-if="uploadError" class="error-card kb-field-error" role="alert">
          <strong>上传失败</strong>
          <p>{{ uploadError.message }}</p>
          <p v-if="uploadError.code || uploadError.traceId" class="mono">
            错误码：{{ uploadError.code || '—' }}
            <template v-if="uploadError.traceId"> · traceId: {{ uploadError.traceId }}</template>
          </p>
        </div>
      </form>

      <section class="panel kb-list-panel">
        <div class="panel__header">
          <div>
            <h3>文档列表</h3>
            <p class="panel-hint">
              共 {{ documents.length }} 份
              <template v-if="processingCount">· {{ processingCount }} 份处理中（自动刷新）</template>
            </p>
          </div>
        </div>

        <div v-if="listError" class="error-banner kb-list-error" role="alert">
          <strong>列表刷新失败</strong>
          <span>{{ listError.message }}</span>
          <span v-if="listError.code">错误码 {{ listError.code }}</span>
          <span v-if="listError.traceId">traceId: {{ listError.traceId }}</span>
          <button type="button" class="link-button" @click="refreshDocuments">重试</button>
        </div>

        <div v-if="loadingList && !documents.length" class="empty-state">
          正在加载文档列表…
        </div>

        <div v-else-if="!documents.length" class="empty-state">
          <div>
            暂无文档<br />
            <span>选择一份公文资料上传，状态会从“处理中”变为“可用”。</span>
          </div>
        </div>

        <div v-else class="kb-table-wrap">
          <table class="kb-table">
            <thead>
              <tr>
                <th scope="col">文件</th>
                <th scope="col">大小</th>
                <th scope="col">状态</th>
                <th scope="col">分段</th>
                <th scope="col">创建时间</th>
                <th scope="col">说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="document in documents" :key="document.id">
                <td class="kb-file-cell">
                  <span class="kb-file-name">{{ document.fileName }}</span>
                  <span class="kb-file-type">{{ fileTypeLabel(document.fileName) }}</span>
                </td>
                <td class="kb-nowrap">{{ formatFileSize(document.size) }}</td>
                <td>
                  <span :class="['badge', 'kb-status-badge', statusMeta(document.status).className]">
                    <span v-if="isProcessing(document.status)" class="kb-status-dot" aria-hidden="true"></span>
                    {{ statusMeta(document.status).label }}
                  </span>
                </td>
                <td class="kb-nowrap">{{ document.chunkCount == null ? '—' : document.chunkCount }}</td>
                <td class="kb-nowrap">
                  <time v-if="document.createdAt" :datetime="document.createdAt">
                    {{ formatDateTime(document.createdAt) }}
                  </time>
                  <span v-else>—</span>
                </td>
                <td :class="{ 'kb-error-text': document.errorMessage }">
                  {{ document.errorMessage || '—' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>
  </section>
</template>
