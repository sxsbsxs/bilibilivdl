package com.sxsbsxs.bilivdl.data.queue
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
object DownloadQueueStore {
 private const val PREFS = "download_queue"
 private const val MIGRATED = "room_queue_migrated_v2"
 suspend fun migrateLegacy(context: Context) = withContext(Dispatchers.IO) {
  val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  if (prefs.getBoolean(MIGRATED, false)) return@withContext
  val arr = JSONArray(prefs.getString("tasks", "[]"))
  val tasks = buildList {
   for (i in 0 until arr.length()) {
    val o = arr.optJSONObject(i) ?: continue
    val id = o.optString("id")
    if (id.isBlank()) continue
    add(DownloadTaskEntity(
     taskId=id, title=o.optString("title","视频"), bvid=o.optString("bvid"), aid=o.optLong("aid"),
     cid=o.optLong("cid"), episodeId=o.optLong("episodeId"), qn=o.optInt("qn",80),
     quality=o.optString("quality"), status=o.optString("status","queued").let { if(it=="downloading") "queued" else it },
     progress=o.optString("progress"), outputPath=o.optString("path"), videoCodec=o.optString("videoCodec"),
     audioCodec=o.optString("audioCodec"), completedBytes=o.optLong("completedBytes"),
     totalBytes=o.optLong("totalBytes",-1L), createdAt=o.optLong("time",System.currentTimeMillis())
    ))
   }
  }
  DownloadQueueDatabase.get(context).tasks().upsertAll(tasks)
  prefs.edit().putBoolean(MIGRATED,true).apply()
 }
 suspend fun save(context: Context, task: DownloadTaskEntity) {
  DownloadQueueDatabase.get(context).tasks().upsert(task.copy(updatedAt=System.currentTimeMillis()))
 }
}
