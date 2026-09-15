<template>
  <div class="chat-container">
    <div ref="messagesContainer" class="chat-messages">
      <div v-for="(msg, index) in messages" :key="index" class="message-wrapper">
        <div v-if="msg.type === 'agent-turn'" class="message ai-message agent-message">
          <div class="avatar ai-avatar">
            <AiAvatarFallback :type="aiType" />
          </div>

          <section class="agent-card">
            <header class="agent-card-header">
              <div>
                <strong>AI 智能体</strong>
                <span class="agent-status" :class="msg.status">{{ statusText(msg.status) }}</span>
              </div>
              <button
                v-if="msg.steps.length"
                class="detail-button"
                @click="msg.showDetails = !msg.showDetails"
              >
                {{ msg.showDetails ? '收起过程' : '查看过程' }}
              </button>
            </header>

            <div
              v-if="msg.steps.length && msg.showDetails"
              class="agent-progress"
            >
              <div v-for="step in msg.steps" :key="step.key" class="progress-item">
                <span class="step-icon" :class="step.status">
                  {{ step.status === 'success' ? '✓' : step.status === 'failed' ? '!' : step.status === 'stopped' ? '■' : '●' }}
                </span>
                <div>
                  <div class="step-title">{{ step.title }}</div>
                  <div v-if="step.toolName" class="tool-name">{{ step.toolName }}</div>
                </div>
              </div>
            </div>

            <div v-if="msg.answer" class="final-answer">
              <div class="answer-label">最终回答</div>
              <div class="message-content formatted-answer" v-html="formatAnswer(msg.answer)"></div>
            </div>

            <div v-if="msg.error" class="agent-error">
              <strong>执行异常</strong>
              <span>{{ msg.error }}</span>
              <span v-if="msg.retryable" class="retry-hint">可以重新发送问题进行重试</span>
            </div>

            <div v-if="msg.notice" class="agent-notice">
              {{ msg.notice }}
            </div>

            <div v-if="msg.status === 'running' && !msg.answer" class="working-indicator">
              <span></span><span></span><span></span>
              智能体正在执行
            </div>

            <div class="message-time">{{ formatTime(msg.time) }}</div>
          </section>
        </div>

        <div v-else-if="!msg.isUser" class="message ai-message" :class="[msg.type]">
          <div class="avatar ai-avatar">
            <AiAvatarFallback :type="aiType" />
          </div>
          <div class="message-bubble">
            <div class="message-content">{{ msg.content }}</div>
            <div class="message-time">{{ formatTime(msg.time) }}</div>
          </div>
        </div>

        <div v-else class="message user-message" :class="[msg.type]">
          <div class="message-bubble">
            <div class="message-content">{{ msg.content }}</div>
            <div class="message-time">{{ formatTime(msg.time) }}</div>
          </div>
          <div class="avatar user-avatar"><div class="avatar-placeholder">我</div></div>
        </div>
      </div>
    </div>

    <div class="chat-input-container">
      <div class="chat-input">
        <textarea
          v-model="inputMessage"
          class="input-box"
          placeholder="请输入消息..."
          :disabled="isRunning"
          @keydown.enter.exact.prevent="sendMessage"
        ></textarea>
        <button v-if="connectionStatus === 'checking'" class="service-button" disabled>启动中</button>
        <button v-else-if="isRunning" class="stop-button" @click="$emit('stop-generation')">停止</button>
        <button
          v-else
          class="send-button"
          :disabled="!inputMessage.trim()"
          @click="sendMessage"
        >发送</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import AiAvatarFallback from './AiAvatarFallback.vue'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  connectionStatus: { type: String, default: 'disconnected' },
  aiType: { type: String, default: 'default' }
})

const emit = defineEmits(['send-message', 'stop-generation'])
const inputMessage = ref('')
const messagesContainer = ref(null)
const isRunning = computed(() => ['checking', 'connecting', 'connected', 'reconnecting', 'stopping'].includes(props.connectionStatus))

const sendMessage = () => {
  const value = inputMessage.value.trim()
  if (!value || isRunning.value) return
  emit('send-message', value)
  inputMessage.value = ''
}

const statusText = status => ({
  checking: '服务启动中',
  running: '执行中',
  reconnecting: '连接中',
  stopping: '停止中',
  completed: '已完成',
  error: '执行失败',
  stopped: '已停止'
}[status] || status)

