package com.sxsbsxs.bilivdl.data

import android.text.Html
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

data class VideoComment(
    val id: Long,
    val user: String,
    val text: String,
    val likes: Int,
    val replyCount: Int,
    val mid: Long,
    val liked: Boolean = false
)

data class DanmakuItem(
    val timeMs: Long,
    val mode: Int,
    val fontSize: Int,
    val color: Int,
    val text: String,
    val pool: Int
)

data class CommentPage(
    val comments: List<VideoComment>,
    val hasMore: Boolean,
    val total: Int
)

object BiliInteractionApi {
    private fun request(url: String, cookie: String = "", post: Map<String, String>? = null): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            requestMethod = if (post == null) "GET" else "POST"
            setRequestProperty("User-Agent", BiliHeaders.UA)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Origin", "https://www.bilibili.com")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            if (post != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }
        }
        return try {
            if (post != null) {
                val body = post.entries.joinToString("&") {
                    java.net.URLEncoder.encode(it.key, "UTF-8") + "=" +
                        java.net.URLEncoder.encode(it.value, "UTF-8")
                }
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (body.isBlank()) error("B站接口 HTTP ${c.responseCode}")
            JSONObject(body)
        } finally { c.disconnect() }
    }

    fun commentsPage(aid: Long, page: Int, sort: Int = 3, cookie: String = ""): CommentPage {
        require(aid > 0) { "视频编号无效，无法加载评论" }
        val root = request(
            "https://api.bilibili.com/x/v2/reply/main?oid=$aid&type=1&sort=$sort&pn=$page&ps=20&plat=2",
            cookie
        )
        val code = root.optInt("code", -1)
        if (code != 0) error(apiError(root, code, "评论加载失败"))
        val data = root.optJSONObject("data") ?: return CommentPage(emptyList(), false, 0)
        val replies = data.optJSONArray("replies")
        val result = ArrayList<VideoComment>()
        if (replies != null) for (i in 0 until replies.length()) {
            val item = replies.optJSONObject(i) ?: continue
            val member = item.optJSONObject("member")
            val content = item.optJSONObject("content")
            result += VideoComment(
                id = item.optLong("rpid"),
                user = member?.optString("uname").orEmpty().ifBlank { "用户" },
                text = content?.optString("message").orEmpty(),
                likes = item.optInt("like"),
                replyCount = item.optInt("rcount"),
                mid = member?.optLong("mid") ?: 0L,
                liked = item.optJSONObject("reply_control")?.optInt("action") == 1
            )
        }
        val pageInfo = data.optJSONObject("page")
        val cursor = data.optJSONObject("cursor")
        val hasMore = cursor?.optBoolean("is_end") == false || (cursor == null && result.size >= 20)
        return CommentPage(result, hasMore, cursor?.optInt("all_count") ?: pageInfo?.optInt("count") ?: result.size)
    }

    fun replies(aid: Long, rootId: Long, page: Int = 1, cookie: String = ""): List<VideoComment> {
        val root = request(
            "https://api.bilibili.com/x/v2/reply/main?oid=$aid&plat=2&root=$rootId&sort=0&type=1&pn=$page&ps=20",
            cookie
        )
        val code = root.optInt("code", -1)
        if (code != 0) error(apiError(root, code, "回复加载失败"))
        val arr = root.optJSONObject("data")?.optJSONArray("replies") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val item = arr.optJSONObject(i) ?: return@mapNotNull null
            val member = item.optJSONObject("member")
            VideoComment(
                id = item.optLong("rpid"),
                user = member?.optString("uname").orEmpty().ifBlank { "用户" },
                text = item.optJSONObject("content")?.optString("message").orEmpty(),
                likes = item.optInt("like"),
                replyCount = item.optInt("rcount"),
                mid = member?.optLong("mid") ?: 0L,
                liked = item.optJSONObject("reply_control")?.optInt("action") == 1
            )
        }
    }

    fun likeComment(aid: Long, rpid: Long, like: Boolean, cookie: String): String {
        if (cookie.isBlank()) error("点赞需要先登录 B站账号")
        val root = request(
            "https://api.bilibili.com/x/v2/reply/action",
            cookie,
            mapOf("oid" to aid.toString(), "type" to "1", "rpid" to rpid.toString(), "action" to if (like) "1" else "0")
        )
        if (root.optInt("code") != 0) error(apiError(root, root.optInt("code"), "评论点赞失败"))
        return root.optString("message", "操作成功")
    }

    fun sendComment(aid: Long, message: String, cookie: String, rootId: Long? = null, parentId: Long? = null): String {
        if (cookie.isBlank()) error("发表评论需要先登录 B站账号")
        val params = mutableMapOf("oid" to aid.toString(), "type" to "1", "message" to message, "plat" to "2")
        rootId?.let { params["root"] = it.toString() }
        parentId?.let { params["parent"] = it.toString() }
        val root = request("https://api.bilibili.com/x/v2/reply/add", cookie, params)
        if (root.optInt("code") != 0) error(apiError(root, root.optInt("code"), "发表评论失败"))
        return "发送成功"
    }

    fun danmaku(cid: Long, cookie: String = ""): List<DanmakuItem> {
        require(cid > 0) { "视频分P编号无效" }
        val headers = mapOf(
            "User-Agent" to BiliHeaders.UA,
            "Referer" to "https://www.bilibili.com/",
            "Accept" to "*/*"
        )
        fun getBytes(url: String): ByteArray {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 25000
                setRequestProperty("User-Agent", headers.getValue("User-Agent"))
                setRequestProperty("Referer", headers.getValue("Referer"))
                setRequestProperty("Accept", headers.getValue("Accept"))
                if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            }
            return try {
                val code = c.responseCode
                if (code !in 200..299) error("弹幕接口 HTTP $code")
                c.inputStream.use { it.readBytes() }
            } finally { c.disconnect() }
        }
        fun readVarint(data: ByteArray, startAt: Int): Pair<Long, Int> {
            var value = 0L
            var shift = 0
            var index = startAt
            while (index < data.size && shift < 64) {
                val b = data[index++].toInt() and 0xff
                value = value or ((b and 0x7f).toLong() shl shift)
                if ((b and 0x80) == 0) return value to index
                shift += 7
            }
            error("弹幕 Protobuf 数据格式错误")
        }
        fun skipField(data: ByteArray, startAt: Int, wire: Int): Int = when (wire) {
            0 -> readVarint(data, startAt).second
            1 -> (startAt + 8).also { require(it <= data.size) }
            2 -> {
                val (length, next) = readVarint(data, startAt)
                (next + length.toInt()).also { require(length >= 0 && it <= data.size) }
            }
            5 -> (startAt + 4).also { require(it <= data.size) }
            else -> error("不支持的弹幕 Protobuf 字段")
        }
        fun parseElem(data: ByteArray): DanmakuItem? {
            var i = 0
            var progress = 0L
            var mode = 1
            var size = 25
            var color = 0xFFFFFF
            var pool = 0
            var message = ""
            while (i < data.size) {
                val (tag, next) = readVarint(data, i); i = next
                val field = (tag shr 3).toInt()
                val wire = (tag and 7).toInt()
                when {
                    field in setOf(2, 3, 4, 5, 9) && wire == 0 -> {
                        val (v, n) = readVarint(data, i); i = n
                        when (field) {
                            2 -> progress = v
                            3 -> mode = v.toInt()
                            4 -> size = v.toInt()
                            5 -> color = v.toInt()
                            9 -> pool = v.toInt()
                        }
                    }
                    field == 7 && wire == 2 -> {
                        val (length, n) = readVarint(data, i); i = n
                        require(length >= 0 && i + length.toInt() <= data.size)
                        message = String(data, i, length.toInt(), Charsets.UTF_8)
                        i += length.toInt()
                    }
                    else -> i = skipField(data, i, wire)
                }
            }
            return message.takeIf { it.isNotBlank() }?.let {
                DanmakuItem(progress, mode, size, color, it, pool)
            }
        }
        fun parseSegment(data: ByteArray): List<DanmakuItem> {
            val result = mutableListOf<DanmakuItem>()
            var i = 0
            while (i < data.size) {
                val (tag, next) = readVarint(data, i); i = next
                val field = (tag shr 3).toInt()
                val wire = (tag and 7).toInt()
                if (field == 1 && wire == 2) {
                    val (length, n) = readVarint(data, i); i = n
                    require(length >= 0 && i + length.toInt() <= data.size)
                    parseElem(data.copyOfRange(i, i + length.toInt()))?.let(result::add)
                    i += length.toInt()
                } else i = skipField(data, i, wire)
            }
            return result
        }
        val protoResult = runCatching {
            val view = JSONObject(String(getBytes("https://api.bilibili.com/x/v2/dm/web/view?type=1&oid=$cid"), Charsets.UTF_8))
            if (view.optInt("code") != 0) error(view.optString("message", "弹幕分段信息获取失败"))
            val total = view.optJSONObject("data")?.optJSONObject("dmSge")?.optInt("total", 1)
                ?.coerceIn(1, 60) ?: 1
            buildList {
                for (segment in 1..total) {
                    val bytes = getBytes("https://api.bilibili.com/x/v2/dm/web/seg.so?type=1&oid=$cid&segment_index=$segment")
                    addAll(parseSegment(bytes))
                }
            }.distinctBy { it.timeMs to it.text }.sortedBy { it.timeMs }
        }.getOrNull()
        if (!protoResult.isNullOrEmpty()) return protoResult

        // Compatibility fallback for older videos/CDN responses that still expose XML.
        val c = (URL("https://api.bilibili.com/x/v1/dm/list.so?oid=$cid").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("User-Agent", BiliHeaders.UA)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
        }
        val xml = try {
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally { c.disconnect() }
        if (xml.isBlank()) error("弹幕加载失败：分段弹幕和旧版 XML 接口均未返回数据")
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        val result = ArrayList<DanmakuItem>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "d") {
                val p = parser.getAttributeValue(null, "p").orEmpty().split(',')
                val raw = parser.nextText()
                if (p.size >= 5) {
                    val seconds = p[0].toDoubleOrNull() ?: 0.0
                    val mode = p[1].toIntOrNull() ?: 1
                    val size = p[2].toIntOrNull() ?: 25
                    val color = p[3].toLongOrNull()?.toInt() ?: 0xFFFFFF
                    val text = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
                    if (text.isNotBlank()) result += DanmakuItem(
                        (seconds * 1000).toLong(), mode, size, color, text, p.getOrNull(5)?.toIntOrNull() ?: 0
                    )
                }
            }
            event = parser.next()
        }
        return result.sortedBy { it.timeMs }
    }

    private fun apiError(root: JSONObject, code: Int, fallback: String): String = when (code) {
        -352 -> "B站风控拦截（-352），请稍后重试或登录账号"
        -101 -> "账号未登录，请先登录 B站"
        -412 -> "请求被拦截（-412），请稍后重试"
        else -> root.optString("message").ifBlank { fallback } + "（$code）"
    }
}
