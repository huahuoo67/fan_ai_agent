<template>
  <div class="super-agent-container">
    <header class="header">
      <button class="header-button" @click="goBack">← 返回</button>
      <h1 class="title">AI超级智能体</h1>
      <button class="header-button history-button" @click="sidebarOpen = !sidebarOpen">历史对话</button>
    </header>
    <main class="workspace">
      <ConversationSidebar :conversations="conversations" :active-id="chatId" :loading="loadingConversations"
        :busy="isBusy" :mobile-open="sidebarOpen" @create="newConversation" @select="selectConversation" @delete="removeConversation" />
      <div v-if="sidebarOpen" class="sidebar-mask" @click="sidebarOpen = false"></div>
      <section class="chat-area">
        <ChatRoom :messages="messages" :connection-status="connectionStatus" ai-type="super"
          @send-message="sendMessage" @stop-generation="stopGeneration" />
      </section>
    </main>
    <div class="footer-container"><AppFooter /></div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import ConversationSidebar from '../components/ConversationSidebar.vue'
import AppFooter from '../components/AppFooter.vue'
import {
  checkHealth, chatWithManus, stopManus, createConversation, deleteConversation,
  getConversationMessages, listConversations
} from '../api'

useHead({ title: 'AI超级智能体 - FanAI超级智能体应用平台' })
const router = useRouter()
const route = useRoute()
const messages = ref([])
const conversations = ref([])
const chatId = ref('')
const connectionStatus = ref('checking')
const loadingConversations = ref(true)
const sidebarOpen = ref(false)
let eventSource = null
let activeTurn = null
let stopFallbackTimer = null
let disposed = false
const isBusy = computed(() => ['checking', 'connecting', 'connected', 'reconnecting', 'stopping'].includes(connectionStatus.value))

const addMessage = (content, isUser, type = '', time = Date.now()) => {
  const message = { content, isUser, type, time }
  messages.value.push(message)
  return message
}
const historicalTurn = item => ({
  isUser: false, type: 'agent-turn', time: new Date(item.createdAt).getTime(),
  status: item.status === 'COMPLETED' ? 'completed' : 'error', showDetails: false,
  steps: [], answer: item.content, error: item.status === 'FAILED' ? '任务执行失败' : null,
  notice: null, retryable: false, receivedEvent: true
})
const createAgentTurn = () => {
  const turn = { isUser:false, type:'agent-turn', time:Date.now(), status:'running', showDetails:true,
    steps:[], answer:'', error:null, notice:null, retryable:false, receivedEvent:false }
  messages.value.push(turn)
  return turn
}
const finishRunningSteps = (turn, status = 'success') => {
  turn.steps.filter(item => item.status === 'running').forEach(item => { item.status = status })
}
const upsertStep = (turn, event) => {
  if (event.type === 'thinking') {
    finishRunningSteps(turn)
    turn.steps.push({ key:`thinking-${event.step}-${turn.steps.length}`, type:'thinking', title:event.content, status:event.status || 'running', step:event.step })
    return
  }
  const key = `tool-${event.step}-${event.toolName}`
  const existing = turn.steps.find(item => item.key === key)
  if (existing) { existing.title = event.content; existing.status = event.status }
  else turn.steps.push({ key, type:'tool', title:event.content, status:event.status, step:event.step, toolName:event.toolName })
}
const closeConnection = () => { eventSource?.close(); eventSource = null }
const refreshConversations = async () => { conversations.value = await listConversations('MANUS') }
const loadMessages = async id => {
  const stored = await getConversationMessages(id)
  messages.value = stored.map(item => item.role === 'USER'
    ? { id:item.id, content:item.content, isUser:true, type:'user-question', time:new Date(item.createdAt).getTime() }
    : historicalTurn(item))
}

const activateConversation = async (id, replace = false) => {
  if (!id || (id === chatId.value && messages.value.length)) return
  closeConnection()
  chatId.value = id
  messages.value = []
  activeTurn = null
  sidebarOpen.value = false
  await loadMessages(id)
  connectionStatus.value = 'disconnected'
  const navigation = { name:'SuperAgent', params:{ conversationId:id } }
  await (replace ? router.replace(navigation) : router.push(navigation))
}
const newConversation = async () => {
  if (isBusy.value) return
  const created = await createConversation('MANUS')
  await refreshConversations()
  await activateConversation(created.id)
}
const selectConversation = id => { if (!isBusy.value) activateConversation(id) }
const removeConversation = async item => {
  if (isBusy.value || !confirm(`确定删除“${item.title}”吗？`)) return
  await deleteConversation(item.id)
  await refreshConversations()
  if (item.id !== chatId.value) return
  let target = conversations.value[0]
  if (!target) { target = await createConversation('MANUS'); await refreshConversations() }
  await activateConversation(target.id, true)
}

