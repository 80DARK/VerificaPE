package com.notibridge.app

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.notibridge.app.db.AppDatabase
import com.notibridge.app.db.NotificationLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Servicio que escucha las notificaciones del dispositivo.
 *
 * Reglas de NotiBridge:
 *  - Solo se procesan las notificaciones de apps ACTIVADAS por el usuario
 *    (por defecto ninguna lo está).
 *  - Las notificaciones de la app de SMS y las que parecen códigos OTP se
 *    IGNORAN SIEMPRE (solo queda constancia del bloqueo, sin contenido).
 *  - Lo que se envía a la web viaja cifrado de extremo a extremo.
 */
class NotiListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Nunca nos miramos a nosotros mismos
        if (sbn.packageName == packageName) return
        scope.launch { process(sbn) }
    }

    private suspend fun process(sbn: StatusBarNotification) {
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
                    loggedAt = System.currentTimeMillis()
                )
            )
        } else 0L

        // ---- Reenvío cifrado al webhook ----
        if (!prefs.getBoolean(Prefs.FORWARD, true)) return

        val payload = org.json.JSONObject().apply {
            put("v", 1)
            put("type", "notification")
            put("ref", logId)
            put("device", Build.MODEL)
            put("app", appName)
            put("pkg", sbn.packageName)
            put("title", title)
            put("text", text)
            put("time", sbn.postTime)
        }

        val ok = Sender.send(this, payload)
        if (logId > 0 && ok) {
            db.logs().markSent(logId, true, System.currentTimeMillis())
        }
    }
}
