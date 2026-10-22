package com.notibridge.app

import android.content.Context
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

        val (iv, ct) = Crypto.encryptAesGcm(key, payload.toString().toByteArray(Charsets.UTF_8))
        return WebhookClient.postEncrypted(serverUrl, channelId, secret.writeTokenB64, iv, ct)
    }
}
