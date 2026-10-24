package com.notibridge.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MonitoredAppDao {

    @Query("SELECT * FROM monitored_apps ORDER BY appName COLLATE NOCASE ASC")
    suspend fun getAll(): List<MonitoredAppEntity>

    // Métodos no-suspend para usar dentro de runInTransaction {}
    @Query("SELECT * FROM monitored_apps ORDER BY appName COLLATE NOCASE ASC")
    fun getAllSync(): List<MonitoredAppEntity>

    @Query("SELECT * FROM monitored_apps WHERE packageName = :pkg")
    suspend fun get(pkg: String): MonitoredAppEntity?

    @Upsert
    fun upsertAllSync(apps: List<MonitoredAppEntity>)

    @Query("DELETE FROM monitored_apps WHERE packageName NOT IN (:pkgs)")
    fun pruneNotIn(pkgs: List<String>)

    @Query("UPDATE monitored_apps SET enabled = :enabled WHERE packageName = :pkg")
    suspend fun setEnabled(pkg: String, enabled: Boolean)

    @Query("SELECT COUNT(*) FROM monitored_apps WHERE enabled = 1")
    suspend fun countEnabled(): Int
}

@Dao
interface NotificationLogDao {

    @Insert
    suspend fun insert(log: NotificationLogEntity): Long

    @Query("UPDATE notification_logs SET sent = :sent, sentAt = :at WHERE id = :id")
    suspend fun markSent(id: Long, sent: Boolean, at: Long?)

    @Query("SELECT * FROM notification_logs ORDER BY loggedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<NotificationLogEntity>

    @Query("SELECT * FROM notification_logs ORDER BY loggedAt DESC LIMIT :limit")
    fun recentSync(limit: Int): List<NotificationLogEntity>

    @Query(
        "SELECT * FROM notification_logs WHERE appName LIKE '%' || :q || '%' " +
        "OR title LIKE '%' || :q || '%' OR text LIKE '%' || :q || '%' " +
        "ORDER BY loggedAt DESC LIMIT :limit"
    )
    suspend fun search(q: String, limit: Int): List<NotificationLogEntity>

    @Query("DELETE FROM notification_logs")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM notification_logs WHERE loggedAt >= :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM notification_logs WHERE sent = 1")
    suspend fun countSent(): Int

    @Query("SELECT COUNT(*) FROM notification_logs WHERE ignoredReason IS NOT NULL")
    suspend fun countIgnored(): Int

    // ── Consultas financieras (v1.3) ──
    @Query("SELECT * FROM notification_logs WHERE isPayment = 1 ORDER BY loggedAt DESC LIMIT :limit")
    suspend fun payments(limit: Int): List<NotificationLogEntity>

    @Query(
        "SELECT * FROM notification_logs WHERE isPayment = 1 AND loggedAt >= :since " +
        "ORDER BY loggedAt DESC LIMIT :limit"
    )
    suspend fun paymentsSince(since: Long, limit: Int): List<NotificationLogEntity>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM notification_logs WHERE isPayment = 1 AND loggedAt >= :since")
    suspend fun incomeSince(since: Long): Double

    @Query("SELECT COUNT(*) FROM notification_logs WHERE isPayment = 1 AND loggedAt >= :since")
    suspend fun paymentsCountSince(since: Long): Int

    // ── Sección Spam (diagnóstico de descartes) ──
    @Query("SELECT * FROM notification_logs WHERE isPayment = 0 ORDER BY loggedAt DESC LIMIT :limit")
    suspend fun nonPayments(limit: Int): List<NotificationLogEntity>

    /** Flujo EN VIVO de descartes (se actualiza solo al llegar notificaciones). */
    @Query("SELECT * FROM notification_logs WHERE isPayment = 0 ORDER BY loggedAt DESC LIMIT :limit")
    fun nonPaymentsFlow(limit: Int): Flow<List<NotificationLogEntity>>

    /** Flujo EN VIVO de pagos desde una fecha (para Inicio). */
    @Query(
        "SELECT * FROM notification_logs WHERE isPayment = 1 AND loggedAt >= :since " +
        "ORDER BY loggedAt DESC LIMIT :limit"
    )
    fun paymentsSinceFlow(since: Long, limit: Int): Flow<List<NotificationLogEntity>>

    /** Flujo EN VIVO del total de ingresos desde una fecha. */
    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM notification_logs " +
        "WHERE isPayment = 1 AND loggedAt >= :since"
    )
    fun incomeSinceFlow(since: Long): Flow<Double>

    /** Flujo EN VIVO de la cantidad de pagos desde una fecha. */
    @Query(
        "SELECT COUNT(*) FROM notification_logs " +
        "WHERE isPayment = 1 AND loggedAt >= :since"
    )
    fun paymentsCountSinceFlow(since: Long): Flow<Int>

    /** El usuario corrige a mano un pago que el filtro descartó. */
    @Query(
        "UPDATE notification_logs SET isPayment = 1, amount = :amount, payerName = :payer, " +
        "wallet = :wallet WHERE id = :id"
    )
    suspend fun markAsPayment(id: Long, amount: Double, payer: String?, wallet: String)

    @Query("DELETE FROM notification_logs WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Deduplicación: registros recientes del mismo paquete (para comparar en código). */
    @Query(
        "SELECT * FROM notification_logs WHERE packageName = :pkg " +
        "AND loggedAt >= :desde ORDER BY id DESC LIMIT 30"
    )
    suspend fun recentForPackage(pkg: String, desde: Long): List<NotificationLogEntity>

    /** Cola de reintentos: notificaciones con contenido que aún no llegaron a la web. */
    @Query(
        "SELECT * FROM notification_logs WHERE sent = 0 AND ignoredReason IS NULL " +
        "AND text IS NOT NULL ORDER BY loggedAt ASC LIMIT :limit"
    )
    suspend fun pendingToSend(limit: Int): List<NotificationLogEntity>
}

@Dao
interface PairingDao {

    @Query("SELECT * FROM pairing ORDER BY pairedAt DESC")
    suspend fun getAll(): List<PairingEntity>

    @Query("SELECT * FROM pairing WHERE channelId = :channelId")
    suspend fun getByChannel(channelId: String): PairingEntity?

    @Query("SELECT COUNT(*) FROM pairing")
    suspend fun count(): Int

    @Insert
    suspend fun insert(pairing: PairingEntity): Long

    @Query("UPDATE pairing SET serverUrl = :serverUrl, pairedAt = :at WHERE channelId = :channelId")
    suspend fun updateServer(channelId: String, serverUrl: String, at: Long)

    @Query("DELETE FROM pairing WHERE channelId = :channelId")
    suspend fun deleteByChannel(channelId: String)

    @Query("DELETE FROM pairing")
    suspend fun clear()
}
