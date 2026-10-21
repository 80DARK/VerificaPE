package com.notibridge.app

import android.net.Uri

data class PairingInfo(
    val serverUrl: String,
    val channelId: String,
    val keyB64: String,      // clave AES-256 en base64url (32 bytes)
    val writeTokenB64: String // token de escritura del webhook en base64url
)

/**
 * Parsea el contenido del QR generado por la web. Formato:
 *
 *   notibridge://pair?server=<url codificada>&channel=<uuid>&key=<b64url>&token=<b64url>
 */
object PairingParser {

    fun parse(raw: String): PairingInfo? {
        return try {
            val uri = Uri.parse(raw.trim())
            if (uri.scheme != "notibridge" || uri.host != "pair") return null
            val server = uri.getQueryParameter("server") ?: return null
            val channel = uri.getQueryParameter("channel") ?: return null
            val key = uri.getQueryParameter("key") ?: return null
            val token = uri.getQueryParameter("token") ?: return null
            if (!server.startsWith("http")) return null
            if (channel.length !in 8..64) return null
            if (key.length !in 40..48) return null   // 32 bytes en base64url
            if (token.isEmpty()) return null
            PairingInfo(server.trimEnd('/'), channel, key, token)
        } catch (e: Exception) {
            null
        }
    }
}
