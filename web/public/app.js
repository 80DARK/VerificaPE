'use strict'

/* ══════════════════════════════════════════════════════════════════
   NotiBridge Web · Lógica del panel
   - Crea el emparejamiento (canal + clave AES + tokens) AQUÍ, en el
     navegador. La clave nunca se envía al servidor (solo sus hashes).
   - Descarga los mensajes cifrados del servidor y los descifra con
     WebCrypto AES-256-GCM de extremo a extremo.
   ══════════════════════════════════════════════════════════════════ */

const $ = (s) => document.querySelector(s)

const LS = {
  pair: 'nb_pair',
  server: 'nb_server',
  cursor: 'nb_cursor',
  lastRead: 'nb_lastRead',
  theme: 'nb_theme',
  interval: 'nb_interval',
  collapsed: 'nb_collapsed',
  sound: 'nb_sound',
  api: 'nb_api'
}

const state = {
  pair: null,          // {channel, key, read, write} (b64url)
  key: null,           // CryptoKey AES-GCM
  msgs: new Map(),     // id -> mensaje {…, plain?, bad?}
  cursor: 0,           // último id descargado
  lastRead: 0,         // último id marcado como leído
  timer: null,
  interval: 5000,
  total: 0,
  badCount: 0,
  tunnelUrl: '',       // URL pública del túnel cloudflared (si existe)
  sse: false,          // push en vivo conectado
  filterApps: new Set(),// multiselección de billeteras (vacío = todas)
  kindFilter: 'pagos'  // 'pagos' | 'spam' | 'todo'
}

/* ─────────────────── utilidades ─────────────────── */

function bytesToB64u (bytes) {
  let bin = ''
  const chunk = 0x8000
  for (let i = 0; i < bytes.length; i += chunk) {
    bin += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk))
  }
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function b64uToBytes (str) {
  str = String(str).replace(/-/g, '+').replace(/_/g, '/')
  while (str.length % 4) str += '='
  const bin = atob(str)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

async function sha256Hex (b64u) {
  const h = await crypto.subtle.digest('SHA-256', b64uToBytes(b64u))
  return [...new Uint8Array(h)].map(b => b.toString(16).padStart(2, '0')).join('')
}

const randB64u = () => bytesToB64u(crypto.getRandomValues(new Uint8Array(32)))

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({
  '&': '&' + 'amp;',
  '<': '&' + 'lt;',
  '>': '&' + 'gt;',
  '"': '&' + 'quot;',
  "'": '&' + '#39;'
})[c])

function toast (text) {
  const el = document.createElement('div')
  el.className = 'toast'
  el.textContent = text
  document.body.appendChild(el)
  setTimeout(() => el.remove(), 2600)
}

function fmtTime (ms) {
  if (!ms) return ''
  return new Date(ms).toLocaleTimeString('es-ES', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

function fmtFecha (ms) {
  if (!ms) return ''
  return new Date(ms).toLocaleDateString('es-ES', { day: '2-digit', month: 'short' })
}

function fmtDay (ms) {
  const d = new Date(ms)
  const today = new Date()
  const yest = new Date(Date.now() - 86400000)
  if (d.toDateString() === today.toDateString()) return 'Hoy'
  if (d.toDateString() === yest.toDateString()) return 'Ayer'
  return d.toLocaleDateString('es-ES', { weekday: 'long', day: 'numeric', month: 'long' })
}

/* ─────────────────── sonido de notificación ─────────────────── */

const audio = new Audio('/sounds/notif.wav')
audio.preload = 'auto'
let audioUnlocked = false

function playSound () {
  if (localStorage.getItem(LS.sound) === 'off') return
  try {
    audio.currentTime = 0
    const pr = audio.play()
    if (pr && pr.then) {
      pr.then(() => { audioUnlocked = true; hideSoundWarn() })
        .catch(() => showSoundWarn())
    } else {
      audioUnlocked = true
    }
  } catch (e) { showSoundWarn() }
}

function showSoundWarn () {
  const el = $('#sound-warn')
  if (el) el.classList.remove('hidden')
}

function hideSoundWarn () {
  const el = $('#sound-warn')
  if (el) el.classList.add('hidden')
}

// Los navegadores exigen un clic antes de reproducir audio:
// el primer toque del usuario desbloquea el sonido para siempre.
function unlockAudio () {
  if (audioUnlocked) return
  try {
    const pr = audio.play()
    if (pr && pr.then) {
      pr.then(() => { audioUnlocked = true }).catch(() => {})
    }
  } catch (e) { /* se reintentará con el próximo clic */ }
}
document.addEventListener('click', unlockAudio)
document.addEventListener('keydown', unlockAudio)

/* ─────────────────── estado persistente ─────────────────── */

function loadPersisted () {
  state.interval = Number(localStorage.getItem(LS.interval) || 5000)
  state.cursor = Number(localStorage.getItem(LS.cursor) || 0)
  state.lastRead = Number(localStorage.getItem(LS.lastRead) || 0)
  try { state.pair = JSON.parse(localStorage.getItem(LS.pair) || 'null') } catch (e) { state.pair = null }
}

const persistCursor = () => localStorage.setItem(LS.cursor, String(state.cursor))
const persistLastRead = () => localStorage.setItem(LS.lastRead, String(state.lastRead))

/* ─────────────────── emparejamiento ─────────────────── */

function serverUrl () {
  // Prioridad: URL guardada manualmente > túnel cloudflared > origen actual
  return (localStorage.getItem(LS.server) || state.tunnelUrl || location.origin).replace(/\/+$/, '')
}

function updateTunnelNote () {
  const el = $('#tunnel-note')
  if (!el) return
  if (state.tunnelUrl) {
    el.textContent = '🌐 Túnel activo: ' + state.tunnelUrl + ' — el QR ya apunta ahí (visible desde cualquier red, con HTTPS).'
    el.classList.remove('hidden')
  } else {
    el.classList.add('hidden')
  }
}

function buildPairString () {
  const p = state.pair
  return 'notibridge://pair' +
    '?server=' + encodeURIComponent(serverUrl()) +
    '&channel=' + encodeURIComponent(p.channel) +
    '&key=' + p.key +
    '&token=' + p.write
}

async function registerChannel () {
  const p = state.pair
  const res = await fetch('/api/channels', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      channelId: p.channel,
      writeHash: await sha256Hex(p.write),
      readHash: await sha256Hex(p.read)
    })
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok || !data.ok) {
    throw new Error(data.error || ('HTTP ' + res.status))
  }
}

