<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, login } from './api'
const route = useRoute(), router = useRouter()
const form = reactive({ account: '', password: '', username: '' })
const error = ref(''), busy = ref(false)
async function submit() {
  error.value = ''; busy.value = true
  try {
    if (route.name === 'register') await api('/auth/register', { method: 'POST', body: form })
    await login({ account: form.account, password: form.password })
    await router.replace('/chat')
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
</script>
<template>
  <main class="auth-screen">
    <section class="auth-intro"><div class="brand">✳ FanAI</div><h1>想法，在这里<br>变成下一步。</h1><p>一个对话空间，三种可组合能力。<br>由你决定怎么聊，记住什么。</p><div class="intro-tags"><span>恋爱咨询</span><span>任务执行</span><span>联网搜索</span></div></section>
    <form class="auth-form" @submit.prevent="submit"><span class="eyebrow">YOUR PERSONAL WORKSPACE</span><h2>{{ route.name === 'register' ? '创建你的账户' : '欢迎回来' }}</h2><p>登录后开启私人对话与可管理记忆。</p>
      <label v-if="route.name === 'register'">昵称<input v-model="form.username" required maxlength="50" autocomplete="nickname" placeholder="希望我们如何称呼你" /></label>
      <label>账号<input v-model="form.account" required pattern="[a-zA-Z0-9_]{4,18}" autocomplete="username" placeholder="4–18位字母、数字或下划线" /></label>
      <label>密码<input v-model="form.password" required minlength="8" maxlength="72" type="password" :autocomplete="route.name === 'register' ? 'new-password' : 'current-password'" placeholder="至少8位" /></label>
      <p v-if="error" class="error" role="alert">{{ error }}</p><button class="primary" :disabled="busy">{{ busy ? '处理中…' : route.name === 'register' ? '注册并进入' : '登录' }}</button>
      <p class="auth-switch"><template v-if="route.name === 'register'">已有账户？<RouterLink to="/login">去登录</RouterLink></template><template v-else>还没有账户？<RouterLink to="/register">创建账户</RouterLink></template></p>
      <small>聊天不会自动成为长期记忆。每一条保存都由你确认。</small>
    </form>
  </main>
</template>
