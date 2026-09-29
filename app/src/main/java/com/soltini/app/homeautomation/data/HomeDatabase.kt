package com.soltini.app.homeautomation.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.launch

/**
 * DeviceConverters
 *
 * Room TypeConverters to seamlessly persist DeviceType enums as Strings.
 */
class DeviceConverters {
    @TypeConverter
    fun fromDeviceType(value: DeviceType?): String {
        return value?.name ?: DeviceType.CUSTOM.name
    }

    @TypeConverter
    fun toDeviceType(value: String?): DeviceType {
        return try {
            if (value.isNullOrBlank()) DeviceType.CUSTOM else DeviceType.valueOf(value)
        } catch (e: Exception) {
            DeviceType.CUSTOM
        }
    }
}

/**
 * HomeDatabase
 *
 * Room Database singleton hosting the unified home automation registry.
 *
 * Connections:
 * - Provides DeviceDao to DeviceController and ViewModels.
 * - Thread-safe double-checked locking singleton ensures a single database instance
 *   across BackgroundVoiceService, UI activities, and background workers.
 */
@Database(
    entities = [DeviceEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(DeviceConverters::class)
abstract class HomeDatabase : RoomDatabase() {

    abstract fun deviceDao(): DeviceDao

    companion object {
        private const val DB_NAME = "soltini_home_devices.db"

        @Volatile
        private var INSTANCE: HomeDatabase? = null

        fun getInstance(context: Context): HomeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HomeDatabase::class.java,
                    DB_NAME
                )
                .fallbackToDestructiveMigration()
                .addCallback(object : Callback() {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        super.onCreate(db)
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            seedDefaultDevices(getInstance(context).deviceDao())
                        }
                    }
                })
                .build()
                .also { instance ->
                    INSTANCE = instance
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        seedDefaultDevices(instance.deviceDao())
                    }
                }
            }
        }

        private suspend fun seedDefaultDevices(dao: DeviceDao) {
            try {
                if (dao.getAllList().isEmpty()) {
                    dao.insert(
                        DeviceEntity(
                            deviceName = "Living Room Light",
                            roomName = "Living Room",
                            deviceType = DeviceType.LIGHT,
                            gpioPin = 2,
                            esp32ClientId = "esp32-livingroom"
                        )
                    )
                    dao.insert(
                        DeviceEntity(
                            deviceName = "Ceiling Fan",
                            roomName = "Living Room",
                            deviceType = DeviceType.FAN,
                            gpioPin = 4,
                            esp32ClientId = "esp32-livingroom"
                        )
                    )
                    dao.insert(
                        DeviceEntity(
                            deviceName = "Kitchen Relay",
                            roomName = "Kitchen",
                            deviceType = DeviceType.RELAY,
                            gpioPin = 16,
                            esp32ClientId = "esp32-kitchen"
                        )
                    )
                }
            } catch (e: Exception) {
                // Ignore seed error
            }
        }
    }
}
