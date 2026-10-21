package com.notibridge.app.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun apps(): MonitoredAppDao
    abstract fun logs(): NotificationLogDao
    abstract fun pairing(): PairingDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "notibridge.db"
                ).build().also { instance = it }
            }
    }
}
