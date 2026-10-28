import test from 'node:test'
import assert from 'node:assert/strict'
import { webcrypto } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { createUuid } from '../src/platform/uuid.js'

test('uses native randomUUID with its crypto receiver when available', () => {
  const provider = { randomUUID() { assert.equal(this, provider); return 'native-uuid' } }
  assert.equal(createUuid(provider), 'native-uuid')
})

test('HTTP fallback sets UUID v4 version and variant bits', () => {
  const provider = { getRandomValues(bytes) { assert.equal(this, provider); bytes.fill(255); return bytes } }
  assert.equal(createUuid(provider), 'ffffffff-ffff-4fff-bfff-ffffffffffff')
})

test('HTTP fallback produces unique canonical UUIDs using secure random values', () => {
  const httpCrypto = { getRandomValues: webcrypto.getRandomValues.bind(webcrypto) }
  const ids = Array.from({ length: 1000 }, () => createUuid(httpCrypto))
  assert.equal(new Set(ids).size, ids.length)
  for (const id of ids) assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
})

test('missing crypto fails explicitly without an insecure Math.random fallback', () => {
  assert.throws(() => createUuid({}), /随机数支持/)
  assert.throws(() => createUuid(null), /随机数支持/)
})

test('chat sender uses the HTTP-compatible UUID helper before clearing the draft', () => {
  const source = readFileSync(new URL('../src/platform/ChatPage.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(source, /crypto\.randomUUID/)
  assert.ok(source.indexOf('const requestId = createUuid()') < source.indexOf("input.value = ''; busy.value = true"))
  assert.match(source, /sendTurn\(id, \{ requestId,/)
})
