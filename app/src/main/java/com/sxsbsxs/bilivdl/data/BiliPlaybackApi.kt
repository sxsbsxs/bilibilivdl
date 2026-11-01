package com.sxsbsxs.bilivdl.data

import bilibili.app.playurl.v1.PlayURLGRPC
import bilibili.app.playurl.v1.PlayViewReq
import bilibili.app.playurl.v1.Stream
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class VideoPart(val cid: Long, val page: Int, val title: String, val duration: Int)
data class DownloadStreamOption(val id: Int, val codec: String, val label: String, val bandwidth: Long)
data class DownloadStreamChoices(val video: List<DownloadStreamOption>, val audio: List<DownloadStreamOption>)
data class PlaybackInfo(val cid: Long, val url: String, val quality: Int, val name: String, val qualities: List<Pair<Int, String>>, val isDash: Boolean = false, val videoUrl: String? = null, val audioUrl: String? = null, val segments: List<String> = emptyList(), val parts: List<VideoPart> = emptyList(), val selectedPart: Int = 0, val videoBackupUrls: List<String> = emptyList(), val audioBackupUrls: List<String> = emptyList(), val viewCount: Long = 0L, val danmakuCount: Long = 0L, val pubdate: Long = 0L, val description: String = "", val cover: String = "", val author: String = "", val likeCount: Long = 0L, val favoriteCount: Long = 0L, val coinCount: Long = 0L, val shareCount: Long = 0L, val replyCount: Long = 0L, val category: String = "", val duration: Int = 0)

