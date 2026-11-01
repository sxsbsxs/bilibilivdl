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

    suspend fun playback(episodeId: Long, qn: Int): PlaybackInfo {
        val ep = episode(episodeId)
        require(ep.cid > 0L) { "番剧剧集缺少 CID" }
        val result = playerApi.getBangumiUrl(episodeId.toString(), ep.cid.toString(), qn, 4048)
        if (result.code != 0) error(result.message.ifBlank { "番剧播放地址获取失败（code=" + result.code + "）" })
        val qualities = result.support_formats.map { it.quality to it.new_description.ifBlank { it.display_desc } }.ifEmpty {
            result.accept_quality.mapIndexed { index, quality -> quality to (result.accept_description.getOrNull(index) ?: "清晰度 $quality") }
        }
        val actual = result.quality.takeIf { it > 0 } ?: qn
        val label = qualities.firstOrNull { it.first == actual }?.second ?: "清晰度 $actual"
        val dash = result.dash
        if (dash != null) {
            val video = dash.video.firstOrNull { it.id == actual } ?: dash.video.firstOrNull() ?: error("番剧没有可用视频轨道")
            val audio = dash.audio?.firstOrNull() ?: error("番剧没有可用音频轨道")
            val videoUrl = video.base_url.ifBlank { video.backup_url?.firstOrNull().orEmpty() }
            val audioUrl = audio.base_url.ifBlank { audio.backup_url?.firstOrNull().orEmpty() }
            if (videoUrl.isBlank() || audioUrl.isBlank()) error("番剧 DASH 地址无效")
            return PlaybackInfo(cid = ep.cid, url = videoUrl, quality = actual, name = label, qualities = qualities, isDash = true, videoUrl = videoUrl, audioUrl = audioUrl)
        }
        val segments = result.durl.orEmpty().sortedBy { it.order }.mapNotNull { it.url.takeIf { url -> url.startsWith("https://") || url.startsWith("http://") } }
        if (segments.isEmpty()) error("番剧接口没有返回可播放的视频流")
        return PlaybackInfo(cid = ep.cid, url = segments.first(), quality = actual, name = label, qualities = qualities, segments = segments)
    }
}
