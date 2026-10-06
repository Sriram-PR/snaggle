package io.github.sriram_pr.snaggle.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.sriram_pr.snaggle.engine.Mode
import kotlinx.coroutines.flow.Flow

enum class JobStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/** One download. Doubles as the history entry once finished. */
@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val id: String,
    val url: String,
    val mode: Mode,
    val status: JobStatus,
    val title: String? = null,
    val progress: Float? = null,
    val error: String? = null,
    val outputUri: String? = null,
    val mimeType: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface JobDao {
    @Insert suspend fun insert(job: JobEntity)

    @Query("SELECT * FROM jobs WHERE id = :id") suspend fun get(id: String): JobEntity?

    @Query("SELECT * FROM jobs ORDER BY createdAt DESC") fun observeAll(): Flow<List<JobEntity>>

    @Query("UPDATE jobs SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: JobStatus, now: Long = System.currentTimeMillis())

    @Query("UPDATE jobs SET progress = :progress, title = COALESCE(:title, title), updatedAt = :now WHERE id = :id")
    suspend fun setProgress(id: String, progress: Float?, title: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE jobs SET status = 'DONE', title = :title, outputUri = :uri, mimeType = :mime, progress = 1, error = NULL, updatedAt = :now WHERE id = :id")
    suspend fun setDone(id: String, title: String, uri: String, mime: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE jobs SET status = 'FAILED', error = :error, updatedAt = :now WHERE id = :id")
    suspend fun setFailed(id: String, error: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE jobs SET status = 'QUEUED', error = NULL, progress = NULL, updatedAt = :now WHERE id = :id")
    suspend fun requeue(id: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM jobs WHERE id = :id") suspend fun delete(id: String)

    @Query("SELECT * FROM jobs WHERE url = :url AND mode = :mode AND status = 'DONE' ORDER BY createdAt DESC LIMIT 1")
    suspend fun findDone(url: String, mode: Mode): JobEntity?
}

@Database(entities = [JobEntity::class], version = 1, exportSchema = false)
abstract class SnaggleDb : RoomDatabase() {
    abstract fun jobs(): JobDao

    companion object {
        fun create(context: Context): SnaggleDb =
            Room.databaseBuilder(context, SnaggleDb::class.java, "snaggle.db").build()
    }
}
