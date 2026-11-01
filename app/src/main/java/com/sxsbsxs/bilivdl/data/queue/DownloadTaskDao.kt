package com.sxsbsxs.bilivdl.data.queue
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
@Dao interface DownloadTaskDao {
 @Upsert suspend fun upsert(task: DownloadTaskEntity)
 @Upsert suspend fun upsertAll(tasks: List<DownloadTaskEntity>)
 @Query("SELECT * FROM download_tasks_v2 ORDER BY createdAt DESC") fun observeAll(): Flow<List<DownloadTaskEntity>>
 @Query("SELECT * FROM download_tasks_v2 WHERE taskId = :id LIMIT 1") suspend fun find(id: String): DownloadTaskEntity?
 @Query("SELECT * FROM download_tasks_v2 WHERE status = :status ORDER BY createdAt ASC") suspend fun tasksByStatus(status: String): List<DownloadTaskEntity>
 @Query("UPDATE download_tasks_v2 SET status = :status, progress = :progress, updatedAt = :now WHERE taskId = :id") suspend fun setState(id: String, status: String, progress: String, now: Long = System.currentTimeMillis())
 @Query("UPDATE download_tasks_v2 SET status = 'queued', progress = '等待恢复下载', updatedAt = :now WHERE status = 'downloading'") suspend fun recoverInterrupted(now: Long = System.currentTimeMillis())
 @Query("DELETE FROM download_tasks_v2 WHERE status IN ('completed','failed','cancelled')") suspend fun clearHistory()
 @Query("DELETE FROM download_tasks_v2 WHERE status = 'completed'") suspend fun clearCompleted()
 @Query("DELETE FROM download_tasks_v2 WHERE taskId = :id") suspend fun delete(id: String)
}
