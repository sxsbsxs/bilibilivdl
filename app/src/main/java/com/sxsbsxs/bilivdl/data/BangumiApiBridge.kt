package com.sxsbsxs.bilivdl.data
import com.a10miaomiao.bilimiao.comm.apis.BangumiAPI
import com.a10miaomiao.bilimiao.comm.apis.PlayerAPI
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import org.json.JSONArray
import org.json.JSONObject
data class BangumiEpisode(val id: Long, val aid: Long, val cid: Long, val bvid: String, val title: String, val longTitle: String, val cover: String, val durationMs: Long)
data class BangumiSeason(val seasonId: Long, val title: String, val cover: String, val description: String, val episodes: List<BangumiEpisode>)
object BangumiApiBridge {
 private val api = BangumiAPI()
 private val playerApi = PlayerAPI()
 private suspend fun read(request: MiaoHttp): JSONObject {
  val raw = request.awaitString()
  if (raw.isBlank()) error("番剧接口没有返回内容")
  val root = JSONObject(raw)
  val code = root.optInt("code", 0)
  if (code != 0) error(root.optString("message").ifBlank { "番剧接口请求失败（$code）" })
  return root.optJSONObject("result") ?: root.optJSONObject("data") ?: error("番剧数据为空")
 }
 private fun parseEpisode(o: JSONObject): BangumiEpisode? {
  val ep = o.optLong("id", o.optLong("ep_id"))
  if (ep <= 0L) return null
  return BangumiEpisode(ep, o.optLong("aid"), o.optLong("cid"), o.optString("bvid"), o.optString("title").ifBlank { "番剧剧集" }, o.optString("long_title"), o.optString("cover"), o.optLong("duration"))
 }
 suspend fun season(seasonId: Long, episodeId: Long = 0L): BangumiSeason {
  require(seasonId > 0L || episodeId > 0L) { "需要番剧 SS 或 EP 编号" }
  val data = read(api.seasonInfoV2(seasonId.takeIf { it > 0 }?.toString().orEmpty(), episodeId.takeIf { it > 0 }?.toString().orEmpty()))
  val array = data.optJSONArray("episodes") ?: JSONArray()
  val episodes = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::parseEpisode) }
  return BangumiSeason(data.optLong("season_id", seasonId), data.optString("title", data.optString("season_title")), data.optString("cover"), data.optString("evaluate", data.optString("subtitle")), episodes)
 }
 suspend fun episode(episodeId: Long): BangumiEpisode {
  require(episodeId > 0L) { "EP 编号无效" }
  val data = read(api.episodeInfo(episodeId.toString()))
  return parseEpisode(data) ?: data.optJSONObject("episode")?.let(::parseEpisode) ?: error("番剧单集信息不完整")
 }
}