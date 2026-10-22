package com.notibridge.app

import android.content.Context

/**
 * Preferencias generales (no sensibles) de NotiBridge.
 * La clave AES y el token de escritura NO viven aquí: van en [SecureStore].
 */
object Prefs {
    const val FORWARD = "forward_enabled"       // Interruptor maestro de reenvío
    const val IGNORE_ONGOING = "ignore_ongoing" // Ignorar notificaciones "en curso" (música, llamadas)
    const val SAVE_HISTORY = "save_history"     // Guardar historial local
    const val LISTENING = "listening_enabled"   // Modo escucha (false = servicio dormido)

    fun get(context: Context) =
        context.getSharedPreferences("notibridge_prefs", Context.MODE_PRIVATE)
}
