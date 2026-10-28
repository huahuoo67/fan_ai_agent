import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { api, clearSession, session, logout, csrfToken } from '../src/platform/api.js'
globalThis.window = new JSDOM('', { url: 'http://localhost' }).window
test('late old-account response is discarded without clearing the new identity', async () => {
  clearSession();session.user = { id: 'A' }
  let finish
  globalThis.fetch = () => new Promise(resolve => { finish = resolve })
  const pending = api('/chat/conversations')
  clearSession();session.user = { id: 'B' }
  finish(new Response(JSON.stringify([{ title: 'A的私密对话' }]), { status: 200 }))
  await assert.rejects(pending, /旧请求已丢弃/)
  assert.equal(session.user.id, 'B')
})
test('network failure during logout does not pretend the server session was invalidated', async () => {
  clearSession();session.user = { id: 'A' }
  globalThis.fetch = async () => { throw new Error('网络中断') }
  await assert.rejects(logout(), /网络中断/)
  assert.equal(session.user.id, 'A')
})
test('late CSRF initialization is also discarded after account change', async () => {
  clearSession()
  let finish
  globalThis.fetch = () => new Promise(resolve => { finish = resolve })
  const pending = csrfToken()
  clearSession();session.user = { id: 'B' }
  finish(new Response(JSON.stringify({ token: 'old-token', headerName: 'X-XSRF-TOKEN' }), { status: 200 }))
  await assert.rejects(pending, /旧请求已丢弃/)
  globalThis.fetch = async () => new Response(JSON.stringify({ token: 'new-token', headerName: 'X-XSRF-TOKEN' }), { status: 200 })
  assert.equal((await csrfToken()).token, 'new-token')
})
