import { API_BASE, USE_MOCK } from './config'
import { parseSseBlock } from '../utils/sse'

async function readErrorPayload(response) {
  try {
    return await response.json()
  } catch {
    return null
  }
}

export async function streamRagChat({ question, datasetId, signal, onEvent }) {
  if (USE_MOCK) {
    const error = new Error('RAG 问答需要连接真实后端，演示模式不提供伪造引用。')
    error.code = 'MOCK_MODE'
    throw error
  }

  let response
  try {
    response = await fetch(`${API_BASE}/rag/chat/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        // 参数校验失败时后端返回 JSON，同时接受 SSE 与 JSON 才能正确读取错误体。
        Accept: 'text/event-stream, application/json',
      },
      body: JSON.stringify({ question, datasetId }),
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
    const payload = await readErrorPayload(response)
    const error = new Error(payload?.message || `请求失败（HTTP ${response.status}）`)
    error.code = payload?.code ?? response.status
    error.traceId = payload?.traceId || ''
    throw error
  }

  const contentType = response.headers.get('content-type') || ''
  if (!contentType.includes('text/event-stream')) {
    const payload = await readErrorPayload(response)
    const error = new Error(payload?.message || '服务未返回流式响应')
    error.code = payload?.code ?? 'INVALID_RESPONSE'
    error.traceId = payload?.traceId || ''
    throw error
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

    const error = new Error('连接中断，未能读完本次回答，请重试')
    error.code = 'STREAM_INTERRUPTED'
    error.cause = caught
    throw error
  } finally {
    reader.releaseLock()
  }
}
