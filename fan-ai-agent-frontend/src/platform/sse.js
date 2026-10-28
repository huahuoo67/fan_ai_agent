export async function parseSSE(stream, receive) {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const consume = () => {
    let split
    while ((split = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, split)
      buffer = buffer.slice(split + 2)
      let type = 'message'
      const data = []
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) type = line.slice(6).trim()
        else if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
      }
      if (data.length) receive(type, JSON.parse(data.join('\n')))
    }
  }
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      buffer = buffer.replace(/\r\n/g, '\n')
      consume()
    }
    buffer += decoder.decode()
    consume()
    if (buffer.trim()) throw new Error('流式消息不完整，请读取服务端状态')
  } finally { reader.releaseLock() }
}
