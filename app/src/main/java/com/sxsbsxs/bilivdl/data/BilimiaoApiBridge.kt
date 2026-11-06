package com.sxsbsxs.bilivdl.data

import com.a10miaomiao.bilimiao.comm.apis.CommentApi
import com.a10miaomiao.bilimiao.comm.apis.VideoAPI
import com.a10miaomiao.bilimiao.comm.apis.UserApi
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import org.json.JSONObject

/**
 * Routes BiliVdl's existing screens through the imported bilimiao API client.
 * Cookie transport is provided by BiliVdlApplication/PlatformProviders.
 */
object BilimiaoApiBridge {
    private val videoApi = VideoAPI()
    private val commentApi = CommentApi()
    private val userApi = UserApi()

    private suspend fun execute(request: MiaoHttp): JSONObject {
        // Keep okhttp's Response type inside bilimiao's own module to avoid exposing
        // a duplicate okhttp ABI across the imported KMP module boundary.
        val body = request.awaitString()
        if (body.isBlank()) error("B站接口没有返回内容")
        val root = JSONObject(body)
        val code = root.optInt("code", -1)
        if (code != 0) {
            val message = root.optString("message").ifBlank { "B站接口请求失败" }
            when (code) {
                -101 -> error("账号未登录，请先登录 B站")
                -352 -> error("B站风控拦截（-352），请稍后重试")
                -412 -> error("请求被拦截（-412），请稍后重试")
                else -> error("$message（$code）")
            }
        }
        return root
    }

    /**
     * Creator profile and archive pages routed through bilimiao2's maintained API client.
     * Raw JSON is returned so the Android UI can tolerate Bilibili response schema changes.
     */
    suspend fun userSpace(mid: Long): JSONObject {
        require(mid > 0L) { "UP主 UID 无效" }
        return execute(userApi.space(mid.toString()))
            .optJSONObject("data") ?: error("B站没有返回 UP 主资料")
    }

    suspend fun userVideos(mid: Long, cursorAid: Long = 0L, pageSize: Int = 30): List<BiliVideo> {
        require(mid > 0L) { "UP主 UID 无效" }
        val root = execute(userApi.upperVideoList(
            vmid = mid.toString(),
            aid = cursorAid.toString(),
            pageSize = pageSize.coerceIn(1, 50)
        ))
        val data = root.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONObject("list")
        val videos = list?.optJSONArray("vlist")
            ?: data.optJSONArray("item")
            ?: data.optJSONArray("archives")
            ?: data.optJSONArray("items")
            ?: return emptyList()
        return (0 until videos.length()).mapNotNull { index ->
            val item = videos.optJSONObject(index) ?: return@mapNotNull null
            val aid = item.optLong("aid").takeIf { it > 0L }
                ?: item.optString("param").toLongOrNull()
                ?: 0L
            val bvid = item.optString("bvid")
            if (aid <= 0L && bvid.isBlank()) return@mapNotNull null
            val owner = item.optJSONObject("owner")
            BiliVideo(
                title = item.optString("title").ifBlank { "未命名视频" },
                cover = item.optString("pic").ifBlank { item.optString("cover") },
                author = item.optString("author").ifBlank { item.optString("name").ifBlank { "UP主" } },
                play = item.optString("play").ifBlank { item.optJSONObject("stat")?.optString("view").orEmpty() },
                duration = item.optString("length").ifBlank { item.optString("duration") },
                bvid = bvid,
                aid = aid,
                description = item.optString("description").ifBlank { item.optString("desc") },
                danmaku = item.optLong("video_review").takeIf { it > 0L }
                    ?: item.optString("danmaku").toLongOrNull()?.takeIf { it > 0L }
                    ?: item.optJSONObject("stat")?.optLong("danmaku")?.takeIf { it > 0L },
                likes = item.optJSONObject("stat")?.optLong("like")?.takeIf { it > 0L },
                favorites = item.optJSONObject("stat")?.optLong("favorite")?.takeIf { it > 0L },
                coins = item.optJSONObject("stat")?.optLong("coin")?.takeIf { it > 0L },
                shares = item.optJSONObject("stat")?.optLong("share")?.takeIf { it > 0L },
                comments = item.optJSONObject("stat")?.optLong("reply")?.takeIf { it > 0L },
                pubdate = item.optLong("created").takeIf { it > 0L },
                authorFace = owner?.optString("face").orEmpty()
            )
        }
    }

    suspend fun videoInfo(id: String, type: String): JSONObject {
        val root = execute(videoApi.info(id, type))
        return root.optJSONObject("data") ?: error("B站没有返回视频详情")
    }

    suspend fun likeVideo(aid: Long, liked: Boolean) {
        execute(videoApi.like(aid.toString(), dislike = 0, like = if (liked) 1 else 2))
    }

    suspend fun coin(aid: Long, count: Int, alsoLike: Boolean) {
        execute(videoApi.coin(aid.toString(), count, if (alsoLike) 1 else 0))
    }

