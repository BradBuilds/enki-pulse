package com.enki.connect.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Offline signal queue — stores signals when server is unreachable.
 * Flushed automatically when connectivity is restored.
 */
@Entity(tableName = "offline_queue")
data class QueuedSignal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val endpoint: String,          // "/signals/geo/ping" or "/signals/ingest"
    val contentType: String,       // "application/json" or "image/jpeg"
    val bodyJson: String? = null,  // inline JSON for small payloads
    val bodyFilePath: String? = null, // file path for large payloads (photos)
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val maxRetries: Int = 10,
    val wifiOnly: Boolean = false, // true for photos, video
    val status: String = "pending" // pending, uploading, failed
)

@Dao
interface QueueDao {
    @Insert
    suspend fun insert(signal: QueuedSignal): Long

    @Query("SELECT * FROM offline_queue WHERE status = 'pending' AND retryCount < maxRetries ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getPending(limit: Int = 50): List<QueuedSignal>

    @Query("SELECT * FROM offline_queue WHERE status = 'pending' AND wifiOnly = 0 AND retryCount < maxRetries ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getPendingCellular(limit: Int = 50): List<QueuedSignal>

    @Query("UPDATE offline_queue SET status = 'uploading' WHERE id = :id")
    suspend fun markUploading(id: Long)

    @Query("DELETE FROM offline_queue WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE offline_queue SET retryCount = retryCount + 1, status = 'pending' WHERE id = :id")
    suspend fun incrementRetry(id: Long)

    @Query("SELECT COUNT(*) FROM offline_queue WHERE status = 'pending'")
    fun pendingCount(): Flow<Int>

    @Query("DELETE FROM offline_queue WHERE createdAt < :before")
    suspend fun pruneOlderThan(before: Long)

    @Query("DELETE FROM offline_queue")
    suspend fun clearAll()
}

@Database(entities = [QueuedSignal::class], version = 1, exportSchema = false)
abstract class EnkiDatabase : RoomDatabase() {
    abstract fun queueDao(): QueueDao

    companion object {
        @Volatile private var INSTANCE: EnkiDatabase? = null

        fun getInstance(context: android.content.Context): EnkiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    EnkiDatabase::class.java,
                    "enki_offline_queue"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