const formatAnswer = value => {
  const escaped = String(value || '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;')

  return escaped
    .replace(/^### (.+)$/gm, '<h3>$1</h3>')
    .replace(/^## (.+)$/gm, '<h2>$1</h2>')
    .replace(/^# (.+)$/gm, '<h1>$1</h1>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\x60([^\x60]+)\x60/g, '<code>$1</code>')
    .replace(/\n/g, '<br>')
}

const formatTime = timestamp =>
  new Date(timestamp).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })

const scrollToBottom = async () => {
  await nextTick()
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

watch(() => props.messages.length, scrollToBottom)

watch(
  () => props.messages.map(message => [
    message.steps?.length || 0,
    message.answer?.length || 0,
    message.status || '',
    message.error || '',
    message.notice || ''
  ].join(':')).join('|'),
  scrollToBottom
)

onMounted(scrollToBottom)
</script>

<style scoped>
.chat-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 560px;
  max-width: 980px;
  margin: 0 auto;
  overflow: hidden;
  background: #fff;
  border: 1px solid #e7eaf0;
  border-radius: 14px;
  box-shadow: 0 8px 30px rgba(31, 45, 61, .07);
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 22px;
}

.message-wrapper { display: flex; flex-direction: column; width: 100%; margin-bottom: 18px; text-align: left; }
.message { display: flex; align-items: flex-start; max-width: 88%; }
.ai-message { align-self: flex-start; margin-right: auto; text-align: left; }
.user-message { align-self: flex-end; width: fit-content; margin-left: auto; text-align: left; }

.avatar {
  display: flex;
  flex: 0 0 36px;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 50%;
}
.ai-avatar { margin-right: 10px; }
.user-avatar { margin-left: 10px; }
.avatar-placeholder {
  display: flex;
  width: 100%;
  height: 100%;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: #3f51b5;
  font-weight: 700;
}

.message-bubble {
  min-width: 100px;
  padding: 11px 14px;
  border-radius: 16px;
  word-break: break-word;
}
.ai-message .message-bubble { background: #f0f2f6; border-bottom-left-radius: 4px; }
.user-message .message-bubble { color: #fff; background: #3f51b5; border-bottom-right-radius: 4px; }
.message-content { line-height: 1.65; white-space: pre-wrap; }
.message-time { margin-top: 7px; color: #8991a3; font-size: 12px; text-align: right; }
.user-message .message-time { color: rgba(255,255,255,.75); }

.agent-message { width: min(760px, calc(100% - 46px)); max-width: none; }
.agent-card {
  flex: 1;
  text-align: left;
  overflow: hidden;
  border: 1px solid #dfe4ef;
  border-radius: 14px;
  background: #fff;
}
.agent-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px 16px;
  border-bottom: 1px solid #edf0f5;
}
.agent-status {
  margin-left: 9px;
  padding: 3px 8px;
  border-radius: 10px;
  color: #3154b8;
  background: #edf2ff;
  font-size: 12px;
}
.agent-status.completed { color: #16794b; background: #e9f8f0; }
.agent-status.error, .agent-status.stopped { color: #b33939; background: #fff0f0; }
.detail-button {
  border: 0;
  color: #4965ba;
  background: transparent;
  cursor: pointer;
}
.agent-progress { padding: 14px 16px; background: #fafbfe; }
.progress-item { display: flex; gap: 10px; padding: 7px 0; }
.step-icon {
  display: inline-flex;
  flex: 0 0 20px;
  width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #fff;
  background: #6c83ca;
  font-size: 12px;
  animation: pulse 1.1s infinite;
}
.step-icon.success { background: #28a66a; animation: none; }
.step-icon.failed { background: #d44e4e; animation: none; }
.step-icon.stopped { background: #8a92a3; animation: none; }
.step-title { color: #3c4352; font-size: 14px; }
.tool-name { margin-top: 2px; color: #9299a8; font-size: 12px; }
.final-answer { padding: 17px 18px; text-align: left; }
.answer-label { margin-bottom: 9px; color: #27324a; font-size: 13px; font-weight: 700; }
.agent-error {
  text-align: left;
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin: 14px 16px;
  padding: 12px;
  color: #a23535;
  border-radius: 8px;
  background: #fff2f2;
  font-size: 14px;
}
.retry-hint { color: #8e6363; font-size: 12px; }
.agent-notice { margin: 14px 16px; padding: 12px; color: #586174; border-radius: 8px; background: #f1f3f7; text-align: left; }
.formatted-answer { white-space: normal; text-align: left; }
.formatted-answer :deep(h1) { font-size: 20px; }
.formatted-answer :deep(h2) { font-size: 18px; }
.formatted-answer :deep(h3) { font-size: 16px; }
.formatted-answer :deep(code) { padding: 2px 5px; border-radius: 4px; background: #eef1f6; }
.working-indicator { padding: 18px; color: #6f7787; font-size: 14px; }
.working-indicator span {
  display: inline-block;
  width: 5px;
  height: 5px;
  margin-right: 3px;
  border-radius: 50%;
  background: #5873c4;
  animation: bounce 1s infinite alternate;
}
.working-indicator span:nth-child(2) { animation-delay: .2s; }
.working-indicator span:nth-child(3) { animation-delay: .4s; }

.chat-input-container { flex: 0 0 52px; border-top: 1px solid #e7eaf0; background: #fff; }
.chat-input { display: flex; height: 52px; gap: 8px; padding: 7px 9px; box-sizing: border-box; }
.input-box {
  flex: 1;
  min-height: 36px;
  max-height: 36px;
  padding: 7px 11px;
  resize: none;
  box-sizing: border-box;
  border: 1px solid #d8dce5;
  border-radius: 12px;
  outline: 0;
  font: inherit;
}
.input-box:focus { border-color: #637ad0; box-shadow: 0 0 0 3px rgba(99,122,208,.12); }
.send-button, .stop-button, .service-button {
  min-width: 62px;
  border: 0;
  border-radius: 12px;
  color: #fff;
  background: #3f51b5;
  cursor: pointer;
}
.stop-button { background: #d04a4a; }
.service-button { color: #596170; background: #edf0f5; cursor: wait; }
.send-button:disabled { opacity: .5; cursor: default; }

@keyframes pulse { from { opacity: .5; } to { opacity: 1; } }
@keyframes bounce { from { transform: translateY(0); } to { transform: translateY(-4px); } }

@media (max-width: 600px) {
  .chat-container { height: 100%; min-height: 0; border-radius: 10px; }
  .chat-messages { padding: 14px 10px; }
  .message { max-width: 96%; }
  .agent-message { width: calc(100% - 42px); }
  .avatar { flex-basis: 32px; width: 32px; height: 32px; }
  .agent-card-header { padding: 11px 12px; }
}
</style>


