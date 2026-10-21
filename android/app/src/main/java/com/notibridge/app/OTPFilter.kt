package com.notibridge.app

import android.content.Context
import android.provider.Telephony

/**
 * Filtro de códigos OTP. SIEMPRE activo (no se puede desactivar):
 * NotiBridge nunca reenvía códigos de un solo uso.
 *
 * Bloquea:
 *  1. Toda notificación de la app de SMS predeterminada del dispositivo.
 *  2. Cualquier notificación (de cualquier app) cuyo contenido parezca
 *     un código de verificación (palabras clave + rango de dígitos).
 */
object OTPFilter {

    private val KEYWORDS = listOf(
        "otp", "código", "codigo", "code", "verificación", "verificacion",
        "verification", "verifica", "pin", "passcode", "contraseña",
        "contrasena", "one-time", "one time", "2fa", "doble factor",
        "security code", "código de seguridad", "token"
    )

    // 3 a 8 dígitos seguidos (los OTP más comunes)
    private val DIGITS = Regex("""\b\d{3,8}\b""")

    /**
     * @return null si la notificación está permitida, o el motivo de bloqueo.
     */
    fun check(context: Context, pkg: String, title: String, text: String): String? {
        // 1) App de SMS predeterminada: se bloquea entera.
        val smsPkg = try {
            Telephony.Sms.getDefaultSmsPackage(context)
        } catch (e: Exception) {
            null
        }
        if (smsPkg != null && pkg == smsPkg) return "APP_SMS"

        // 2) Contenido con apariencia de OTP.
        val content = "$title $text"
        val lower = content.lowercase()
        if (DIGITS.containsMatchIn(content) && KEYWORDS.any { lower.contains(it) }) {
            return "OTP"
        }
        return null
    }
}
