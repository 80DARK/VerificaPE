package com.notibridge.app.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Una app instalada en el dispositivo y si se monitorean o no sus notificaciones. */
@Entity(tableName = "monitored_apps")
data class MonitoredAppEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val enabled: Boolean = false,   // Por defecto TODO desactivado
    val smsProvider: Boolean = false // App de SMS: bloqueada siempre por el filtro OTP
)

/** Registro local de notificaciones capturadas (historial propio de la app). */
@Entity(tableName = "notification_logs")
data class NotificationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,          // null si fue bloqueada por el filtro OTP (no guardamos el contenido)
    val postTime: Long,         // cuándo ocurrió la notificación (epoch ms)
    val loggedAt: Long,         // cuándo la registramos (epoch ms)
    val ignoredReason: String? = null, // "APP_SMS" u "OTP" si fue bloqueada
    val sent: Boolean = false,  // se envió con éxito al servidor
    val sentAt: Long? = null
)

/** Datos del emparejamiento actual con la web (solo 1 fila, id = 1). */
@Entity(tableName = "pairing")
data class PairingEntity(
    @PrimaryKey val id: Long = 1L,
    val serverUrl: String,
    val channelId: String,
    val pairedAt: Long
)
