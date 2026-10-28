<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, session, logout, sendTurn } from './api'
import { createUuid } from './uuid.js'
import { createScrollFollower } from './scroll-follow.js'
import MarkdownMessage from './MarkdownMessage.vue'
const router = useRouter()
const conversations = ref([]), messages = ref([]), current = ref(''), input = ref(''), busy = ref(false)
const error = ref(''), mobile = ref(false), panel = ref(''), memories = ref([]), artifacts = ref([]), users = ref([])
const controls = reactive({ love: false, task: false, web: false })
const query = ref(''), taskInput = ref(''), grants = ref([]), selected = ref([]), profile = ref({})
const scroll = ref(null), activeRun = ref(''), live = ref(null)
const follow = reactive(createScrollFollower())
let touchY = null
function onTouchStart(event) { touchY = event.touches[0]?.clientY ?? null }
function onTouchMove(event) {
  const y = event.touches[0]?.clientY
  if (touchY !== null && y > touchY) follow.pause()
  touchY = y ?? null
}
function latest() { follow.resume(); bottom() }
let controller = null, pollTimer = null, disposed = false, starting = false
const labels = { love: '恋爱咨询', task: '任务执行', web: '联网搜索' }
const sourceLabel = fact => fact.sourceType === 'USER_EDIT' ? `用户编辑（关联原消息 ${fact.sourceMessageId}）` : `用户消息 ${fact.sourceMessageId}`
const displayName = computed(() => session.user?.username || '你')
const statusLabels = { RUNNING: '执行中', STREAMING: '生成中', COMPLETED: '已完成', PARTIAL: '部分完成', FAILED: '未完成', STOPPED: '已停止', INTERRUPTED: '连接中断', TIMED_OUT: '已超时' }
const showError = e => { error.value = e.message || String(e); if (!session.user) router.replace('/login') }
async function refresh() { conversations.value = await api('/chat/conversations') }
async function activate(item) {
  if (busy.value) return
  follow.resume()
  clearTimeout(pollTimer); current.value = item.id; mobile.value = false; grants.value = []; selected.value = []; query.value = ''; taskInput.value = ''
  Object.assign(controls, { love: item.love, task: item.task, web: item.web })
  await loadMessages()
  const running = messages.value.find(m => m.status === 'STREAMING')
  if (running) { activeRun.value = running.run_id; busy.value = true; poll() }
}
async function loadMessages() {
  if (!current.value) { messages.value = []; return }
  const id = current.value
  const result = await api(`/chat/conversations/${id}/messages`)
  const saved = result.some(m => m.events?.some(e => e.type === 'memory_candidate')) ? await api('/memories') : []
  for (const m of result) {
    Object.assign(m, { steps: [], sources: [], artifacts: [], candidates: [], notices: [], context: [] })
    for (const e of m.events || []) {
      if (e.type === 'tool') m.steps.push(e.data)
      if (e.type === 'sources') m.sources.push(...e.data)
      if (e.type === 'artifact') m.artifacts.push(e.data)
      if (e.type === 'memory_candidate') { const fact = saved.find(f => f.id === e.data.id); if (fact) m.candidates.push(fact) }
      if (e.type === 'notice' || e.type === 'error') m.notices.push(e.data.message)
      if (e.type === 'context_required') m.context = e.data.messages
    }
  }
  if (id === current.value && !disposed) { messages.value = result; await bottom() }
}
async function newChat() {
  try { if (busy.value) return; const item = await api('/chat/conversations', { method: 'POST' }); await refresh(); await activate(item) } catch (e) { showError(e) }
}
async function remove(item) {
  if (busy.value || !confirm(`删除“${item.title}”？由此会话产生的记忆与文件也会删除。`)) return
  try { await api(`/chat/conversations/${item.id}`, { method: 'DELETE' }); await refresh(); if (current.value === item.id) {
    current.value = ''; messages.value = []; Object.assign(controls, { love: false, task: false, web: false }); if (conversations.value.length) await activate(conversations.value[0])
  } } catch (e) { showError(e) }
}
async function bottom() {
  await nextTick()
  // Recheck after DOM updates: a wheel/touch event may have paused following.
  if (follow.following && scroll.value) scroll.value.scrollTo({ top: scroll.value.scrollHeight, behavior: 'instant' })
}
async function send() {
  const text = input.value.trim(); if (!text || busy.value || starting) return
  const snapshot = { ...controls }, approvedQuery = query.value, approvedTask = taskInput.value, approvedGrants = [...grants.value]
  starting = true
  error.value = ''
  try {
    if (!current.value) await newChat()
    if (!current.value) return
    Object.assign(controls, snapshot)
    const id = current.value
    const requestId = createUuid(), messageId = createUuid()
    follow.resume()
    input.value = ''; busy.value = true; activeRun.value = ''; controller = new AbortController()
    query.value = ''; taskInput.value = ''; grants.value = []; selected.value = []
    messages.value.push({ id: messageId, role: 'USER', content: text, ...snapshot })
    live.value = reactive({ role: 'ASSISTANT', content: '', status: 'STREAMING', ...snapshot, steps: [], sources: [], artifacts: [], candidates: [], notices: [], context: [] })
    let done = false
    await sendTurn(id, { requestId, message: text, capabilities: snapshot, contextGrants: approvedGrants,
      reviewedSearchQuery: approvedQuery || null, taskInput: approvedTask || null }, (type, event) => {
      activeRun.value = event.runId
      const data = event.data
      if (!live.value || disposed || !session.user) return
      if (type === 'answer_delta') live.value.content += data.text
      if (type === 'tool') live.value.steps.push(data)
      if (type === 'sources') live.value.sources.push(...data)
      if (type === 'artifact') live.value.artifacts.push(data)
      if (type === 'memory_candidate') live.value.candidates.push(data)
      if (type === 'notice' || type === 'error') live.value.notices.push(data.message)
      if (type === 'context_required') live.value.context = data.messages
      if (type === 'done') { done = true; live.value.status = data.status || 'COMPLETED' }
      bottom()
    }, controller)
    if (!done) throw new Error('连接中断，正在读取服务端任务状态')
    messages.value.push(live.value); live.value = null; busy.value = false
    grants.value = []; selected.value = []; await refresh()
  } catch (e) {
    if (live.value) { live.value.status = 'INTERRUPTED'; messages.value.push(live.value); live.value = null }
    if (session.user) { error.value = e.message; if (activeRun.value) poll(); else busy.value = false }
    else { busy.value = false; router.replace('/login') }
  } finally { starting = false }
}
async function poll() {
  if (disposed || !session.user || !activeRun.value) return
  try {
    const state = await api(`/chat/runs/${activeRun.value}`)
    await loadMessages()
    if (state.status === 'RUNNING') { pollTimer = setTimeout(poll, 1500); return }
    busy.value = false; await refresh()
  } catch (e) { busy.value = false; showError(e) }
}
async function stop() { try { if (activeRun.value) await api(`/chat/runs/${activeRun.value}/stop`, { method: 'POST' }) } catch (e) { showError(e) } }
async function openPanel(name) {
  panel.value = name; error.value = ''
  try {
    if (name === 'memory') memories.value = await api('/memories')
    if (name === 'files') artifacts.value = await api('/artifacts')
    if (name === 'users') users.value = await api('/admin/users')
    if (name === 'profile') profile.value = { ...session.user }
  } catch (e) { showError(e) }
}
async function confirmMemory(item) { try { await api(`/memory-candidates/${item.id}/confirm`, { method: 'POST', body: { version: item.version } }); item.status = 'ACTIVE'; item.version++; if (panel.value === 'memory') await openPanel('memory') } catch (e) { showError(e) } }
async function revoke(item) { try { await api(`/memories/${item.id}?version=${item.version}`, { method: 'DELETE' }); item.status = 'REVOKED'; if (panel.value === 'memory') await openPanel('memory') } catch (e) { showError(e) } }
async function editMemory(item) {
  const value = prompt('编辑内容（保存即确认本次更新）：', item.value); if (!value?.trim()) return
  try { await api(`/memories/${item.id}`, { method: 'PATCH', body: { version: item.version, value, scope: item.scope } }); await openPanel('memory') } catch (e) { showError(e) }
}
async function transfer(msg) {
  try {
    grants.value = []
    for (const id of selected.value) { const result = await api(`/chat/conversations/${current.value}/context-grants`, { method: 'POST', body: { messageId: id } }); grants.value.push(result.id) }
    if (grants.value.length) { input.value = messages.value.filter(m => m.role === 'USER').at(-1)?.content || ''; msg.context = []; error.value = '已选择本轮上下文。确认输入内容后再次发送；授权10分钟内有效且只用一次。' }
  } catch (e) { showError(e) }
}
async function saveProfile() { try { session.user = await api('/user/profile', { method: 'PATCH', body: profile.value }); panel.value = '' } catch (e) { showError(e) } }
async function setEnabled(user) { try { await api(`/admin/users/${user.id}`, { method: 'PATCH', body: { enabled: !user.enabled } }); await openPanel('users') } catch (e) { showError(e) } }
async function deleteFile(file) { if (!confirm('删除此文件？')) return; try { await api(`/artifacts/${file.id}`, { method: 'DELETE' }); await openPanel('files') } catch (e) { showError(e) } }
async function exit() { controller?.abort(); try { await logout(); router.replace('/login') } catch (e) { showError(e) } }
function reset() { controller?.abort(); clearTimeout(pollTimer); messages.value = []; conversations.value = []; memories.value = []; artifacts.value = []; users.value = []; live.value = null; current.value = ''; profile.value = {}; query.value = ''; taskInput.value = ''; grants.value = []; selected.value = []; panel.value = ''; activeRun.value = ''; busy.value = false }
onMounted(async () => { window.addEventListener('fan-session-cleared', reset); try { await refresh(); if (conversations.value.length) await activate(conversations.value[0]) } catch (e) { showError(e) } })
onBeforeUnmount(() => { disposed = true; reset(); window.removeEventListener('fan-session-cleared', reset) })
</script>

