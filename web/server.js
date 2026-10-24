'use strict'

/**
 * NotiBridge · Servidor web
 * -------------------------
 * Recibe notificaciones CIFRADAS (AES-256-GCM) desde la app Android a través
 * de un webhook y las guarda en su PROPIA base de datos SQLite.
 *
 * Modelo de seguridad E2E:
 *  - La clave AES solo viaja dentro del QR de emparejamiento (navegador ↔ teléfono).
 *  - El servidor solo ve: canal, iv, ciphertext y marcas de tiempo.
 *  - La descarga se autentica con un token de lectura; la escritura con uno de
 *    escritura. De ambos, el servidor solo guarda el hash SHA-256.
 *  - El descifrado ocurre EN EL NAVEGADOR con WebCrypto, no aquí.
 */

const express = require('express')
const Database = require('better-sqlite3')
const crypto = require('crypto')
const fs = require('fs')
const os = require('os')
const path = require('path')

const PORT = Number(process.env.PORT || 3000)
const DATA_DIR = path.join(__dirname, 'data')
const MAX_MESSAGES_PER_CHANNEL = 5000
// URL pública del túnel cloudflared (la establece start.ps1; vacío si no hay túnel)
const TUNNEL_URL = (process.env.TUNNEL_URL || '').replace(/\/+$/, '')

fs.mkdirSync(DATA_DIR, { recursive: true })

// ---------------------------------------------------------------------------
// Base de datos (SQLite)
// ---------------------------------------------------------------------------
const db = new Database(path.join(DATA_DIR, 'notibridge.db'))
db.pragma('journal_mode = WAL')

db.exec(`
CREATE TABLE IF NOT EXISTS channels (
  id          TEXT PRIMARY KEY,
  write_hash  TEXT NOT NULL,
  read_hash   TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  last_post   INTEGER
);
CREATE TABLE IF NOT EXISTS messages (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  channel_id   TEXT NOT NULL,
  iv           TEXT NOT NULL,
  ct           TEXT NOT NULL,
  algo         TEXT NOT NULL DEFAULT 'AES-256-GCM',
  time         INTEGER,
  received_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_channel ON messages(channel_id, id);
`)

// Migración suave: columnas del token de API (bots y otros servicios)
try { db.exec('ALTER TABLE channels ADD COLUMN api_hash TEXT') } catch (e) { /* ya existe */ }
try { db.exec("ALTER TABLE channels ADD COLUMN api_paused INTEGER NOT NULL DEFAULT 0") } catch (e) { /* ya existe */ }
// v1.3: la clave de la API se guarda ENVUELTA (cifrada) con el propio token.
try { db.exec('ALTER TABLE channels ADD COLUMN api_key_wrapped TEXT') } catch (e) { /* ya existe */ }
// v1.2 legacy: eliminar la columna de texto claro si aún existe
try { db.exec('ALTER TABLE channels DROP COLUMN api_key') } catch (e) { /* no existe */ }

// v1.3.5: webhook IDEMPOTENTE — el mismo mensaje (canal + hora de notificación)
// solo se guarda una vez, así los reintentos de la app nunca duplican en el panel.
try {
  db.exec('DELETE FROM messages WHERE id NOT IN (SELECT MIN(id) FROM messages GROUP BY channel_id, time)')
  db.exec('CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_canal_hora ON messages(channel_id, time)')
} catch (e) { /* índice ya creado o duplicados imposibles */ }

