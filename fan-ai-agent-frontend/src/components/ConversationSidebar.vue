<template>
  <aside class="conversation-sidebar" :class="{ open: mobileOpen }">
    <div class="sidebar-header">
      <strong>历史对话</strong>
      <button class="new-chat-button" :disabled="busy" @click="$emit('create')">＋ 新建</button>
    </div>
    <div v-if="loading" class="sidebar-empty">正在加载…</div>
    <div v-else-if="!conversations.length" class="sidebar-empty">还没有历史对话</div>
    <div v-else class="conversation-list">
      <button
        v-for="item in conversations"
        :key="item.id"
        class="conversation-item"
        :class="{ active: item.id === activeId }"
        :disabled="busy && item.id !== activeId"
        @click="$emit('select', item.id)"
      >
        <span class="conversation-title">{{ item.title }}</span>
        <span class="conversation-meta">{{ formatDate(item.lastMessageAt) }}</span>
        <span class="delete-chat" title="删除会话" @click.stop="$emit('delete', item)">×</span>
      </button>
    </div>
  </aside>
</template>

<script setup>
defineProps({
  conversations: { type: Array, default: () => [] },
  activeId: { type: String, default: '' },
  loading: Boolean,
  busy: Boolean,
  mobileOpen: Boolean
})

defineEmits(['create', 'select', 'delete'])

const formatDate = value => {
  const date = new Date(value)
  const today = new Date()
  if (date.toDateString() === today.toDateString()) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}
</script>

<style scoped>
.conversation-sidebar {
  width: 250px;
  min-width: 250px;
  height: 100%;
  overflow: hidden;
  border: 1px solid #e2e6ee;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 8px 24px rgba(31, 45, 61, .05);
}
.sidebar-header { display: flex; align-items: center; justify-content: space-between; padding: 14px; border-bottom: 1px solid #edf0f5; }
.new-chat-button { padding: 7px 10px; border: 0; border-radius: 8px; color: #fff; background: #5269c7; cursor: pointer; }
.new-chat-button:disabled { opacity: .5; cursor: default; }
.conversation-list { height: calc(100% - 59px); padding: 8px; overflow-y: auto; box-sizing: border-box; }
.conversation-item { position: relative; display: flex; width: 100%; flex-direction: column; gap: 5px; padding: 11px 32px 11px 12px; margin-bottom: 5px; border: 0; border-radius: 10px; color: #3d4350; background: transparent; text-align: left; cursor: pointer; }
.conversation-item:hover { background: #f2f4f9; }
.conversation-item.active { color: #30469f; background: #e9edff; }
.conversation-title { width: 100%; overflow: hidden; font-size: 14px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.conversation-meta { color: #9097a7; font-size: 11px; }
.delete-chat { position: absolute; top: 50%; right: 10px; display: none; width: 20px; height: 20px; align-items: center; justify-content: center; border-radius: 50%; color: #8a91a0; font-size: 18px; transform: translateY(-50%); }
.conversation-item:hover .delete-chat, .conversation-item.active .delete-chat { display: flex; }
.delete-chat:hover { color: #c43f4d; background: #fff; }
.sidebar-empty { padding: 30px 12px; color: #8a91a0; font-size: 13px; text-align: center; }

@media (max-width: 760px) {
  .conversation-sidebar { position: fixed; top: 58px; bottom: 0; left: 0; z-index: 30; height: auto; border-radius: 0 14px 14px 0; transform: translateX(-105%); transition: transform .2s ease; }
  .conversation-sidebar.open { transform: translateX(0); }
}
</style>
