import { computed, onBeforeUnmount, ref } from 'vue'
import {
  confirmWorkflow,
  downloadWorkflowExport,
  getWorkflowRun,
  streamWorkflow,
} from '../api/workflow'

export function useWorkflow() {
  const task = ref('')
  const initialDraft = ref('')
  const draft = ref('')
  const nodes = ref([])
  const toolResults = ref({})
  const answer = ref('')
  const status = ref('IDLE')
  const runId = ref('')
  const traceId = ref('')
  const documentMeta = ref(null)
  const confirmation = ref(null)
  const history = ref([])
  const error = ref('')
  const isStreaming = ref(false)
  const isBusy = ref(false)

  let controller = null
  let pollTimer = null

  const canConfirm = computed(() => status.value === 'WAITING_CONFIRM' && !isBusy.value)
  const canExport = computed(() => status.value === 'SUCCEEDED' && Boolean(runId.value) && !isBusy.value)

  function reset() {
    stop()
    draft.value = ''
    nodes.value = []
    toolResults.value = {}
    answer.value = ''
    status.value = 'IDLE'
    runId.value = ''
    traceId.value = ''
    documentMeta.value = null
    confirmation.value = null
    history.value = []
    error.value = ''
    isBusy.value = false
  }

  function stop() {
    if (controller) {
      controller.abort()
      controller = null
    }
    isStreaming.value = false
    stopPolling()
  }

  async function start() {
    const input = task.value.trim()
    if (!input) {
      error.value = '请先填写任务描述'
      return false
    }
    reset()
    status.value = 'RUNNING'
    isStreaming.value = true
    controller = new AbortController()
    let terminal = false

    try {
      await streamWorkflow({
        input,
        draft: initialDraft.value,
        signal: controller.signal,
        onEvent: (event) => {
          if (event.event === 'node') {
            mergeNode(event.data)
            return
          }
          if (event.event === 'message') {
            answer.value += event.data?.delta || ''
            return
          }
          if (event.event === 'done') {
            terminal = true
            runId.value = event.data?.runId || ''
            traceId.value = event.data?.traceId || ''
            status.value = event.data?.status || 'SUCCEEDED'
            draft.value = extractDraft(answer.value)
            if (status.value === 'WAITING_CONFIRM') {
              startPolling()
            }
            return
          }
          if (event.event === 'error') {
            terminal = true
            fail(event.data?.message || '工作流执行失败')
          }
        },
      })
      if (!terminal) {
        fail('工作流未正常结束，请重试')
      }
    } catch (caught) {
      if (caught?.name !== 'AbortError') {
        fail(caught.message || '工作流执行失败')
      }
    } finally {
      isStreaming.value = false
      controller = null
    }
    return !error.value
  }

  async function refresh() {
    if (!runId.value) {
      error.value = '当前没有可查询的运行记录'
      return
    }
    try {
      const view = await getWorkflowRun(runId.value)
      applyView(view)
    } catch (caught) {
      error.value = caught.message || '查询运行状态失败'
    }
  }

  async function confirm(action) {
    if (!runId.value) {
      error.value = '运行记录不存在'
      return
    }
    if (action === 'modify' && !draft.value.trim()) {
      error.value = '修改内容不能为空'
      return
    }
    error.value = ''
    isBusy.value = true
    try {
      const view = await confirmWorkflow(
        runId.value,
        action,
        action === 'modify' ? draft.value : '',
      )
      applyView(view)
      stopPolling()
    } catch (caught) {
      error.value = caught.message || '确认失败'
      if (caught.code === 5005) {
        await refresh()
      }
    } finally {
      isBusy.value = false
    }
  }

  async function download(format) {
    if (!runId.value) {
      return
    }
    error.value = ''
    isBusy.value = true
    try {
      const file = await downloadWorkflowExport(runId.value, format)
      const href = URL.createObjectURL(file.blob)
      const link = window.document.createElement('a')
      link.href = href
      link.download = file.filename
      window.document.body.appendChild(link)
      link.click()
      link.remove()
      URL.revokeObjectURL(href)
    } catch (caught) {
      error.value = caught.message || '导出失败'
    } finally {
      isBusy.value = false
    }
  }

  function applyView(view) {
    if (!view) {
      return
    }
    status.value = view.status || status.value
    runId.value = view.runId || runId.value
    traceId.value = view.traceId || traceId.value
    documentMeta.value = view.document || documentMeta.value
    toolResults.value = view.toolResults || toolResults.value
    if (Array.isArray(view.nodes) && view.nodes.length) {
      nodes.value = view.nodes
    }
    confirmation.value = view.confirmation || confirmation.value
    history.value = Array.isArray(view.history) ? view.history : history.value
    if (view.confirmation?.content) {
      draft.value = view.confirmation.content
    } else if (view.draft && status.value === 'WAITING_CONFIRM' && !draft.value.trim()) {
      draft.value = view.draft
    }
    if (view.errorMessage) {
      error.value = view.errorMessage
    }
  }

  function mergeNode(node) {
    if (!node?.nodeId) {
      return
    }
    const index = nodes.value.findIndex((item) => item.nodeId === node.nodeId)
    if (index >= 0) {
      nodes.value[index] = node
    } else {
      nodes.value.push(node)
    }
  }

  function startPolling() {
    if (pollTimer) {
      return
    }
    pollTimer = window.setInterval(async () => {
      if (!runId.value || isBusy.value || status.value !== 'WAITING_CONFIRM') {
        return
      }
      try {
        const view = await getWorkflowRun(runId.value)
        applyView(view)
        if (view.status !== 'WAITING_CONFIRM') {
          stopPolling()
        }
      } catch (caught) {
        error.value = caught.message || '查询运行状态失败'
      }
    }, 5000)
  }

  function stopPolling() {
    if (pollTimer) {
      window.clearInterval(pollTimer)
      pollTimer = null
    }
  }

  function fail(message) {
    status.value = 'FAILED'
    error.value = message
    stopPolling()
  }

  function extractDraft(text) {
    if (!text) {
      return ''
    }
    for (const marker of ['草稿如下，未做任何修改：\n', '草稿如下：\n', '草稿：\n']) {
      const index = text.indexOf(marker)
      if (index >= 0) {
        return text.slice(index + marker.length).trim()
      }
    }
    return text.trim()
  }

  onBeforeUnmount(() => {
    stop()
  })

  return {
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
  }
}
