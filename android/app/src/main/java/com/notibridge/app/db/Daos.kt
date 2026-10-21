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
}

@Dao
interface PairingDao {

    @Query("SELECT * FROM pairing WHERE id = 1")
    suspend fun get(): PairingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(pairing: PairingEntity)

    @Query("DELETE FROM pairing")
    suspend fun clear()
}
