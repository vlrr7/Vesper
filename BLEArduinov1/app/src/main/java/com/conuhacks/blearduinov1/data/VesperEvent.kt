package com.conuhacks.blearduinov1.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "vesper_events")
data class VesperEvent(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: String,
    val activity: String,
    val accMagnitude: Float,
    val details: String,
)

@Dao
interface VesperEventDao {
    @Insert
    suspend fun insert(event: VesperEvent)

    @Query("SELECT * FROM vesper_events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<VesperEvent>>

    @Query("SELECT * FROM vesper_events WHERE eventType = :type ORDER BY timestamp DESC")
    fun getEventsByType(type: String): Flow<List<VesperEvent>>

    @Query("SELECT * FROM vesper_events WHERE timestamp > :timestamp ORDER BY timestamp DESC")
    fun getEventsAfter(timestamp: Long): Flow<List<VesperEvent>>

    @Query("DELETE FROM vesper_events")
    suspend fun deleteAll()
}

@Database(entities = [VesperEvent::class], version = 1, exportSchema = false)
abstract class VesperDatabase : RoomDatabase() {
    abstract fun vesperEventDao(): VesperEventDao

    companion object {
        @Volatile
        private var INSTANCE: VesperDatabase? = null

        fun getInstance(context: android.content.Context): VesperDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    VesperDatabase::class.java,
                    "vesper_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
