import { createRouter, createWebHistory } from 'vue-router'
import { restoreSession, session } from '../platform/api'
const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/', redirect: '/chat' },
  { path: '/chat', name: 'chat', component: () => import('../platform/ChatPage.vue') },
  { path: '/login', name: 'login', component: () => import('../platform/AuthPage.vue'), meta: { public: true } },
  { path: '/register', name: 'register', component: () => import('../platform/AuthPage.vue'), meta: { public: true } },
  { path: '/:pathMatch(.*)*', redirect: '/chat' }
] })
router.beforeEach(async to => {
  if (!session.ready) await restoreSession()
  if (!to.meta.public && !session.user) return '/login'
  if (to.meta.public && session.user) return '/chat'
  document.title = 'FanAI · 对话空间'
})
window.addEventListener('fan-session-cleared', () => {
  if (router.currentRoute.value.name === 'chat') router.replace('/login')
})
export default router
