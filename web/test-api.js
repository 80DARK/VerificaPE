'use strict'

/**
 * Test de la API v1 de VerificaPE (la que usará ComercIA).
 * Prueba: token de API, entrega cifrada, filtros de fecha del servidor,
 * y filtros del cliente (palabra clave, nombre, monto, fecha, app).
 *
 * Uso:  node test-api.js  [URL_BASE]
 */

const crypto = require('crypto')
const { VerificaPE } = require('./cliente-verificape')

const BASE = process.argv[2] || 'http://localhost:3000'

function b64url (buf) { return Buffer.from(buf).toString('base64url') }
function sha256Hex (bytes) { return crypto.createHash('sha256').update(bytes).digest('hex') }

function encryptAesGcm (key, iv, plain) {
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv)
  return Buffer.concat([cipher.update(plain), cipher.final(), cipher.getAuthTag()])
}

async function postNotif (channelId, writeToken, key, payload) {
  const iv = crypto.randomBytes(12)
  const blob = encryptAesGcm(key, iv, Buffer.from(JSON.stringify(payload), 'utf8'))
  const res = await fetch(BASE + '/api/webhook/' + channelId, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Write-Token': b64url(writeToken) },
    body: JSON.stringify({ iv: b64url(iv), ct: b64url(blob), algo: 'AES-256-GCM', time: Date.now() })
  })
  return res.json()
}