async function newPairing () {
  savePair({ channel: crypto.randomUUID(), key: randB64u(), read: randB64u(), write: randB64u() })
  await registerChannel()
  renderPairView()
  toast('Emparejamiento creado ✔')
}

function savePair (pair) {
  state.pair = pair
  if (pair) localStorage.setItem(LS.pair, JSON.stringify(pair))
  else localStorage.removeItem(LS.pair)
  state.key = null
  state.msgs.clear()
  state.cursor = 0
  state.lastRead = 0
  state.badCount = 0
  persistCursor()
  persistLastRead()
  // El token de API pertenece al canal: al cambiar de canal, fuera.
  localStorage.removeItem(LS.api)
}

async function ensureKey () {
  if (state.key) return state.key
  if (!state.pair) return null
  if (!window.crypto || !crypto.subtle) return null
  state.key = await crypto.subtle.importKey('raw', b64uToBytes(state.pair.key), 'AES-GCM', false, ['decrypt'])
  return state.key
}

/* ─────────────────── mensajes ─────────────────── */

async function tryDecrypt (m) {
  if (m.plain !== undefined || m.bad) return
  const key = await ensureKey()
  if (!key) { m.bad = true; return }
  try {
    const plain = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: b64uToBytes(m.iv) },
      key,
      b64uToBytes(m.ct)
    )
    m.plain = JSON.parse(new TextDecoder().decode(plain))
  } catch (e) {
    m.bad = true
  }
}

async function pull () {
  const p = state.pair
  if (!p || document.visibilityState !== 'visible') return
  try {
    const url = '/api/messages?channel=' + encodeURIComponent(p.channel) +
      '&token=' + encodeURIComponent(p.read) +
      '&since=' + state.cursor + '&limit=1000'
    const res = await fetch(url)
    const data = await res.json()
    if (!res.ok || !data.ok) throw new Error('HTTP ' + res.status)

    let added = 0
    for (const m of data.messages) {
      if (!state.msgs.has(m.id)) { state.msgs.set(m.id, m); added++ }
      if (m.id > state.cursor) { state.cursor = m.id; persistCursor() }
    }
    state.total = data.total

    let bad = 0
    for (const m of state.msgs.values()) {
      await tryDecrypt(m)
      if (m.bad) bad++
    }
    const badChanged = bad !== state.badCount
    state.badCount = bad

    setOnline(true, state.sse ? 'live' : 'poll')
    // Solo repintamos si algo cambió: evita el "parpadeo" cada ciclo de sondeo
    if (added > 0 || badChanged) {
      renderFeed()
      renderStats()
      renderAppFilter()
    }
  } catch (e) {
    setOnline(false, state.sse ? 'live' : 'poll')
  }
}

/* ─────────────────── push en vivo (SSE) ─────────────────── */

let sseSource = null

