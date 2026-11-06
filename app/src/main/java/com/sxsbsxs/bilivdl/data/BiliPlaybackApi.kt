package com.sxsbsxs.bilivdl.data

import bilibili.app.playurl.v1.PlayURLGRPC
import bilibili.app.playurl.v1.PlayViewReq
import bilibili.app.playurl.v1.Stream
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class VideoPart(val cid: Long, val page: Int, val title: String, val duration: Int)
data class DownloadStreamOption(
    val id: Int,
    val codec: String,
    val label: String,
    val bandwidth: Long,
    val height: Int = 0,
    val frameRate: String = "",
    val codecId: Long = 0L,
    val mimeType: String = ""
)
data class DownloadStreamChoices(
    val video: List<DownloadStreamOption>,
    val audio: List<DownloadStreamOption>,
    val lockedQualities: List<RestrictedQuality> = emptyList()
)
data class RestrictedQuality(val id: Int, val label: String, val requiresLogin: Boolean, val requiresVip: Boolean)
data class PlaybackInfo(val cid: Long, val url: String, val quality: Int, val name: String, val qualities: List<Pair<Int, String>>, val isDash: Boolean = false, val videoUrl: String? = null, val audioUrl: String? = null, val segments: List<String> = emptyList(), val parts: List<VideoPart> = emptyList(), val selectedPart: Int = 0, val videoBackupUrls: List<String> = emptyList(), val audioBackupUrls: List<String> = emptyList(), val viewCount: Long = 0L, val danmakuCount: Long = 0L, val pubdate: Long = 0L, val description: String = "", val cover: String = "", val author: String = "", val likeCount: Long = 0L, val favoriteCount: Long = 0L, val coinCount: Long = 0L, val shareCount: Long = 0L, val replyCount: Long = 0L, val category: String = "", val duration: Int = 0, val ownerFace: String = "", val ownerMid: Long = 0L, val ownerSign: String = "", val title: String = "", val restrictedQualities: List<RestrictedQuality> = emptyList())

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

    private fun qualityLabel(id: Int): String = when (id) {
        127 -> "8K"
        126 -> "杜比视界"
        125 -> "HDR"
        120 -> "4K"
        116 -> "1080P 60帧"
        112 -> "1080P 高码率"
        80 -> "1080P"
        74 -> "720P 60帧"
        64 -> "720P"
        32 -> "480P"
        16 -> "360P"
        else -> "清晰度 $id"
    }

    private val wbiMixinTable = intArrayOf(
        46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,
        33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,
        61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,
        36,20,34,44,52
    )

    private fun wbiPlayUrl(aid: Long, cid: Long, qn: Int, fnval: Int, cookie: String): String {
        val nav = get("https://api.bilibili.com/x/web-interface/nav", cookie)
        if (nav.optInt("code", -1) != 0) error("无法获取 B站 WBI 签名密钥")
        val img = nav.optJSONObject("data")?.optJSONObject("wbi_img") ?: error("B站未返回 WBI 密钥")
        fun key(name: String): String = img.optString(name).substringAfterLast('/').substringBeforeLast('.')
        val raw = key("img_url") + key("sub_url")
        if (raw.length < 64) error("B站 WBI 密钥长度无效")
        val mixin = buildString { for (i in 0 until 32) append(raw[wbiMixinTable[i]]) }
        val params = linkedMapOf(
            "avid" to aid.toString(), "cid" to cid.toString(), "qn" to qn.toString(),
            "fnval" to fnval.toString(), "fnver" to "0", "force_host" to "2",
            "fourk" to "1", "high_quality" to "1", "wts" to (System.currentTimeMillis() / 1000L).toString()
        )
        val query = params.toSortedMap().entries.joinToString("&") {
            enc(it.key) + "=" + enc(it.value.replace(Regex("[!'()*]"), ""))
        }
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest((query + mixin).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "https://api.bilibili.com/x/player/wbi/playurl?$query&w_rid=$digest&otype=json"
    }


    private fun authorSign(mid: Long, cookie: String): String {
        if (mid <= 0L) return ""
        val root = get("https://api.bilibili.com/x/space/acc/info?mid=$mid", cookie)
        if (root.optInt("code", -1) != 0) return ""
        return root.optJSONObject("data")?.optString("sign").orEmpty().trim()
    }
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
            val streams = videoInfo.streamList
                .filter {
                    it.content != null &&
                        (it.streamInfo?.quality ?: 0) in 1..requestedQuality
                }
                .sortedByDescending { it.streamInfo?.quality ?: 0 }
            if (streams.isEmpty()) return null
            val selected = streams.firstOrNull { it.streamInfo?.quality == requestedQuality }
                ?: streams.firstOrNull()
                ?: return null
            val actualQuality = selected.streamInfo?.quality?.takeIf { it > 0 } ?: return null
            val qualities = streams.mapNotNull { stream ->
                stream.streamInfo?.takeIf { it.quality > 0 }?.let { it.quality to it.newDescription }
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
                    if (audioUrl.isNullOrBlank()) return null
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 某些网络环境可能无法访问 gRPC；不可因此阻断匿名 HTTP 播放。
            null
        }
    }

    /**
     * BiliMiao 使用独立的请求头和官方 playurl 解析器。
     * 当公开 JSON 线路静默降档时，用它做第二条独立 HTTP 路线核对真实轨道。
     */
    private suspend fun tryBilimiaoPlayback(aid: Long, cid: Long, qn: Int, videoCodec: String = "", audioCodec: String = "", audioTrackId: Int? = null): PlaybackInfo? {
        return runCatching {
            val data = com.a10miaomiao.bilimiao.comm.apis.PlayerAPI()
                .getVideoPalyUrl(aid.toString(), cid.toString(), qn, 4048)
            val dash = data.dash
            if (dash != null && dash.video.isNotEmpty()) {
                // Keep the same strict quality ceiling as the official JSON and gRPC paths.
                // Do not silently jump to a different tier when the requested one is unavailable.
                val eligibleVideo = dash.video.filter {
                    it.id in 1..qn && (videoCodec.isBlank() || it.id == qn && it.codecs.equals(videoCodec, ignoreCase = true))
                }
                val selected = eligibleVideo.maxByOrNull { it.id } ?: return@runCatching null
                val eligibleAudio = dash.audio.orEmpty().filter {
                    (audioTrackId == null || it.id == audioTrackId) &&
                        (audioCodec.isBlank() || it.codecs.equals(audioCodec, ignoreCase = true))
                }
                val audio = eligibleAudio.maxByOrNull { it.bandwidth }
                if (audioCodec.isNotBlank() && audio == null) return@runCatching null
                val labels = data.accept_quality.zip(data.accept_description).toMap()
                val qualities = eligibleVideo.map { it.id }.distinct()
                    .sortedDescending().map { it to (labels[it] ?: qualityLabel(it)) }
                val videoUrl = selected.base_url.ifBlank { selected.backup_url?.firstOrNull().orEmpty() }
                val audioUrl = audio?.let { track -> track.base_url.ifBlank { track.backup_url?.firstOrNull().orEmpty() } }
                if (videoUrl.isBlank() || audioUrl.isNullOrBlank()) return@runCatching null
                PlaybackInfo(
                    cid = cid,
                    url = videoUrl,
                    quality = selected.id,
                    name = labels[selected.id] ?: qualityLabel(selected.id),
                    qualities = qualities,
                    isDash = !audioUrl.isNullOrBlank(),
                    videoUrl = videoUrl,
                    audioUrl = audioUrl?.takeIf { it.isNotBlank() },
                    videoBackupUrls = selected.backup_url.orEmpty().filter { it.isNotBlank() && it != videoUrl },
                    audioBackupUrls = audio?.backup_url.orEmpty().filter { it.isNotBlank() && it != audioUrl }
                )
            } else {
                val actual = data.quality.takeIf { it > 0 } ?: return@runCatching null
                val urls = data.durl.orEmpty().map { it.url }.filter { it.startsWith("http") }
                if (urls.isEmpty()) return@runCatching null
                val label = data.accept_quality.zip(data.accept_description).toMap()[actual] ?: qualityLabel(actual)
                PlaybackInfo(cid, urls.first(), actual, label, listOf(actual to label), segments = urls)
            }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
    }

    suspend fun downloadQualities(aid: Long, cid: Long, cookie: String, videoCodec: String = "", isVip: Boolean = false, isLoggedIn: Boolean = cookie.isNotBlank()): List<Pair<Int, String>> {
        // 不只依赖 qn=127 的一次响应：部分账号/视频在不同 qn 请求下返回的可用轨道集合不同。
        // 汇总每次接口真实返回的 DASH 视频轨道，绝不把 accept_quality 中未返回的档位伪造出来。
        val requested = listOf(127, 126, 125, 120, 116, 112, 80, 74, 64, 32, 16)
        val found = linkedMapOf<Int, String>()
        for (qn in requested) {
            currentCoroutineContext().ensureActive()
            // WBI 请求即使 HTTP 200，也可能返回业务错误码；这种情况同样要回退普通 playurl，
            // 不能只在网络异常时回退，否则整轮清晰度探测可能一直停留在单一降档结果。
            val wbiRoot = runCatching { get(wbiPlayUrl(aid, cid, qn, 4048, cookie), cookie) }.getOrNull()
            val root = if (wbiRoot?.optInt("code", -1) == 0) wbiRoot else runCatching {
                get("https://api.bilibili.com/x/player/playurl?avid=$aid&cid=$cid&qn=$qn&fnval=4048&fnver=0&force_host=2&fourk=1&high_quality=1&otype=json", cookie)
            }.getOrNull() ?: continue
            if (root.optInt("code", -1) != 0) continue
            val data = root.optJSONObject("data") ?: continue
            val labels = data.optJSONArray("accept_description")
            val accepted = data.optJSONArray("accept_quality")
            val support = data.optJSONArray("support_formats")
            val hasAccepted = accepted != null
            val hasSupport = support != null && support.length() > 0
            val acceptedIds = (0 until (accepted?.length() ?: 0)).mapNotNull { accepted?.optInt(it)?.takeIf { id -> id > 0 } }.toSet()
            val access = (0 until (support?.length() ?: 0)).mapNotNull { index ->
                val item = support?.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optInt("quality")
                if (id <= 0) null else id to (item.optInt("need_login") == 1 && !isLoggedIn || item.optInt("need_vip") == 1 && !isVip)
            }.toMap()
            val dashVideo = data.optJSONObject("dash")?.optJSONArray("video")
            if (dashVideo == null && videoCodec.isNotBlank()) continue
            val actualIds = if (dashVideo != null) {
                (0 until dashVideo.length()).mapNotNull { index ->
                    val track = dashVideo.optJSONObject(index) ?: return@mapNotNull null
                    val id = track.optInt("id")
                    id.takeIf { it > 0 && (videoCodec.isBlank() || track.optString("codecs").equals(videoCodec, ignoreCase = true)) }
                }.toSet()
            } else {
                setOf(data.optInt("quality").takeIf { it > 0 } ?: continue)
            }
            for (i in 0 until (accepted?.length() ?: 0)) {
                val id = accepted?.optInt(i) ?: 0
                if (id > 0 && id in actualIds && (!hasSupport || id in access.keys) && access[id] != true) {
                    found[id] = labels?.optString(i).orEmpty().ifBlank { "清晰度 $id" }
                }
            }
            actualIds.filter { (!hasAccepted || it in acceptedIds) && (!hasSupport || it in access.keys) && access[it] != true }.forEach { id -> found.putIfAbsent(id, qualityLabel(id)) }
        }
        return found.entries.sortedByDescending { it.key }.map { it.key to it.value }
    }

    suspend fun downloadStreamChoices(aid: Long, cid: Long, qn: Int, cookie: String = ""): DownloadStreamChoices {
        val url = wbiPlayUrl(aid, cid, qn, 4048, cookie)
        val wbiRoot = runCatching { get(url, cookie) }.getOrNull()
        val root = if (wbiRoot?.optInt("code", -1) == 0) wbiRoot else
            get("https://api.bilibili.com/x/player/playurl?avid=$aid&cid=$cid&qn=$qn&fnval=4048&fnver=0&force_host=2&fourk=1&high_quality=1&otype=json", cookie)
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
     * Fetch the complete DASH resource list using the same request shape as
     * KafuuNeko/BiliDownload: qn is intentionally omitted so Bilibili returns
     * every resource track instead of negotiating one quality in advance.
     * Video, normal audio, Dolby audio and FLAC are kept as distinct choices.
     */
    suspend fun downloadStreamChoicesAll(
        bvid: String,
        aid: Long,
        cid: Long,
        cookie: String,
        isVip: Boolean,
        isLoggedIn: Boolean
    ): DownloadStreamChoices {
        require((bvid.isNotBlank() || aid > 0L) && cid > 0L) { "缺少视频编号或 CID" }
        currentCoroutineContext().ensureActive()
        // Match BiliDownload's x/player/playurl request: no qn parameter, so the
        // response contains the complete DASH track inventory for this page.
        val identity = if (bvid.isNotBlank()) "bvid=${enc(bvid)}" else "avid=$aid"
        val url = "https://api.bilibili.com/x/player/playurl?$identity&cid=$cid&fnval=4048&fnver=0&fourk=1"
        val root = get(url, cookie)
        if (root.optInt("code", -1) != 0) {
            error(root.optString("message").ifBlank { "B站清晰度解析失败（${root.optInt("code")}）" })
        }
        val data = root.optJSONObject("data") ?: error("B站没有返回播放资源")
        val support = data.optJSONArray("support_formats")
        val locked = (0 until (support?.length() ?: 0)).mapNotNull { index ->
            val item = support?.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optInt("quality")
            if (id <= 0) null else RestrictedQuality(
                id,
                item.optString("new_description")
                    .ifBlank { item.optString("display_desc") }
                    .ifBlank { qualityLabel(id) },
                item.optInt("need_login") == 1,
                item.optInt("need_vip") == 1
            )
        }.filter { (it.requiresLogin && !isLoggedIn) || (it.requiresVip && !isVip) }
        val lockedIds = locked.map { it.id }.toSet()
        val dash = data.optJSONObject("dash") ?: error("B站没有返回 DASH 资源，无法按轨道选择")
        val videoArray = dash.optJSONArray("video")
        val video = (0 until (videoArray?.length() ?: 0)).mapNotNull { index ->
            val item = videoArray?.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optInt("id")
            val codec = item.optString("codecs")
            val height = item.optInt("height")
            val bandwidth = item.optLong("bandwidth")
            val frameRate = item.optString("frameRate").ifBlank { item.optString("frame_rate") }
            if (id <= 0 || codec.isBlank() || id in lockedIds) null else {
                val fps = frameRate.takeIf { it.isNotBlank() }?.let { value -> " · " + if (value.contains("fps", ignoreCase = true)) value else "$value fps" }.orEmpty()
                DownloadStreamOption(
                    id = id,
                    codec = codec,
                    label = "${height}P$fps · $codec · ${bandwidth / 1000} kbps",
                    bandwidth = bandwidth,
                    height = height,
                    frameRate = frameRate,
                    codecId = item.optLong("codecid"),
                    mimeType = item.optString("mimeType")
                )
            }
        }.distinctBy { Triple(it.id, it.codec, it.bandwidth) }
            .sortedWith(compareByDescending<DownloadStreamOption> { it.id }.thenByDescending { it.bandwidth })

        val audioResources = mutableListOf<Pair<JSONObject, String>>()
        val audioArray = dash.optJSONArray("audio")
        for (index in 0 until (audioArray?.length() ?: 0)) {
            audioArray?.optJSONObject(index)?.let { audioResources += it to "" }
        }
        val dolbyArray = dash.optJSONObject("dolby")?.optJSONArray("audio")
        for (index in 0 until (dolbyArray?.length() ?: 0)) {
            dolbyArray?.optJSONObject(index)?.let { audioResources += it to "杜比音频" }
        }
        dash.optJSONObject("flac")?.optJSONObject("audio")?.let {
            audioResources += it to "FLAC 无损"
        }
        val audio = audioResources.mapNotNull { (item, typeLabel) ->
            val id = item.optInt("id")
            val codec = item.optString("codecs")
            val bandwidth = item.optLong("bandwidth")
            if (id <= 0 || codec.isBlank()) null else {
                val type = typeLabel.takeIf { it.isNotBlank() }?.let { "$it · " }.orEmpty()
                DownloadStreamOption(
                    id = id,
                    codec = codec,
                    label = type + codec + " · " + (bandwidth / 1000) + " kbps",
                    bandwidth = bandwidth,
                    codecId = item.optLong("codecid"),
                    mimeType = item.optString("mimeType")
                )
            }
        }.distinctBy { Triple(it.id, it.codec, it.bandwidth) }
            .sortedWith(compareByDescending<DownloadStreamOption> { it.bandwidth }.thenBy { it.id })

        if (video.isEmpty()) error("B站没有返回当前账号可用的视频轨道")
        if (audio.isEmpty()) error("B站没有返回可用音频轨道")
        return DownloadStreamChoices(video, audio, locked)
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
                pubdate = item.optLong("pubdate").takeIf { it > 0L },
                authorFace = owner?.optString("face").orEmpty().let { if (it.startsWith("//")) "https:$it" else it }
            )
        }.distinctBy { it.bvid.ifBlank { it.aid.toString() } }
    }

    private fun httpPlayUrl(aid: Long, cid: Long, qn: Int, cookie: String, fnval: Int = 4048,
        videoCodec: String = "", audioCodec: String = "",
        videoTrackId: Int? = null, videoCodecId: Long? = null,
        audioTrackId: Int? = null, audioCodecId: Long? = null,
        isVip: Boolean = false, isLoggedIn: Boolean = cookie.isNotBlank(), onStage: (String) -> Unit = {},
        onRestrictions: (List<RestrictedQuality>) -> Unit = {}): PlaybackInfo? {
        onStage("正在请求 WBI 播放地址…")
        val wbiRoot = runCatching { get(wbiPlayUrl(aid, cid, qn, fnval, cookie), cookie) }.getOrNull()
        val root = if (wbiRoot?.optInt("code", -1) == 0) wbiRoot else {
            onStage("WBI 暂不可用，正在切换普通 HTTP 线路…")
            get("https://api.bilibili.com/x/player/playurl?avid=" + aid + "&cid=" + cid + "&qn=" + qn + "&fnval=" + fnval + "&fnver=0&force_host=2&fourk=1&high_quality=1&otype=json", cookie)
        }
        if (root.optInt("code", -1) != 0) return null
        val data = root.optJSONObject("data") ?: return null
        val qa = data.optJSONArray("accept_quality")
        val la = data.optJSONArray("accept_description")
        val choices = (0 until (qa?.length() ?: 0)).mapNotNull { i ->
            val id = qa?.optInt(i) ?: 0
            if (id <= 0) null else id to (la?.optString(i).orEmpty().ifBlank { "清晰度 " + id })
        }.distinctBy { it.first }.sortedByDescending { it.first }
        val reportedQuality = data.optInt("quality", 0)
        val supportArray = data.optJSONArray("support_formats")
        val hasSupportFormats = supportArray != null && supportArray.length() > 0
        val hasAcceptedQualityList = qa != null
        val supportFormats = (0 until (supportArray?.length() ?: 0)).mapNotNull { index ->
            val item = supportArray?.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optInt("quality")
            if (id <= 0) null else RestrictedQuality(
                id,
                item.optString("new_description").ifBlank { item.optString("display_desc") }.ifBlank { qualityLabel(id) },
                item.optInt("need_login") == 1,
                item.optInt("need_vip") == 1
            )
        }
        val restricted = supportFormats.filter { (it.requiresLogin && !isLoggedIn) || (it.requiresVip && !isVip) }
        onRestrictions(restricted)
        val supportIds = supportFormats.map { it.id }.toSet()
        val acceptedIds = choices.map { it.first }.toSet()
        val allowedIds = when {
            hasSupportFormats && hasAcceptedQualityList -> supportIds.intersect(acceptedIds)
                .filterNot { id -> restricted.any { it.id == id } }.toSet()
            hasSupportFormats -> supportIds.filterNot { id -> restricted.any { it.id == id } }.toSet()
            hasAcceptedQualityList -> acceptedIds
            else -> emptySet()
        }
        val dash = data.optJSONObject("dash")
        if (dash != null) {
            val va = dash.optJSONArray("video")
            val aa = dash.optJSONArray("audio")
            val videos = (0 until (va?.length() ?: 0)).mapNotNull { va?.optJSONObject(it) }
            val audios = mutableListOf<JSONObject>().apply {
                for (index in 0 until (aa?.length() ?: 0)) aa?.optJSONObject(index)?.let(::add)
                val dolby = dash.optJSONObject("dolby")?.optJSONArray("audio")
                for (index in 0 until (dolby?.length() ?: 0)) dolby?.optJSONObject(index)?.let(::add)
                dash.optJSONObject("flac")?.optJSONObject("audio")?.let(::add)
            }
            // B站的 data.quality 可能是本次请求协商出的档位（例如 64/720P），
            // 但 dash.video 里仍可能包含更高的真实可用轨道。不能优先照搬 data.quality，
            // 否则 qn=127 也会被应用固定选成 720P。
            // 按用户请求上限，从实际返回的 DASH 轨道中选最高档；手动选低档时不会越级。
            // Never fall back to a track above the requested ceiling. The previous fallback
            // could silently play a different quality when the requested tier was unavailable.
            val eligibleVideos = videos.filter {
                it.optInt("id") in 1..qn &&
                    (if (hasSupportFormats || hasAcceptedQualityList) it.optInt("id") in allowedIds else true) &&
                    (videoTrackId == null || it.optInt("id") == videoTrackId) &&
                    (videoCodecId == null || it.optLong("codecid") == videoCodecId) &&
                    (videoCodec.isBlank() || it.optInt("id") == qn && it.optString("codecs").equals(videoCodec, ignoreCase = true))
            }
            val video = eligibleVideos.maxWithOrNull(
                compareBy<JSONObject> { it.optInt("id") }.thenBy { it.optLong("bandwidth") }
            ) ?: return null
            val actual = video.optInt("id").takeIf { it in 1..qn } ?: return null
            val eligibleAudios = audios.filter {
                (audioTrackId == null || it.optInt("id") == audioTrackId) &&
                    (audioCodecId == null || it.optLong("codecid") == audioCodecId) &&
                    (audioCodec.isBlank() || it.optString("codecs").equals(audioCodec, ignoreCase = true))
            }
            val audio = eligibleAudios.maxByOrNull { it.optLong("bandwidth") }
            if (audioCodec.isNotBlank() && audio == null) return null
            fun primary(item: JSONObject): String = item.optString("baseUrl").ifBlank { item.optString("base_url") }
            val videoUrl = primary(video).ifBlank { video.optJSONArray("backupUrl")?.optString(0).orEmpty() }
            val audioUrl = audio?.let { primary(it).ifBlank { it.optJSONArray("backupUrl")?.optString(0).orEmpty() } }
            fun backupUrls(item: JSONObject): List<String> =
                (0 until (item.optJSONArray("backupUrl")?.length() ?: 0))
                    .mapNotNull { index -> item.optJSONArray("backupUrl")?.optString(index)?.takeIf { it.isNotBlank() } }
            if (videoUrl.isBlank() || audioUrl.isNullOrBlank()) return null
            // accept_quality 是能力声明/排序提示，不是 DASH 实际轨道清单。
            // 以 dash.video 中真实存在的 id 为准，避免 accept_quality 缺项时把高画质过滤掉。
            val labelsById = choices.toMap()
            val realQualities = eligibleVideos.mapNotNull { track ->
                val id = track.optInt("id")
                id.takeIf { it in 1..qn }?.let { it to (labelsById[it] ?: qualityLabel(it)) }
            }.distinctBy { it.first }.sortedByDescending { it.first }
            return PlaybackInfo(cid, videoUrl, actual,
                labelsById[actual] ?: qualityLabel(actual),
                realQualities.ifEmpty { listOf(actual to qualityLabel(actual)) },
                isDash = !audioUrl.isNullOrBlank(), videoUrl = videoUrl, audioUrl = audioUrl,
                videoBackupUrls = backupUrls(video).filterNot { it == videoUrl },
                audioBackupUrls = audio?.let(::backupUrls).orEmpty().filterNot { it == audioUrl },
                restrictedQualities = restricted)
        }
        val durl = data.optJSONArray("durl") ?: return null
        val urls = (0 until durl.length()).mapNotNull { durl.optJSONObject(it)?.optString("url")?.takeIf { u -> u.startsWith("http") } }
        if (urls.isEmpty()) return null
        val actual = reportedQuality.takeIf { it > 0 } ?: return null
        val actualLabel = choices.firstOrNull { it.first == actual }?.second ?: "清晰度 " + actual
        val allowedDurl = if (hasSupportFormats || hasAcceptedQualityList) actual in allowedIds else true
        if (!allowedDurl || restricted.any { it.id == actual }) return null
        return PlaybackInfo(cid, urls.first(), actual, actualLabel,
            listOf(actual to actualLabel), segments = urls, restrictedQualities = restricted)
    }

    suspend fun load(bvid:String, aid:Long, qn:Int=127, cookie:String="", pageIndex:Int=0,
        videoCodec: String = "", audioCodec: String = "", isVip: Boolean = false,
        isLoggedIn: Boolean = cookie.isNotBlank(), onStage: (String) -> Unit = {}):PlaybackInfo {
        require(bvid.isNotBlank()||aid>0){"缺少 BV/AV 视频编号"}
        // The player dialog stores "trackId|codec" for both regular videos and bangumi.
        // Preserve the selected audio track ID as well as its codec when refreshing URLs.
        val videoSelection = videoCodec.split('|', limit = 3)
        val audioSelection = audioCodec.split('|', limit = 3)
        val requestedVideoTrackId = videoSelection.firstOrNull()?.toIntOrNull()
        val requestedVideoCodec = if (videoSelection.size >= 2) videoSelection[1] else videoCodec
        val requestedVideoCodecId = videoSelection.getOrNull(2)?.toLongOrNull()
        val requestedAudioTrackId = audioSelection.firstOrNull()?.toIntOrNull()
        val requestedAudioCodec = if (audioSelection.size >= 2) audioSelection[1] else audioCodec
        val requestedAudioCodecId = audioSelection.getOrNull(2)?.toLongOrNull()
        val hasExplicitTrackSelection = requestedVideoTrackId != null || requestedVideoCodecId != null ||
            requestedAudioTrackId != null || requestedAudioCodecId != null
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
        }.onFailure { if (it is CancellationException) throw it }.getOrElse { publicError ->
            runCatching {
                BilimiaoApiBridge.videoInfo(if (bvid.isNotBlank()) bvid else aid.toString(), if (bvid.isNotBlank()) "BV" else "AV")
            }.onFailure { if (it is CancellationException) throw it }.getOrElse { fallbackError ->
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
                ownerFace = owner?.optString("face").orEmpty(),
                ownerMid = owner?.optLong("mid") ?: 0L,
                ownerSign = runCatching { authorSign(owner?.optLong("mid") ?: 0L, cookie) }.getOrDefault(""),
                category = d.optString("tname"), duration = d.optInt("duration"),
                title = d.optString("title").replace(Regex("<[^>]*>"), "").trim()
            )
        }
        // The official JSON endpoint avoids localized-number conversion errors in the imported parser.
        // 先请求用户选定档位；若匿名接口对最高档返回空结果，依次尝试常见档位，
        // 但只显示响应中真实存在的视频轨 ID，绝不把请求值当成实际画质。
        // Ask for the user's requested tier once. If B站 silently downgrades it,
        // independent BiliMiao/gRPC routes and the verified-track probe handle recovery;
        // serially retrying every lower qn only adds latency and often repeats the same 720P cap.
        val httpCandidates = listOf(qn)
        var bestHttp: PlaybackInfo? = null
        var reportedRestrictions: List<RestrictedQuality> = emptyList()
        for (fnval in listOf(4048, 1)) {
            currentCoroutineContext().ensureActive()
            for (candidate in httpCandidates) {
                currentCoroutineContext().ensureActive()
                onStage(if (fnval == 4048) "正在尝试 HTTP / WBI 线路…" else "正在尝试兼容 HTTP 线路…")
                val resolved = runCatching {
                    httpPlayUrl(resolvedAid, cid, candidate, cookie, fnval, requestedVideoCodec, requestedAudioCodec, requestedVideoTrackId, requestedVideoCodecId, requestedAudioTrackId, requestedAudioCodecId, isVip, isLoggedIn, onStage) { if (it.isNotEmpty()) reportedRestrictions = it }
                }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                    ?: continue
                if (bestHttp == null || resolved.quality > bestHttp!!.quality) bestHttp = resolved
                // Exact requested tier is authoritative; otherwise continue probing lower qn requests
                // because B站 may silently downgrade qn=127 to 480P while a subsequent qn request works.
                if (resolved.quality == qn) {
                    return enrich(resolved.copy(parts = parts, selectedPart = selectedIndex))
                }
            }
        }
        // Try independent app/gRPC routes before the slow per-tier probe. The previous
        // order could issue many sequential requests before trying the BiliMiao route.
        onStage("HTTP 线路未达到请求画质，正在尝试 BiliMiao 备用线路…")
        val miaoRaw = if (!hasExplicitTrackSelection && (bestHttp?.quality ?: 0) < qn)
            tryBilimiaoPlayback(resolvedAid, cid, qn, requestedVideoCodec, requestedAudioCodec, requestedAudioTrackId) else null
        val activeRestrictions = bestHttp?.restrictedQualities?.takeIf { it.isNotEmpty() } ?: reportedRestrictions
        val lockedIds = activeRestrictions.map { it.id }.toSet()
        val miao = miaoRaw?.takeUnless { it.quality in lockedIds }
        val currentBestQuality = listOfNotNull(bestHttp?.quality, miao?.quality).maxOrNull() ?: 0
        onStage("正在尝试 gRPC 播放线路…")
        val grpcRaw = if (requestedVideoCodec.isBlank() && requestedAudioCodec.isBlank() && currentBestQuality < qn)
            tryGrpcPlayback(resolvedAid, cid, qn) else null
        val grpc = grpcRaw?.takeUnless { it.quality in lockedIds }

        // Probe whenever the best independent route is below the requested tier.
        // A silent 1080P downgrade must not suppress discovery of 4K/HDR/8K tracks.
        var discoveredQualities: List<Pair<Int, String>> = emptyList()
        val independentBestQuality = listOfNotNull(bestHttp?.quality, miao?.quality, grpc?.quality).maxOrNull() ?: 0
        if (!hasExplicitTrackSelection && requestedVideoCodec.isBlank() && qn >= 80 && independentBestQuality < qn) {
            onStage("正在核对视频实际可用画质…")
            discoveredQualities = runCatching { downloadQualities(resolvedAid, cid, cookie, requestedVideoCodec, isVip, isLoggedIn) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(emptyList())
                .filter { it.first <= qn }
            val highestDiscovered = discoveredQualities.maxByOrNull { it.first }
            if (highestDiscovered != null && highestDiscovered.first > independentBestQuality) {
                val retried = runCatching {
                    httpPlayUrl(resolvedAid, cid, highestDiscovered.first, cookie, 4048, requestedVideoCodec, requestedAudioCodec, requestedVideoTrackId, requestedVideoCodecId, requestedAudioTrackId, requestedAudioCodecId, isVip, isLoggedIn, onStage) { reportedRestrictions = it }
                }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                if (retried != null && retried.quality >= highestDiscovered.first) bestHttp = retried
            }
        }
        val candidates = listOfNotNull(bestHttp, miao, grpc)
        val best = candidates.maxByOrNull { it.quality }
        best?.let {
            val mergedQualities = (candidates.flatMap { candidate -> candidate.qualities } + discoveredQualities)
                .filterNot { quality -> quality.first in lockedIds }
                .distinctBy { quality -> quality.first }
                .sortedByDescending { quality -> quality.first }
            return enrich(it.copy(qualities = mergedQualities, parts = parts, selectedPart = selectedIndex,
                restrictedQualities = activeRestrictions))
        }

        // Avoid the legacy parser: it converts localized numeric values (e.g. 万) and can throw. Only trust streams returned by official JSON or gRPC.
        error(
            if (requestedVideoCodec.isNotBlank() || requestedAudioCodec.isNotBlank())
                "当前清晰度没有匹配所选视频/音频编码的可用轨道，请更换编码或清晰度"
            else "B站公开播放接口（DASH/DURL）和 gRPC 均未返回可用视频流；请稍后重试或切换网络"
        )
    }
}