object BiliPlaybackApi {
    private fun get(url: String, cookie: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout=20000; readTimeout=25000; instanceFollowRedirects=true
            setRequestProperty("User-Agent", BiliHeaders.UA); setRequestProperty("Referer","https://www.bilibili.com/")
            setRequestProperty("Origin","https://www.bilibili.com"); setRequestProperty("Accept","application/json, text/plain, */*")
            if(cookie.isNotBlank()) setRequestProperty("Cookie",cookie)
        }
        return try {
            val stream=if(c.responseCode in 200..299)c.inputStream else c.errorStream
            val body=stream?.bufferedReader()?.use{it.readText()}.orEmpty()
            if(body.isBlank()) error("B站接口 HTTP ${c.responseCode}")
            JSONObject(body)
        } finally { c.disconnect() }
    }
    private fun enc(s:String)=URLEncoder.encode(s,"UTF-8")
    /**
     * gRPC 优先的匿名播放线路。gRPC 不可用时返回 null，由调用方继续 HTTP 备用线路。
     */
    private suspend fun tryGrpcPlayback(aid: Long, cid: Long, requestedQuality: Int): PlaybackInfo? {
        return try {
            val request = PlayViewReq(
                aid = aid,
                cid = cid,
                qn = requestedQuality.toLong(),
                download = 0,
                fnval = 4048,
                fnver = 0,
                forceHost = 2,
                fourk = true
            )
            val response = BiliGRPCHttp.request { PlayURLGRPC.playView(request) }.awaitCall()
            val videoInfo = response.videoInfo ?: return null
            val streams = videoInfo.streamList.filter { it.content != null }.sortedByDescending { it.streamInfo?.quality ?: 0 }
            if (streams.isEmpty()) return null
            val selected = streams.firstOrNull { it.streamInfo?.quality == requestedQuality }
                ?: streams.firstOrNull()
                ?: return null
            val actualQuality = selected.streamInfo?.quality ?: videoInfo.quality
            val qualities = streams.mapNotNull { stream ->
                stream.streamInfo?.let { it.quality to it.newDescription }
            }.distinctBy { it.first }.sortedByDescending { it.first }
            when (val content = selected.content ?: return null) {
                is Stream.Content.DashVideo -> {
                    val dash = content.value
                    val videoUrl = dash.baseUrl.ifBlank { dash.backupUrl.firstOrNull().orEmpty() }
                    if (videoUrl.isBlank()) return null
                    val audio = videoInfo.dashAudio.firstOrNull {
                        it.id == dash.audioId && it.baseUrl.isNotBlank()
                    } ?: videoInfo.dashAudio.firstOrNull { it.baseUrl.isNotBlank() }
                    val audioUrl = audio?.let { it.baseUrl.ifBlank { it.backupUrl.firstOrNull().orEmpty() } }
                    PlaybackInfo(
                        cid = cid,
                        url = videoUrl,
                        quality = actualQuality,
                        name = qualities.firstOrNull { it.first == actualQuality }?.second ?: "当前清晰度",
                        qualities = qualities,
                        isDash = audioUrl?.isNotBlank() == true,
                        videoUrl = videoUrl,
                        audioUrl = audioUrl?.takeIf { it.isNotBlank() },
                        videoBackupUrls = dash.backupUrl.filter { it.isNotBlank() && it != videoUrl },
                        audioBackupUrls = audio?.backupUrl.orEmpty().filter { it.isNotBlank() && it != audioUrl }
                    )
                }
                is Stream.Content.SegmentVideo -> {
                    val segments = content.value.segment.mapNotNull { segment ->
                        segment.url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    }
                    if (segments.isEmpty()) return null
                    PlaybackInfo(
                        cid = cid,
                        url = segments.first(),
                        quality = actualQuality,
                        name = qualities.firstOrNull { it.first == actualQuality }?.second ?: "当前清晰度",
                        qualities = qualities,
                        segments = segments
                    )
                }
            }
        } catch (_: Exception) {
            // 某些网络环境可能无法访问 gRPC；不可因此阻断匿名 HTTP 播放。
            null
        }
    }

    suspend fun downloadQualities(aid: Long, cid: Long, cookie: String): List<Pair<Int, String>> {
        val url = "https://api.bilibili.com/x/player/playurl?avid=$aid&cid=$cid&qn=127&fnval=4048&fnver=0&force_host=2&fourk=1&otype=json"
        val root = get(url, cookie)
        if (root.optInt("code", -1) != 0) {
            val code = root.optInt("code")
            error(root.optString("message", "获取可下载清晰度失败") + "（$code）")
        }
        val data = root.optJSONObject("data") ?: error("清晰度响应缺少 data")
        val qualities = data.optJSONArray("accept_quality") ?: return emptyList()
        val labels = data.optJSONArray("accept_description")
        return (0 until qualities.length()).mapNotNull { index ->
            val qn = qualities.optInt(index)
            if (qn <= 0) null else qn to (labels?.optString(index).orEmpty().ifBlank { "清晰度 $qn" })
        }.distinctBy { it.first }.sortedByDescending { it.first }
    }

    suspend fun downloadStreamChoices(aid: Long, cid: Long, qn: Int, cookie: String = ""): DownloadStreamChoices {
        val url = "https://api.bilibili.com/x/player/playurl?avid=$aid&cid=$cid&qn=$qn&fnval=4048&fnver=0&force_host=2&fourk=1&otype=json"
        val root = get(url, cookie)
        if (root.optInt("code", -1) == 0) {
            val dash = root.optJSONObject("data")?.optJSONObject("dash")
            if (dash != null) {
                val videoArray = dash.optJSONArray("video")
                val audioArray = dash.optJSONArray("audio")
                val videos = (0 until (videoArray?.length() ?: 0)).mapNotNull { index ->
                    val item = videoArray?.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optInt("id")
                    val codec = item.optString("codecs")
                    val height = item.optInt("height")
                    val bandwidth = item.optLong("bandwidth")
                    if (id <= 0 || codec.isBlank()) null else DownloadStreamOption(
                        id, codec, "${height}P · $codec · ${bandwidth / 1000} kbps", bandwidth
                    )
                }.sortedWith(compareByDescending<DownloadStreamOption> { it.id }.thenByDescending { it.bandwidth })
                val audios = (0 until (audioArray?.length() ?: 0)).mapNotNull { index ->
                    val item = audioArray?.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optInt("id")
                    val codec = item.optString("codecs")
                    val bandwidth = item.optLong("bandwidth")
                    if (id <= 0 || codec.isBlank()) null else DownloadStreamOption(
                        id, codec, "$codec · ${bandwidth / 1000} kbps", bandwidth
                    )
                }.sortedByDescending { it.bandwidth }
                return DownloadStreamChoices(videos, audios)
            }
        }
        // 兼容 gRPC / 官方接口暂时不可用的情况，仍保留 BiliMiao 的备用实现。
        val data = com.a10miaomiao.bilimiao.comm.apis.PlayerAPI()
            .getVideoPalyUrl(aid.toString(), cid.toString(), qn, 4048)
        val dash = data.dash ?: return DownloadStreamChoices(emptyList(), emptyList())
        val videos = dash.video.map { item ->
            DownloadStreamOption(item.id, item.codecs,
                item.height.toString() + "P · " + item.codecs + " · " + (item.bandwidth / 1000).toString() + " kbps",
                item.bandwidth.toLong())
        }
        val audios = dash.audio.orEmpty().map { item ->
            DownloadStreamOption(item.id, item.codecs, item.codecs + " · " + (item.bandwidth / 1000).toString() + " kbps", item.bandwidth.toLong())
        }
        return DownloadStreamChoices(videos, audios)
    }


    /**
     * B站视频详情页的相关推荐接口。结果按官方返回顺序展示，最多保留 20 条。
     */
    fun relatedVideos(bvid: String, aid: Long, cookie: String = ""): List<BiliVideo> {
        require(bvid.isNotBlank() || aid > 0L) { "缺少视频编号，无法获取相关推荐" }
        val id = if (bvid.isNotBlank()) "bvid=${enc(bvid)}" else "aid=$aid"
        val root = get("https://api.bilibili.com/x/web-interface/archive/related?$id", cookie)
        if (root.optInt("code", -1) != 0) {
            error(root.optString("message", "获取相关推荐失败"))
        }
        val items = root.optJSONArray("data") ?: return emptyList()
        return (0 until minOf(items.length(), 20)).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val bvidValue = item.optString("bvid")
            val aidValue = item.optLong("aid")
            if (bvidValue.isBlank() && aidValue <= 0L) return@mapNotNull null
            val owner = item.optJSONObject("owner")
            val stat = item.optJSONObject("stat")
            BiliVideo(
                title = item.optString("title").replace(Regex("<[^>]+>"), ""),
                cover = item.optString("pic").let { if (it.startsWith("//")) "https:$it" else it },
                author = owner?.optString("name").orEmpty().ifBlank { "未知 UP 主" },
                play = stat?.optLong("view")?.toString() ?: "0",
                duration = item.optString("duration"),
                bvid = bvidValue,
                aid = aidValue,
                description = item.optString("desc"),
                danmaku = stat?.optLong("danmaku"),
                likes = stat?.optLong("like"),
                favorites = stat?.optLong("favorite"),
                coins = stat?.optLong("coin"),
                shares = stat?.optLong("share"),
                comments = stat?.optLong("reply"),
                category = item.optString("tname"),
                pubdate = item.optLong("pubdate").takeIf { it > 0L }
            )
        }.distinctBy { it.bvid.ifBlank { it.aid.toString() } }
    }

    suspend fun load(bvid:String, aid:Long, qn:Int=127, cookie:String="", pageIndex:Int=0,
        videoCodec: String = "", audioCodec: String = ""):PlaybackInfo {
        require(bvid.isNotBlank()||aid>0){"缺少 BV/AV 视频编号"}
        val id=if(bvid.isNotBlank()) mapOf("bvid" to bvid) else mapOf("aid" to aid.toString())
        // bilimiao 使用免登录的普通视频详情接口；先走公开接口，避免把 WBI/登录状态当作播放前置条件。
        val detailUrl = "https://api.bilibili.com/x/web-interface/view?" +
            id.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        // 游客详情必须先走公开 Web 接口：不调用需要 WBI/登录态的资料聚合接口。
        // 公开接口被风控或暂不可用时，再回退 BiliMiao 的 app 端详情接口。
        val d = runCatching {
            val detail = get(detailUrl, cookie)
            if (detail.optInt("code") != 0) {
                val code = detail.optInt("code")
                error(if (code == -352) "B站暂时限制了游客视频详情请求（-352），请稍后重试" else detail.optString("message", "视频信息获取失败"))
            }
            detail.optJSONObject("data") ?: error("视频信息为空")
        }.getOrElse { publicError ->
            runCatching {
                BilimiaoApiBridge.videoInfo(if (bvid.isNotBlank()) bvid else aid.toString(), if (bvid.isNotBlank()) "BV" else "AV")
            }.getOrElse { fallbackError ->
                throw IllegalStateException("公开视频详情和 BiliMiao 备用详情均失败：${publicError.message}；${fallbackError.message}", fallbackError)
            }
        }
        val pageArray = d.optJSONArray("pages")
        val parts = if (pageArray != null) (0 until pageArray.length()).mapNotNull { index ->
            val item = pageArray.optJSONObject(index) ?: return@mapNotNull null
            val partCid = item.optLong("cid")
            if (partCid <= 0L) null else VideoPart(
                cid = partCid,
                page = item.optInt("page", index + 1),
                title = item.optString("part").ifBlank { "第 " + (index + 1) + " P" },
                duration = item.optInt("duration")
            )
        } else emptyList()
        val selectedIndex = pageIndex.coerceIn(0, (parts.size - 1).coerceAtLeast(0))
        val cid = parts.getOrNull(selectedIndex)?.cid ?: d.optLong("cid")
        if(cid<=0) error("没有获取到视频分P信息")
        val resolvedAid = d.optLong("aid", aid)
        fun enrich(value: PlaybackInfo): PlaybackInfo {
            val stat = d.optJSONObject("stat")
            val owner = d.optJSONObject("owner")
            return value.copy(
                viewCount = stat?.optLong("view") ?: 0L,
                danmakuCount = stat?.optLong("danmaku") ?: 0L,
                pubdate = d.optLong("pubdate"),
                description = d.optString("desc"),
                cover = d.optString("pic"),
                author = owner?.optString("name").orEmpty(),
                likeCount = stat?.optLong("like") ?: 0L,
                favoriteCount = stat?.optLong("favorite") ?: 0L,
                coinCount = stat?.optLong("coin") ?: 0L,
                shareCount = stat?.optLong("share") ?: 0L,
                replyCount = stat?.optLong("reply") ?: 0L,
                category = d.optString("tname"), duration = d.optInt("duration")
            )
        }
        // 先用 BiliMiao 原版 gRPC 播放实现；失败后调用其 PlayerAPI.getVideoPalyUrl，
        // 与 BiliMiao 自身使用同一套 MiaoHttp、默认 UA、Referer 和请求参数。
        if (cookie.isBlank() && videoCodec.isBlank() && audioCodec.isBlank()) {
            tryGrpcPlayback(resolvedAid, cid, qn)?.let { return enrich(it.copy(parts = parts, selectedPart = selectedIndex)) }
        }

        val upstream = com.a10miaomiao.bilimiao.comm.apis.PlayerAPI()
            .getVideoPalyUrl(resolvedAid.toString(), cid.toString(), qn, 4048)
        val qualities = upstream.accept_quality.mapIndexedNotNull { index, quality ->
            quality to upstream.accept_description.getOrNull(index).orEmpty().ifBlank { "清晰度 $quality" }
        }.distinctBy { it.first }.sortedByDescending { it.first }
        val actual = upstream.quality.takeIf { it > 0 } ?: qn
        val dash = upstream.dash
        if (dash != null) {
            fun itemJson(item: com.a10miaomiao.bilimiao.comm.apis.PlayerAPI.DashItem): JSONObject {
                val segment = JSONObject()
                    .put("initialization", item.segment_base.initialization)
                    .put("index_range", item.segment_base.index_range)
                return JSONObject()
                    .put("id", item.id)
                    .put("bandwidth", item.bandwidth)
                    .put("base_url", item.base_url)
                    .put("backup_url", org.json.JSONArray(item.backup_url ?: emptyList<String>()))
                    .put("mime_type", item.mime_type)
                    .put("codecs", item.codecs)
                    .put("width", item.width)
                    .put("height", item.height)
                    .put("frame_rate", item.frame_rate)
                    .put("segment_base", segment)
            }
            val dashJson = JSONObject()
                .put("duration", dash.duration)
                .put("video", org.json.JSONArray().apply { dash.video.forEach { put(itemJson(it)) } })
                .put("audio", org.json.JSONArray().apply { dash.audio.orEmpty().forEach { put(itemJson(it)) } })
            val selectedVideoId = videoCodec.substringBefore('|').toIntOrNull()
            val selectedVideoCodec = videoCodec.substringAfter('|', videoCodec)
            val video = if (videoCodec.isNotBlank()) {
                dash.video.firstOrNull { it.id == actual && (selectedVideoId == null || it.id == selectedVideoId) && it.codecs == selectedVideoCodec }
                    ?: error("B站没有返回所选视频编码：" + videoCodec)
            } else dash.video.firstOrNull { it.id == actual } ?: dash.video.lastOrNull()
                ?: error("BiliMiao 没有返回 DASH 视频轨道")
            val selectedAudioId = audioCodec.substringBefore('|').toIntOrNull()
            val selectedAudioCodec = audioCodec.substringAfter('|', audioCodec)
            val audio = if (audioCodec.isNotBlank()) {
                dash.audio.orEmpty().firstOrNull { (selectedAudioId == null || it.id == selectedAudioId) && it.codecs == selectedAudioCodec }
                    ?: error("B站没有返回所选音频编码：" + audioCodec)
            } else dash.audio?.firstOrNull()
            fun bestUrl(primary: String, backups: List<String>?): String =
                primary.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    ?: backups.orEmpty().firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
                    ?: error("BiliMiao 返回了无效的 CDN 地址")
            return PlaybackInfo(
                cid = cid,
                url = DashManifestBuilder.toDataUri(dashJson, actual),
                quality = actual,
                name = qualities.firstOrNull { it.first == actual }?.second ?: "当前清晰度",
                qualities = qualities,
                isDash = audio != null,
                videoUrl = bestUrl(video.base_url, video.backup_url),
                audioUrl = audio?.let { bestUrl(it.base_url, it.backup_url) },
                videoBackupUrls = video.backup_url.orEmpty().filter { it.isNotBlank() && it != video.base_url },
                audioBackupUrls = audio?.let { selectedAudio -> selectedAudio.backup_url.orEmpty().filter { it.isNotBlank() && it != selectedAudio.base_url } }.orEmpty(),
                parts = parts,
                selectedPart = selectedIndex,
                viewCount = d.optJSONObject("stat")?.optLong("view") ?: 0L,
                danmakuCount = d.optJSONObject("stat")?.optLong("danmaku") ?: 0L,
                pubdate = d.optLong("pubdate"), description = d.optString("desc"),
                cover = d.optString("pic"), author = d.optJSONObject("owner")?.optString("name").orEmpty(),
            likeCount = d.optJSONObject("stat")?.optLong("like") ?: 0L,
            favoriteCount = d.optJSONObject("stat")?.optLong("favorite") ?: 0L,
            coinCount = d.optJSONObject("stat")?.optLong("coin") ?: 0L,
            shareCount = d.optJSONObject("stat")?.optLong("share") ?: 0L,
            replyCount = d.optJSONObject("stat")?.optLong("reply") ?: 0L,
            category = d.optString("tname"), duration = d.optInt("duration")
            )
        }
        val streams = upstream.durl.orEmpty().mapNotNull { segment ->
            segment.url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }
        if (streams.isEmpty()) error("BiliMiao 没有返回可播放的视频流")
        return PlaybackInfo(
            cid = cid,
            url = streams.first(),
            quality = actual,
            name = qualities.firstOrNull { it.first == actual }?.second ?: "当前清晰度",
            qualities = qualities,
            segments = streams,
            parts = parts,
            selectedPart = selectedIndex,
            viewCount = d.optJSONObject("stat")?.optLong("view") ?: 0L,
            danmakuCount = d.optJSONObject("stat")?.optLong("danmaku") ?: 0L,
            pubdate = d.optLong("pubdate"), description = d.optString("desc"),
            cover = d.optString("pic"), author = d.optJSONObject("owner")?.optString("name").orEmpty()
        )
    }
}
