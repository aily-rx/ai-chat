import { computed, ref } from 'vue'
import { streamRagChat } from '../api/rag'

function createWelcomeMessage() {
  return {
    id: 'rag-welcome',
    role: 'assistant',
    isWelcome: true,
    content: '请选择知识库 A 或 B 后提问。我会只依据所选知识库中的资料回答，并列出引用依据。',
    status: 'done',
    datasetId: '',
    citations: [],
    citationsReceived: false,
    refusal: false,
    error: null,
    traceId: '',
    runId: '',
    firstTokenMs: null,
  }
}

function createAssistantMessage(datasetId) {
  return {
    id: '',
    role: 'assistant',
    isWelcome: false,
    content: '',
    status: 'streaming',
    datasetId,
    citations: [],
    citationsReceived: false,
    refusal: false,
    error: null,
    traceId: '',
    runId: '',
    firstTokenMs: null,
  }
}

function isValidCitation(item) {
  return Boolean(
    item
    && typeof item.docName === 'string'
    && item.docName.trim()
    && typeof item.segmentIndex === 'number'
    && Number.isFinite(item.segmentIndex)
    && typeof item.excerpt === 'string'
    && item.excerpt.trim(),
  )
}

function normalizeCitation(item) {
  const score = typeof item.score === 'number' && Number.isFinite(item.score) ? item.score : null
  const page = item.page === undefined || item.page === null || item.page === '' ? null : item.page

  return {
    docId: typeof item.docId === 'string' ? item.docId : '',
    docName: item.docName.trim(),
    segmentIndex: item.segmentIndex,
    excerpt: item.excerpt,
    page,
    score,
  }
}

export function useRagChat() {
  const messages = ref([createWelcomeMessage()])
  const isStreaming = ref(false)
  const datasetId = ref('B')
  const traceId = ref('')
  const firstTokenMs = ref(null)
  const totalMs = ref(null)

  let controller = null
  let sequence = 0

  const canSend = computed(() => !isStreaming.value)

  function nextId(prefix) {
    sequence += 1
    return `${prefix}-${Date.now()}-${sequence}`
  }

  function markError(assistant, code, message, errorTraceId = '') {
    assistant.status = 'error'
    assistant.error = {
      code,
      message,
      traceId: errorTraceId,
    }
    assistant.traceId = errorTraceId
    traceId.value = errorTraceId
  }

  async function send(rawQuestion) {
    const question = String(rawQuestion || '').trim()

    if (!question || isStreaming.value) {
      return false
    }

    const selectedDataset = datasetId.value === 'A' ? 'A' : 'B'
    traceId.value = ''
    firstTokenMs.value = null
    totalMs.value = null

    messages.value.push({
      id: nextId('rag-user'),
      role: 'user',
      isWelcome: false,
      content: question,
      status: 'done',
      datasetId: selectedDataset,
      citations: [],
      citationsReceived: false,
      refusal: false,
      error: null,
      traceId: '',
      runId: '',
      firstTokenMs: null,
    })

    const assistantDraft = createAssistantMessage(selectedDataset)
    assistantDraft.id = nextId('rag-assistant')
    messages.value.push(assistantDraft)
    // 必须使用数组中的响应式代理；直接修改传入 push 的原始对象不会触发渲染。
    const assistant = messages.value[messages.value.length - 1]

    const activeController = new AbortController()
    controller = activeController
    isStreaming.value = true

    const startedAt = performance.now()
    let firstDeltaReceived = false
    let terminalReceived = false

    try {
      await streamRagChat({
        question,
        datasetId: selectedDataset,
        signal: activeController.signal,
        onEvent: (event) => {
          if (terminalReceived) {
            return
          }

          if (event.event === 'message' && typeof event.data?.delta === 'string') {
            if (!firstDeltaReceived) {
              firstDeltaReceived = true
              assistant.firstTokenMs = Math.round(performance.now() - startedAt)
              firstTokenMs.value = assistant.firstTokenMs
            }

            assistant.content += event.data.delta
            return
          }

          if (event.event === 'citations') {
            const items = event.data?.items
            const invalid = !Array.isArray(items) || items.some((item) => !isValidCitation(item))

            if (invalid) {
              terminalReceived = true
              markError(assistant, 'CITATIONS_INVALID', '服务返回的引用数据不完整，请重试')
              return
            }

            assistant.citations = items.map(normalizeCitation)
            assistant.citationsReceived = true
            return
          }

          if (event.event === 'done') {
            terminalReceived = true
            const doneTraceId = event.data?.traceId || ''
            assistant.traceId = doneTraceId
            assistant.runId = event.data?.runId || ''
            traceId.value = doneTraceId

            if (!assistant.citationsReceived) {
              markError(assistant, 'CITATIONS_MISSING', '回答已结束，但未收到引用数据，请重试', doneTraceId)
              return
            }

            assistant.status = 'done'
            assistant.refusal = assistant.citations.length === 0
            return
          }

          if (event.event === 'error') {
            terminalReceived = true
            markError(
              assistant,
              event.data?.code ?? 'UNKNOWN',
              event.data?.message || '生成失败',
              event.data?.traceId || '',
            )
          }
        },
      })

      if (!terminalReceived && assistant.status === 'streaming') {
        markError(assistant, 'STREAM_INCOMPLETE', '连接提前结束，未收到完成事件，请重试')
      }
    } catch (caught) {
      if (terminalReceived) {
        return true
      }

      if (caught.name === 'AbortError') {
        assistant.status = 'stopped'
      } else {
        markError(
          assistant,
          caught.code ?? 'NETWORK',
          caught.message || '请求失败',
          caught.traceId || '',
        )
      }
    } finally {
      totalMs.value = Math.round(performance.now() - startedAt)
      isStreaming.value = false
      controller = null
    }

    return true
  }

  function stop() {
    controller?.abort()
  }

  function reset() {
    if (isStreaming.value) {
      return
    }

    messages.value = [createWelcomeMessage()]
    traceId.value = ''
    firstTokenMs.value = null
    totalMs.value = null
  }

  return {
    messages,
    isStreaming,
    datasetId,
    traceId,
    firstTokenMs,
    totalMs,
    canSend,
    send,
    stop,
    reset,
  }
}
