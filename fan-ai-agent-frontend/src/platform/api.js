import { reactive } from 'vue'

export const session = reactive({ user: null, ready: false })
let csrf = null
let generation = 0
const streams = new Set()
const authChannel = typeof window !== 'undefined' && window.BroadcastChannel ? new window.BroadcastChannel('fan-platform-auth') : null
authChannel?.addEventListener('message', () => clearSession(false))
export function clearSession(broadcast = true) {
  generation++
  for (const stream of streams) stream.abort()
  streams.clear()
  session.user = null
  csrf = null
  window.dispatchEvent(new window.Event('fan-session-cleared'))
  if (broadcast) authChannel?.postMessage({ type: 'identity-changed' })
}
export async function csrfToken() {
  if (!csrf) {
    const epoch = generation
    const response = await fetch('/api/auth/csrf', { credentials: 'same-origin', cache: 'no-store' })
    if (!response.ok) throw new Error('请求校验初始化失败')
    const token = await response.json()
    if (epoch !== generation) throw new Error('账户已变化，本次旧请求已丢弃')
    csrf = token
  }
  return csrf
}
export async function api(path, options = {}) {
  const epoch = generation
  const headers = { ...options.headers }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (options.method && options.method !== 'GET') {
    const token = await csrfToken()
    headers[token.headerName] = token.token
  }
  if (epoch !== generation) throw new Error('账户已变化，本次旧请求已丢弃')
  const response = await fetch(`/api${path}`, { ...options, headers, credentials: 'same-origin', cache: 'no-store',
    body: options.body === undefined ? undefined : JSON.stringify(options.body) })
  if (epoch !== generation) throw new Error('账户已变化，本次旧请求已丢弃')
  if (!response.ok) {
    if (response.status === 401) clearSession()
    if (response.status === 403) csrf = null
    const detail = await response.json().catch(() => ({}))
    const error = new Error(detail.message || `请求未完成 (${response.status})`)
    error.status = response.status
    throw error
  }
  if (response.status === 204) return null
  const text = await response.text()
  if (epoch !== generation) throw new Error('账户已变化，本次旧请求已丢弃')
  return text ? JSON.parse(text) : null
}
export async function restoreSession() {
  const epoch = generation
  try { const user = await api('/auth/current'); if (epoch === generation) session.user = user } catch { if (epoch === generation) session.user = null }
  session.ready = true
}
export async function login(body) {
  clearSession()
  session.user = await api('/auth/login', { method: 'POST', body })
  csrf = null
  authChannel?.postMessage({ type: 'identity-changed' })
  return session.user
}
export async function logout() {
  await api('/auth/logout', { method: 'POST' })
  clearSession()
}
export async function sendTurn(id, body, onEvent, controller) {
  const epoch = generation
  const token = await csrfToken()
  if (epoch !== generation) throw new Error('账户已变化，本次旧请求已丢弃')
  streams.add(controller)
  try {
    const response = await fetch(`/api/chat/conversations/${id}/turns`, {
      method: 'POST', credentials: 'same-origin', signal: controller.signal,
      headers: { 'Content-Type': 'application/json', [token.headerName]: token.token }, body: JSON.stringify(body)
    })
    if (epoch !== generation) throw new Error('账户已变化，本次旧请求已丢弃')
    if (!response.ok) {
      if (response.status === 401) clearSession()
      if (response.status === 403) csrf = null
      const error = await response.json().catch(() => ({}))
      throw new Error(error.message || `发送失败 (${response.status})`)
    }
    const { parseSSE } = await import('./sse.js')
    await parseSSE(response.body, onEvent)
  } finally { streams.delete(controller) }
}
