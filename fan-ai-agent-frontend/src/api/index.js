import axios from 'axios'

const API_BASE_URL = import.meta.env.PROD
  ? '/api'
  : 'http://localhost:8123/api'

const request = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000
})

export const connectSSE = (url, params, onMessage, onError) => {
  const queryString = Object.keys(params)
    .map(key => `${encodeURIComponent(key)}=${encodeURIComponent(params[key])}`)
    .join('&')

  const eventSource = new EventSource(`${API_BASE_URL}${url}?${queryString}`)

  eventSource.onmessage = event => {
    if (onMessage) onMessage(event.data)
  }

  eventSource.onerror = error => {
    if (onError) onError(error)
  }

  return eventSource
}

export const checkHealth = () =>
  request.get('/health', { timeout: 2500 })

export const chatWithLoveApp = (message, chatId) =>
  connectSSE('/ai/love_app/chat/sse', { message, chatId })

export const chatWithManus = (message, chatId) =>
  connectSSE('/ai/manus/chat', { message, chatId })

export const stopManus = chatId =>
  request.post('/ai/manus/chat/stop', null, { params: { chatId } })

export default {
  checkHealth,
  chatWithLoveApp,
  chatWithManus,
  stopManus
}
