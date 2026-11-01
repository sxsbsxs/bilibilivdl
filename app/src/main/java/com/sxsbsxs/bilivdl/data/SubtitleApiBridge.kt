package com.sxsbsxs.bilivdl.data

import android.content.Context
import android.net.Uri
import com.a10miaomiao.bilimiao.comm.apis.PlayerAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class BiliSubtitleTrack(val id: Long, val language: String, val label: String, val url: String)

object SubtitleApiBridge {
 private val api = PlayerAPI()

 suspend fun tracks(aid: Long, cid: Long, epId: Long = 0L, seasonId: Long = 0L): List<BiliSubtitleTrack> = withContext(Dispatchers.IO) {
  val request = if (epId > 0L) api.getPlayerV2Info(aid.toString(), cid.toString(), epId.toString(), seasonId.toString())
               else api.getPlayerV2Info(aid.toString(), cid.toString())
  val raw = request.awaitString()
  val root = JSONObject(raw)
  if (root.optInt("code", 0) != 0) error(root.optString("message").ifBlank { "字幕列表获取失败" })
  val data = root.optJSONObject("data") ?: return@withContext emptyList()
  val subtitles = data.optJSONObject("subtitle")?.optJSONArray("subtitles") ?: return@withContext emptyList()
  (0 until subtitles.length()).mapNotNull { i ->
   val item = subtitles.optJSONObject(i) ?: return@mapNotNull null
   val url = item.optString("subtitle_url").let { if (it.startsWith("//")) "https:$it" else it }
   if (url.isBlank()) return@mapNotNull null
   BiliSubtitleTrack(item.optLong("id"), item.optString("lan"), item.optString("lan_doc").ifBlank { item.optString("lan") }, url)
  }
 }

 suspend fun downloadAsSrt(context: Context, track: BiliSubtitleTrack): String {
  val uri = downloadAsVtt(context, track)
  val vtt = File(checkNotNull(uri.path)).readLines(Charsets.UTF_8)
  val out = StringBuilder()
  var index = 0
  var i = 0
  while (i < vtt.size) {
   val line = vtt[i].trim()
   if (line.contains(" --> ")) {
    index++
    val times = line.replace('.', ',')
    out.append(index).append('\n').append(times).append('\n')
    i++
    while (i < vtt.size && vtt[i].isNotBlank()) {
     out.append(vtt[i]).append('\n')
     i++
    }
    out.append('\n')
   }
   i++
  }
  return out.toString()
 }

 suspend fun downloadAsVtt(context: Context, track: BiliSubtitleTrack): Uri = withContext(Dispatchers.IO) {
  val connection = (URL(track.url).openConnection() as HttpURLConnection).apply {
   connectTimeout = 15000; readTimeout = 20000
   setRequestProperty("User-Agent", BiliHeaders.UA)
   setRequestProperty("Referer", "https://www.bilibili.com/")
  }
  val raw = try {
   if (connection.responseCode !in 200..299) error("字幕下载失败：HTTP ${connection.responseCode}")
   connection.inputStream.bufferedReader().use { it.readText() }
  } finally { connection.disconnect() }
  val root = JSONObject(raw)
  val body = root.optJSONArray("body") ?: error("字幕文件格式不正确")
  fun stamp(seconds: Double): String {
   val ms = (seconds * 1000).toLong().coerceAtLeast(0)
   val h = ms / 3600000; val m = (ms / 60000) % 60; val s = (ms / 1000) % 60; val milli = ms % 1000
   return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, milli)
  }
  val vtt = buildString {
   append("WEBVTT\n\n")
   for (i in 0 until body.length()) {
    val line = body.optJSONObject(i) ?: continue
    val from = line.optDouble("from", -1.0); val to = line.optDouble("to", -1.0)
    if (from < 0 || to <= from) continue
    append(stamp(from)).append(" --> ").append(stamp(to)).append('\n')
    append(line.optString("content").replace("-->", "—>")).append("\n\n")
   }
  }
  val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
  val file = File(dir, "subtitle_${track.id}.vtt")
  file.writeText(vtt, Charsets.UTF_8)
  Uri.fromFile(file)
 }
}