function refreshLive () {
  try { if (sseSource) sseSource.close() } catch (e) {}
  sseSource = null
  state.sse = false
  if (!state.pair || typeof EventSource === 'undefined') return
  const p = state.pair
  const url = '/api/stream?channel=' + encodeURIComponent(p.channel) +
    '&token=' + encodeURIComponent(p.read)
  sseSource = new EventSource(url)

  sseSource.addEventListener('hello', () => {
    state.sse = true
    setOnline(true, 'live')
  })

  sseSource.addEventListener('message', (ev) => {
    try { onLive(JSON.parse(ev.data)) } catch (e) { /* ignorar */ }
  })

  sseSource.addEventListener('clear', () => {
    state.msgs.clear()
    state.total = 0
    state.cursor = 0
    renderedIds.clear()
    persistCursor()
    renderFeed(); renderStats(); renderAppFilter()
  })

  sseSource.addEventListener('deleted', (ev) => {
    try {
      const d = JSON.parse(ev.data)
      state.msgs.delete(d.id)
      state.total = Math.max(0, state.total - 1)
      renderFeed(); renderStats()
    } catch (e) { /* ignorar */ }
  })

  sseSource.onerror = () => {
    state.sse = false
    setOnline(false)
    // EventSource reintenta solo; el sondeo de respaldo cubre el hueco
  }
}

/** Llega una notificación CIFRADA en vivo: descifrar, pintar y SONAR. */
async function onLive (m) {
  if (!m || state.msgs.has(m.id)) return
  state.msgs.set(m.id, m)
  if (m.id > state.cursor) { state.cursor = m.id; persistCursor() }
  state.total++
  await tryDecrypt(m)
  renderFeed()
  renderAppFilter()
  renderStats()
  setOnline(true, 'live')
  playSound()   // 🔔 ¡pago recibido, al instante!
}

async function loadAll () {
  //Descarga todo el historial cifrado del canal (en tandas)
  const p = state.pair
  if (!p) return
  state.msgs.clear()
  state.cursor = 0
  for (let i = 0; i < 20; i++) {
    const url = '/api/messages?channel=' + encodeURIComponent(p.channel) +
      '&token=' + encodeURIComponent(p.read) +
      '&since=' + state.cursor + '&limit=1000'
    const data = await fetch(url).then(r => r.json())
    if (!data.ok) break
    for (const m of data.messages) {
      state.msgs.set(m.id, m)
      if (m.id > state.cursor) state.cursor = m.id
    }
    persistCursor()
    state.total = data.total
    if (data.messages.length < 1000) break
  }
  for (const m of state.msgs.values()) await tryDecrypt(m)
  renderFeed(); renderStats(); renderAppFilter()
}

async function deleteMsg (id) {
  const p = state.pair
  if (!p) return
  const res = await fetch('/api/messages/' + id +
    '?channel=' + encodeURIComponent(p.channel) + '&token=' + encodeURIComponent(p.read),
  { method: 'DELETE' })
  if (res.ok) {
    state.msgs.delete(id)
    state.total = Math.max(0, state.total - 1)
    renderFeed(); renderStats()
  } else toast('No se pudo borrar el mensaje')
}

/* ─────────────────── datos financieros ─────────────────── */

const WALLETS = [
  { match: 'yape', name: 'Yape', color: '#7A29E9' },
  { match: 'plin', name: 'Plin', color: '#00A9E0' },
  { match: 'sip', name: 'SIP', color: '#00B04F' },
  { match: 'bim', name: 'bIM', color: '#00A0B8' },
  { match: 'interbank', name: 'Interbank', color: '#0B6EC9' },
  { match: 'bcp', name: 'BCP', color: '#002878' },
  { match: 'bbva', name: 'BBVA', color: '#004481' },
  { match: 'scotiabank', name: 'Scotiabank', color: '#EC1111' },
  { match: 'mibanco', name: 'MiBanco', color: '#D51317' },
  { match: 'pichincha', name: 'Pichincha', color: '#E30613' },
  { match: 'caja', name: 'Caja', color: '#6D28D9' }
]

function walletInfo (name) {
  const n = String(name || '').toLowerCase()
  const w = WALLETS.find(x => n.includes(x.match))
  if (w) return { name: w.name, color: w.color, letter: w.name.charAt(0) }
  let h = 0
  for (const c of String(name || '')) h = (h * 31 + c.charCodeAt(0)) % 360
  return { name: name || 'App', color: 'hsl(' + h + ', 52%, 44%)', letter: (name || '?').charAt(0).toUpperCase() }
}

/** Extrae el primer monto "S/ x" del texto. */
function extraerMonto (text) {
  const m = /S\/\s*([0-9]{1,3}(?:[.,][0-9]{3})*(?:[.,][0-9]{1,2})?|[0-9]+(?:[.,][0-9]{1,2})?)/.exec(text || '')
  if (!m) return null
  return parseFloat(m[1].replace(/,/g, ''))
}

const VERBOS_PAGO = [
  'te envió un pago', 'te envio un pago', 'te envió dinero', 'recibiste un pago',
  'recibiste un abono', 'recibiste una transferencia', 're pagó', 'te pagó',
  'te realizó un pago', 'te transfirió', 'te transfirio', 'recibiste s/',
  'abono a tu cuenta', 'te yapeó', 'te yapeo', 'pago recibido'
]

