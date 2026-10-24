package com.notibridge.app

import android.content.Context
import android.os.Build
import com.notibridge.app.db.AppDatabase
import org.json.JSONObject

/**
 * Envía un payload a TODOS los paneles emparejados (multi-QR), cifrándolo
 * con la clave AES de cada canal (cada QR tiene su propia clave).
 * Si no hay emparejamientos, no hace nada.
 */
object Sender {

    suspend fun send(context: Context, payload: JSONObject): Boolean {
        val db = AppDatabase.get(context)
        val secrets = SecureStore.readPairings(context).associateBy { it.channelId }
        val pairings = db.pairing().getAll()
        if (pairings.isEmpty()) return false

        var anySuccess = false
        for (p in pairings) {
            if (sendToChannel(p.serverUrl, p.channelId, secrets[p.channelId], payload)) {
                anySuccess = true
            }
        }
        return anySuccess
    }

    /** Envía a UN canal concreto (botón «Probar» de cada emparejamiento). */
    suspend fun sendTo(context: Context, channelId: String, payload: JSONObject): Boolean {
        val db = AppDatabase.get(context)
        val pairing = db.pairing().getByChannel(channelId) ?: return false
        val secret = SecureStore.readPairings(context).firstOrNull { it.channelId == channelId }
        return sendToChannel(pairing.serverUrl, channelId, secret, payload)
    }

    /**
     * COLA DE REINTENTOS: reenvía las notificaciones capturadas que aún no
     * llegaron a la web (p. ej. el envío falló con la app en segundo plano
     * porque el equipo bloqueó la red). El webhook es idempotente (dedupe
     * por canal+hora), así que reintentar nunca duplica en el panel.
     * @return cuántas se lograron enviar.
     */
    suspend fun reintentarPendientes(context: Context, limite: Int = 50): Int {
        val appCtx = context.applicationContext
        val db = AppDatabase.get(appCtx)
        val pendientes = try {
            db.logs().pendingToSend(limite)
        } catch (e: Exception) {
            return 0
        }
        if (pendientes.isEmpty()) return 0

        var exitos = 0
        for (p in pendientes) {
            val payload = JSONObject().apply {
                put("v", 2)
                put("type", if (p.isPayment) "payment" else "notification")
                put("ref", p.id)
                put("device", Build.MODEL)
                put("app", p.appName)
                put("pkg", p.packageName)
                put("title", p.title ?: "")
                put("text", p.text ?: "")
                put("time", p.postTime)
                if (p.isPayment) {
                    put("amount", p.amount ?: 0.0)
                    put("payer", p.payerName ?: "")
                    put("wallet", p.wallet ?: p.appName)
                }
            }
            val ok = try {
                send(appCtx, payload)
            } catch (e: Exception) {
                false
            }
            if (ok) {
                db.logs().markSent(p.id, true, System.currentTimeMillis())
                exitos++
            }
        }
        return exitos
    }

    private suspend fun sendToChannel(
        serverUrl: String,
        channelId: String,
        secret: SecureStore.PairSecret?,
        payload: JSONObject
    ): Boolean {
        if (secret == null) return false
        val key = try {
            Crypto.b64urlDec(secret.keyB64)
        } catch (e: Exception) {
            return false
        }
        if (key.size != 32) return false

        // Hora de la NOTIFICACIÓN como clave de idempotencia del servidor:
        // un reintento envía la misma hora y no duplica en el panel.
        val notifTime = payload.optLong("time", System.currentTimeMillis())

        val (iv, ct) = Crypto.encryptAesGcm(key, payload.toString().toByteArray(Charsets.UTF_8))
        return WebhookClient.postEncrypted(serverUrl, channelId, secret.writeTokenB64, iv, ct, notifTime)
    }
}
