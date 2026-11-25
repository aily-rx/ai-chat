import { API_BASE, USE_MOCK } from './config'
import { parseSseBlock } from '../utils/sse'

async function readPayload(response) {
  try {
    return await response.json()
  } catch {
    return null
  }
}

async function readError(response) {
  const payload = await readPayload(response)
  const error = new Error(payload?.message || `请求失败（HTTP ${response.status}）`)
  error.code = payload?.code ?? response.status
  error.traceId = payload?.traceId || ''
  return error
}

export async function streamWorkflow({ input, draft, signal, onEvent }) {
  if (USE_MOCK) {
    const error = new Error('公文工作流需要连接真实后端，演示模式不提供伪造结果。')
    error.code = 'MOCK_MODE'
    throw error
  }

  let response
  try {
    response = await fetch(`${API_BASE}/workflow/run/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream, application/json',
      },
      body: JSON.stringify({ input, draft: draft || '' }),
      signal,
    })
  } catch (caught) {
    if (caught?.name === 'AbortError') {
      throw caught
    }
    const error = new Error('无法连接后端，请确认服务已启动')
    error.code = 'NETWORK'
    error.cause = caught
    throw error
  }

  if (!response.ok) {
    throw await readError(response)
  }
  if (!(response.headers.get('content-type') || '').includes('text/event-stream')) {
    throw await readError(response)
  }
  if (!response.body) {
    const error = new Error('当前浏览器不支持流式响应')
    error.code = 'STREAM_UNSUPPORTED'
    throw error
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true })
      const blocks = buffer.split(/\r?\n\r?\n/)
      buffer = blocks.pop() ?? ''
      for (const block of blocks) {
        const event = parseSseBlock(block)
        if (event) {
          onEvent(event)
        }
      }
    }
    buffer += decoder.decode()
    if (buffer.trim()) {
      const event = parseSseBlock(buffer)
      if (event) {
        onEvent(event)
      }
    }
  } catch (caught) {
    if (caught?.name === 'AbortError') {
      throw caught
    }
    const error = new Error('连接中断，未能读完本次工作流')
    error.code = 'STREAM_INTERRUPTED'
    error.cause = caught
    throw error
  } finally {
    reader.releaseLock()
  }
}

export async function getWorkflowRun(runId) {
  const response = await fetch(`${API_BASE}/workflow/${encodeURIComponent(runId)}`)
  if (!response.ok) {
    throw await readError(response)
  }
  const payload = await readPayload(response)
  if (!payload || payload.code !== 0) {
    const error = new Error(payload?.message || '查询运行状态失败')
    error.code = payload?.code ?? 'INVALID_RESPONSE'
    error.traceId = payload?.traceId || ''
    throw error
  }
  return payload.data
}

export async function confirmWorkflow(runId, action, content) {
  const response = await fetch(`${API_BASE}/workflow/${encodeURIComponent(runId)}/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action, content: content || '' }),
  })
  if (!response.ok) {
    throw await readError(response)
  }
  const payload = await readPayload(response)
  if (!payload || payload.code !== 0) {
    const error = new Error(payload?.message || '确认失败')
    error.code = payload?.code ?? 'INVALID_RESPONSE'
    error.traceId = payload?.traceId || ''
    throw error
  }
  return payload.data
}

export async function downloadWorkflowExport(runId, format) {
  const response = await fetch(
    `${API_BASE}/workflow/${encodeURIComponent(runId)}/export?format=${encodeURIComponent(format)}`,
  )
  if (!response.ok) {
    throw await readError(response)
  }
  const contentType = response.headers.get('content-type') || ''
  if (contentType.includes('application/json')) {
    throw await readError(response)
  }
  const disposition = response.headers.get('content-disposition') || ''
  return {
    blob: await response.blob(),
    filename: parseFilename(disposition, format),
  }
}

function parseFilename(disposition, format) {
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (encoded?.[1]) {
    try {
      return decodeURIComponent(encoded[1])
    } catch {
      // 忽略非法编码，使用默认文件名。
    }
  }
  const plain = disposition.match(/filename="?([^";]+)"?/i)
  return plain?.[1] || `公文.${format === 'docx' ? 'docx' : 'md'}`
}
