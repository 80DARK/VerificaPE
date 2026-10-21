package com.notibridge.app

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/** Utilidad para saber si el servicio de acceso a notificaciones está activo. */
object NotiAccess {

    fun isEnabled(context: Context): Boolean {
        val flat = try {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        } catch (e: Exception) {
            null
        } ?: return false
        val cn = ComponentName(context, NotiListenerService::class.java)
        return flat.split(":").any { it.equals(cn.flattenToString(), ignoreCase = true) }
    }
}
