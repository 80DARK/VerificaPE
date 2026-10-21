package com.notibridge.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Almacenamiento cifrado en reposo (Android Keystore) para los secretos
 * de emparejamiento: la clave AES-256 y el token de escritura del webhook.
 * El servidor NUNCA conoce la clave AES; solo recibe datos ya cifrados.
 */
object SecureStore {

    private const val PREFS_FILE = "nb_secure_prefs"
    private const val KEY_AES = "aes_key"
    private const val KEY_TOKEN = "write_token"

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences = synchronized(this) {
        prefs ?: run {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.also { prefs = it }
    }

    fun savePairing(context: Context, keyB64: String, writeTokenB64: String) {
        prefs(context).edit()
            .putString(KEY_AES, keyB64)
            .putString(KEY_TOKEN, writeTokenB64)
            .apply()
    }

    fun getAesKey(context: Context): ByteArray? =
        prefs(context).getString(KEY_AES, null)?.let { Crypto.b64urlDec(it) }

    fun getWriteToken(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_AES).remove(KEY_TOKEN).apply()
    }
}