<template>
  <div class="workspace">
    <aside class="sidebar" :class="{ open: mobile }">
      <RouterLink class="brand" to="/chat">✳ FanAI <span class="version">WORKSPACE</span></RouterLink>
      <button class="new-chat" :disabled="busy" @click="newChat"><span>＋</span> 新建对话 <kbd>NEW</kbd></button>
      <span class="section-label">你的对话</span>
      <div class="conversation-list"><div v-for="item in conversations" :key="item.id" class="conversation-row" :class="{ active: current === item.id }"><button :disabled="busy" @click="activate(item).catch(showError)"><span>◌</span>{{ item.title }}</button><button class="delete-icon" :disabled="busy" aria-label="删除对话" @click="remove(item)">×</button></div><p v-if="!conversations.length" class="muted small">从一个新想法开始。</p></div>
      <div class="sidebar-bottom"><button @click="openPanel('memory')">◈ 用户记忆 <span>由你确认</span></button><button @click="openPanel('files')">▱ 我的文件</button><button v-if="session.user?.role === 'ADMIN'" @click="openPanel('users')">⚙ 用户管理</button><div class="account-row"><button class="account" @click="openPanel('profile')"><span class="avatar">{{ displayName.slice(0, 1) }}</span><span>{{ displayName }}<small>个人空间</small></span></button><button title="退出登录" @click="exit">↪</button></div></div>
    </aside>
    <button v-if="mobile" class="sidebar-backdrop" aria-label="关闭侧栏" @click="mobile = false"></button>
    <main class="chat-main">
      <header class="topbar"><button class="mobile-toggle" @click="mobile = !mobile">☰</button><div><strong>对话空间</strong><span>一个输入框，按需组合能力</span></div><div class="private-badge"><i></i> 私人会话</div></header>
      <div ref="scroll" class="message-scroll" aria-live="polite" tabindex="0" @scroll.passive="follow.onScroll($event.target)" @wheel.passive="follow.onWheel($event)" @keydown="follow.onKey($event)" @touchstart.passive="onTouchStart" @touchmove.passive="onTouchMove">
        <section v-if="!messages.length && !live" class="welcome"><div class="welcome-symbol">✳</div><span class="eyebrow">A LITTLE CLARITY. A NEXT STEP.</span><h1>{{ displayName }}，今天想聊些什么？</h1><p>聊一个想法，解开一个困惑，或把计划付诸行动。<br>下方能力可以自由组合，也可以全部关闭。</p><div class="starter-grid"><button @click="input = '你能帮我做些什么？'"><span>✧</span><strong>理清一个想法</strong><small>从自然交流开始</small></button><button @click="controls.love = true; input = '如何更自然地表达我的感受？'"><span>♡</span><strong>聊聊关系与感受</strong><small>开启恋爱咨询</small></button><button @click="controls.task = true; input = '生成一份周末安排计划文件'"><span>↗</span><strong>把想法变成计划</strong><small>开启任务执行</small></button></div><div class="memory-explainer">◈ 你说的话不会自动成为长期记忆；保存之前，我们会问你。</div></section>
        <div class="message-column">
          <article v-for="(msg, index) in [...messages, ...(live ? [live] : [])]" :key="msg.id || index" class="chat-message" :class="msg.role === 'USER' ? 'from-user' : 'from-ai'">
            <div v-if="msg.role !== 'USER'" class="ai-mark">✳</div><div class="message-body">
              <div v-if="msg.role === 'USER'" class="mode-tags"><span v-for="(label, key) in labels" v-show="msg[key]" :key="key">{{ label }}</span></div>
              <p v-if="msg.role === 'USER'" class="user-text">{{ msg.content }}</p><MarkdownMessage v-else :content="msg.content" />
              <div v-if="msg.status === 'STREAMING' && !msg.content" class="working"><span class="pulse"></span>正在处理本轮请求…</div>
              <details v-if="msg.steps?.length" class="progress"><summary>执行活动 · {{ msg.steps.length }} 条</summary><p v-for="(step, i) in msg.steps" :key="i">{{ step.status === 'completed' ? '✓' : step.status === 'failed' ? '!' : '·' }} {{ step.name }} · {{ step.status }}</p></details>
              <div v-if="msg.sources?.length" class="sources"><a v-for="(source, i) in msg.sources" :key="i" :href="/^https?:\/\//.test(source.url) ? source.url : undefined" target="_blank" rel="noopener noreferrer">{{ i + 1 }}. {{ source.title }}</a></div>
              <div v-for="file in msg.artifacts || []" :key="file.id" class="artifact-card"><span>▱ {{ file.name }}</span><a :href="`/api/artifacts/${file.id}/download`">下载 ↗</a></div>
              <div v-for="candidate in msg.candidates || []" :key="candidate.id" class="memory-card"><span class="eyebrow">◈ 要保存这条记忆吗？</span><p>{{ candidate.value }}</p><small>{{ candidate.subject }} · {{ candidate.scope }} · {{ candidate.kind }} · {{ sourceLabel(candidate) }}</small><div v-if="candidate.status === 'PENDING'"><button @click="confirmMemory(candidate)">确认保存</button><button class="text-button" @click="revoke(candidate)">忽略</button></div><small v-else>{{ candidate.status === 'ACTIVE' ? '已保存' : '已忽略' }}</small></div>
              <div v-for="notice in msg.notices || []" :key="notice" class="notice">{{ notice }}</div>
              <div v-if="msg.context?.length" class="memory-card"><strong>选择本轮带入的上下文</strong><p class="small">仅带入你选中的用户原话，不共享其他恋爱历史。</p><label v-for="context in msg.context" :key="context.id" class="checkbox-line"><input v-model="selected" type="checkbox" :value="context.id" />{{ context.content }}</label><button :disabled="!selected.length" @click="transfer(msg)">确认带入</button></div>
              <small v-if="msg.role !== 'USER' && msg.status" class="message-status">{{ statusLabels[msg.status] || msg.status }}</small>
            </div>
          </article>
        </div>
      </div>
      <footer class="composer-area"><button v-if="!follow.following" class="jump-latest" type="button" @click="latest">↓ 回到最新消息</button><div v-if="error" class="error banner" role="alert">{{ error }}<button @click="error = ''">×</button></div><div class="composer"><textarea v-model="input" aria-label="聊天消息" placeholder="发送消息，开始新的可能…" rows="2" maxlength="12000" @keydown.enter.exact.prevent="send"></textarea><div v-if="controls.web" class="optional-input"><label>对外检索词<input v-model="query" maxlength="200" placeholder="可选；填写即确认将这些词发送给搜索服务。留空自动从本轮问题提取检索词。" /></label></div><details v-if="controls.task" class="task-settings"><summary>工具内容边界</summary><textarea v-model="taskInput" maxlength="6000" placeholder="可选：明确允许本轮工具使用的内容。留空自动根据聊天请求选择工具；补充内容仅用于约束。" rows="2"></textarea></details><div class="composer-bottom"><div class="capabilities"><button v-for="(label, key) in labels" :key="key" type="button" :class="{ selected: controls[key] }" :aria-pressed="controls[key]" @click="controls[key] = !controls[key]"><span>{{ key === 'love' ? '♡' : key === 'task' ? '↗' : '◎' }}</span>{{ label }}</button></div><button v-if="busy" class="send-button" aria-label="停止生成" @click="stop">■</button><button v-else class="send-button" :disabled="!input.trim()" aria-label="发送消息" @click="send">↑</button></div></div><p class="composer-note">{{ busy ? '能力切换仅影响下一轮。任务结束前不会重复发送。' : 'Enter 发送 · Shift + Enter 换行　｜　AI 回答可能有误，重要信息请核实。' }}</p></footer>
    </main>
    <div v-if="panel" class="modal-backdrop" @click.self="panel = ''"><section class="modal"><header><h2>{{ { memory: '用户记忆', files: '我的文件', profile: '个人资料', users: '用户管理' }[panel] }}</h2><button aria-label="关闭" @click="panel = ''">×</button></header><p v-if="error" class="error">{{ error }}</p>
      <template v-if="panel === 'memory'"><p class="muted">确认后跨会话使用。临时限制优先于一般偏好；情感内容按作用域隔离。删除记忆不删除聊天原文，删除来源会话会撤销相应记忆。</p><p v-if="!memories.length" class="empty-state">这里还没有记忆。可在对话中明确说“记住：…”并确认。</p><div v-for="item in memories" :key="item.id" class="memory-card"><p>{{ item.value }}</p><small>{{ item.subject }} · {{ item.scope }} · {{ item.kind }} · {{ item.status === 'ACTIVE' ? '已确认' : '待确认' }}<br>来源会话 {{ item.sourceConversationId }} · {{ sourceLabel(item) }}{{ item.expiresAt ? ' · 期限 ' + item.expiresAt : '' }}</small><div><button v-if="item.status === 'PENDING' && item.kind !== 'TASK'" @click="confirmMemory(item)">确认</button><button v-if="item.kind !== 'RETRACT'" class="text-button" @click="editMemory(item)">编辑并确认</button><button class="text-button" @click="revoke(item)">删除</button></div></div></template>
      <template v-if="panel === 'files'"><p class="muted">只有你的账户可以访问。文件默认最多10MB，总空间50MB。</p><p v-if="!artifacts.length" class="empty-state">尚无文件，开启任务执行即可创建。</p><div v-for="file in artifacts" :key="file.id" class="artifact-card"><div class="artifact-info">{{ file.name }}<small>{{ Math.ceil(file.size_bytes / 1024) }} KB</small></div><div class="artifact-actions"><a class="artifact-action" :href="`/api/artifacts/${file.id}/download`">下载</a><button class="artifact-action" @click="deleteFile(file)">删除</button></div></div></template>
      <form v-if="panel === 'profile'" class="profile-form" @submit.prevent="saveProfile"><label>账号<input :value="session.user.account" disabled /></label><label>昵称<input v-model="profile.username" required maxlength="50" /></label><label>邮箱<input v-model="profile.email" type="email" maxlength="512" /></label><label>电话<input v-model="profile.phone" maxlength="128" /></label><label>头像地址<input v-model="profile.avatarUrl" maxlength="1024" /></label><label>性别<select v-model="profile.gender"><option :value="null">未设置</option><option :value="0">保密</option><option :value="1">男</option><option :value="2">女</option></select></label><label class="checkbox-line"><input v-model="profile.memoryEnabled" type="checkbox" />从明确陈述生成待确认记忆；关闭不删除已有记忆</label><button class="primary">保存资料</button></form>
      <template v-if="panel === 'users'"><p class="muted">仅管理账户状态，不显示私人聊天和记忆。</p><div v-for="user in users" :key="user.id" class="artifact-card"><div>{{ user.username }}<small>{{ user.account }} · {{ user.role }}</small></div><button :disabled="user.id === session.user.id" @click="setEnabled(user)">{{ user.enabled ? '停用' : '启用' }}</button></div></template>
    </section></div>
  </div>
</template>
