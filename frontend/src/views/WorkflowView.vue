<script setup>
import { computed } from 'vue'
import { USE_MOCK } from '../api/config'
import { useWorkflow } from '../composables/useWorkflow'

const mockMode = USE_MOCK
const {
  task,
  initialDraft,
  draft,
  nodes,
  toolResults,
  answer,
  status,
  runId,
  traceId,
  documentMeta,
  retryCount,
  rejectReason,
  confirmation,
  history,
  error,
  isStreaming,
  isBusy,
  canConfirm,
  canExport,
  start,
  stop,
  reset,
  refresh,
  confirm,
  download,
} = useWorkflow()

const examples = [
  '起草一份关于开展安全生产大检查的通知',
  '起草一份关于2027年元旦放假安排的通知',
  '起草一份关于推进政务服务标准化的通报',
]

const statusLabels = {
  IDLE: '待发起',
  RUNNING: '运行中',
  WAITING_CONFIRM: '待人工确认',
  RETRYING: '重新生成中',
  SUCCEEDED: '已通过',
  FAILED: '失败',
  REJECTED: '已驳回',
}

const activeLabel = computed(() => statusLabels[status.value] || status.value)
const confirmationTitle = computed(() => {
  if (status.value === 'REJECTED') {
    return '已驳回'
  }
  if (status.value === 'RETRYING') {
    return '已驳回，正在重新生成'
  }
  if (confirmation.value?.action === 'reject') {
    return '上次驳回'
  }
  return '确认完成'
})
const formatResult = computed(() => summarize(toolResults.value?.format, 'format'))
const termsResult = computed(() => summarize(toolResults.value?.terms, 'terms'))

function useExample(index) {
  task.value = examples[index] || examples[0]
}

function summarize(value, type) {
  if (!value) {
    return { label: '未返回结果', tone: 'muted', detail: '' }
  }
  if (value.fallback) {
    return { label: '工具不可用，已兜底', tone: 'warning', detail: value.answer || '' }
  }
  if (type === 'format') {
    const passed = value.passed === true || value.passed === 'true'
    return {
      label: passed ? '格式通过' : '格式未通过',
      tone: passed ? 'ok' : 'warning',
      detail: passed ? '' : value.issue_text || '',
    }
  }
  const hits = value.has_hits === true || value.has_hits === 'true'
  return {
    label: hits ? '发现用语问题' : '用语正常',
    tone: hits ? 'warning' : 'ok',
    detail: hits ? value.hit_text || '' : '',
  }
}

function statusTone(value) {
  const normalized = String(value || '').toLowerCase()
  if (normalized === 'succeeded' || normalized === 'success') {
    return 'ok'
  }
  if (normalized === 'failed' || normalized === 'stopped') {
    return 'warning'
  }
  return 'muted'
}

function actionLabel(action) {
  const labels = { approve: '通过', modify: '修改后通过', reject: '驳回' }
  return labels[action] || action || ''
}

function formatTime(value) {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
}
</script>

