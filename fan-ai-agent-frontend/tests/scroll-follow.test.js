import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createScrollFollower } from '../src/platform/scroll-follow.js'

test('stream follows by default but upward wheel pauses immediately', () => {
  const state = createScrollFollower()
  assert.equal(state.following, true)
  state.onWheel({ deltaY: -100 })
  assert.equal(state.following, false)
  state.onWheel({ deltaY: 100 })
  assert.equal(state.following, false)
})
test('manual scrolling away stays paused; returning to bottom resumes following', () => {
  const state = createScrollFollower()
  state.onScroll({ scrollHeight: 2000, clientHeight: 500, scrollTop: 600 })
  assert.equal(state.following, false)
  state.onScroll({ scrollHeight: 2100, clientHeight: 500, scrollTop: 600 })
  assert.equal(state.following, false)
  state.onScroll({ scrollHeight: 2100, clientHeight: 500, scrollTop: 1600 })
  assert.equal(state.following, true)
})
test('keyboard reading pauses; jump to latest explicitly resumes', () => {
  const state = createScrollFollower()
  state.onKey({ key: 'PageUp' }); assert.equal(state.following, false)
  state.resume(); assert.equal(state.following, true)
})
test('file actions share a fixed-width trailing group independent of filename length', () => {
  const source = readFileSync(new URL('../src/platform/ChatPage.vue', import.meta.url), 'utf8')
  assert.match(source, /class="artifact-actions"><a class="artifact-action"[^>]*>下载<\/a><button class="artifact-action"/)
  assert.match(source, /if \(follow.following && scroll.value\)/)
  assert.match(source, /@wheel.passive=/)
})