async function main () {
  let failures = 0
  const check = (name, cond) => {
    console.log((cond ? '  ✔' : '  ✘') + ' ' + name)
    if (!cond) failures++
  }

  console.log('VerificaPE · test de la API v1 contra ' + BASE)

  // ── Preparar canal (como lo hace el panel) ─────────────────────────
  const channelId = crypto.randomUUID()
  const aesKey = crypto.randomBytes(32)
  const writeToken = crypto.randomBytes(32)
  const readToken = crypto.randomBytes(32)
  const apiToken = crypto.randomBytes(32)

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

  // ── Registrar el token de API (como lo hace el panel) ─────────────
  const apiReg = await fetch(
    `${BASE}/api/api-token?channel=${encodeURIComponent(channelId)}&token=${encodeURIComponent(b64url(readToken))}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ apiHash: sha256Hex(apiToken) })
    }
  ).then(r => r.json())
  check('registro del token de API', apiReg.ok === true)

  // ── Enviar 3 pagos de prueba (como la app Android) ────────────────
  await postNotif(channelId, writeToken, aesKey, {
    v: 1, type: 'notification', device: 'Pixel 8', app: 'Yape', pkg: 'com.yape.yape',
    title: 'Yape', text: 'Recibiste S/ 25.00 de Maria Quispe Mamani', time: Date.now()
  })
  await postNotif(channelId, writeToken, aesKey, {
    v: 1, type: 'notification', device: 'Pixel 8', app: 'Yape', pkg: 'com.yape.yape',
    title: 'Yape', text: 'Recibiste S/ 120.50 de Juan Perez Rojas', time: Date.now()
  })
  await postNotif(channelId, writeToken, aesKey, {
    v: 1, type: 'notification', device: 'Pixel 8', app: 'Plin', pkg: 'com.plin.plin',
    title: 'Plin', text: 'Pago recibido S/ 40.00 de Ana Torres', time: Date.now()
  })
  console.log('  · 3 pagos de prueba enviados (2 Yape, 1 Plin)')

  // ── API v1 directa ────────────────────────────────────────────────
  const pingApi = await fetch(BASE + '/api/v1/ping').then(r => r.json())
  check('ping de la API', pingApi.ok === true && pingApi.service === 'VerificaPE API')

  const noAuth = await fetch(BASE + '/api/v1/messages')
  check('sin token la API rechaza (401)', noAuth.status === 401)

  const msgs = await fetch(BASE + '/api/v1/messages', {
    headers: { 'X-API-Token': b64url(apiToken) }
  }).then(r => r.json())
  check('la API entrega los 3 mensajes cifrados', msgs.ok === true && msgs.messages.length === 3)

  const stats = await fetch(BASE + '/api/v1/stats', {
    headers: { 'X-API-Token': b64url(apiToken) }
  }).then(r => r.json())
  check('stats: total = 3', stats.ok === true && stats.total === 3)

  // Filtro de fecha del SERVIDOR: rango de hace una hora hasta ahora
  const rango = await fetch(
    `${BASE}/api/v1/messages?from=${Date.now() - 3600000}&to=${Date.now() + 1000}`,
    { headers: { 'X-API-Token': b64url(apiToken) } }
  ).then(r => r.json())
  check('filtro servidor por rango de fecha (hoy → 3)', rango.messages.length === 3)

  const viejo = await fetch(
    `${BASE}/api/v1/messages?from=0&to=${Date.now() - 3600000}`,
    { headers: { 'X-API-Token': b64url(apiToken) } }
  ).then(r => r.json())
  check('rango en el pasado → 0 mensajes', viejo.messages.length === 0)

  // ── Cliente para bots (como lo usará ComercIA) ────────────────────
  const vpe = new VerificaPE({
    baseUrl: BASE,
    apiToken: b64url(apiToken),
    aesKey: b64url(aesKey)
  })

  const todos = await vpe.mensajes()
  check('cliente: descifra los 3 mensajes', todos.length === 3)

  const porMonto = await vpe.mensajes({ monto: 25 })
  check('cliente: filtro por MONTO (S/ 25) → 1', porMonto.length === 1 && porMonto[0].text.includes('Maria'))

  const porNombre = await vpe.mensajes({ nombre: 'Juan Perez' })
  check('cliente: filtro por NOMBRE (Juan Perez) → 1', porNombre.length === 1 && porNombre[0].text.includes('120.50'))

  const porApp = await vpe.mensajes({ app: 'Yape' })
  check('cliente: filtro por APP (Yape) → 2', porApp.length === 2)

  const porPalabra = await vpe.mensajes({ q: 'Ana Torres' })
  check('cliente: filtro por PALABRA CLAVE → 1 (Plin)', porPalabra.length === 1 && porPalabra[0].app === 'Plin')

  const hoy = new Date()
  const hoyStr = hoy.getFullYear() + '-' + String(hoy.getMonth() + 1).padStart(2, '0') + '-' + String(hoy.getDate()).padStart(2, '0')
  const porFecha = await vpe.mensajes({ fecha: hoyStr })
  check('cliente: filtro por FECHA (hoy) → 3', porFecha.length === 3)

  const combinado = await vpe.mensajes({ app: 'Yape', monto: 120.50 })
  check('cliente: filtros COMBINADOS (Yape + S/ 120.50) → 1', combinado.length === 1)

  const ultimo = await vpe.ultimo()
  check('cliente: ultimo() es el pago de Plin', ultimo && ultimo.app === 'Plin')

  const total = await vpe.total()
  check('cliente: total() = 3', total === 3)

  // ── Revocar el token ──────────────────────────────────────────────
  const rev = await fetch(
    `${BASE}/api/api-token?channel=${encodeURIComponent(channelId)}&token=${encodeURIComponent(b64url(readToken))}`,
    { method: 'DELETE' }
  ).then(r => r.json())
  check('revocación del token de API', rev.ok === true)

  const trasRevocar = await fetch(BASE + '/api/v1/messages', {
    headers: { 'X-API-Token': b64url(apiToken) }
  })
  check('token revocado → la API rechaza (401)', trasRevocar.status === 401)

  console.log('')
  if (failures === 0) {
    console.log('TODO OK ✔ — la API v1 de VerificaPE está lista para ComercIA.')
    process.exit(0)
  } else {
    console.log('FALLOS: ' + failures)
    process.exit(1)
  }
}

main().catch(e => {
  console.error('Error ejecutando el test:', e.message)
  process.exit(1)
})
