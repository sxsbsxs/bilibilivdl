package com.sxsbsxs.bilivdl.data.queue
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName = "download_tasks_v2")
data class DownloadTaskEntity(
 @PrimaryKey val taskId: String, val title: String, val bvid: String, val aid: Long,
 val cid: Long = 0L, val qn: Int, val quality: String = "", val status: String = "queued",
 val progress: String = "", val completedBytes: Long = 0L, val totalBytes: Long = -1L,
 val outputPath: String = "", val createdAt: Long = System.currentTimeMillis(),
 val updatedAt: Long = System.currentTimeMillis()
)