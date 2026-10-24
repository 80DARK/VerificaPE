package com.notibridge.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Tras reiniciar el teléfono, el sistema reengancha solo el lector de
 * notificaciones; este receptor además arranca el Guardián (servicio en
 * primer plano) para que el proceso no lo maten los ahorro-batería
 * agresivos cuando bloqueas o cierras la app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            Prefs.get(context).getBoolean(Prefs.LISTENING, true)
        ) {
            GuardianService.start(context)
        }
    }
}
