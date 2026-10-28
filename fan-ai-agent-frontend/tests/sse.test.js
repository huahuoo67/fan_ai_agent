import test from 'node:test'
import assert from 'node:assert/strict'
import { parseSSE } from '../src/platform/sse.js'
function stream(chunks) {
  return new ReadableStream({ start(controller) { for (const chunk of chunks) controller.enqueue(new TextEncoder().encode(chunk)); controller.close() } })
}
test('parses coalesced events and CRLF', async () => {
  const out = []
  await parseSSE(stream(['event: start\r\ndata: {"n":1}\r\n\r\nevent: done\ndata: {"n":2}\n\n']), (type, data) => out.push([type, data]))
  assert.deepEqual(out, [['start', { n: 1 }], ['done', { n: 2 }]])
})
test('accepts arbitrarily split JSON and Unicode bytes', async () => {
  const bytes = new TextEncoder().encode('event: answer_delta\ndata: {"text":"你好"}\n\n')
  const rs = new ReadableStream({ start(controller) { for (let i = 0; i < bytes.length; i++) controller.enqueue(bytes.slice(i, i + 1)); controller.close() } })
  const out = []
  await parseSSE(rs, (type, data) => out.push(data.text))
  assert.deepEqual(out, ['你好'])
})
test('incomplete events fail without replaying the request', async () => {
  await assert.rejects(parseSSE(stream(['event: done\ndata: {']), () => {}), /不完整/)
})
test('malformed JSON is not silently discarded', async () => {
  await assert.rejects(parseSSE(stream(['data: invalid\n\n']), () => {}), SyntaxError)
})
test('CRLF boundaries may split between network chunks', async () => {
  const out = []
  await parseSSE(stream(['event: done\r', '\ndata: {"ok":true}\r', '\n\r', '\n']), (type, data) => out.push([type, data]))
  assert.deepEqual(out, [['done', { ok: true }]])
})
