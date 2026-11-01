package com.sxsbsxs.bilivdl.data
import android.util.Base64
import org.json.JSONObject
object DashManifestBuilder {
 fun toDataUri(dash: JSONObject, selectedQuality: Int): String {
  val videos=dash.optJSONArray("video") ?: error("DASH 视频轨道为空")
  val video=(0 until videos.length()).mapNotNull { videos.optJSONObject(it) }.firstOrNull { it.optInt("id")==selectedQuality } ?: (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }.firstOrNull() ?: error("没有可用的视频轨道")
  val audios=dash.optJSONArray("audio")
  val audio=audios?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull() }
  val duration=dash.optLong("duration").takeIf { it>0 } ?: 0L
  val audioXml=audio?.let { representation(it,"audio") }.orEmpty()
  val mpd="<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" minBufferTime=\"PT1.5S\" mediaPresentationDuration=\"PT"+duration+"S\"><Period id=\"0\" duration=\"PT"+duration+"S\">" + representation(video,"video") + audioXml + "</Period></MPD>"
  return "data:application/dash+xml;base64," + Base64.encodeToString(mpd.toByteArray(Charsets.UTF_8),Base64.NO_WRAP)
 }
 private fun representation(item:JSONObject,kind:String):String {
  val base=item.optString("baseUrl").ifBlank { item.optString("base_url") }
  val backups=item.optJSONArray("backupUrl") ?: item.optJSONArray("backup_url")
  val url=base.ifBlank { backups?.optString(0).orEmpty() }
  require(url.startsWith("https://") || url.startsWith("http://")) { "DASH "+kind+" 地址无效" }
  val mime=escape(item.optString("mimeType").ifBlank { item.optString("mime_type") })
  val codecs=escape(item.optString("codecs"))
  val id=item.optInt("id")
  val bandwidth=item.optLong("bandwidth",1L)
  val segment=item.optJSONObject("SegmentBase") ?: item.optJSONObject("segment_base")
  val indexRange=segment?.optString("index_range").orEmpty()
  val initRange=segment?.optString("initialization").orEmpty()
  val index=if(indexRange.isNotBlank()) " indexRange=\""+escape(indexRange)+"\"" else ""
  val init=if(initRange.isNotBlank()) "<Initialization range=\""+escape(initRange)+"\"/>" else ""
  val dimensions=if(kind=="video") " width=\""+item.optInt("width")+"\" height=\""+item.optInt("height")+"\"" else ""
  val frame=item.optString("frameRate").ifBlank { item.optString("frame_rate") }
  val frameAttr=if(kind=="video" && frame.isNotBlank()) " frameRate=\""+escape(frame)+"\"" else ""
  return "<AdaptationSet contentType=\""+kind+"\" mimeType=\""+mime+"\" segmentAlignment=\"true\"><Representation id=\""+id+"\" bandwidth=\""+bandwidth+"\" codecs=\""+codecs+"\""+dimensions+frameAttr+"><BaseURL>"+escape(url)+"</BaseURL><SegmentBase"+index+">"+init+"</SegmentBase></Representation></AdaptationSet>"
 }
 private fun escape(v:String)=v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
}