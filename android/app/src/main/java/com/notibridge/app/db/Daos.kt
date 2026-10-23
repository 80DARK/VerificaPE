package com.notibridge.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

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
