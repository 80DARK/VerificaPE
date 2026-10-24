package com.notibridge.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService

/**
 * Cura para el bug "actualicé la app y ya no registra notificaciones":
 * tras instalar una nueva versión, Android suele DESENGANCHAR el lector
 * aunque el permiso siga "activo" en Ajustes. El requestRebind a veces no
 * basta en algunos equipos, así que usamos el truco confiable:
 * desactivar y reactivar el COMPONENTE del servicio, lo que obliga al
 * sistema a re-registrarlo y volver a enlazarlo.
 */
object ListenerKick {

    fun kick(context: Context) {
        val cn = ComponentName(context, NotiListenerService::class.java)
        val pm = context.packageManager

        // 1) Desactivar y reactivar el componente: re-registro forzado
        try {
            pm.setComponentEnabledSetting(
                cn,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                cn,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            // mejor esfuerzo
        }

        // 2) Además, pedir el reenganche explícito
        try {
            NotificationListenerService.requestRebind(cn)
        } catch (e: Exception) {
            // mejor esfuerzo
        }
    }
}
