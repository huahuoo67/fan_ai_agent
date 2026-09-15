<template>
  <div class="super-agent-container">
    <div class="header">
      <div class="back-button" @click="goBack">返回</div>
      <h1 class="title">AI超级智能体</h1>
      <div class="placeholder"></div>
    </div>

    <main class="content-wrapper">
      <div class="chat-area">
        <ChatRoom
          :messages="messages"
          :connection-status="connectionStatus"
          ai-type="super"
          @send-message="sendMessage"
          @stop-generation="stopGeneration"
        />
      </div>
    </main>

    <div class="footer-container">
      <AppFooter />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import AppFooter from '../components/AppFooter.vue'
import { checkHealth, chatWithManus, stopManus } from '../api'

useHead({
  title: 'AI超级智能体 - FanAI超级智能体应用平台',
  meta: [{ name: 'description', content: '可以展示执行过程和最终回答的 AI 超级智能体' }]
})

const router = useRouter()
const messages = ref([])
const connectionStatus = ref('checking')
let eventSource = null
let activeTurn = null
let stopFallbackTimer = null
let disposed = false

const buildChatId = () =>
  globalThis.crypto?.randomUUID?.() || `chat-${Date.now()}-${Math.random().toString(16).slice(2)}`

const chatId = sessionStorage.getItem('fan-manus-chat-id') || buildChatId()
sessionStorage.setItem('fan-manus-chat-id', chatId)

const addMessage = (content, isUser, type = '') => {
  const message = { content, isUser, type, time: Date.now() }
  messages.value.push(message)
  return message
}

const createAgentTurn = () => {
  const turn = {
    isUser: false,
    type: 'agent-turn',
    time: Date.now(),
    status: 'running',
    showDetails: true,
    steps: [],
    answer: '',
    error: null,
    notice: null,
    retryable: false,
    receivedEvent: false
  }
  messages.value.push(turn)
  return turn
}

const finishRunningSteps = (turn, status = 'success') => {
  turn.steps
    .filter(item => item.status === 'running')
    .forEach(item => { item.status = status })
}

const upsertStep = (turn, event) => {
  if (event.type === 'thinking') {
    finishRunningSteps(turn)
    turn.steps.push({
      key: `thinking-${event.step}-${turn.steps.length}`,
      type: 'thinking',
      title: event.content,
      status: event.status || 'running',
      step: event.step
    })
    return
  }

  const key = `tool-${event.step}-${event.toolName}`
  const existing = turn.steps.find(item => item.key === key)
  if (existing) {
    existing.title = event.content
    existing.status = event.status
  } else {
    turn.steps.push({
      key,
      type: 'tool',
      title: event.content,
      status: event.status,
      step: event.step
    })
  }
}

const closeConnection = () => {
  eventSource?.close()
  eventSource = null
}

const handleAgentEvent = (turn, event) => {
  switch (event.type) {
    case 'thinking':
    case 'tool':
      upsertStep(turn, event)
      break
    case 'answer':
      turn.answer += event.content || ''
      break
    case 'error':
      turn.error = event.content || '任务执行失败'
      turn.retryable = Boolean(event.retryable)
      turn.status = 'error'
      break
    case 'stopped':
      finishRunningSteps(turn, 'stopped')
      turn.status = 'stopped'
      turn.notice = event.content || '任务已停止，可以继续当前会话'
      turn.error = null
      break
    case 'done':
      clearTimeout(stopFallbackTimer)
      if (event.status === 'stopped' || turn.status === 'stopped') {
        turn.status = 'stopped'
        finishRunningSteps(turn, 'stopped')
      } else {
        turn.status = event.success ? 'completed' : 'error'
        finishRunningSteps(turn, event.success ? 'success' : 'failed')
      }
      connectionStatus.value = 'disconnected'
      closeConnection()
      break
  }
}

const openConnection = (message, turn, attempt = 0) => {
  if (turn !== activeTurn || !['running', 'reconnecting'].includes(turn.status)) return

  closeConnection()
  connectionStatus.value = attempt === 0 ? 'connecting' : 'reconnecting'
  turn.status = attempt === 0 ? 'running' : 'reconnecting'
  eventSource = chatWithManus(message, chatId)

  eventSource.onopen = () => {
    connectionStatus.value = 'connected'
    turn.status = 'running'
  }

  eventSource.onmessage = messageEvent => {
    turn.receivedEvent = true
    try {
      handleAgentEvent(turn, JSON.parse(messageEvent.data))
    } catch (error) {
      turn.error = '服务端返回的数据格式异常'
      turn.status = 'error'
      turn.retryable = true
      connectionStatus.value = 'error'
      closeConnection()
    }
  }

  eventSource.onerror = () => {
    closeConnection()

    // 请求尚未到达后端时进行短暂重试，避免后端刚启动导致第一次提问直接失败。
    if (!turn.receivedEvent && attempt < 2 && turn === activeTurn) {
      turn.status = 'reconnecting'
      const retryStep = turn.steps.find(item => item.key === 'connection-retry')
      const title = `服务连接中，正在进行第 ${attempt + 1} 次重试`
      if (retryStep) {
        retryStep.title = title
      } else {
        turn.steps.push({ key: 'connection-retry', title, status: 'running' })
      }
      setTimeout(() => openConnection(message, turn, attempt + 1), 1000 * (attempt + 1))
      return
    }

    if (turn.status !== 'stopped') {
      stopManus(chatId).catch(() => {})
      turn.error = turn.receivedEvent
        ? '连接已中断，本轮任务已停止；你可以继续当前会话'
        : '后端服务连接失败，请确认服务已启动'
      turn.retryable = !turn.receivedEvent
      turn.status = 'error'
      finishRunningSteps(turn, 'failed')
      connectionStatus.value = 'error'
    }
  }
}

