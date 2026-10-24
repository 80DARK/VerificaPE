package com.notibridge.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.notibridge.app.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale

/**
 * GUARDIÁN — servicio en PRIMER PLANO que mantiene vivo el proceso de
 * VerificaPE. Sin esto, los equipos con gestión agresiva de batería
 * (Xiaomi, Huawei, Oppo, Vivo…) matan el proceso al cerrar la app o
 * bloquear el teléfono, y con él el lector de notificaciones.
 *
 * Además muestra el total del día en una notificación persistente:
 * "VerificaPE · escuchando pagos — Hoy: S/ xx.xx · N pagos".
 */
class GuardianService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var totalHoy = 0.0
    private var nPagos = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VerificaPE escuchando", NotificationManager.IMPORTANCE_LOW)
            )
        }

        // Arrancar en primer plano YA (obligatorio en menos de 5 s)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, construirNotificacion(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, construirNotificacion())
        }

        // Total del día en vivo dentro de la notificación persistente
        scope.launch {
            val db = AppDatabase.get(applicationContext)
            val inicio = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            launch {
                db.logs().incomeSinceFlow(inicio).collectLatest { total ->
                    totalHoy = total
                    repintar()
                }
            }
            launch {
                db.logs().paymentsCountSinceFlow(inicio).collectLatest { n ->
                    nPagos = n
                    repintar()
                }
            }
        }

        // COLA DE REINTENTOS: cada 60 s reenvía lo que no llegó a la web
        // (cubre el caso "el equipo bloquea la red en segundo plano").
        scope.launch {
            // primer intento apenas arranca el Guardián
            reintentarConWakelock()
            while (true) {
                kotlinx.coroutines.delay(60_000)
                reintentarConWakelock()
            }
        }

        return START_STICKY   // si el sistema lo mata, lo reinicia
    }

    private suspend fun reintentarConWakelock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            val wl = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "verificape:reintento"
            ).also { it.acquire(30_000) }
            try {
                Sender.reintentarPendientes(applicationContext)
            } finally {
                if (wl.isHeld) wl.release()
            }
        } catch (e: Exception) {
            // mejor esfuerzo
        }
    }

    private fun construirNotificacion(): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_home)
            .setContentTitle("VerificaPE · escuchando pagos")
            .setContentText(textoDelDia())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()

    private fun textoDelDia(): String {
        val money = NumberFormat.getNumberInstance(Locale("es", "PE")).apply {
            maximumFractionDigits = 2; minimumFractionDigits = 2
        }
        return "Hoy: S/ ${money.format(totalHoy)} · $nPagos pago(s)"
    }

    private fun repintar() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, construirNotificacion())
        } catch (e: Exception) { /* mejor esfuerzo */ }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "verificape_guardian"
        private const val NOTIF_ID = 100

        fun start(context: Context) {
            try {
                val intent = Intent(context, GuardianService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) { /* mejor esfuerzo */ }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, GuardianService::class.java))
            } catch (e: Exception) { /* mejor esfuerzo */ }
        }
    }
}