const insertChannel = db.prepare(
  'INSERT INTO channels (id, write_hash, read_hash, created_at) VALUES (?, ?, ?, ?)'
)
const getChannel = db.prepare('SELECT * FROM channels WHERE id = ?')
const getChannelByApiHash = db.prepare('SELECT * FROM channels WHERE api_hash = ?')
const setApiToken = db.prepare('UPDATE channels SET api_hash = ?, api_key_wrapped = ? WHERE id = ?')
const setApiPaused = db.prepare('UPDATE channels SET api_paused = ? WHERE id = ?')
const insertMessage = db.prepare(
  'INSERT OR IGNORE INTO messages (channel_id, iv, ct, algo, time, received_at) VALUES (?, ?, ?, ?, ?, ?)'
)
const getMessages = db.prepare(
  'SELECT id, channel_id, iv, ct, algo, time, received_at ' +
  'FROM messages WHERE channel_id = ? AND id > ? ORDER BY id DESC LIMIT ?'
)
const countMessages = db.prepare('SELECT COUNT(*) AS n FROM messages WHERE channel_id = ?')
const deleteAllMessages = db.prepare('DELETE FROM messages WHERE channel_id = ?')
const deleteOneMessage = db.prepare('DELETE FROM messages WHERE id = ? AND channel_id = ?')
const deleteChannelStmt = db.prepare('DELETE FROM channels WHERE id = ?')
const touchChannel = db.prepare('UPDATE channels SET last_post = ? WHERE id = ?')
const trimMessages = db.prepare(
  'DELETE FROM messages WHERE channel_id = ? AND id NOT IN ' +
  '(SELECT id FROM messages WHERE channel_id = ? ORDER BY id DESC LIMIT ?)'
)

// ---------------------------------------------------------------------------
// Utilidades de autenticación
// ---------------------------------------------------------------------------
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const B64U_RE = /^[A-Za-z0-9_-]+$/
const HEX64_RE = /^[0-9a-f]{64}$/

/** SHA-256 en hex de los BYTES del token (el token llega en base64url). */
function tokenHash (token) {
  return crypto.createHash('sha256').update(Buffer.from(token, 'base64url')).digest('hex')
}

/**
 * ENVUELVE la clave AES del canal con una clave derivada del TOKEN de API.
 * La base de datos nunca guarda la clave en texto claro: para desenvolverla
 * se necesita el token completo (que solo tiene el consumidor). El hash del
 * token guardado en la DB NO sirve para desenvolver, así una fuga de la
 * base de datos sola no revela ninguna clave.
 */
function wrapKeyForToken (apiToken, keyB64) {
  const wrapKey = crypto.createHash('sha256')
    .update(Buffer.from(apiToken, 'base64url'))
    .update('verificape-wrap-v1')
    .digest()
  const iv = crypto.randomBytes(12)
  const cipher = crypto.createCipheriv('aes-256-gcm', wrapKey, iv)
  const ct = Buffer.concat([cipher.update(Buffer.from(keyB64, 'utf8')), cipher.final()])
  const tag = cipher.getAuthTag()
  return Buffer.concat([iv, ct, tag]).toString('base64url')
}

/** Desenvuelve la clave con el token de la petición (solo en memoria). */
function unwrapKeyForToken (apiToken, wrapped) {
  try {
    const blob = Buffer.from(wrapped, 'base64url')
    const iv = blob.subarray(0, 12)
    const ct = blob.subarray(12, blob.length - 16)
    const tag = blob.subarray(blob.length - 16)
    const wrapKey = crypto.createHash('sha256')
      .update(Buffer.from(apiToken, 'base64url'))
      .update('verificape-wrap-v1')
      .digest()
    const d = crypto.createDecipheriv('aes-256-gcm', wrapKey, iv)
    d.setAuthTag(tag)
    return Buffer.concat([d.update(ct), d.final()]).toString('utf8')
  } catch (e) {
    return null
  }
}

function eqHex (a, b) {
  const A = Buffer.from(a)
  const B = Buffer.from(b)
  return A.length === B.length && crypto.timingSafeEqual(A, B)
}

/**
 * Autentica la petición con el token de lectura o de escritura.
 * Devuelve el canal o null.
 */
function authChannel (req, which) {
  const channelId = req.params.channelId || req.query.channel
  if (typeof channelId !== 'string' || !UUID_RE.test(channelId)) return null
  const channel = getChannel.get(channelId)
  if (!channel) return null
  const token = req.get(which === 'write' ? 'x-write-token' : 'x-read-token') ||
                req.query.token
  if (typeof token !== 'string' || !B64U_RE.test(token) || token.length > 200) return null
  const stored = which === 'write' ? channel.write_hash : channel.read_hash
  if (!eqHex(tokenHash(token), stored)) return null
  return channel
}