/** ¿La notificación descifrada es un PAGO? (como en la app Android) */
function esPago (m) {
  if (!m.plain) return false
  if (m.plain.type === 'payment' || m.plain.amount !== undefined) return true
  const t = ((m.plain.title || '') + ' ' + (m.plain.text || '')).toLowerCase()
  const recepcion = VERBOS_PAGO.some(v => t.includes(v))
  return recepcion && extraerMonto(m.plain.text || '') !== null
}

function montoDe (m) {
  if (m.plain.amount !== undefined && m.plain.amount !== null) return m.plain.amount
  return extraerMonto(m.plain.text || '')
}

/** Resumen financiero del día (strip superior del Panel). */
function updateTodayStrip () {
  const inicio = new Date()
  inicio.setHours(0, 0, 0, 0)
  let total = 0
  let n = 0
  for (const m of state.msgs.values()) {
    if (!m.plain || !esPago(m)) continue
    const t = m.plain.time || m.received_at
    if (t < inicio.getTime()) continue
    const monto = montoDe(m)
    if (monto !== null) { total += monto; n++ }
  }
  $('#today-amount').textContent = 'S/ ' + total.toFixed(2)
  $('#today-count').textContent = n + (n === 1 ? ' pago' : ' pagos')
}

/* ─────────────────── render ─────────────────── */

/** Iconos SVG del indicador de conexión. */
const PILL_ICONS = {
  live: '<svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><path d="M13 2 4.5 13.5H11L9.5 22 19 10h-6.5L13 2z"/></svg>',
  ok: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3.2"><circle cx="12" cy="12" r="7"/></svg>',
  off: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2 1 21h23L12 2z"/><path d="M12 9.5v4.5M12 17.4v.4"/></svg>'
}

function setOnline (ok, mode) {
  const pill = $('#conn-pill')
  if (!pill) return
  if (!ok) {
    pill.className = 'pill offline'
    pill.innerHTML = PILL_ICONS.off + ' Sin conexión'
  } else if (mode === 'live') {
    pill.className = 'pill live'
    pill.innerHTML = PILL_ICONS.live + ' En vivo'
  } else {
    pill.className = 'pill online'
    pill.innerHTML = PILL_ICONS.ok + ' Conectado'
  }
}

function visibleMsgs () {
  const q = $('#search').value.trim().toLowerCase()
  const list = []
  for (const m of state.msgs.values()) {
    if (!m.plain) continue
    const pago = esPago(m)
    if (state.kindFilter === 'pagos' && !pago) continue
    if (state.kindFilter === 'spam' && pago) continue
    const app = m.plain.app || m.plain.pkg || 'App'
    if (state.filterApps.size > 0 && !state.filterApps.has(app)) continue
    if (q) {
      const hay = ((m.plain.title || '') + ' ' + (m.plain.text || '') + ' ' +
        app + ' ' + (m.plain.payer || '')).toLowerCase()
      if (!hay.includes(q)) continue
    }
    list.push(m)
  }
  list.sort((a, b) => b.id - a.id)
  return list
}

function avatarFor (app) {
  const name = String(app || '?')
  const letter = name.trim().charAt(0).toUpperCase() || '?'
  let h = 0
  for (const c of name) h = (h * 31 + c.charCodeAt(0)) % 360
  return { letter, color: 'hsl(' + h + ', 52%, 44%)' }
}

/** IDs ya pintados: la animación de entrada solo se aplica a los NUEVOS. */
const renderedIds = new Set()

