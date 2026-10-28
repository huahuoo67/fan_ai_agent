import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import createDOMPurify from 'dompurify'
import { renderMarkdown } from '../src/platform/markdown.js'
const purifier = createDOMPurify(new JSDOM('').window)
test('renders standard Markdown without regular-expression HTML assembly', () => {
  const html = renderMarkdown('**重点**\n\n- 事项\n\n[来源](https://example.com)', purifier)
  assert.match(html, /<strong>重点<\/strong>/)
  assert.match(html, /<li>事项<\/li>/)
  assert.match(html, /href="https:\/\/example.com"/)
})
test('removes execution, auto-fetch, form and encoded javascript payloads', () => {
  const html = renderMarkdown('<script>alert(1)</script><img src="https://example.com/track" onerror="alert(1)"><iframe src="https://example.com"></iframe><svg onload="alert(1)"></svg><form><input></form><a href="jav&#x61;script:alert(1)">点我</a><span style="background:url(https://example.com)">文字</span>', purifier)
  assert.doesNotMatch(html, /<script|<img|<iframe|<svg|<form|<input|javascript:|onerror|onload|style=/i)
})
test('blocks data and arbitrary same-origin routes; allows authenticated artifact links', () => {
  const html = renderMarkdown('[a](data:text/html,test) [b](/api/auth/logout) [c](/api/artifacts/12345678-1234-1234-1234-123456789abc/download)', purifier)
  assert.doesNotMatch(html, /href="data:|href="\/api\/auth/)
  assert.match(html, /href="\/api\/artifacts\/12345678-1234-1234-1234-123456789abc\/download"/)
})