// ---------------------------------------------------------------------------
// API
// ---------------------------------------------------------------------------
const app = express()
app.disable('x-powered-by')
app.use(express.json({ limit: '256kb' }))
app.use(express.static(path.join(__dirname, 'public')))

app.get('/api/ping', (req, res) => {
  res.json({ ok: true, name: 'VerificaPE', version: 1, tunnel: TUNNEL_URL })
})

/** El navegador registra el canal enviando SOLO los hashes de los tokens. */
app.post('/api/channels', (req, res) => {
  const { channelId, writeHash, readHash } = req.body || {}
  if (typeof channelId !== 'string' || !UUID_RE.test(channelId)) {
    return res.status(400).json({ ok: false, error: 'channelId inválido' })
  }
  if (typeof writeHash !== 'string' || !HEX64_RE.test(writeHash)) {
    return res.status(400).json({ ok: false, error: 'writeHash inválido' })
  }
  if (typeof readHash !== 'string' || !HEX64_RE.test(readHash)) {
    return res.status(400).json({ ok: false, error: 'readHash inválido' })
  }
  const existing = getChannel.get(channelId)
  if (existing) {
    if (eqHex(existing.write_hash, writeHash) && eqHex(existing.read_hash, readHash)) {
      return res.json({ ok: true, status: 'exists' })
    }
    return res.status(409).json({ ok: false, error: 'El canal ya existe con otras credenciales' })
  }
  insertChannel.run(channelId, writeHash, readHash, Date.now())
  res.json({ ok: true, status: 'created' })
})

/** WEBHOOK: la app Android envía aquí las notificaciones ya cifradas. */
app.post('/api/webhook/:channelId', (req, res) => {
  const channel = authChannel(req, 'write')
  if (!channel) return res.status(401).json({ ok: false, error: 'No autorizado' })

  const { iv, ct, algo, time } = req.body || {}
  if (typeof iv !== 'string' || !B64U_RE.test(iv) || iv.length < 8 || iv.length > 64) {
    return res.status(400).json({ ok: false, error: 'iv inválido' })
  }
  if (typeof ct !== 'string' || !B64U_RE.test(ct) || ct.length < 16 || ct.length > 200000) {
    return res.status(400).json({ ok: false, error: 'ct inválido' })
  }
  if (algo !== 'AES-256-GCM') {
    return res.status(400).json({ ok: false, error: 'algo no soportado' })
  }
  const t = Number(time)
  const now = Date.now()
  // INSERT OR IGNORE + índice único (channel_id, time) = idempotente:
  // si la app reintenta un envío, no se duplica.
  const info = insertMessage.run(
    channel.id,
    iv, ct, algo,
    Number.isFinite(t) ? Math.trunc(t) : null,
    now
    )
  trimMessages.run(channel.id, channel.id, MAX_MESSAGES_PER_CHANNEL)
  touchChannel.run(now, channel.id)

  if (info.changes === 0) {
    // Ya existía (reintento): responder OK con el id existente, sin reenviar push
    const previo = db.prepare(
      'SELECT id FROM messages WHERE channel_id = ? AND time = ? LIMIT 1'
    ).get(channel.id, Number.isFinite(t) ? Math.trunc(t) : null)
    return res.json({ ok: true, id: previo ? previo.id : null, duplicado: true })
  }

  // PUSH EN VIVO: notifica al instante a los paneles conectados (SSE)
  sseBroadcast(channel.id, 'message', {
    id: Number(info.lastInsertRowid),
    channel_id: channel.id,
    iv, ct, algo,
    time: Number.isFinite(t) ? Math.trunc(t) : null,
    received_at: now
  })
  res.json({ ok: true, id: Number(info.lastInsertRowid) })
})

