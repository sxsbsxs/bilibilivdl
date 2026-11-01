package com.sxsbsxs.bilivdl.data

import com.a10miaomiao.bilimiao.comm.apis.BangumiAPI
import com.a10miaomiao.bilimiao.comm.apis.PlayerAPI
import com.a10miaomiao.bilimiao.comm.apis.SearchApi
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import org.json.JSONArray
import org.json.JSONObject

data class BangumiEpisode(val id: Long, val aid: Long, val cid: Long, val bvid: String, val title: String, val longTitle: String, val cover: String, val durationMs: Long)
data class BangumiSeason(val seasonId: Long, val title: String, val cover: String, val description: String, val episodes: List<BangumiEpisode>)
data class BangumiSearchResult(val seasonId: Long, val title: String, val cover: String, val description: String, val newestEpisode: String = "") : java.io.Serializable

object BangumiApiBridge {
    private val api = BangumiAPI()
    private val playerApi = PlayerAPI()
    private val searchApi = SearchApi()

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

    suspend fun search(keyword: String, page: Int = 1, pageSize: Int = 20): List<BangumiSearchResult> {
        require(keyword.isNotBlank()) { "请输入番剧名称" }
        val raw = searchApi.searchBangumi(keyword.trim(), page.coerceAtLeast(1), pageSize.coerceIn(1, 50)).awaitString()
        if (raw.isBlank()) error("番剧搜索接口没有返回内容")
        val root = JSONObject(raw)
        val code = root.optInt("code", 0)
        if (code != 0) error(root.optString("message").ifBlank { "番剧搜索失败（$code）" })
        val data = root.optJSONObject("data") ?: root.optJSONObject("result") ?: JSONObject()
        val array = data.optJSONArray("result") ?: data.optJSONArray("items") ?: JSONArray()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val seasonId = item.optLong("season_id", item.optLong("seasonId"))
            if (seasonId <= 0L) return@mapNotNull null
            val newest = item.optJSONObject("newest_ep")?.optString("index_show").orEmpty()
            BangumiSearchResult(
                seasonId = seasonId,
                title = item.optString("title").replace(Regex("<[^>]+>"), "").ifBlank { "未命名番剧" },
                cover = item.optString("cover").let { if (it.startsWith("//")) "https:$it" else it },
                description = item.optString("desc").ifBlank { item.optString("evaluate") },
                newestEpisode = newest
            )
        }.distinctBy { it.seasonId }
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

     suspend fun downloadStreamChoices(episodeId: Long, cid: Long, qn: Int): DownloadStreamChoices {
        val result = playerApi.getBangumiUrl(episodeId.toString(), cid.toString(), qn, 4048)
        val dash = result.dash ?: return DownloadStreamChoices(emptyList(), emptyList())
        val videos = dash.video.map { item ->
            DownloadStreamOption(item.id, item.codecs,
                item.height.toString() + "P · " + item.codecs + " · " + (item.bandwidth / 1000) + " kbps",
                item.bandwidth.toLong())
        }
        val audios = dash.audio.orEmpty().map { item ->
            DownloadStreamOption(item.id, item.codecs,
                item.codecs + " · " + (item.bandwidth / 1000) + " kbps", item.bandwidth.toLong())
        }
        return DownloadStreamChoices(videos, audios)
    }

    suspend fun playback(episodeId: Long, qn: Int, videoCodec: String = "", audioCodec: String = ""): PlaybackInfo {
        val ep = episode(episodeId)
        require(ep.cid > 0L) { "番剧剧集缺少 CID" }
        val result = playerApi.getBangumiUrl(episodeId.toString(), ep.cid.toString(), qn, 4048)
        if (result.code != 0) error(result.message.ifBlank { "番剧播放地址获取失败（code=" + result.code + "）" })
        val qualities = (result.support_formats.map { it.quality to it.new_description.ifBlank { it.display_desc } }.ifEmpty {
            result.accept_quality.mapIndexed { index, quality -> quality to (result.accept_description.getOrNull(index) ?: "清晰度 $quality") }
        }).distinctBy { it.first }.sortedByDescending { it.first }
        val actual = result.quality.takeIf { it > 0 } ?: qn
        val label = qualities.firstOrNull { it.first == actual }?.second ?: "清晰度 $actual"
        val dash = result.dash
        if (dash != null) {
            val videoId = videoCodec.substringBefore('|').toIntOrNull()
            val videoName = videoCodec.substringAfter('|', videoCodec)
            val video = if (videoCodec.isNotBlank()) {
                dash.video.firstOrNull { (videoId == null || it.id == videoId) && it.codecs == videoName }
                    ?: error("番剧没有返回所选视频编码：" + videoCodec)
            } else dash.video.firstOrNull { it.id == actual } ?: dash.video.firstOrNull()
                ?: error("番剧没有可用视频轨道")
            val audioId = audioCodec.substringBefore('|').toIntOrNull()
            val audioName = audioCodec.substringAfter('|', audioCodec)
            val audio = if (audioCodec.isNotBlank()) {
                dash.audio.orEmpty().firstOrNull { (audioId == null || it.id == audioId) && it.codecs == audioName }
                    ?: error("番剧没有返回所选音频编码：" + audioCodec)
            } else dash.audio?.firstOrNull() ?: error("番剧没有可用音频轨道")
            val videoUrl = video.base_url.ifBlank { video.backup_url?.firstOrNull().orEmpty() }
            val audioUrl = audio.base_url.ifBlank { audio.backup_url?.firstOrNull().orEmpty() }
            if (videoUrl.isBlank() || audioUrl.isBlank()) error("番剧 DASH 地址无效")
            return PlaybackInfo(cid = ep.cid, url = videoUrl, quality = actual, name = label, qualities = qualities, isDash = true, videoUrl = videoUrl, audioUrl = audioUrl, videoBackupUrls = video.backup_url.orEmpty(), audioBackupUrls = audio.backup_url.orEmpty())
        }
        val segments = result.durl.orEmpty().sortedBy { it.order }.mapNotNull { it.url.takeIf { url -> url.startsWith("https://") || url.startsWith("http://") } }
        if (segments.isEmpty()) error("番剧接口没有返回可播放的视频流")
        return PlaybackInfo(cid = ep.cid, url = segments.first(), quality = actual, name = label, qualities = qualities, segments = segments)
    }
}