<template>
  <section class="chat-view workflow-view">
    <div class="chat-toolbar">
      <div>
        <h2>公文起草与校验</h2>
        <p class="subtitle">发起任务后先经过格式与用语工具检查，再由人工确认并导出。</p>
      </div>
      <button type="button" class="ghost-button" :disabled="isStreaming || isBusy" @click="reset">
        清空流程
      </button>
    </div>

    <div v-if="mockMode" class="rag-mock-notice" role="status">
      <strong>公文工作流需要连接真实后端</strong>
      <span>当前为演示模式，不会伪造工具结果或确认状态。请设置 <code>VITE_USE_MOCK=false</code> 并启动后端。</span>
    </div>

    <div class="workflow-layout">
      <aside class="workflow-form">
        <label class="workflow-label" for="workflow-task">任务描述</label>
        <textarea
          id="workflow-task"
          v-model="task"
          class="workflow-textarea"
          rows="4"
          maxlength="2000"
          placeholder="例如：起草一份关于开展安全生产大检查的通知"
          :disabled="isStreaming || isBusy"
        ></textarea>

        <label class="workflow-label" for="workflow-existing-draft">已有草稿（可选）</label>
        <textarea
          id="workflow-existing-draft"
          v-model="initialDraft"
          class="workflow-textarea"
          rows="5"
          maxlength="20000"
          placeholder="粘贴草稿时跳过检索生成，直接进入格式与用语检查"
          :disabled="isStreaming || isBusy"
        ></textarea>

        <div class="workflow-examples">
          <button
            v-for="(example, index) in examples"
            :key="example"
            type="button"
            class="prompt-chip"
            :disabled="isStreaming || isBusy"
            @click="useExample(index)"
          >
            {{ example }}
          </button>
        </div>

        <div class="workflow-actions">
          <button
            type="button"
            class="primary-button"
            :disabled="mockMode || isStreaming || isBusy"
            @click="start"
          >
            {{ isStreaming ? '运行中…' : '发起工作流' }}
          </button>
          <button
            v-if="isStreaming"
            type="button"
            class="ghost-button"
            @click="stop"
          >
            停止
          </button>
          <button
            type="button"
            class="ghost-button"
            :disabled="!runId || isBusy"
            @click="refresh"
          >
            刷新状态
          </button>
        </div>
      </aside>

      <div class="workflow-main">
        <div class="workflow-status-row">
          <span :class="['status-chip', `status-chip--${status.toLowerCase()}`]">{{ activeLabel }}</span>
          <span v-if="runId" class="mono workflow-meta">runId: {{ runId }}</span>
          <span v-if="retryCount" class="mono workflow-meta">retryCount: {{ retryCount }}</span>
          <span v-if="traceId" class="mono workflow-meta">traceId: {{ traceId }}</span>
        </div>

        <p v-if="error" class="workflow-error" role="alert">{{ error }}</p>

        <p v-if="status === 'RETRYING'" class="workflow-retry-notice" role="status">
          已驳回，正在按驳回意见重新生成（最多重试 1 次）…
        </p>

        <div v-if="nodes.length" class="workflow-section">
          <h3>节点进度</h3>
          <div class="workflow-nodes">
            <span
              v-for="node in nodes"
              :key="node.nodeId"
              :class="['node-chip', `node-chip--${statusTone(node.status)}`]"
              :title="`${node.type || ''} · ${node.status || ''}`"
            >
              {{ node.name || node.nodeId }}
            </span>
          </div>
        </div>

        <div class="workflow-section">
          <h3>工具结果</h3>
          <div class="tool-grid">
            <div class="tool-card">
              <div class="tool-card-head">
                <span>格式检查</span>
                <span :class="['tool-tone', `tool-tone--${formatResult.tone}`]">{{ formatResult.label }}</span>
              </div>
              <p v-if="formatResult.detail" class="tool-detail">{{ formatResult.detail }}</p>
            </div>
            <div class="tool-card">
              <div class="tool-card-head">
                <span>用语检查</span>
                <span :class="['tool-tone', `tool-tone--${termsResult.tone}`]">{{ termsResult.label }}</span>
              </div>
              <p v-if="termsResult.detail" class="tool-detail">{{ termsResult.detail }}</p>
            </div>
          </div>
        </div>

        <div v-if="answer" class="workflow-section">
          <h3>工作流输出</h3>
          <pre class="workflow-answer">{{ answer }}</pre>
        </div>

        <div v-if="status === 'WAITING_CONFIRM'" class="confirm-card">
          <div class="confirm-card-head">
            <div>
              <h3>人工确认</h3>
              <p class="subtitle">检查草稿后可原稿通过、修改后通过或驳回。未确认不能导出。</p>
              <p v-if="retryCount > 0" class="subtitle">
                本次为驳回后重新生成的草稿，再次驳回将直接结束流程。
              </p>
            </div>
            <span class="status-chip status-chip--waiting_confirm">WAITING_CONFIRM</span>
          </div>
          <textarea
            v-model="draft"
            class="workflow-textarea confirm-textarea"
            rows="14"
            maxlength="50000"
            aria-label="待确认草稿"
            :disabled="isBusy"
          ></textarea>
          <label class="workflow-label" for="workflow-reject-reason">驳回意见（可选）</label>
          <textarea
            id="workflow-reject-reason"
            v-model="rejectReason"
            class="workflow-textarea reject-reason-input"
            rows="2"
            maxlength="2000"
            placeholder="例如：请补充检查日期和责任人；首次驳回会作为修改要求用于重新生成"
            :disabled="isBusy"
          ></textarea>
          <div class="confirm-actions">
            <button
              type="button"
              class="primary-button"
              :disabled="!canConfirm"
              @click="confirm('approve')"
            >
              通过原稿
            </button>
            <button
              type="button"
              class="ghost-button"
              :disabled="!canConfirm"
              @click="confirm('modify')"
            >
              修改后通过
            </button>
            <button
              type="button"
              class="danger-button"
              :disabled="!canConfirm"
              @click="confirm('reject')"
            >
              {{ retryCount < 1 ? '驳回并重新生成' : '驳回' }}
            </button>
          </div>
        </div>

        <div v-if="confirmation" class="workflow-section confirmation-summary">
          <h3>{{ confirmationTitle }}</h3>
          <p>动作：{{ actionLabel(confirmation.action) }} · 时间：{{ formatTime(confirmation.confirmedAt) }}</p>
          <p v-if="documentMeta" class="workflow-meta">
            {{ documentMeta.title }} · {{ documentMeta.docType }} · {{ documentMeta.date }}
          </p>
        </div>

        <div v-if="canExport" class="workflow-section export-actions">
          <h3>导出</h3>
          <div class="confirm-actions">
            <button type="button" class="primary-button" :disabled="isBusy" @click="download('md')">
              下载 Markdown
            </button>
            <button type="button" class="ghost-button" :disabled="isBusy" @click="download('docx')">
              下载 DOCX
            </button>
          </div>
        </div>

        <div v-if="history.length" class="workflow-section">
          <h3>状态历史</h3>
          <ol class="history-list">
            <li v-for="item in history" :key="`${item.status}-${item.at}`">
              <strong>{{ statusLabels[item.status] || item.status }}</strong>
              <span v-if="item.action"> · {{ actionLabel(item.action) }}</span>
              <span class="workflow-meta"> · {{ formatTime(item.at) }}</span>
            </li>
          </ol>
        </div>

        <div v-if="status === 'IDLE'" class="workflow-empty">
          填写任务描述并点击“发起工作流”。流程会先做格式与用语检查，再等待人工确认。
        </div>
      </div>
    </div>
  </section>
</template>