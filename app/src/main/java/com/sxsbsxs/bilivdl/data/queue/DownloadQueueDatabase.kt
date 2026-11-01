package com.sxsbsxs.bilivdl.data.queue
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
@Database(entities = [DownloadTaskEntity::class], version = 2, exportSchema = true)
abstract class DownloadQueueDatabase : RoomDatabase() {
 abstract fun tasks(): DownloadTaskDao
 companion object {
  private val MIGRATION_1_2 = object : Migration(1, 2) {
   override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE download_tasks_v2 ADD COLUMN episodeId INTEGER NOT NULL DEFAULT 0")
   }
  }
  @Volatile private var instance: DownloadQueueDatabase? = null
  fun get(context: Context): DownloadQueueDatabase = instance ?: synchronized(this) {
   instance ?: Room.databaseBuilder(context.applicationContext, DownloadQueueDatabase::class.java, "bili_download_queue_v2.db").addMigrations(MIGRATION_1_2).build().also { instance = it }
  }
 }
}