function renderFeed () {
  const feed = $('#feed')
  const empty = $('#empty')
  const warn = $('#decrypt-warn')
  const list = visibleMsgs()

  feed.innerHTML = ''
  warn.classList.toggle('hidden', state.badCount === 0)

  const hasPlain = [...state.msgs.values()].some(m => m.plain)
  empty.classList.toggle('hidden', hasPlain)

  let lastDay = null
  for (const m of list) {
    const t = m.plain.time || m.received_at
    const day = fmtDay(t)
    if (day !== lastDay) {
      lastDay = day
      const sep = document.createElement('div')
      sep.className = 'day-sep'
      sep.textContent = day
      feed.appendChild(sep)
    }
    const isTest = m.plain.type === 'test'
    const unread = m.id > state.lastRead
    const appName = m.plain.app || m.plain.pkg || 'App'
    const pago = esPago(m)
    const card = document.createElement('div')
    const isNew = !renderedIds.has(m.id)
    card.className = 'msg' + (pago ? ' pago' : ' spam') +
      (unread ? ' unread' : '') + (isTest ? ' otp-test' : '') + (isNew ? ' msg-in' : '')
    renderedIds.add(m.id)

    if (pago) {
      // ── Tarjeta FINANCIERA: billetera, monto, pagador y FECHA DEL YAPEO ──
      const w = walletInfo(m.plain.wallet || appName)
      const monto = montoDe(m)
      const pagador = m.plain.payer || m.plain.title || ''
      const tYapeo = m.plain.time || m.received_at
      card.innerHTML =
        '<div class="msg-head">' +
          '<span class="msg-avatar" style="background:' + w.color + '">' + esc(w.letter) + '</span>' +
          '<span class="msg-app">' + esc(w.name) + '</span>' +
          (unread ? '<span class="badge-new">NUEVO</span>' : '') +
          (isTest ? '<span class="badge-type">TEST</span>' : '') +
        '</div>' +
        '<div class="msg-amount-row">' +
          '<span class="msg-amount">S/ ' + (monto !== null ? Number(monto).toFixed(2) : '—') + '</span>' +
          '<span class="msg-payer">' + esc(pagador || 'Pago recibido') + '</span>' +
          '<div class="msg-fecha-principal">' +
            '<span class="msg-fecha">' + esc(fmtFecha(tYapeo)) + '</span>' +
            '<span class="msg-hora">' + esc(fmtTime(tYapeo)) + '</span>' +
          '</div>' +
        '</div>' +
        ((m.plain.text && m.plain.text !== pagador)
          ? '<div class="msg-text">' + esc(m.plain.text) + '</div>' : '') +
        '<div class="msg-llegada">↳ llegó al servidor ' + esc(fmtTime(m.received_at)) + '</div>'
    } else {
      // ── Tarjeta SPAM: notificación que NO es pago ──
      const av = avatarFor(appName)
      card.innerHTML =
        '<div class="msg-head">' +
          '<span class="msg-avatar" style="background:' + av.color + '">' + esc(av.letter) + '</span>' +
          '<span class="msg-app">' + esc(appName) + '</span>' +
          '<span class="badge-type spam-badge">OTRO</span>' +
          (unread ? '<span class="badge-new">NUEVO</span>' : '') +
        '</div>' +
        '<div class="msg-title">' + esc(m.plain.title || '') + '</div>' +
        '<div class="msg-text">' + esc(m.plain.text || '') + '</div>' +
        '<div class="msg-llegada">↳ llegó al servidor ' + esc(fmtTime(m.received_at)) + '</div>'
    }

    card.innerHTML +=
      '<div class="msg-actions">' +
        '<button data-act="copy">Copiar</button>' +
        '<button data-act="del">Borrar</button>' +
      '</div>'
    card.querySelector('[data-act="copy"]').onclick = () => {
      navigator.clipboard && navigator.clipboard.writeText((m.plain.title || '') + '\n' + (m.plain.text || ''))
        .then(() => toast('Copiado'))
        .catch(() => toast('No se pudo copiar'))
    }
    card.querySelector('[data-act="del"]').onclick = () => deleteMsg(m.id)
    feed.appendChild(card)
  }

  updateTodayStrip()

  const unreadTotal = [...state.msgs.values()].filter(m => m.plain && m.id > state.lastRead).length
  document.title = (unreadTotal > 0 ? '(' + unreadTotal + ') ' : '') + 'VerificaPE · Panel web'
}

function renderAppFilter () {
  const box = $('#app-filter')
  if (!box) return
  const apps = new Map()
  for (const m of state.msgs.values()) {
    if (!m.plain) continue
    const a = m.plain.app || m.plain.pkg || 'App'
    apps.set(a, (apps.get(a) || 0) + 1)
  }
  const todas = state.filterApps.size === 0
  box.innerHTML =
    '<button class="achip' + (todas ? ' on' : '') + '" data-app="">Todas</button>' +
    [...apps.keys()].sort().map(a =>
      '<button class="achip' + (state.filterApps.has(a) ? ' on' : '') +
      '" data-app="' + esc(a) + '">' + esc(a) + '</button>'
    ).join('')

  box.querySelectorAll('.achip').forEach(ch => {
    ch.onclick = () => {
      const a = ch.dataset.app
      if (!a) {
        state.filterApps.clear()               // «Todas» = sin filtro
      } else if (state.filterApps.has(a)) {
        state.filterApps.delete(a)             // multiselección: alternar
      } else {
        state.filterApps.add(a)
      }
      renderAppFilter()
      renderFeed()
    }
  })
}

