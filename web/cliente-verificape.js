'use strict'

/* ══════════════════════════════════════════════════════════════════
   VerificaPE · Cliente para bots (ComercIA y otros)
   ══════════════════════════════════════════════════════════════════
   Copia este archivo a tu proyecto (Node 18+, sin dependencias) y:

     const { VerificaPE } = require('./cliente-verificape')

     const vpe = new VerificaPE({
       baseUrl:  'https://tu-servidor.com',   // donde corre el panel
       apiToken: '...',                        // Panel web → sección API
       aesKey:   '...'                         // clave AES del QR (b64url)
     })

     // ¿Llegó el pago de 25 soles de María hoy?
     const pagos = await vpe.mensajes({ monto: 25, nombre: 'Maria' })

     const ultimo = await vpe.ultimo()          // { id, app, title, text, ... }

   El servidor nunca ve el contenido: entrega los mensajes CIFRADOS y
   este cliente los descifra y filtra AQUÍ, en tu bot. Filtros soportados:
   q (palabra clave), nombre, monto (S/), fecha (día), app, desde, hasta,
   desdeId, limite.
   ══════════════════════════════════════════════════════════════════ */

const crypto = require('crypto')

class VerificaPE {
  constructor ({ baseUrl, apiToken, aesKey }) {
    if (!baseUrl || !apiToken || !aesKey) {
      throw new Error('VerificaPE: faltan baseUrl, apiToken o aesKey')
    }
    this.baseUrl = baseUrl.replace(/\/+$/, '')
    this.apiToken = apiToken
    this.key = Buffer.from(aesKey, 'base64url')
    if (this.key.length !== 32) {
      throw new Error('VerificaPE: aesKey debe ser base64url de 32 bytes (la del QR)')
    }
  }

  /** Descifra un mensaje del servidor (AES-256-GCM, ct||tag). */
  _decrypt (m) {
    const iv = Buffer.from(m.iv, 'base64url')
    const blob = Buffer.from(m.ct, 'base64url')
    const decipher = crypto.createDecipheriv('aes-256-gcm', this.key, iv)
    decipher.setAuthTag(blob.subarray(blob.length - 16))
    return JSON.parse(
      Buffer.concat([decipher.update(blob.subarray(0, blob.length - 16)), decipher.final()]).toString('utf8')
    )
  }

  /** Descarga (y descifra) mensajes con filtros de SERVIDOR: fechas e ids. */
  async _fetch ({ desde, hasta, desdeId, limite } = {}) {
    const q = new URLSearchParams()
    if (desdeId !== undefined) q.set('since', String(desdeId))
    if (desde !== undefined) q.set('from', String(new Date(desde).getTime()))
    if (hasta !== undefined) q.set('to', String(new Date(hasta).getTime()))
    q.set('limit', String(limite || 500))
    const res = await fetch(this.baseUrl + '/api/v1/messages?' + q.toString(), {
      headers: { 'X-API-Token': this.apiToken }
    })
    const data = await res.json()
    if (!res.ok || !data.ok) {
      throw new Error('VerificaPE API: HTTP ' + res.status + ' ' + JSON.stringify(data))
    }
    return data.messages.map(m => {
      try {
        const plain = this._decrypt(m)
        return { id: m.id, recibidoEn: m.received_at, ilegible: false, ...plain }
      } catch (e) {
        return { id: m.id, recibidoEn: m.received_at, ilegible: true }
      }
    })
  }

  /** Fecha exacta respetando la zona horaria local (acepta Date, ms, 'YYYY-MM-DD', 'DD/MM/YYYY'). */
  _dia (v) {
    if (v instanceof Date) return v
    const s = String(v)
    let m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s)
    if (m) return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]))       // local
    m = /^(\d{2})[\/](\d{2})[\/](\d{4})$/.exec(s)
    if (m) return new Date(Number(m[3]), Number(m[2]) - 1, Number(m[1]))        // local
    return new Date(v)                                                          // ms o ISO
  }

  /**
   * Busca notificaciones con TODOS los filtros pedidos.
   * @param {object} f
   *   q      palabra clave (busca en título + texto)
   *   nombre nombre de la persona (texto)
   *   monto  monto en soles (S/ 25 o 25.00 → 25)
   *   fecha  día exacto: 'YYYY-MM-DD', 'DD/MM/YYYY' u objeto Date
   *   app    nombre de app (p. ej. 'Yape', 'Plin')
   *   desde/hasta rango de fecha (string/Date/ms) — filtro del servidor
   *   desdeId id mínimo — filtro del servidor
   *   limite  máx. mensajes (default 500)
   */
  async mensajes (f = {}) {
    const list = await this._fetch(f)
    return list.filter(msg => {
      if (msg.ilegible) return false
      const texto = ((msg.title || '') + ' ' + (msg.text || '')).toLowerCase()

      if (f.q !== undefined && !texto.includes(String(f.q).toLowerCase())) return false
      if (f.nombre !== undefined && !texto.includes(String(f.nombre).toLowerCase())) return false
      if (f.app !== undefined &&
          !String(msg.app || '').toLowerCase().includes(String(f.app).toLowerCase())) return false

      if (f.monto !== undefined) {
        const objetivo = typeof f.monto === 'number' ? f.monto : parseFloat(f.monto)
        const montos = [...texto.matchAll(/(?:s\/|soles?)\s*([\d,]+(?:\.\d{1,2})?)/g)]
          .map(x => parseFloat(x[1].replace(/,/g, '')))
        if (!montos.some(v => Math.abs(v - objetivo) < 0.011)) return false
      }

      if (f.fecha !== undefined) {
        const d = this._dia(f.fecha)
        if (isNaN(d.getTime())) return false
        if (new Date(msg.recibidoEn).toDateString() !== d.toDateString()) return false
      }
      return true
    })
  }

  /** La notificación más reciente (opcionalmente filtrada). */
  async ultimo (f = {}) {
    const list = await this.mensajes({ limite: 100, ...f })
    return list[0] || null
  }

  /** Ping al servicio API. */
  async ping () {
    const res = await fetch(this.baseUrl + '/api/v1/ping')
    const data = await res.json()
    return data.ok === true
  }

  /** Total de mensajes cifrados almacenados del canal. */
  async total () {
    const res = await fetch(this.baseUrl + '/api/v1/stats', {
      headers: { 'X-API-Token': this.apiToken }
    })
    const data = await res.json()
    if (!res.ok || !data.ok) throw new Error('VerificaPE API: HTTP ' + res.status)
    return data.total
  }
}

module.exports = VerificaPE
module.exports.VerificaPE = VerificaPE
