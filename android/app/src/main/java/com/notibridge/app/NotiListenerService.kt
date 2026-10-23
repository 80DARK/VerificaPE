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
        // Nunca nos miramos a nosotros mismos
        if (sbn.packageName == packageName) return

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

        // Opción: ignorar notificaciones "en curso" (música, llamadas, navegación)
        val prefs = Prefs.get(this)
        if (prefs.getBoolean(Prefs.IGNORE_ONGOING, true) &&
            (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        ) return

        // Resúmenes de grupo (duplicados) -> fuera
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val db = AppDatabase.get(this)
        val monitored = db.apps().get(sbn.packageName) ?: return
        if (!monitored.enabled) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = listOf(
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_SUB_TEXT
        ).firstNotNullOfOrNull { extras.getCharSequence(it)?.toString() } ?: ""

        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (e: Exception) {
            monitored.appName
        }

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

        // Por defecto SOLO se envían los pagos (las promos ensuciarían la caja)
        val soloPagos = prefs.getBoolean(Prefs.PAYMENTS_ONLY, true)
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
    }
}