function renderStats () {
  const withPlain = [...state.msgs.values()].filter(m => m.plain)
  const dayAgo = Date.now() - 86400000
  const recent = withPlain.filter(m => (m.plain.time || m.received_at) >= dayAgo)

  $('#stats-cards').innerHTML =
    '<div class="stat-card"><div class="num">' + state.total + '</div><div class="lbl">Mensajes cifrados en el servidor</div></div>' +
    '<div class="stat-card"><div class="num">' + withPlain.length + '</div><div class="lbl">Descifrados en este navegador</div></div>' +
    '<div class="stat-card"><div class="num">' + recent.length + '</div><div class="lbl">En las últimas 24 h</div></div>' +
    '<div class="stat-card"><div class="num">' + state.badCount + '</div><div class="lbl">Ilegibles (clave incorrecta)</div></div>'

  const byKey = (key, elId) => {
    const counts = new Map()
    for (const m of withPlain) {
      const k = key(m)
      counts.set(k, (counts.get(k) || 0) + 1)
    }
    const max = Math.max(1, ...counts.values())
    const box = $(elId)
    if (counts.size === 0) { box.innerHTML = '<p class="muted tiny">Sin datos todavía.</p>'; return }
    box.innerHTML = [...counts.entries()].sort((a, b) => b[1] - a[1]).map(([k, n]) =>
      '<div class="bar-row">' +
        '<div class="bar-label"><span>' + esc(k) + '</span><span class="bar-count">' + n + '</span></div>' +
        '<div class="bar-track"><div class="bar-fill" style="width:' + Math.round(n / max * 100) + '%"></div></div>' +
      '</div>').join('')
  }
  byKey(m => m.plain.app || m.plain.pkg || 'App', '#stats-apps')
  byKey(m => m.plain.device || 'Desconocido', '#stats-devices')
}

function renderPairView () {
  const p = state.pair
  if (!p) return
  $('#server-url').value = serverUrl()

  // Origen de la URL que usa el QR: manual (dominio propio) o automática
  const manual = !!localStorage.getItem(LS.server)
  const nota = $('#url-source-note')
  if (nota) {
    nota.textContent = manual
      ? '⚙️ Dominio manual ACTIVO: el QR apunta SIEMPRE a esta URL.'
      : (state.tunnelUrl
          ? '🌐 Automática (túnel): ' + state.tunnelUrl
          : '📍 Automática: ' + location.origin)
  }

  $('#pair-string').value = buildPairString()
  $('#channel-id').textContent = p.channel
  renderQR()
  refreshLive()   // (re)conecta el push en vivo con el canal actual
}

function renderQR () {
  const box = $('#qr')
  box.innerHTML = ''
  const text = buildPairString()
  if (typeof QRCode === 'undefined') {
    box.innerHTML = '<p class="muted tiny">No se pudo cargar el generador de QR.<br>Copia el código de emparejamiento de abajo.</p>'
    return
  }
  // eslint-disable-next-line no-undef
  new QRCode(box, { text, width: 220, height: 220, correctLevel: QRCode.CorrectLevel.M })
}

/* ─────────────────── vistas y menú lateral ─────────────────── */

const VIEW_TITLES = { panel: 'Panel', pair: 'Emparejamiento', api: 'API', stats: 'Estadísticas', settings: 'Ajustes' }

function switchView (name) {
  document.querySelectorAll('.view').forEach(v => v.classList.remove('active'))
  const el = document.getElementById('view-' + name)
  if (el) el.classList.add('active')
  document.querySelectorAll('.nav-item').forEach(b =>
    b.classList.toggle('active', b.dataset.view === name))
  $('#view-title').textContent = VIEW_TITLES[name] || 'Panel'
  $('#sidebar').classList.remove('open')
  $('#backdrop').classList.add('hidden')
  if (name === 'stats') renderStats()
  if (name === 'api') renderApiView()
}

function initSidebar () {
  const sb = $('#sidebar')
  if (localStorage.getItem(LS.collapsed) === '1') sb.classList.add('collapsed')

  $('#btn-collapse').onclick = () => {
    sb.classList.toggle('collapsed')
    localStorage.setItem(LS.collapsed, sb.classList.contains('collapsed') ? '1' : '0')
  }
  $('#btn-menu').onclick = () => {
    sb.classList.add('open')
    $('#backdrop').classList.remove('hidden')
  }
  $('#backdrop').onclick = () => {
    sb.classList.remove('open')
    $('#backdrop').classList.add('hidden')
  }
  document.querySelectorAll('.nav-item').forEach(btn => {
    btn.onclick = () => switchView(btn.dataset.view)
  })
}

/* ─────────────────── API de acceso ─────────────────── */

function apiToken () {
  try { return JSON.parse(localStorage.getItem(LS.api) || 'null') } catch (e) { return null }
}

async function copyText (txt, msg) {
  try {
    await navigator.clipboard.writeText(txt)
    toast(msg || 'Copiado')
  } catch (e) {
    toast('No se pudo copiar')
  }
}

