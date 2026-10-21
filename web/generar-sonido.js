'use strict'

/**
 * Genera el sonido de notificación de VerificaPE (campanilla ascendente
 * "di-ding" tipo pago recibido) como WAV 16-bit PCM, sin dependencias.
 *
 * Uso:  node generar-sonido.js   →  crea public/sounds/notif.wav
 */

const fs = require('fs')
const path = require('path')

const SR = 44100          // muestras por segundo
const DUR = 0.85          // segundos
const N = Math.floor(SR * DUR)
const samples = new Float32Array(N)

/** Añade una campana: seno + armónico con ataque suave y caída exponencial. */
function addTone (start, freq, amp, decay) {
  const s0 = Math.floor(start * SR)
  for (let i = 0; s0 + i < N; i++) {
    const t = i / SR
    const env = Math.exp(-t * decay) * Math.min(1, t / 0.004) // ataque 4 ms, sin clics
    const w = Math.sin(2 * Math.PI * freq * t) +
              0.35 * Math.sin(4 * Math.PI * freq * t)       // armónico brillante
    samples[s0 + i] += amp * env * w
  }
}

// "Di—ding" ascendente y positivo: E6 → G6
addTone(0.00, 1318.51, 0.42, 7)
addTone(0.14, 1567.98, 0.50, 5)

// Normalizar a 0.85 de pico
let max = 0
for (const s of samples) max = Math.max(max, Math.abs(s))
const norm = max > 0 ? 0.85 / max : 1

const data = Buffer.alloc(N * 2)
for (let i = 0; i < N; i++) {
  const v = Math.max(-1, Math.min(1, samples[i] * norm))
  data.writeInt16LE(Math.round(v * 32767), i * 2)
}

const header = Buffer.alloc(44)
header.write('RIFF', 0)
header.writeUInt32LE(36 + data.length, 4)
header.write('WAVE', 8)
header.write('fmt ', 12)
header.writeUInt32LE(16, 16)
header.writeUInt16LE(1, 20)      // PCM
header.writeUInt16LE(1, 22)     // mono
header.writeUInt32LE(SR, 24)
header.writeUInt32LE(SR * 2, 28)
header.writeUInt16LE(2, 32)
header.writeUInt16LE(16, 34)
header.write('data', 36)
header.writeUInt32LE(data.length, 40)

const outDir = path.join(__dirname, 'public', 'sounds')
fs.mkdirSync(outDir, { recursive: true })
fs.writeFileSync(path.join(outDir, 'notif.wav'), Buffer.concat([header, data]))
console.log('OK: public/sounds/notif.wav generado (' + (DUR) + ' s, ' + N + ' muestras)')