/** Descarga de mensajes cifrados (el navegador los descifra). */
app.get('/api/messages', (req, res) => {
  const channel = authChannel(req, 'read')
  if (!channel) return res.status(401).json({ ok: false, error: 'No autorizado' })
  let since = Number.parseInt(String(req.query.since || '0'), 10)
  if (!Number.isFinite(since) || since < 0) since = 0
  let limit = Number.parseInt(String(req.query.limit || '200'), 10)
  if (!Number.isFinite(limit) || limit < 1) limit = 200
  limit = Math.min(limit, 1000)
  const rows = getMessages.all(channel.id, since, limit)
  res.json({ ok: true, messages: rows, total: countMessages.get(channel.id).n })
})

/** Borrar todas los mensajes del canal. */
app.delete('/api/messages', (req, res) => {
  const channel = authChannel(req, 'read')
  if (!channel) return res.status(401).json({ ok: false, error: 'No autorizado' })
  deleteAllMessages.run(channel.id)
  sseBroadcast(channel.id, 'clear', { at: Date.now() })
  res.json({ ok: true })
})

/** Borrar un mensaje concreto. */
app.delete('/api/messages/:id', (req, res) => {
  const channel = authChannel(req, 'read')
  if (!channel) return res.status(401).json({ ok: false, error: 'No autorizado' })
  const id = Number.parseInt(String(req.params.id), 10)
  if (!Number.isFinite(id)) return res.status(400).json({ ok: false, error: 'id inválido' })
  deleteOneMessage.run(id, channel.id)
  sseBroadcast(channel.id, 'deleted', { id })
  res.json({ ok: true })
})

/** Eliminar el canal y todos sus mensajes. */
app.delete('/api/channels', (req, res) => {
  const channel = authChannel(req, 'read')
  if (!channel) return res.status(401).json({ ok: false, error: 'No autorizado' })
  sseBroadcast(channel.id, 'clear', { at: Date.now() })
  deleteAllMessages.run(channel.id)
  deleteChannelStmt.run(channel.id)
  res.json({ ok: true })
})

// ---------------------------------------------------------------------------
// SSE · Push en vivo (latencia ~0)
// El navegador abre /api/stream con su token de lectura y recibe cada
// notificación CIFRADA en el mismo instante en que llega al webhook.
// ---------------------------------------------------------------------------
const sseClients = new Map() // channelId -> Set<res>

function sseBroadcast (channelId, event, data) {
  const set = sseClients.get(channelId)
  if (!set || set.size === 0) return
  const payload = 'event: ' + event + '\ndata: ' + JSON.stringify(data) + '\n\n'
  for (const res of set) {
    try { res.write(payload) } catch (e) { set.delete(res) }
  }
}

app.get('/api/stream', (req, res) => {
  const channel = authChannel(req, 'read')
  if (!channel) return res.status(401).json({ ok: false, error: 'No autorizado' })

  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache, no-transform',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no'
  })
  res.write('retry: 3000\n\n')
  res.write('event: hello\ndata: ' + JSON.stringify({ ok: true, at: Date.now() }) + '\n\n')

  let set = sseClients.get(channel.id)
  if (!set) { set = new Set(); sseClients.set(channel.id, set) }
  set.add(res)

  const ping = setInterval(() => {
    try { res.write(': ping\n\n') } catch (e) { /* cliente muerto */ }
  }, 15000)

  req.on('close', () => {
    clearInterval(ping)
    set.delete(res)
    if (set.size === 0) sseClients.delete(channel.id)
  })
})

/**
 * Autentica una petición de la API v1 (bots y otros servicios) con el token
 * de API: el token por sí solo identifica el canal (el servidor solo guarda
 * su hash SHA-256). Responde 401/403 directamente si falla.
 */
function authApi (req, res) {
  const token = req.get('x-api-token') || req.query.token
  if (typeof token !== 'string' || !B64U_RE.test(token) || token.length > 200) {
    res.status(401).json({ ok: false, error: 'Token de API inválido' })
    return null
  }
  const channel = getChannelByApiHash.get(tokenHash(token))
  if (!channel) {
    res.status(401).json({ ok: false, error: 'Token de API inválido o revocado' })
    return null
  }
  if (channel.api_paused) {
    res.status(403).json({ ok: false, error: 'El token de API está en pausa' })
    return null
  }
  return { channel, token }
}

