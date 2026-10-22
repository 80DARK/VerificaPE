package com.notibridge.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Almacenamiento cifrado en reposo (Android Keystore) para los secretos de
 * TODOS los emparejamientos (multi-QR): por cada canal guarda su clave
 * AES-256 y su token de escritura del webhook.
 */
object SecureStore {

    private const val PREFS_FILE = "nb_secure_prefs"
    private const val KEY_PAIRINGS = "pairings"

    // Claves del formato antiguo (v1.x, un solo canal) para migrar
    private const val LEGACY_KEY_AES = "aes_key"
    private const val LEGACY_KEY_TOKEN = "write_token"

    data class PairSecret(
        val channelId: String,
        val keyB64: String,
        val writeTokenB64: String
    )

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

    private fun encode(list: List<PairSecret>): String {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("channel", s.channelId)
                    .put("key", s.keyB64)
                    .put("token", s.writeTokenB64)
            )
        }
        return arr.toString()
    }

    private fun decode(raw: String?): List<PairSecret> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PairSecret(
                    channelId = o.getString("channel"),
                    keyB64 = o.getString("key"),
                    writeTokenB64 = o.getString("token")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun readPairings(context: Context): List<PairSecret> =
        decode(prefs(context).getString(KEY_PAIRINGS, null))

    /** Añade o reemplaza (mismo canal) un emparejamiento. */
    fun savePairing(context: Context, channelId: String, keyB64: String, writeTokenB64: String) {
        val list = readPairings(context).filter { it.channelId != channelId } +
            PairSecret(channelId, keyB64, writeTokenB64)
        prefs(context).edit().putString(KEY_PAIRINGS, encode(list)).apply()
    }

    fun removePairing(context: Context, channelId: String) {
        val list = readPairings(context).filter { it.channelId != channelId }
        prefs(context).edit().putString(KEY_PAIRINGS, encode(list)).apply()
    }

    fun clearAllPairings(context: Context) {
        prefs(context).edit()
            .remove(KEY_PAIRINGS)
            .remove(LEGACY_KEY_AES)
            .remove(LEGACY_KEY_TOKEN)
            .apply()
    }

    /**
     * Migración v1 → v1.2: el formato antiguo guardaba UNA clave y UN token
     * sueltos. Si existen y el canal sigue en la DB, se convierten al formato
     * multi-emparejamiento. Llamar una vez al abrir la app.
     */
    fun migrateLegacy(context: Context, legacyChannelId: String?) {
        val p = prefs(context)
        val oldKey = p.getString(LEGACY_KEY_AES, null) ?: return
        val oldToken = p.getString(LEGACY_KEY_TOKEN, null) ?: return
        val channelId = legacyChannelId ?: return
        if (readPairings(context).none { it.channelId == channelId }) {
            savePairing(context, channelId, oldKey, oldToken)
        }
        p.edit().remove(LEGACY_KEY_AES).remove(LEGACY_KEY_TOKEN).apply()
    }
}
