package com.soltini.app.homeautomation.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DeviceDao
 *
 * Data Access Object for the shared home automation registry.
 *
 * Connections:
 * - Injected into DeviceController to resolve voice commands into MQTT topics in real time.
 * - Injected into HomeViewModel to supply reactive Flows of devices to the UI.
 * - Used by Esp32CodeGenerator to fetch all devices mapped to a particular esp32ClientId.
 *
 * All operations execute asynchronously using Kotlin Coroutines and StateFlow/Flow.
 */
@Dao
interface DeviceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: DeviceEntity): Long

    @Update
    suspend fun update(device: DeviceEntity)

    @Delete
    suspend fun delete(device: DeviceEntity)

    @Query("DELETE FROM devices WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM devices ORDER BY roomName ASC, deviceName ASC")
    fun getAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices ORDER BY roomName ASC, deviceName ASC")
    suspend fun getAllList(): List<DeviceEntity>

    @Query("SELECT * FROM devices WHERE esp32ClientId = :esp32ClientId ORDER BY gpioPin ASC")
    suspend fun getByEsp32ClientId(esp32ClientId: String): List<DeviceEntity>

    @Query("SELECT * FROM devices WHERE esp32ClientId = :esp32ClientId ORDER BY gpioPin ASC")
    fun getByEsp32ClientIdFlow(esp32ClientId: String): Flow<List<DeviceEntity>>

    /**
     * Primary lookup used by the Gemini Live voice assistant.
     * Case-insensitive match on device name.
     */
    @Query("SELECT * FROM devices WHERE LOWER(TRIM(deviceName)) = LOWER(TRIM(:deviceName)) LIMIT 1")
    suspend fun getByDeviceName(deviceName: String): DeviceEntity?

    /**
     * Secondary lookup if user specifies both device name and room name.
     */
    @Query("SELECT * FROM devices WHERE LOWER(TRIM(deviceName)) = LOWER(TRIM(:deviceName)) AND LOWER(TRIM(roomName)) = LOWER(TRIM(:roomName)) LIMIT 1")
    suspend fun getByDeviceAndRoom(deviceName: String, roomName: String): DeviceEntity?

    @Query("SELECT * FROM devices WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DeviceEntity?

    @Query("SELECT DISTINCT esp32ClientId FROM devices ORDER BY esp32ClientId ASC")
    fun getAllBoardIds(): Flow<List<String>>

    @Query("SELECT DISTINCT esp32ClientId FROM devices ORDER BY esp32ClientId ASC")
    suspend fun getAllBoardIdsList(): List<String>
}
