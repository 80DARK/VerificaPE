package com.notibridge.app

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP para hablar con el servidor web de NotiBridge.
 * Al webhook solo llega contenido YA CIFRADO (AES-256-GCM): el servidor
 * nunca ve títulos, textos ni nombres de app en claro.
 */
object WebhookClient {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** Comprueba que el servidor responde (GET /api/ping). */
    suspend fun ping(serverUrl: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder()
                .url(serverUrl.trimEnd('/') + "/api/ping")
                .get()
                .build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(false)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { if (cont.isActive) cont.resume(it.isSuccessful) }
                }
            })
        }

    /**
     * POST al webhook con el sobre cifrado:
     * { "iv": "b64url", "ct": "b64url", "algo": "AES-256-GCM", "time": epoch_ms }
     */
    suspend fun postEncrypted(
        serverUrl: String,
        channelId: String,
        writeToken: String,
        iv: ByteArray,
        ciphertext: ByteArray
    ): Boolean = suspendCancellableCoroutine { cont ->
        val body = JSONObject().apply {
            put("iv", Crypto.b64url(iv))
            put("ct", Crypto.b64url(ciphertext))
            put("algo", "AES-256-GCM")
            put("time", System.currentTimeMillis())
        }
        val request = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/api/webhook/" + channelId)
            .header("X-Write-Token", writeToken)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { if (cont.isActive) cont.resume(it.isSuccessful) }
            }
        })
    }
}
