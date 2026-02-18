package com.waph1.markithub.repository

import androidx.room.*
import android.content.Context

@Entity(tableName = "file_metadata")
data class FileMetadata(
    @PrimaryKey val filePath: String, // Relative path
    val calendarName: String,
    val lastModified: Long,
    val systemEventId: Long?,
    val needsUpdate: Boolean = false // Flag to track if file was changed in this session
)

@Dao
interface FileMetadataDao {
    @Query("SELECT * FROM file_metadata WHERE filePath = :path")
    suspend fun getMetadata(path: String): FileMetadata?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metadata: FileMetadata)

    @Query("DELETE FROM file_metadata WHERE filePath = :path")
    suspend fun delete(path: String)

    @Query("SELECT * FROM file_metadata")
    suspend fun getAll(): List<FileMetadata>
    
    @Query("DELETE FROM file_metadata WHERE calendarName = :calendarName")
    suspend fun deleteByCalendar(calendarName: String)
}

@Database(entities = [FileMetadata::class], version = 1, exportSchema = false)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun fileMetadataDao(): FileMetadataDao

    companion object {
        @Volatile
        private var INSTANCE: SyncDatabase? = null

        fun getDatabase(context: Context): SyncDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SyncDatabase::class.java,
                    "sync_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