    suspend fun triple(aid: Long) {
        execute(videoApi.triple(aid.toString()))
    }

    suspend fun commentsPage(aid: Long, page: Int, sort: Int): CommentPage {
        val root = execute(commentApi.mainList(aid.toString(), sort, 1, page, 20))
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
                liked = item.optJSONObject("reply_control")?.optInt("action") == 1,
                face = member?.optString("avatar").orEmpty().ifBlank { member?.optString("face").orEmpty() },
                ctime = item.optLong("ctime"),
                isUp = item.optJSONObject("reply_control")?.optInt("up_action") == 1,
                level = member?.optJSONObject("level_info")?.optInt("current_level") ?: 0
            )
        }
        val cursor = data.optJSONObject("cursor")
        val pageInfo = data.optJSONObject("page")
        // New responses expose cursor.is_end; older responses expose page num/size/count.
        // A missing is_end must not be interpreted as "there are more pages".
        val hasMore = when {
            cursor?.has("is_end") == true -> !cursor.optBoolean("is_end", true)
            pageInfo != null -> {
                val current = pageInfo.optInt("num", page)
                val size = pageInfo.optInt("size", 20).coerceAtLeast(1)
                val total = pageInfo.optInt("count", result.size)
                current * size < total
            }
            else -> result.size >= 20
        }
        val total = cursor?.optInt("all_count")?.takeIf { it > 0 }
            ?: pageInfo?.optInt("count")?.takeIf { it > 0 }
            ?: result.size
        return CommentPage(result, hasMore, total)
    }

    suspend fun replies(aid: Long, rootId: Long, page: Int = 1): CommentPage {
        val root = execute(commentApi.replyList(aid.toString(), rootId.toString(), 1, page.coerceAtLeast(1), 20))
        val data = root.optJSONObject("data") ?: return CommentPage(emptyList(), false, 0)
        val replies = data.optJSONArray("replies")
        val result = ArrayList<VideoComment>()
        if (replies != null) for (i in 0 until replies.length()) {
            val item = replies.optJSONObject(i) ?: continue
            val member = item.optJSONObject("member")
            result += VideoComment(
                id = item.optLong("rpid"),
                user = member?.optString("uname").orEmpty().ifBlank { "用户" },
                text = item.optJSONObject("content")?.optString("message").orEmpty(),
                likes = item.optInt("like"),
                replyCount = item.optInt("rcount"),
                mid = member?.optLong("mid") ?: 0L,
                liked = item.optJSONObject("reply_control")?.optInt("action") == 1,
                face = member?.optString("avatar").orEmpty().ifBlank { member?.optString("face").orEmpty() },
                ctime = item.optLong("ctime"),
                isUp = item.optJSONObject("reply_control")?.optInt("up_action") == 1,
                level = member?.optJSONObject("level_info")?.optInt("current_level") ?: 0
            )
        }
        val cursor = data.optJSONObject("cursor")
        val pageInfo = data.optJSONObject("page")
        val hasMore = when {
            cursor?.has("is_end") == true -> !cursor.optBoolean("is_end", true)
            pageInfo != null -> pageInfo.optInt("num", page) * pageInfo.optInt("size", 20).coerceAtLeast(1) < pageInfo.optInt("count", result.size)
            else -> result.size >= 20
        }
        val total = cursor?.optInt("all_count")?.takeIf { it > 0 }
            ?: pageInfo?.optInt("count")?.takeIf { it > 0 }
            ?: result.size
        return CommentPage(result, hasMore, total)
    }

    suspend fun sendReply(aid: Long, rootId: Long, parentId: Long, message: String): String {
        require(message.isNotBlank()) { "回复内容不能为空" }
        val root = execute(commentApi.add(message.trim(), 1, aid.toString(), rootId.toString(), parentId.toString()))
        return root.optString("message").ifBlank { "回复发送成功" }
    }
    suspend fun likeComment(aid: Long, rpid: Long, liked: Boolean): String {
        val root = execute(commentApi.action(1, aid.toString(), rpid.toString(), if (liked) 1 else 0))
        return root.optString("message").ifBlank { "评论点赞操作成功" }
    }

    suspend fun sendComment(aid: Long, message: String): String {
        val root = execute(commentApi.add(message, 1, aid.toString()))
        return root.optString("message").ifBlank { "评论发送成功" }
    }

    suspend fun favoriteFolders(aid: Long): List<Pair<String, String>> {
        val root = execute(videoApi.favoriteCreated(aid.toString()))
        val data = root.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id").ifBlank { item.optString("media_id") }
            val title = item.optString("title")
            if (id.isBlank() || title.isBlank()) null else id to title
        }
    }

    suspend fun addFavorite(aid: Long, folderId: String) {
        execute(videoApi.favoriteDeal(aid.toString(), listOf(folderId), emptyList()))
    }
}
