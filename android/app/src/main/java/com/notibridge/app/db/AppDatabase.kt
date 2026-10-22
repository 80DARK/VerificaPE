package com.notibridge.app.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Base de datos LOCAL de la app (su propio historial y configuración).
 * La web tiene su propia DB independiente en el servidor.
 */
@Database(
    entities = [
        MonitoredAppEntity::class,
        NotificationLogEntity::class,
        PairingEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun apps(): MonitoredAppDao
    abstract fun logs(): NotificationLogDao
    abstract fun pairing(): PairingDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v1 → v2: la tabla 'pairing' pasa de 1 canal fijo a MULTIPLES canales
         * (multi-QR). Se conservan los pareos existentes.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pairing_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`serverUrl` TEXT NOT NULL, " +
                        "`channelId` TEXT NOT NULL, " +
                        "`pairedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO pairing_new (serverUrl, channelId, pairedAt) " +
                        "SELECT serverUrl, channelId, pairedAt FROM pairing"
                )
                db.execSQL("DROP TABLE pairing")
                db.execSQL("ALTER TABLE pairing_new RENAME TO pairing")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_pairing_channelId` ON `pairing` (`channelId`)")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "notibridge.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
