package com.sxsbsxs.bilivdl.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class BiliDynamicItem(
    val id: String,
    val type: String,
    val ownerMid: Long,
    val ownerName: String,
    val ownerFace: String,
    val timeLabel: String,
    val text: String,
    val title: String,
    val cover: String,
    val images: List<String>,
    val aid: Long,
    val bvid: String,
    val episodeId: Long,
    val seasonId: Long,
    val url: String,
    val repostCount: Long,
    val replyCount: Long,
    val likeCount: Long,
    val liked: Boolean,
    val pinned: Boolean
)

data class BiliDynamicPage(
    val items: List<BiliDynamicItem>,
    val offset: String,
    val updateBaseline: String,
    val hasMore: Boolean
)

object BiliDynamicApi {
    private const val FEED_URL = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all"
    private const val LIKE_URL = "https://api.bilibili.com/x/dynamic/feed/dyn/thumb"

    private fun normalizeUrl(value: String): String =
        when {
            value.isBlank() -> ""
            value.startsWith("//") -> "https:$value"
            else -> value
        }

    private fun text(obj: JSONObject?, key: String): String =
        obj?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()

    private fun request(
        url: String,
        cookie: String,
        method: String = "GET",
        body: String? = null,
        contentType: String = "application/x-www-form-urlencoded; charset=UTF-8"
    ): JSONObject {
        require(cookie.isNotBlank()) { "查看关注动态需要先登录 B站账号" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            requestMethod = method
            useCaches = false
            setRequestProperty("User-Agent", BiliHeaders.UA)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Origin", "https://www.bilibili.com")
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Cache-Control", "no-cache")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
            }
        }
        return try {
            if (body != null) connection.outputStream.use {
                it.write(body.toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (raw.isBlank()) error("动态接口没有返回内容（HTTP $status）")
            JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }

    private fun checked(root: JSONObject, fallback: String): JSONObject {
        val code = root.optInt("code", -1)
        if (code != 0) {
            val message = root.optString("message").ifBlank { fallback }
            error(when (code) {
                -101 -> "B站账号登录已失效，请重新扫码登录"
                -352, -412 -> "B站风控拦截（$code），请稍后重试"
                else -> "$message（$code）"
            })
        }
        return root.optJSONObject("data") ?: JSONObject()
    }

    suspend fun feed(
        cookie: String,
        offset: String = "",
        updateBaseline: String = "",
        type: String = "all"
    ): BiliDynamicPage {
        require(type in setOf("all", "video", "pgc", "article")) { "动态筛选类型无效" }
        val params = linkedMapOf(
            "type" to type,
            "platform" to "web",
            "features" to "itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote,decorationCard,onlyfansAssetsV2,forwardListHidden,ugcDelete",
            "web_location" to "333.1365"
        )
        if (offset.isNotBlank()) params["offset"] = offset
        if (updateBaseline.isNotBlank()) params["update_baseline"] = updateBaseline
        val query = params.entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
        val data = checked(request("$FEED_URL?$query", cookie), "动态列表加载失败")
        val array = data.optJSONArray("items")
        val parsed = ArrayList<BiliDynamicItem>()
        if (array != null) for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (!item.optBoolean("visible", true)) continue
            parseItem(item)?.let(parsed::add)
        }
        return BiliDynamicPage(
            items = parsed,
            offset = text(data, "offset"),
            updateBaseline = text(data, "update_baseline"),
            hasMore = data.optBoolean("has_more", false)
        )
    }

    private fun parseItem(item: JSONObject): BiliDynamicItem? {
        val modules = item.optJSONObject("modules") ?: JSONObject()
        val author = modules.optJSONObject("module_author") ?: JSONObject()
        val dynamic = modules.optJSONObject("module_dynamic") ?: JSONObject()
        val major = dynamic.optJSONObject("major") ?: JSONObject()
        val desc = dynamic.optJSONObject("desc") ?: JSONObject()
        val stat = modules.optJSONObject("module_stat") ?: JSONObject()
        val likeInfo = stat.optJSONObject("like_info") ?: JSONObject()
        val authorMid = author.optLong("mid")
            .takeIf { it > 0L }
            ?: author.optJSONObject("avatar")?.optLong("mid")
            ?: 0L
        val userName = text(author, "name").ifBlank { "B站用户" }
        val face = normalizeUrl(text(author, "face"))
        val archive = major.optJSONObject("archive") ?: dynamic.optJSONObject("additional")
            ?.optJSONObject("ugc") ?: JSONObject()
        val pgc = major.optJSONObject("pgc") ?: JSONObject()
        val draw = major.optJSONObject("draw") ?: JSONObject()
        val opus = major.optJSONObject("opus") ?: JSONObject()
        val article = major.optJSONObject("article") ?: JSONObject()
        val contentText = text(desc, "text").ifBlank {
            text(opus.optJSONObject("summary"), "text")
        }.ifBlank {
            text(article, "desc")
        }
        val aid = archive.optString("aid").toLongOrNull()
            ?: archive.optLong("aid").takeIf { it > 0L }
            ?: major.optJSONObject("ugc_season")?.optLong("aid")?.takeIf { it > 0L }
            ?: 0L
        val bvid = text(archive, "bvid")
        val episodeId = pgc.optLong("epid")
        val seasonId = pgc.optLong("season_id")
        val title = text(archive, "title").ifBlank {
            text(pgc, "title").ifBlank {
                text(opus, "title").ifBlank {
                    text(article, "title").ifBlank {
                        text(major.optJSONObject("common"), "title")
                    }
                }
            }
        }
        val cover = normalizeUrl(text(archive, "cover").ifBlank {
            text(pgc, "cover").ifBlank {
                text(major.optJSONObject("ugc_season"), "cover").ifBlank {
                    text(major.optJSONObject("common"), "cover")
                }
            }
        })
        val images = ArrayList<String>()
        val drawItems = draw.optJSONArray("items")
        if (drawItems != null) for (i in 0 until drawItems.length()) {
            val url = normalizeUrl(text(drawItems.optJSONObject(i), "src"))
            if (url.isNotBlank()) images += url
        }
        val opusPics = opus.optJSONArray("pics")
        if (opusPics != null) for (i in 0 until opusPics.length()) {
            val photo = opusPics.optJSONObject(i) ?: continue
            val url = normalizeUrl(text(photo, "url").ifBlank { text(photo, "src") })
            if (url.isNotBlank()) images += url
        }
        val articleCovers = article.optJSONArray("covers")
        if (articleCovers != null) for (i in 0 until articleCovers.length()) {
            val url = normalizeUrl(articleCovers.optString(i))
            if (url.isNotBlank()) images += url
        }
        if (images.isEmpty() && cover.isNotBlank()) images += cover
        val id = text(item, "id_str").ifBlank { text(item.optJSONObject("extend"), "dyn_id_str") }
        if (id.isBlank()) return null
        val url = when {
            bvid.isNotBlank() -> "https://www.bilibili.com/video/$bvid"
            episodeId > 0L -> "https://www.bilibili.com/bangumi/play/ep$episodeId"
            id.isNotBlank() -> "https://t.bilibili.com/$id"
            else -> ""
        }
        val timeLabel = text(author, "pub_time").ifBlank {
            text(author, "pub_ts").let { value ->
                val timestamp = value.toLongOrNull() ?: 0L
                if (timestamp > 0L) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                    java.util.Locale.getDefault()).format(java.util.Date(timestamp * 1000L)) else ""
            }
        }
        return BiliDynamicItem(
            id = id,
            type = text(item, "type"),
            ownerMid = authorMid,
            ownerName = userName,
            ownerFace = face,
            timeLabel = timeLabel,
            text = contentText,
            title = title,
            cover = cover,
            images = images.distinct().take(9),
            aid = aid,
            bvid = bvid,
            episodeId = episodeId,
            seasonId = seasonId,
            url = url,
            repostCount = stat.optLong("repost"),
            replyCount = stat.optLong("reply"),
            likeCount = stat.optLong("like"),
            liked = likeInfo.optBoolean("is_like", false),
            pinned = modules.has("module_tag")
        )
    }

    suspend fun toggleLike(cookie: String, dynamicId: String, liked: Boolean) {
        require(dynamicId.isNotBlank()) { "动态编号无效" }
        val csrf = Regex("(?:^|;\\s*)bili_jct=([^;]+)").find(cookie)?.groupValues?.getOrNull(1)
            ?: error("登录 Cookie 缺少 bili_jct，无法给动态点赞")
        val endpoint = "$LIKE_URL?csrf=" + URLEncoder.encode(csrf, "UTF-8")
        val body = JSONObject()
            .put("dyn_id_str", dynamicId)
            .put("up", if (liked) 1 else 2)
            .toString()
        checked(request(endpoint, cookie, "POST", body, "application/json; charset=UTF-8"), "动态点赞失败")
    }
}
