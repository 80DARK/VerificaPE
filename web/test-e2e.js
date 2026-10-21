'use strict'

/**
 * Test E2E de NotiBridge: simula exactamente lo que hacen la web (crear canal)
 * y la app Android (cifrar con AES-256-GCM y enviar al webhook), y verifica
 * que el servidor almacena y devuelve los datos cifrados correctamente.
 *
 * Uso:  node test-e2e.js  [URL_BASE]
 * Por defecto: http://localhost:3000
 */

const crypto = require('crypto')

const BASE = process.argv[2] || 'http://localhost:3000'

function b64url (buf) {
  return Buffer.from(buf).toString('base64url')
}

function sha256Hex (bytes) {
  return crypto.createHash('sha256').update(bytes).digest('hex')
}

/** Cifra como la app Android (AES-256-GCM, salida ct||tag, igual que WebCrypto). */
function encryptAesGcm (key, iv, plain) {
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv)
  const ct = Buffer.concat([cipher.update(plain), cipher.final()])
  const tag = cipher.getAuthTag()
  return Buffer.concat([ct, tag])
}

/** Descifra como el navegador (WebCrypto espera ct||tag). */
function decryptAesGcm (key, iv, blob) {
  const tag = blob.subarray(blob.length - 16)
  const ct = blob.subarray(0, blob.length - 16)
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv)
  decipher.setAuthTag(tag)
  return Buffer.concat([decipher.update(ct), decipher.final()])
}

/** Abre el stream en vivo (SSE) y expone .next(event, timeout) y .close(). */
async function openSse (channelId, token) {
  const res = await fetch(
    `${BASE}/api/stream?channel=${encodeURIComponent(channelId)}&token=${encodeURIComponent(token)}`,
    { headers: { accept: 'text/event-stream' } }
  )
  if (!res.ok) throw new Error('SSE HTTP ' + res.status)
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  const queue = []
  const waiters = []

  ;(async () => {
    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })
        let idx
        while ((idx = buf.indexOf('\n\n')) >= 0) {
          const block = buf.slice(0, idx)
          buf = buf.slice(idx + 2)
          // ignora bloques sin evento ni datos (comentarios ": ping", "retry:")
          if (!block.includes('event: ') && !block.includes('data: ')) continue
          let ev = 'message'
          let data = ''
          for (const line of block.split('\n')) {
            if (line.startsWith('event: ')) ev = line.slice(7).trim()
            else if (line.startsWith('data: ')) data += line.slice(6)
          }
          queue.push({ event: ev, data })
          const w = waiters.shift()
          if (w) w()
        }
      }
    } catch (e) { /* stream cancelado */ }
  })()

  return {
    next (event, timeoutMs = 4000) {
      return new Promise((resolve, reject) => {
        const timer = setTimeout(
          () => reject(new Error('SSE: timeout esperando "' + event + '"')),
          timeoutMs
        )
        const check = () => {
          const i = queue.findIndex(q => q.event === event)
          if (i >= 0) {
            const [item] = queue.splice(i, 1)
            clearTimeout(timer)
            resolve(item)
            return true
          }
          return false
        }
        if (!check()) waiters.push(check) // espera pasiva solo si aún no llegó
      })
    },
    close () { try { reader.cancel() } catch (e) { /* nada */ } }
  }
}