/** Descifra un mensaje con la clave AES del canal (modo API descifrada). */
function decryptRow (keyBuf, m) {
  try {
    const iv = Buffer.from(m.iv, 'base64url')
    const blob = Buffer.from(m.ct, 'base64url')
    const d = crypto.createDecipheriv('aes-256-gcm', keyBuf, iv)
    d.setAuthTag(blob.subarray(blob.length - 16))
    const plain = JSON.parse(
      Buffer.concat([d.update(blob.subarray(0, blob.length - 16)), d.final()]).toString('utf8')
    )
    return { id: m.id, recibidoEn: m.received_at, ilegible: false, ...plain }
  } catch (e) {
    return { id: m.id, recibidoEn: m.received_at, ilegible: true }
  }
}

// ---------------------------------------------------------------------------
// API v1 · para bots y otros servicios
// La API entrega los mensajes YA DESCIFRADOS (opt-in al crear el token:
// el panel manda la clave del canal junto con el hash del token). El
// transporte está protegido por HTTPS. Los filtros de fecha/límite corren
// aquí; los de texto (palabra, nombre, monto, app) en el cliente de Node.
// ---------------------------------------------------------------------------
app.use('/api/v1', (req, res, next) => {
  res.set('Access-Control-Allow-Origin', '*')
  res.set('Access-Control-Allow-Headers', 'Content-Type, X-API-Token')
  res.set('Access-Control-Allow-Methods', 'GET, OPTIONS')
  if (req.method === 'OPTIONS') return res.status(204).end()
  next()
})

app.get('/api/v1/ping', (req, res) => {
  res.json({ ok: true, service: 'VerificaPE API', version: 1 })
})

app.get('/api/v1/messages', (req, res) => {
  const auth = authApi(req, res)
  if (!auth) return
  const { channel, token } = auth

  let since = Number.parseInt(String(req.query.since || '0'), 10)
  if (!Number.isFinite(since) || since < 0) since = 0
  let limit = Number.parseInt(String(req.query.limit || '200'), 10)
  if (!Number.isFinite(limit) || limit < 1) limit = 200
  limit = Math.min(limit, 1000)
  const from = Number.parseInt(String(req.query.from || ''), 10) // received_at >= from (ms)
  const to = Number.parseInt(String(req.query.to || ''), 10)     // received_at <= to (ms)

  const where = ['channel_id = ?', 'id > ?']
  const args = [channel.id, since]
  if (Number.isFinite(from)) { where.push('received_at >= ?'); args.push(from) }
  if (Number.isFinite(to)) { where.push('received_at <= ?'); args.push(to) }

  const rows = db.prepare(
    'SELECT id, channel_id, iv, ct, algo, time, received_at FROM messages WHERE ' +
    where.join(' AND ') + ' ORDER BY id DESC LIMIT ?'
  ).all(...args, limit)

  // La clave se desenvuelve SOLO en memoria, con el token de esta petición
  const keyB64 = channel.api_key_wrapped ? unwrapKeyForToken(token, channel.api_key_wrapped) : null
  const keyBuf = keyB64 ? Buffer.from(keyB64, 'base64url') : null
  const messages = keyBuf ? rows.map(r => decryptRow(keyBuf, r)) : rows

  res.json({
    ok: true,
    total: countMessages.get(channel.id).n,
    messages,
    modo: keyBuf ? 'descifrado' : 'cifrado',
    filtros: { since, from: Number.isFinite(from) ? from : null, to: Number.isFinite(to) ? to : null, limit }
  })
})

app.get('/api/v1/stats', (req, res) => {
  const auth = authApi(req, res)
  if (!auth) return
  const { channel } = auth
  const row = db.prepare(
    'SELECT COUNT(*) AS total, MIN(id) AS first_id, MAX(id) AS last_id, ' +
    'MIN(received_at) AS first_at, MAX(received_at) AS last_at ' +
    'FROM messages WHERE channel_id = ?'
  ).get(channel.id)
  res.json({ ok: true, total: row.total, first_id: row.first_id, last_id: row.last_id, first_at: row.first_at, last_at: row.last_at })
})

