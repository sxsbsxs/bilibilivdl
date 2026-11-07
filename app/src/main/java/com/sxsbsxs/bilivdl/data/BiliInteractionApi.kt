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
    val liked: Boolean = false,
    val face: String = "",
    val ctime: Long = 0L,
    val isUp: Boolean = false,
    val level: Int = 0,
    val likedByUp: Boolean = false
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

    /**
     * Read web danmaku metadata and segments.
     *
     * /x/v2/dm/web/view is a protobuf endpoint, not JSON. Treating its response as
     * JSONObject made the primary route fail every time and forced the old XML fallback.
     */
    suspend fun danmaku(cid: Long, cookie: String = ""): List<DanmakuItem> {
        require(cid > 0) { "视频分P编号无效" }
        val userAgent = BiliHeaders.UA

        fun getBytes(url: String): ByteArray {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 25000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Referer", "https://www.bilibili.com/")
                setRequestProperty("Accept", "*/*")
                if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            }
            return try {
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val body = stream?.use { it.readBytes() } ?: ByteArray(0)
                if (code !in 200..299) {
                    val hint = body.toString(Charsets.UTF_8).take(120)
                    error("HTTP $code${if (hint.isBlank()) "" else "：$hint"}")
                }
                if (body.isEmpty()) error("服务器返回空内容")
                body
            } finally {
                c.disconnect()
            }
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
                require(length >= 0 && length <= (data.size - next).toLong())
                next + length.toInt()
            }
            3 -> {
                var index = startAt
                while (index < data.size) {
                    val (tag, next) = readVarint(data, index)
                    index = next
                    val nestedField = (tag shr 3).toInt()
                    val nestedWire = (tag and 7).toInt()
                    if (nestedWire == 4) {
                        require(nestedField > 0)
                        return index
                    }
                    index = skipField(data, index, nestedWire)
                }
                error("弹幕 Protobuf group 未闭合")
            }
            4 -> error("弹幕 Protobuf group 结束标记不匹配")
            5 -> (startAt + 4).also { require(it <= data.size) }
            else -> error("不支持的弹幕 Protobuf 字段")
        }

        fun parseDmWebView(data: ByteArray): Triple<Int, Long, Long> {
            if (data.isEmpty()) error("弹幕元数据为空")
            val prefix = data.toString(Charsets.UTF_8).trimStart()
            if (prefix.startsWith("{")) {
                val response = JSONObject(prefix)
                val code = response.optInt("code", -1)
                error(if (code != 0) apiError(response, code, "弹幕元数据获取失败")
                    else "弹幕元数据返回了 JSON，接口响应格式异常")
            }

            var index = 0
            var state = 0
            var total = -1L
            var count = -1L
            while (index < data.size) {
                val (tag, next) = readVarint(data, index)
                index = next
                val field = (tag shr 3).toInt()
                val wire = (tag and 7).toInt()
                when {
                    field == 1 && wire == 0 -> {
                        val (value, after) = readVarint(data, index)
                        state = value.toInt()
                        index = after
                    }
                    field == 4 && wire == 2 -> {
                        val (length, after) = readVarint(data, index)
                        require(length >= 0 && length <= (data.size - after).toLong())
                        val end = after + length.toInt()
                        var nested = after
                        while (nested < end) {
                            val (nestedTag, nestedNext) = readVarint(data, nested)
                            nested = nestedNext
                            val nestedField = (nestedTag shr 3).toInt()
                            val nestedWire = (nestedTag and 7).toInt()
                            if (nestedField == 2 && nestedWire == 0) {
                                val (value, afterValue) = readVarint(data, nested)
                                total = value
                                nested = afterValue
                            } else {
                                val afterField = skipField(data.copyOfRange(0, end), nested, nestedWire)
                                nested = afterField
                            }
                        }
                        index = end
                    }
                    field == 8 && wire == 0 -> {
                        val (value, after) = readVarint(data, index)
                        count = value
                        index = after
                    }
                    else -> index = skipField(data, index, wire)
                }
            }
            if (total < 0L && count < 0L) error("弹幕元数据缺少分段信息")
            return Triple(state, total, count)
        }

        fun parseElem(data: ByteArray): DanmakuItem? {
            var index = 0
            var progress = 0L
            var mode = 1
            var size = 25
            var color = 0xFFFFFF
            var pool = 0
            var message = ""
            while (index < data.size) {
                val (tag, next) = readVarint(data, index)
                index = next
                val field = (tag shr 3).toInt()
                val wire = (tag and 7).toInt()
                when {
                    field in setOf(2, 3, 4, 5, 11) && wire == 0 -> {
                        val (value, after) = readVarint(data, index)
                        index = after
                        when (field) {
                            2 -> progress = value
                            3 -> mode = value.toInt()
                            4 -> size = value.toInt()
                            5 -> color = value.toInt()
                            11 -> pool = value.toInt()
                        }
                    }
                    field == 7 && wire == 2 -> {
                        val (length, after) = readVarint(data, index)
                        require(length >= 0 && length <= (data.size - after).toLong())
                        message = String(data, after, length.toInt(), Charsets.UTF_8)
                        index = after + length.toInt()
                    }
                    else -> index = skipField(data, index, wire)
                }
            }
            return message.takeIf { it.isNotBlank() }?.let {
                DanmakuItem(progress, mode, size, color, it, pool)
            }
        }

        fun parseSegment(data: ByteArray): List<DanmakuItem> {
            val result = mutableListOf<DanmakuItem>()
            var index = 0
            while (index < data.size) {
                val (tag, next) = readVarint(data, index)
                index = next
                val field = (tag shr 3).toInt()
                val wire = (tag and 7).toInt()
                if (field == 1 && wire == 2) {
                    val (length, after) = readVarint(data, index)
                    require(length >= 0 && length <= (data.size - after).toLong())
                    parseElem(data.copyOfRange(after, after + length.toInt()))?.let(result::add)
                    index = after + length.toInt()
                } else {
                    index = skipField(data, index, wire)
                }
            }
            return result
        }

        var protoError: Exception? = null
        val protoResult = try {
            val metadata = parseDmWebView(
                getBytes("https://api.bilibili.com/x/v2/dm/web/view?type=1&oid=$cid")
            )
            val (state, total, count) = metadata
            if (state == 1) error("UP主或平台已关闭此视频的弹幕")
            if (total == 0L && count == 0L) return emptyList()
            val segmentTotal = (if (total > 0L) total else 1L).coerceIn(1L, 60L).toInt()
            val items = mutableListOf<DanmakuItem>()
            val segmentErrors = mutableListOf<Exception>()
            for (segment in 1..segmentTotal) {
                try {
                    val bytes = getBytes(
                        "https://api.bilibili.com/x/v2/dm/web/seg.so?type=1&oid=$cid&segment_index=$segment"
                    )
                    items += parseSegment(bytes)
                } catch (error: Exception) {
                    segmentErrors += error
                }
            }
            if (items.isEmpty() && segmentErrors.isNotEmpty()) {
                throw IllegalStateException(
                    "分段弹幕全部请求失败：${segmentErrors.first().message}",
                    segmentErrors.first()
                )
            }
            items.distinctBy { it.timeMs to it.text }.sortedBy { it.timeMs }
        } catch (error: Exception) {
            protoError = error
            null
        }
        if (protoResult != null) return protoResult

        fun parseXml(xmlBody: String): List<DanmakuItem> {
            val xml = xmlBody.removePrefix("\\uFEFF").trim()
            if (xml.isBlank()) error("XML 弹幕接口返回空内容")
            if (!xml.startsWith("<")) error("XML 弹幕接口返回的不是 XML")
            val parser = Xml.newPullParser()
            parser.setInput(xml.reader())
            val result = ArrayList<DanmakuItem>()
            var sawRoot = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    if (!sawRoot) {
                        sawRoot = true
                        if (parser.name != "i") error("XML 弹幕接口返回了非弹幕文档")
                    } else if (parser.name == "d") {
                        val parts = parser.getAttributeValue(null, "p").orEmpty().split(',')
                        val raw = parser.nextText()
                        if (parts.size >= 5) {
                            val seconds = parts[0].toDoubleOrNull() ?: 0.0
                            val mode = parts[1].toIntOrNull() ?: 1
                            val size = parts[2].toIntOrNull() ?: 25
                            val color = parts[3].toLongOrNull()?.toInt() ?: 0xFFFFFF
                            val text = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
                            if (text.isNotBlank()) {
                                result += DanmakuItem(
                                    (seconds * 1000).toLong(),
                                    mode,
                                    size,
                                    color,
                                    text,
                                    parts.getOrNull(5)?.toIntOrNull() ?: 0
                                )
                            }
                        }
                    }
                }
                event = parser.next()
            }
            if (!sawRoot) error("XML 弹幕接口没有返回有效文档")
            return result.sortedBy { it.timeMs }
        }

        val xmlErrors = mutableListOf<String>()
        try {
            val xml = BilimiaoApiBridge.danmakuXml(cid)
            return parseXml(xml)
        } catch (error: Exception) {
            xmlErrors += "Bilimiao XML：${error.message ?: error.javaClass.simpleName}"
        }

        try {
            return parseXml(
                String(getBytes("https://comment.bilibili.com/$cid.xml"), Charsets.UTF_8)
            )
        } catch (error: Exception) {
            xmlErrors += "评论 CDN XML：${error.message ?: error.javaClass.simpleName}"
        }

        try {
            return parseXml(
                String(getBytes("https://api.bilibili.com/x/v1/dm/list.so?oid=$cid"), Charsets.UTF_8)
            )
        } catch (error: Exception) {
            xmlErrors += "旧版弹幕接口：${error.message ?: error.javaClass.simpleName}"
        }

        val summary = buildList {
            protoError?.message?.takeIf { it.isNotBlank() }?.let { add("Protobuf：$it") }
            addAll(xmlErrors)
        }.joinToString("；").ifBlank { "所有可用接口均未返回有效弹幕" }.take(220)
        error("弹幕加载失败：$summary")
    }

    private fun apiError(root: JSONObject, code: Int, fallback: String): String = when (code) {
        -352 -> "B站风控拦截（-352），请稍后重试或登录账号"
        -101 -> "账号未登录，请先登录 B站"
        -412 -> "请求被拦截（-412），请稍后重试"
        else -> root.optString("message").ifBlank { fallback } + "（$code）"
    }
}
