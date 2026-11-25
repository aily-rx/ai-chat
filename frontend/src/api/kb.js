import { API_BASE, USE_MOCK } from './config'

function ensureRealMode() {
  if (!USE_MOCK) {
    return
  }

  const error = new Error('知识库需要连接真实后端，请关闭演示模式后重启前端')
  error.code = 'MOCK_MODE'
  throw error
}

function createApiError(payload, response, fallback) {
  const error = new Error(payload?.message || `${fallback}（HTTP ${response.status}）`)
  error.code = payload?.code ?? (response.ok ? 'INVALID_RESPONSE' : response.status)
  error.traceId = payload?.traceId
  return error
}

async function request(path, options, fallback) {
  ensureRealMode()

  let response
  try {
    response = await fetch(`${API_BASE}${path}`, {
      cache: 'no-store',
      ...options,
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

  let payload = null
  try {
    payload = await response.json()
  } catch {
    payload = null
  }

  if (!response.ok) {
    throw createApiError(payload, response, fallback)
  }

  if (!payload || payload.code !== 0) {
    throw createApiError(payload, response, fallback)
  }

  return payload.data
}

export async function listDocuments({ signal } = {}) {
  const data = await request(
    '/kb/documents',
    {
      method: 'GET',
      headers: { Accept: 'application/json' },
      signal,
    },
    '加载知识库列表失败',
  )

  if (!Array.isArray(data)) {
    const error = new Error('知识库返回数据格式异常')
    error.code = 'INVALID_RESPONSE'
    throw error
  }

  return data
}

export async function uploadDocument(file) {
  const body = new FormData()
  body.append('file', file)

  const data = await request(
    '/kb/documents',
    {
      method: 'POST',
      headers: { Accept: 'application/json' },
      body,
    },
    '上传知识库文档失败',
  )

  if (!data || typeof data !== 'object') {
    const error = new Error('知识库返回数据格式异常')
    error.code = 'INVALID_RESPONSE'
    throw error
  }

  return data
}
