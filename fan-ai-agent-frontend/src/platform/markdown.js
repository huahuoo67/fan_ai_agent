import { marked } from 'marked'
import DOMPurify from 'dompurify'

export function renderMarkdown(content, purifier = DOMPurify) {
  return purifier.sanitize(marked.parse(content || '', { breaks: true }), {
    FORBID_TAGS: ['img', 'iframe', 'form', 'input', 'style', 'script', 'svg', 'math', 'object', 'embed', 'link', 'meta', 'base', 'video', 'audio', 'source'],
    FORBID_ATTR: ['style'],
    ALLOWED_URI_REGEXP: /^(?:https?:\/\/|\/api\/artifacts\/[a-f0-9-]{36}\/download$)/i
  })
}
