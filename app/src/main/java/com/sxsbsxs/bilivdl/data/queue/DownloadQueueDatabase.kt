package com.sxsbsxs.bilivdl.data.queue
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
@Database(entities = [DownloadTaskEntity::class], version = 1, exportSchema = true)
abstract class DownloadQueueDatabase : RoomDatabase() {
 abstract fun tasks(): DownloadTaskDao
 companion object {
  @Volatile private var instance: DownloadQueueDatabase? = null
  fun get(context: Context): DownloadQueueDatabase = instance ?: synchronized(this) {
   instance ?: Room.databaseBuilder(context.applicationContext, DownloadQueueDatabase::class.java, "bili_download_queue_v2.db").build().also { instance = it }
  }
 }
}