package com.notibridge.app

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.notibridge.app.db.AppDatabase
import com.notibridge.app.db.NotificationLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Servicio que escucha las notificaciones del dispositivo.
 *
 * Reglas de VerificaPE:
 *  - Solo se procesan las notificaciones de apps ACTIVADAS por el usuario
 *    (por defecto ninguna lo está).
 *  - Las notificaciones de la app de SMS y las que parecen códigos OTP se
 *    IGNORAN SIEMPRE (solo queda constancia del bloqueo, sin contenido).
 *  - Las notificaciones se analizan con [PaymentFilter]: los PAGOS reales
 *    quedan registrados con monto, pagador y billetera; las promos no cuentan.
 *  - Lo que se envía a la web viaja cifrado de extremo a extremo.
 *  - MODO DORMIR: el botón circular de Inicio suspende la lectura al instante.
 */
class NotiListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** El sistema nos reenganchó (arranque, tras actualización, tras OEM). */
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Listener conectado (reenganche del sistema)")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return

        // Nuestras propias notificaciones solo pasan si son la PRUEBA DEL LECTOR
        val selfTest = sbn.packageName == packageName
        if (selfTest) {
            val t = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            if (!t.contains("[VPE-TEST]")) return
        }

        // MODO DORMIR: si el dueño apagó la escucha, ignorar TODO
        // (sin leer, sin guardar, sin enviar). Se reactiva con el botón de Inicio.
        if (!Prefs.get(this).getBoolean(Prefs.LISTENING, true)) return

        // Blindaje: NINGÚN error puede tumbar el proceso (un crash
        // desengancharía el listener hasta reactivarlo en Ajustes).
        try {
            scope.launch { process(sbn) }
        } catch (e: Exception) {
            Log.e(TAG, "onNotificationPosted", e)
        }
    }

    private suspend fun process(sbn: StatusBarNotification) {
        try {
            processInner(sbn)
        } catch (e: Exception) {
            Log.e(TAG, "process", e)
        }
    }

    private suspend fun processInner(sbn: StatusBarNotification) {
        val notification: Notification = sbn.notification ?: return
        val selfTest = sbn.packageName == packageName

        // Opción: ignorar notificaciones "en curso" (música, llamadas, navegación)
        val prefs = Prefs.get(this)
        if (!selfTest &&
            prefs.getBoolean(Prefs.IGNORE_ONGOING, true) &&
            (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        ) return

        // v1.3.1: los resúmenes de grupo YA SE PROCESAN (algunas billeteras
        // solo publican el contenido en el resumen). Los duplicados se
        // eliminan abajo con deduplicación por texto en 15 s.

        val db = AppDatabase.get(this)
        val monitored = if (selfTest) null else db.apps().get(sbn.packageName)
        if (!selfTest) {
            if (monitored == null) return
            if (!monitored.enabled) return
        }

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = listOf(
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_SUB_TEXT
        ).firstNotNullOfOrNull { extras.getCharSequence(it)?.toString() } ?: ""

        val appName = if (selfTest) {
            "VerificaPE"
        } else {
            try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(sbn.packageName, 0)
                ).toString()
            } catch (e: Exception) {
                monitored?.appName ?: sbn.packageName
            }
        }

        // Deduplicación robusta: la misma notificación puede llegar como hija
        // de grupo y como resumen (a veces con textos ligeramente distintos).
        val recientes = db.logs().recentForPackage(
            sbn.packageName, System.currentTimeMillis() - 90_000
        )
        val duplicado = recientes.any { r ->
            // Mismo instante de publicación (hija y resumen van en el mismo lote)
            (kotlin.math.abs(r.postTime - sbn.postTime) < 5_000) ||
                // O mismo título + texto normalizado (re-publicación de la misma)
                (r.title == title && r.text != null && text.isNotBlank() &&
                    normalizar(r.text!!) == normalizar(text))
        }
        if (duplicado) return

        // ---- Filtro OTP (siempre activo) ----
        val otpReason = OTPFilter.check(this, sbn.packageName, title, text)
        if (otpReason != null) {
            // Registramos el bloqueo SIN guardar el contenido (privacidad)
            if (prefs.getBoolean(Prefs.SAVE_HISTORY, true)) {
                db.logs().insert(
                    NotificationLogEntity(
                        packageName = sbn.packageName,
                        appName = appName,
                        title = null,
                        text = null,
                        postTime = sbn.postTime,
                        loggedAt = System.currentTimeMillis(),
                        ignoredReason = otpReason
                    )
                )
            }
            return
        }

        // ---- Análisis financiero: ¿es un PAGO real? ----
        val pago = try {
            PaymentFilter.analizar(title, text)
        } catch (e: Exception) {
            Log.e(TAG, "PaymentFilter", e); null
        }
        val wallet = Wallets.detect(appName)

        // ---- Historial local ----
        val saveHistory = prefs.getBoolean(Prefs.SAVE_HISTORY, true)
        val logId = if (saveHistory) {
            db.logs().insert(
                NotificationLogEntity(
                    packageName = sbn.packageName,
                    appName = appName,
                    title = title,
                    text = text,
                    postTime = sbn.postTime,
                    loggedAt = System.currentTimeMillis(),
                    isPayment = pago != null,
                    amount = pago?.amount,
                    payerName = pago?.payerName,
                    wallet = if (pago != null) wallet.name else null
                )
            )
        } else 0L

        // ---- Reenvío cifrado al webhook ----
        if (!prefs.getBoolean(Prefs.FORWARD, true)) return

        // Por defecto se envía TODO al panel (pagos y spam); el panel filtra.
        val soloPagos = prefs.getBoolean(Prefs.PAYMENTS_ONLY, false)
        if (soloPagos && pago == null) return

        val payload = org.json.JSONObject().apply {
            put("v", 2)
            put("type", if (pago != null) "payment" else "notification")
            put("ref", logId)
            put("device", Build.MODEL)
            put("app", appName)
            put("pkg", sbn.packageName)
            put("title", title)
            put("text", text)
            put("time", sbn.postTime)
            if (pago != null) {
                put("amount", pago.amount)
                put("payer", pago.payerName ?: "")
                put("wallet", wallet.name)
            }
        }

        val ok = Sender.send(this, payload)
        if (logId > 0 && ok) {
            db.logs().markSent(logId, true, System.currentTimeMillis())
        }
    }

    companion object {
        private const val TAG = "VerificaPE"

        /** Colapsa espacios y recorta: para comparar textos casi idénticos. */
        private fun normalizar(s: String) = s.replace(Regex("\\s+"), " ").trim().take(60)
    }
}