async function renderApiView () {
  const p = state.pair
  if (!p) return
  const saved = apiToken()

  if (!saved) {
    $('#api-no-token').classList.remove('hidden')
    $('#api-token-block').classList.add('hidden')
    return
  }

  // El estado real vive en el servidor: actívalo/pausado
  let status = { exists: true, paused: false }
  try {
    const r = await fetch('/api/api-token?channel=' + encodeURIComponent(p.channel) +
      '&token=' + encodeURIComponent(p.read))
    const d = await r.json()
    if (d.ok) status = d
  } catch (e) { /* sin conexión: mostramos lo local */ }
  if (status.exists === false) {
    localStorage.removeItem(LS.api)
    $('#api-no-token').classList.remove('hidden')
    $('#api-token-block').classList.add('hidden')
    return
  }
  saved.paused = !!status.paused
  localStorage.setItem(LS.api, JSON.stringify(saved))

  $('#api-no-token').classList.add('hidden')
  $('#api-token-block').classList.remove('hidden')

  const st = $('#api-status')
  st.textContent = saved.paused ? '⏸ En pausa' : '● Activo'
  st.className = 'api-status ' + (saved.paused ? 'paused' : 'active')
  $('#btn-api-pause').textContent = saved.paused ? '▶ Reanudar' : '⏸ Pausar'

  $('#api-token-value').textContent = saved.token
  $('#api-channel-value').textContent = p.channel
  $('#api-url-value').textContent = serverUrl()

  // Aviso si el token es de la versión anterior (modo cifrado, sin clave envuelta)
  const modoEl = $('#api-mode-note')
  if (modoEl) {
    if (status.modo === 'cifrado') {
      modoEl.textContent = 'ℹ️ Este token es de una versión anterior (modo cifrado). Revócalo y genera uno nuevo para activar el modo descifrado.'
      modoEl.classList.remove('hidden')
    } else {
      modoEl.classList.add('hidden')
    }
  }

  const config = { baseUrl: serverUrl(), apiToken: saved.token }
  $('#api-config').textContent = JSON.stringify(config, null, 2)
  $('#api-example-curl').textContent =
    'curl -H "X-API-Token: ' + saved.token + '" "' + serverUrl() + '/api/v1/messages?limit=10"'
  $('#api-example-node').textContent =
    "const { VerificaPE } = require('./cliente-verificape')\n" +
    "const vpe = new VerificaPE(" + JSON.stringify(config) + ")\n" +
    "const pagos = await vpe.mensajes({ monto: 25, nombre: 'Maria' })"
}

async function genApiToken () {
  const p = state.pair
  if (!p) return
  const token = randB64u()
  const res = await fetch('/api/api-token?channel=' + encodeURIComponent(p.channel) +
    '&token=' + encodeURIComponent(p.read), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    // El servidor ENVUELVE la clave con el token (nunca la guarda en claro)
    body: JSON.stringify({ apiToken: token, key: p.key })
  })
  const data = await res.json().catch(() => ({}))
  if (res.ok && data.ok) {
    localStorage.setItem(LS.api, JSON.stringify({ token, paused: false }))
    toast('Token de API creado ✔')
    renderApiView()
  } else {
    toast('No se pudo crear el token: ' + (data.error || 'HTTP ' + res.status))
  }
}

async function toggleApiPause () {
  const p = state.pair
  const saved = apiToken()
  if (!p || !saved) return
  const action = saved.paused ? 'resume' : 'pause'
  const res = await fetch('/api/api-token/' + action +
    '?channel=' + encodeURIComponent(p.channel) + '&token=' + encodeURIComponent(p.read), {
    method: 'POST'
  })
  if (res.ok) {
    saved.paused = !saved.paused
    localStorage.setItem(LS.api, JSON.stringify(saved))
    toast(saved.paused ? 'Token en pausa ⏸' : 'Token reanudado ▶')
    renderApiView()
  } else {
    toast('No se pudo cambiar el estado del token')
  }
}

async function revokeApiToken () {
  const p = state.pair
  const saved = apiToken()
  if (!p || !saved) return
  if (!confirm('¿Revocar el token de API? ComercIA perderá el acceso al instante.')) return
  const res = await fetch('/api/api-token?channel=' + encodeURIComponent(p.channel) +
    '&token=' + encodeURIComponent(p.read), { method: 'DELETE' })
  if (res.ok) {
    localStorage.removeItem(LS.api)
    toast('Token revocado')
    renderApiView()
  } else {
    toast('No se pudo revocar')
  }
}

/* ─────────────────── polling ─────────────────── */

function startPolling () {
  if (state.timer) clearInterval(state.timer)
  if (state.interval > 0) {
    state.timer = setInterval(pull, state.interval)
  }
}

/* ─────────────────── eventos ─────────────────── */