/** El panel consulta el estado del token de API. */
app.get('/api/api-token', (req, res) => {
  const channel = authChannel(req, 'read')
  if (!channel) return res.status(401).json({ ok: false, error: 'No autorizado' })
  res.json({
    ok: true,
    exists: !!channel.api_hash,
    paused: !!channel.api_paused,
    modo: channel.api_hash ? (channel.api_key_wrapped ? 'descifrado' : 'cifrado') : null
  })
})

/**
 * El panel registra el token de API autenticándose con su token de lectura.
 * La clave AES del canal llega junto con el TOKEN de API y se guarda
 * ENVUELTA con él (wrapKeyForToken): la DB jamás contiene la clave en
 * texto claro (ver hallazgo de seguridad v1.3).
 */
app.post('/api/api-token', (req, res) => {
  const channel = authChannel(req, 'read')
  if (!channel) return res.status(401).json({ ok: false, error: 'No autorizado' })
  const { apiToken, key } = req.body || {}
  if (typeof apiToken !== 'string' || !B64U_RE.test(apiToken) || apiToken.length < 20 || apiToken.length > 200) {
    return res.status(400).json({ ok: false, error: 'apiToken inválido' })
  }
  if (typeof key !== 'string' || !B64U_RE.test(key) || Buffer.from(key, 'base64url').length !== 32) {
    return res.status(400).json({ ok: false, error: 'key inválida (se requiere la clave AES b64url de 32 bytes)' })
  }
  const apiHash = tokenHash(apiToken)
  const wrapped = wrapKeyForToken(apiToken, key)
  setApiToken.run(apiHash, wrapped, channel.id)
  setApiPaused.run(0, channel.id)
  res.json({ ok: true })
})

/** Pausar / reanudar el token de API (el consumidor recibe 403 mientras esté en pausa). */
app.post('/api/api-token/pause', (req, res) => {
  const channel = authChannel(req, 'read')
  if (!channel) return res.status(401).json({ ok: false, error: 'No autorizado' })
  setApiPaused.run(1, channel.id)
  res.json({ ok: true, paused: true })
})

app.post('/api/api-token/resume', (req, res) => {
  const channel = authChannel(req, 'read')
  if (!channel) return res.status(401).json({ ok: false, error: 'No autorizado' })
  setApiPaused.run(0, channel.id)
  res.json({ ok: true, paused: false })
})

/** Revocar el token de API (también borra la clave guardada). */
app.delete('/api/api-token', (req, res) => {
  const channel = authChannel(req, 'read')
  if (!channel) return res.status(401).json({ ok: false, error: 'No autorizado' })
  setApiToken.run(null, null, channel.id)
  res.json({ ok: true })
})

app.use((req, res) => {
  res.status(404).json({ ok: false, error: 'No encontrado' })
})

// eslint-disable-next-line no-unused-vars
app.use((err, req, res, next) => {
  console.error('[NotiBridge] Error:', err.message)
  res.status(500).json({ ok: false, error: 'Error interno' })
})

// ---------------------------------------------------------------------------
// Arranque
// ---------------------------------------------------------------------------
app.listen(PORT, '0.0.0.0', () => {
  const nets = os.networkInterfaces()
  const ips = []
  for (const name of Object.keys(nets)) {
    for (const net of nets[name] || []) {
      if (net.family === 'IPv4' && !net.internal) ips.push(net.address)
    }
  }
  console.log('┌─────────────────────────────────────────────┐')
  console.log('│  VerificaPE Web · servidor escuchando       │')
  console.log('├─────────────────────────────────────────────┤')
  console.log(`│  Local:    http://localhost:${PORT}            `.padEnd(47, ' ') + '│')
  for (const ip of ips) {
    console.log(`│  Red:       http://${ip}:${String(PORT).padEnd(16, ' ')}`.padEnd(47, ' ') + '│')
  }
  console.log('└─────────────────────────────────────────────┘')
  console.log('Abre el panel en el navegador y escanea el QR desde la app VerificaPE.')
})