const waitForBackend = async (timeoutMs = 60000) => {
  const deadline = Date.now() + timeoutMs
  while (!disposed && Date.now() < deadline) {
    try {
      await checkHealth()
      return true
    } catch (error) {
      await new Promise(resolve => setTimeout(resolve, 1000))
    }
  }
  return false
}

const sendMessage = message => {
  closeConnection()
  addMessage(message, true, 'user-question')
  activeTurn = createAgentTurn()
  openConnection(message, activeTurn)
}

const stopGeneration = async () => {
  if (!activeTurn || !['running', 'reconnecting'].includes(activeTurn.status)) return

  activeTurn.status = 'stopping'
  activeTurn.notice = '正在停止当前任务…'
  connectionStatus.value = 'stopping'

  try {
    await stopManus(chatId)
    // 保持 SSE 打开，等待后端发回 stopped 和 done，避免产生连接中断异常。
    stopFallbackTimer = setTimeout(() => {
      closeConnection()
      activeTurn.status = 'stopped'
      activeTurn.notice = '任务已停止，可以继续当前会话'
      finishRunningSteps(activeTurn, 'stopped')
      connectionStatus.value = 'disconnected'
    }, 10000)
  } catch (error) {
    closeConnection()
    activeTurn.status = 'stopped'
    activeTurn.notice = '任务已停止，可以继续当前会话'
    finishRunningSteps(activeTurn, 'stopped')
    connectionStatus.value = 'disconnected'
  }
}

const goBack = () => router.push('/')

onMounted(async () => {
  addMessage('你好，我是AI超级智能体。你可以提出一个任务，我会展示执行过程并给出最终结果。', false)
  connectionStatus.value = 'checking'
  const ready = await waitForBackend()
  if (disposed) return
  connectionStatus.value = ready ? 'disconnected' : 'error'
  if (!ready) {
    addMessage('后端启动等待超时，请检查后端启动日志。', false, 'ai-error')
  }
})

onBeforeUnmount(() => {
  disposed = true
  clearTimeout(stopFallbackTimer)
  closeConnection()
})
</script>

<style scoped>
.super-agent-container {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100vh;
  min-height: 0;
  padding-bottom: 76px;
  box-sizing: border-box;
  overflow: hidden;
  background: #f6f8fc;
  text-align: left;
}

.header {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  padding: 16px 24px;
  color: #fff;
  background: #3f51b5;
  box-shadow: 0 2px 8px rgba(0, 0, 0, .1);
}

.back-button { cursor: pointer; justify-self: start; }
.back-button::before { content: '←'; margin-right: 8px; }
.title { margin: 0; font-size: 20px; text-align: center; }
.placeholder { width: 1px; }
.content-wrapper { display: flex; flex: 1; width: 100%; min-height: 0; }
.chat-area { flex: 1; min-height: 0; padding: 12px 16px; }
.chat-area :deep(.chat-container) { height: 100%; min-height: 0; }

.footer-container {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 20;
  height: 76px;
}

.footer-container :deep(.app-footer) {
  height: 76px;
  margin: 0;
  padding: 7px 0 4px;
  box-sizing: border-box;
  overflow: hidden;
}

.footer-container :deep(.footer-content) {
  max-width: 980px;
  flex-wrap: nowrap;
  align-items: center;
  padding: 0 16px;
}

.footer-container :deep(.footer-section) {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 14px;
  margin: 0;
  padding: 0 8px;
}

.footer-container :deep(.footer-logo h3),
.footer-container :deep(.footer-section h4) {
  margin: 0;
  font-size: 13px;
}

.footer-container :deep(.footer-links) {
  flex-direction: row;
  gap: 10px;
}

.footer-container :deep(.footer-links a) {
  margin: 0;
  font-size: 12px;
}

.footer-container :deep(.footer-bottom) {
  margin-top: 5px;
  padding-top: 4px;
  font-size: 11px;
}

.footer-container :deep(.footer-bottom p) {
  margin: 0;
}

@media (max-width: 768px) {
  .header { padding: 12px 16px; }
  .title { font-size: 18px; }
  .chat-area { padding: 8px; }
}
</style>