function bindEvents () {
  $('#search').addEventListener('input', renderFeed)

  // Filtro Pagos / Spam / Todo
  document.querySelectorAll('.kchip').forEach(b => {
    b.onclick = () => {
      state.kindFilter = b.dataset.kind
      document.querySelectorAll('.kchip').forEach(x =>
        x.classList.toggle('active', x === b))
      renderFeed()
    }
  })

  $('#btn-refresh').onclick = pull
  $('#btn-read-all').onclick = () => {
    state.lastRead = state.cursor
    persistLastRead()
    renderFeed()
    toast('Todo marcado como leído')
  }

  $('#server-url').addEventListener('change', () => {
    let v = $('#server-url').value.trim().replace(/\/+$/, '')
    if (v && !v.startsWith('http')) {
      toast('La URL debe empezar con http:// o https://')
      $('#server-url').value = serverUrl()
      return
    }
    if (!v) return
    localStorage.setItem(LS.server, v)
    renderPairView()
    toast('Dominio del servidor actualizado')
  })

  $('#btn-url-auto').onclick = () => {
    localStorage.removeItem(LS.server)
    renderPairView()
    toast('URL automática restaurada')
  }

  $('#btn-copy').onclick = async () => {
    try {
      await navigator.clipboard.writeText($('#pair-string').value)
      toast('Código copiado')
    } catch (e) {
      $('#pair-string').select()
      document.execCommand('copy')
      toast('Código copiado')
    }
  }

  $('#btn-regen').onclick = async () => {
    if (!confirm('¿Regenerar el emparejamiento? El QR anterior dejará de funcionar.')) return
    try { await deleteChannel(false) } catch (e) { /* el canal puede no existir */ }
    await newPairing()
    await loadAll()
  }

  $('#btn-delete-channel').onclick = () => deleteChannel(true)

  $('#btn-forget').onclick = async () => {
    if (!confirm('¿Olvidar el emparejamiento en este navegador? (El canal seguirá en el servidor)')) return
    savePair(null)
    state.pair = null
    await newPairing()
  }

  $('#btn-clear-server').onclick = async () => {
    const p = state.pair
    if (!p) return
    if (!confirm('¿Borrar TODOS los mensajes cifrados de este canal en el servidor?')) return
    const res = await fetch('/api/messages?channel=' + encodeURIComponent(p.channel) +
      '&token=' + encodeURIComponent(p.read), { method: 'DELETE' })
    if (res.ok) {
      state.msgs.clear()
      state.total = 0
      renderFeed(); renderStats()
      toast('Mensajes borrados del servidor')
    } else toast('No se pudo borrar')
  }

  $('#refresh-interval').addEventListener('change', (e) => {
    state.interval = Number(e.target.value)
    localStorage.setItem(LS.interval, String(state.interval))
    startPolling()
    toast(state.interval === 0 ? 'Refresco automático desactivado' : 'Intervalo actualizado')
  })

  $('#theme').addEventListener('change', (e) => {
    document.body.dataset.theme = e.target.value
    localStorage.setItem(LS.theme, e.target.value)
  })

  // Sonido de notificación
  $('#sound-toggle').value = localStorage.getItem(LS.sound) || 'on'
  $('#sound-toggle').addEventListener('change', (e) => {
    localStorage.setItem(LS.sound, e.target.value)
    if (e.target.value === 'on') playSound()   // confirmación audible
  })
  $('#btn-test-sound').onclick = () => { unlockAudio(); playSound() }

  // API de acceso
  $('#btn-api-gen').onclick = genApiToken
  $('#btn-api-revoke').onclick = revokeApiToken
  $('#btn-api-pause').onclick = toggleApiPause
  $('#btn-copy-token').onclick = () => copyText($('#api-token-value').textContent, 'Token copiado')
  $('#btn-copy-config').onclick = () => copyText($('#api-config').textContent, 'Configuración copiada')
  $('#btn-copy-curl').onclick = () => copyText($('#api-example-curl').textContent, 'Comando copiado')
}

async function deleteChannel (alsoRegen) {
  const p = state.pair
  if (p) {
    await fetch('/api/channels?channel=' + encodeURIComponent(p.channel) +
      '&token=' + encodeURIComponent(p.read), { method: 'DELETE' }).catch(() => {})
  }
  if (alsoRegen) {
    savePair(null)
    state.pair = null
    await newPairing()
    toast('Canal eliminado. Se creó uno nuevo.')
  }
}

/* ─────────────────── arranque ─────────────────── */

async function init () {
  loadPersisted()

  // Túnel de cloudflared (el servidor lo anuncia en /api/ping)
  try {
    const ping = await fetch('/api/ping').then(r => r.json())
    if (ping && ping.tunnel) state.tunnelUrl = ping.tunnel
  } catch (e) { /* sin túnel */ }
  updateTunnelNote()

  document.body.dataset.theme = localStorage.getItem(LS.theme) || 'dark'
  $('#theme').value = document.body.dataset.theme
  $('#refresh-interval').value = String(state.interval)

  const secureOk = !!(window.crypto && crypto.subtle)
  if (!secureOk) $('#secure-warn').classList.remove('hidden')

  initSidebar()
  bindEvents()
  switchView('panel')

  try {
    if (!state.pair || !secureOk) {
      if (!state.pair) await newPairing()
      else renderPairView()
    } else {
      renderPairView()
      await loadAll()
      setOnline(true, state.sse ? 'live' : 'poll')
    }
  } catch (e) {
    console.error(e)
    toast('Error preparando el emparejamiento: ' + e.message)
  }

  startPolling()
  document.addEventListener('visibilitychange', pull)
}

document.addEventListener('DOMContentLoaded', init)