async function main () {
  let failures = 0
  const check = (name, cond) => {
    console.log((cond ? '  ✔' : '  ✘') + ' ' + name)
    if (!cond) failures++
  }

  console.log('VerificaPE · test end-to-end contra ' + BASE)

  // 1) Ping
  const ping = await fetch(BASE + '/api/ping').then(r => r.json())
  check('el servidor responde al ping', ping.ok === true)

  // 2) El navegador crea el emparejamiento (claves y tokens SOLO locales)
  const channelId = crypto.randomUUID()
  const aesKey = crypto.randomBytes(32)
  const writeToken = crypto.randomBytes(32)
  const readToken = crypto.randomBytes(32)
  const reg = await fetch(BASE + '/api/channels', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      channelId,
      writeHash: sha256Hex(writeToken),
      readHash: sha256Hex(readToken)
    })
  }).then(r => r.json())
  check('registro del canal', reg.ok === true)

  // 3) La app Android cifra una notificación y la envía al webhook
  const payload = {
    v: 1,
    type: 'notification',
    device: 'TestDevice',
    app: 'WhatsApp',
    pkg: 'com.whatsapp',
    title: 'Hola',
    text: 'Mensaje de prueba E2E',
    time: Date.now()
  }
  const iv = crypto.randomBytes(12)
  const blob = encryptAesGcm(aesKey, iv, Buffer.from(JSON.stringify(payload), 'utf8'))
  const post = await fetch(BASE + '/api/webhook/' + channelId, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Write-Token': b64url(writeToken)
    },
    body: JSON.stringify({
      iv: b64url(iv),
      ct: b64url(blob),
      algo: 'AES-256-GCM',
      time: Date.now()
    })
  }).then(r => r.json())
  check('el webhook acepta la notificación cifrada', post.ok === true && Number.isInteger(post.id))

  // 4) Un token de escritura falso debe ser rechazado
  const badPost = await fetch(BASE + '/api/webhook/' + channelId, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Write-Token': b64url(crypto.randomBytes(32))
    },
    body: JSON.stringify({ iv: b64url(iv), ct: b64url(blob), algo: 'AES-256-GCM', time: 1 })
  })
  check('un token de escritura incorrecto es rechazado (401)', badPost.status === 401)

  // 5) El navegador descarga y descifra
  const read = await fetch(
    `${BASE}/api/messages?channel=${encodeURIComponent(channelId)}&token=${encodeURIComponent(b64url(readToken))}&since=0&limit=100`
  ).then(r => r.json())
  check('la descarga con token de lectura funciona', read.ok === true && read.messages.length === 1)

  // 5b) SSE · push en vivo: el mensaje debe llegar en menos de 1 segundo
  const sse = await openSse(channelId, b64url(readToken))
  const hello = await sse.next('hello', 3000).catch(() => null)
  check('el navegador conecta al stream en vivo (SSE)', !!hello)

  const t0 = Date.now()
  const iv2 = crypto.randomBytes(12)
  const payload2 = { v: 1, type: 'notification', device: 'TestDevice', app: 'Yape', pkg: 'com.yape.yape', title: 'Yape', text: 'Recibiste S/ 25.00', time: Date.now() }
  const blob2 = encryptAesGcm(aesKey, iv2, Buffer.from(JSON.stringify(payload2), 'utf8'))
  await fetch(BASE + '/api/webhook/' + channelId, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Write-Token': b64url(writeToken) },
    body: JSON.stringify({ iv: b64url(iv2), ct: b64url(blob2), algo: 'AES-256-GCM', time: Date.now() })
  })
  const ev = await sse.next('message', 4000)
  const ms = Date.now() - t0
  const evMsg = JSON.parse(ev.data)
  const evPlain = JSON.parse(decryptAesGcm(aesKey, Buffer.from(evMsg.iv, 'base64url'), Buffer.from(evMsg.ct, 'base64url')).toString('utf8'))
  check('push SSE llega y descifra en <1 s (tardó ' + ms + ' ms)',
    evPlain.text === payload2.text && ms < 1000)
  sse.close()

  const m = read.messages[0]
  const plain = JSON.parse(decryptAesGcm(aesKey, Buffer.from(m.iv, 'base64url'), Buffer.from(m.ct, 'base64url')).toString('utf8'))
  check('el mensaje descifrado coincide con el original',
    plain.title === payload.title &&
    plain.text === payload.text &&
    plain.app === payload.app)

  // 6) Una clave incorrecta NO puede descifrar (E2E real)
  let wrongKeyFails = false
  try {
    decryptAesGcm(crypto.randomBytes(32), Buffer.from(m.iv, 'base64url'), Buffer.from(m.ct, 'base64url'))
  } catch (e) {
    wrongKeyFails = true
  }
  check('con una clave equivocada el descifrado falla (el servidor nunca tiene la clave)', wrongKeyFails)

  // 7) Borrado de todos los mensajes
  const cleared = await fetch(
    `${BASE}/api/messages?channel=${encodeURIComponent(channelId)}&token=${encodeURIComponent(b64url(readToken))}`,
    { method: 'DELETE' }
  ).then(r => r.json())
  check('borrado de mensajes del canal', cleared.ok === true)

  const after = await fetch(
    `${BASE}/api/messages?channel=${encodeURIComponent(channelId)}&token=${encodeURIComponent(b64url(readToken))}&since=0`
  ).then(r => r.json())
  check('el canal quedó vacío', after.messages.length === 0)

  // 8) Eliminar canal
  const deleted = await fetch(
    `${BASE}/api/channels?channel=${encodeURIComponent(channelId)}&token=${encodeURIComponent(b64url(readToken))}`,
    { method: 'DELETE' }
  ).then(r => r.json())
  check('eliminación del canal', deleted.ok === true)

  console.log('')
  if (failures === 0) {
    console.log('TODO OK ✔ — el flujo E2E completo funciona.')
    process.exit(0)
  } else {
    console.log(`FALLOS: ${failures}`)
    process.exit(1)
  }
}

main().catch(e => {
  console.error('Error ejecutando el test:', e.message)
  process.exit(1)
})
