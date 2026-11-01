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
data class PlaybackInfo(val cid: Long, val url: String, val quality: Int, val name: String, val qualities: List<Pair<Int, String>>, val isDash: Boolean = false, val videoUrl: String? = null, val audioUrl: String? = null, val segments: List<String> = emptyList(), val parts: List<VideoPart> = emptyList(), val selectedPart: Int = 0, val videoBackupUrls: List<String> = emptyList(), val audioBackupUrls: List<String> = emptyList(), val viewCount: Long = 0L, val danmakuCount: Long = 0L, val pubdate: Long = 0L, val description: String = "", val cover: String = "", val author: String = "", val likeCount: Long = 0L, val favoriteCount: Long = 0L, val coinCount: Long = 0L, val shareCount: Long = 0L, val replyCount: Long = 0L, val category: String = "", val duration: Int = 0, val ownerFace: String = "", val ownerMid: Long = 0L, val ownerSign: String = "", val title: String = "")

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
            "fourk" to "1", "wts" to (System.currentTimeMillis() / 1000L).toString()
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
            val streams = videoInfo.streamList.filter { it.content != null && (it.streamInfo?.quality ?: 0) > 0 }.sortedByDescending { it.streamInfo?.quality ?: 0 }
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
        // 不只依赖 qn=127 的一次响应：部分账号/视频在不同 qn 请求下返回的可用轨道集合不同。
        // 汇总每次接口真实返回的 DASH 视频轨道，绝不把 accept_quality 中未返回的档位伪造出来。
        val requested = listOf(127, 120, 116, 112, 80, 74, 64, 32, 16)
        val found = linkedMapOf<Int, String>()
        for (qn in requested) {
            val root = runCatching {
                get(wbiPlayUrl(aid, cid, qn, 4048, cookie), cookie)
            }.getOrElse {
                get("https://api.bilibili.com/x/player/playurl?avid=$aid&cid=$cid&qn=$qn&fnval=4048&fnver=0&force_host=2&fourk=1&otype=json", cookie)
            }
            if (root.optInt("code", -1) != 0) continue
            val data = root.optJSONObject("data") ?: continue
            val labels = data.optJSONArray("accept_description")
            val accepted = data.optJSONArray("accept_quality")
            val dashVideo = data.optJSONObject("dash")?.optJSONArray("video")
            val actualIds = if (dashVideo != null) {
                (0 until dashVideo.length()).mapNotNull { dashVideo.optJSONObject(it)?.optInt("id")?.takeIf { id -> id > 0 } }.toSet()
            } else {
                setOf(data.optInt("quality").takeIf { it > 0 } ?: continue)
            }
            for (i in 0 until (accepted?.length() ?: 0)) {
                val id = accepted?.optInt(i) ?: 0
                if (id > 0 && id in actualIds) {
                    found[id] = labels?.optString(i).orEmpty().ifBlank { "清晰度 $id" }
                }
            }
            actualIds.forEach { id -> found.putIfAbsent(id, "清晰度 $id") }
        }
        return found.entries.sortedByDescending { it.key }.map { it.key to it.value }
    }

    suspend fun downloadStreamChoices(aid: Long, cid: Long, qn: Int, cookie: String = ""): DownloadStreamChoices {
        val url = wbiPlayUrl(aid, cid, qn, 4048, cookie)
        val root = runCatching { get(url, cookie) }.getOrElse {
            get("https://api.bilibili.com/x/player/playurl?avid=$aid&cid=$cid&qn=$qn&fnval=4048&fnver=0&force_host=2&fourk=1&otype=json", cookie)
        }
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

    private fun httpPlayUrl(aid: Long, cid: Long, qn: Int, cookie: String, fnval: Int = 4048): PlaybackInfo? {
        val root = runCatching { get(wbiPlayUrl(aid, cid, qn, fnval, cookie), cookie) }.getOrElse {
            get("https://api.bilibili.com/x/player/playurl?avid=" + aid + "&cid=" + cid + "&qn=" + qn + "&fnval=" + fnval + "&fnver=0&force_host=2&fourk=1&otype=json", cookie)
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
        val dash = data.optJSONObject("dash")
        if (dash != null) {
            val va = dash.optJSONArray("video")
            val aa = dash.optJSONArray("audio")
            val videos = (0 until (va?.length() ?: 0)).mapNotNull { va?.optJSONObject(it) }
            val audios = (0 until (aa?.length() ?: 0)).mapNotNull { aa?.optJSONObject(it) }
            // B站的 data.quality 可能是本次请求协商出的档位（例如 64/720P），
            // 但 dash.video 里仍可能包含更高的真实可用轨道。不能优先照搬 data.quality，
            // 否则 qn=127 也会被应用固定选成 720P。
            // 按用户请求上限，从实际返回的 DASH 轨道中选最高档；手动选低档时不会越级。
            val video = videos
                .filter { it.optInt("id") > 0 && it.optInt("id") <= qn }
                .maxByOrNull { it.optInt("id") }
                ?: videos.firstOrNull { it.optInt("id") == reportedQuality }
                ?: videos.maxByOrNull { it.optLong("bandwidth") }
                ?: return null
            val actual = video.optInt("id").takeIf { it > 0 } ?: reportedQuality.takeIf { it > 0 } ?: return null
            val audio = audios.maxByOrNull { it.optLong("bandwidth") }
            fun primary(item: JSONObject): String = item.optString("baseUrl").ifBlank { item.optString("base_url") }
            val videoUrl = primary(video).ifBlank { video.optJSONArray("backupUrl")?.optString(0).orEmpty() }
            val audioUrl = audio?.let { primary(it).ifBlank { it.optJSONArray("backupUrl")?.optString(0).orEmpty() } }
            if (videoUrl.isBlank()) return null
            return PlaybackInfo(cid, videoUrl, video.optInt("id", actual),
                choices.firstOrNull { it.first == actual }?.second ?: "清晰度 " + actual,
                (choices.filter { choice -> videos.any { it.optInt("id") == choice.first } } + (actual to (choices.firstOrNull { it.first == actual }?.second ?: "清晰度 " + actual))).distinctBy { it.first }.sortedByDescending { it.first },
                isDash = !audioUrl.isNullOrBlank(), videoUrl = videoUrl, audioUrl = audioUrl)
        }
        val durl = data.optJSONArray("durl") ?: return null
        val urls = (0 until durl.length()).mapNotNull { durl.optJSONObject(it)?.optString("url")?.takeIf { u -> u.startsWith("http") } }
        if (urls.isEmpty()) return null
        val actual = reportedQuality.takeIf { it > 0 } ?: return null
        val actualLabel = choices.firstOrNull { it.first == actual }?.second ?: "清晰度 " + actual
        return PlaybackInfo(cid, urls.first(), actual, actualLabel,
            listOf(actual to actualLabel), segments = urls)
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
        val httpCandidates = listOf(qn, 127, 120, 116, 112, 80, 74, 64, 32, 16)
            .filter { it <= qn }.distinct()
        var bestHttp: PlaybackInfo? = null
        for (fnval in listOf(4048, 1)) {
            for (candidate in httpCandidates) {
                val resolved = runCatching { httpPlayUrl(resolvedAid, cid, candidate, cookie, fnval) }.getOrNull()
                    ?: continue
                if (bestHttp == null || resolved.quality > bestHttp!!.quality) bestHttp = resolved
                // Exact requested tier is authoritative; otherwise continue probing lower qn requests
                // because B站 may silently downgrade qn=127 to 480P while a subsequent qn request works.
                if (resolved.quality == qn) {
                    return enrich(resolved.copy(parts = parts, selectedPart = selectedIndex))
                }
            }
        }
        // HTTP 线路可能静默降档；与 gRPC 返回的真实轨道比较后再决定最终播放画质。
        val grpc = if (videoCodec.isBlank() && audioCodec.isBlank()) tryGrpcPlayback(resolvedAid, cid, qn) else null
        val best = listOfNotNull(bestHttp, grpc).maxByOrNull { it.quality }
        best?.let { return enrich(it.copy(parts = parts, selectedPart = selectedIndex)) }

        // Avoid the legacy parser: it converts localized numeric values (e.g. 万) and can throw. Only trust streams returned by official JSON or gRPC.
        error("B站公开播放接口（DASH/DURL）和 gRPC 均未返回可用视频流；已停止使用会触发本地化数字解析异常的旧线路，请稍后重试或切换网络")
    }
}
