package com.notibridge.app

import android.content.Context
import com.notibridge.app.db.AppDatabase
import org.json.JSONObject

/**
 * Envía un payload a la web cifrándolo de extremo a extremo con la clave
 * compartida por QR. Si no hay emparejamiento, no hace nada.
 */
object Sender {

    suspend fun send(context: Context, payload: JSONObject): Boolean {
        val pairing = AppDatabase.get(context).pairing().get() ?: return false
        val key = SecureStore.getAesKey(context) ?: return false
        val token = SecureStore.getWriteToken(context) ?: return false
        val (iv, ct) = Crypto.encryptAesGcm(key, payload.toString().toByteArray(Charsets.UTF_8))
        return WebhookClient.postEncrypted(pairing.serverUrl, pairing.channelId, token, iv, ct)
    }
}
