<template>
  <div class="love-master-container">
    <header class="header">
      <button class="header-button" @click="goBack">← 返回</button>
      <h1 class="title">AI恋爱大师</h1>
      <button class="header-button history-button" @click="sidebarOpen = !sidebarOpen">历史对话</button>
    </header>
    <main class="workspace">
      <ConversationSidebar :conversations="conversations" :active-id="chatId" :loading="loadingConversations"
        :busy="isBusy" :mobile-open="sidebarOpen" @create="newConversation" @select="selectConversation" @delete="removeConversation" />
      <div v-if="sidebarOpen" class="sidebar-mask" @click="sidebarOpen = false"></div>
      <section class="chat-area">
        <ChatRoom :messages="messages" :connection-status="connectionStatus" ai-type="love" @send-message="sendMessage" />
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
import { chatWithLoveApp, createConversation, deleteConversation, getConversationMessages, listConversations } from '../api'

useHead({ title: 'AI恋爱大师 - FanAI超级智能体应用平台' })
const router = useRouter()
const route = useRoute()
const messages = ref([])
const conversations = ref([])
const chatId = ref('')
const connectionStatus = ref('disconnected')
const loadingConversations = ref(true)
const sidebarOpen = ref(false)
let eventSource = null
const isBusy = computed(() => ['connecting', 'connected'].includes(connectionStatus.value))

const closeConnection = () => { eventSource?.close(); eventSource = null }
const toUiMessage = item => ({ id: item.id, content: item.content, isUser: item.role === 'USER', type: item.status === 'FAILED' ? 'ai-error' : '', time: new Date(item.createdAt).getTime() })
const loadMessages = async id => { messages.value = (await getConversationMessages(id)).map(toUiMessage) }
const refreshConversations = async () => { conversations.value = await listConversations('LOVE') }

const activateConversation = async (id, replace = false) => {
  if (!id || (id === chatId.value && messages.value.length)) return
  closeConnection()
  connectionStatus.value = 'disconnected'
  chatId.value = id
  messages.value = []
  sidebarOpen.value = false
  await loadMessages(id)
  const navigation = { name: 'LoveMaster', params: { conversationId: id } }
  await (replace ? router.replace(navigation) : router.push(navigation))
}

const newConversation = async () => {
  if (isBusy.value) return
  const created = await createConversation('LOVE')
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
  if (!target) { target = await createConversation('LOVE'); await refreshConversations() }
  await activateConversation(target.id, true)
}

const sendMessage = async content => {
  if (!chatId.value) await newConversation()
  messages.value.push({ content, isUser: true, time: Date.now() })
  const aiMessage = { content: '', isUser: false, time: Date.now() }
  messages.value.push(aiMessage)
  closeConnection()
  connectionStatus.value = 'connecting'
  eventSource = chatWithLoveApp(content, chatId.value)
  eventSource.onopen = () => { connectionStatus.value = 'connected' }
  eventSource.onmessage = event => {
    if (event.data === '[DONE]') {
      connectionStatus.value = 'disconnected'; closeConnection(); refreshConversations(); return
    }
    aiMessage.content += event.data || ''
  }
  eventSource.onerror = () => {
    if (!aiMessage.content) aiMessage.content = '请求失败，请稍后重试。'
    connectionStatus.value = 'error'; closeConnection(); refreshConversations()
  }
}

const goBack = () => router.push('/')
onMounted(async () => {
  try {
    await refreshConversations()
    let target = conversations.value.find(item => item.id === route.params.conversationId) || conversations.value[0]
    if (!target) { target = await createConversation('LOVE'); await refreshConversations() }
    await activateConversation(target.id, true)
  } finally { loadingConversations.value = false }
})
onBeforeUnmount(closeConnection)
</script>

<style scoped>
.love-master-container { display:flex; height:100vh; min-height:0; flex-direction:column; padding-bottom:76px; box-sizing:border-box; overflow:hidden; background:#fff8fa; }
.header { display:grid; grid-template-columns:1fr auto 1fr; align-items:center; padding:14px 22px; color:#fff; background:#ff6b8b; box-shadow:0 2px 8px rgba(0,0,0,.1); }
.title { margin:0; font-size:20px; }
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