const handleAgentEvent = (turn, event) => {
  switch (event.type) {
    case 'thinking': case 'tool': upsertStep(turn, event); break
    case 'answer': turn.answer += event.content || ''; break
    case 'error':
      turn.error = event.content || '任务执行失败'; turn.retryable = Boolean(event.retryable); turn.status = 'error'; break
    case 'stopped':
      finishRunningSteps(turn, 'stopped'); turn.status = 'stopped'; turn.notice = event.content || '任务已停止，可以继续当前会话'; turn.error = null; break
    case 'done':
      clearTimeout(stopFallbackTimer)
      if (event.status === 'stopped' || turn.status === 'stopped') { turn.status = 'stopped'; finishRunningSteps(turn, 'stopped') }
      else { turn.status = event.success ? 'completed' : 'error'; finishRunningSteps(turn, event.success ? 'success' : 'failed') }
      connectionStatus.value = 'disconnected'; closeConnection(); refreshConversations(); break
  }
}
const openConnection = (message, turn, attempt = 0) => {
  if (turn !== activeTurn || !['running', 'reconnecting'].includes(turn.status)) return
  closeConnection()
  connectionStatus.value = attempt === 0 ? 'connecting' : 'reconnecting'
  turn.status = attempt === 0 ? 'running' : 'reconnecting'
  eventSource = chatWithManus(message, chatId.value)
  eventSource.onopen = () => { connectionStatus.value = 'connected'; turn.status = 'running' }
  eventSource.onmessage = messageEvent => {
    turn.receivedEvent = true
    try { handleAgentEvent(turn, JSON.parse(messageEvent.data)) }
    catch {
      turn.error = '服务端返回的数据格式异常'; turn.status = 'error'; turn.retryable = true
      connectionStatus.value = 'error'; closeConnection()
    }
  }
  eventSource.onerror = () => {
    closeConnection()
    if (!turn.receivedEvent && attempt < 2 && turn === activeTurn) {
      turn.status = 'reconnecting'
      const retryStep = turn.steps.find(item => item.key === 'connection-retry')
      const title = `服务连接中，正在进行第 ${attempt + 1} 次重试`
      if (retryStep) retryStep.title = title
      else turn.steps.push({ key:'connection-retry', title, status:'running' })
      setTimeout(() => openConnection(message, turn, attempt + 1), 1000 * (attempt + 1))
      return
    }
    if (turn.status !== 'stopped') {
      stopManus(chatId.value).catch(() => {})
      turn.error = turn.receivedEvent ? '连接已中断，本轮任务已停止；你可以继续当前会话' : '后端服务连接失败，请确认服务已启动'
      turn.retryable = !turn.receivedEvent; turn.status = 'error'; finishRunningSteps(turn, 'failed'); connectionStatus.value = 'error'
    }
  }
}
const waitForBackend = async (timeoutMs = 60000) => {
  const deadline = Date.now() + timeoutMs
  while (!disposed && Date.now() < deadline) {
    try { await checkHealth(); return true } catch { await new Promise(resolve => setTimeout(resolve, 1000)) }
  }
  return false
}
const sendMessage = async message => {
  if (!chatId.value) await newConversation()
  closeConnection()
  addMessage(message, true, 'user-question')
  activeTurn = createAgentTurn()
  openConnection(message, activeTurn)
}
const stopGeneration = async () => {
  if (!activeTurn || !['running', 'reconnecting'].includes(activeTurn.status)) return
  activeTurn.status = 'stopping'; activeTurn.notice = '正在停止当前任务…'; connectionStatus.value = 'stopping'
  try {
    await stopManus(chatId.value)
    stopFallbackTimer = setTimeout(() => {
      closeConnection(); activeTurn.status = 'stopped'; activeTurn.notice = '任务已停止，可以继续当前会话'
      finishRunningSteps(activeTurn, 'stopped'); connectionStatus.value = 'disconnected'
    }, 10000)
  } catch {
    closeConnection(); activeTurn.status = 'stopped'; activeTurn.notice = '任务已停止，可以继续当前会话'
    finishRunningSteps(activeTurn, 'stopped'); connectionStatus.value = 'disconnected'
  }
}
const goBack = () => router.push('/')

onMounted(async () => {
  const ready = await waitForBackend()
  if (disposed) return
  if (!ready) { connectionStatus.value = 'error'; addMessage('后端启动等待超时，请检查后端启动日志。', false, 'ai-error'); return }
  try {
    await refreshConversations()
    let target = conversations.value.find(item => item.id === route.params.conversationId) || conversations.value[0]
    if (!target) { target = await createConversation('MANUS'); await refreshConversations() }
    await activateConversation(target.id, true)
  } finally { loadingConversations.value = false; if (connectionStatus.value === 'checking') connectionStatus.value = 'disconnected' }
})
onBeforeUnmount(() => { disposed = true; clearTimeout(stopFallbackTimer); closeConnection() })
</script>

<style scoped>
.super-agent-container { display:flex; height:100vh; min-height:0; flex-direction:column; padding-bottom:76px; box-sizing:border-box; overflow:hidden; background:#f6f8fc; text-align:left; }
.header { display:grid; grid-template-columns:1fr auto 1fr; align-items:center; padding:14px 22px; color:#fff; background:#3f51b5; box-shadow:0 2px 8px rgba(0,0,0,.1); }
.title { margin:0; font-size:20px; text-align:center; }
.header-button { justify-self:start; border:0; color:#fff; background:transparent; font-size:15px; cursor:pointer; }
.history-button { display:none; justify-self:end; }
.workspace { display:flex; flex:1; min-height:0; gap:12px; padding:12px 16px; }
.chat-area { flex:1; min-width:0; min-height:0; }
.chat-area :deep(.chat-container) { height:100%; min-height:0; max-width:none; }
.footer-container { position:fixed; right:0; bottom:0; left:0; z-index:20; height:76px; }
.footer-container :deep(.app-footer) { height:76px; margin:0; padding:7px 0 4px; box-sizing:border-box; overflow:hidden; }
.sidebar-mask { display:none; }
@media (max-width:760px) {
  .header { padding:12px; } .title { font-size:18px; } .history-button { display:block; } .workspace { padding:8px; }
  .sidebar-mask { position:fixed; inset:58px 0 0; z-index:25; display:block; background:rgba(22,28,40,.3); }
}
</style>