package com.sxsbsxs.bilivdl.data

import com.a10miaomiao.bilimiao.comm.apis.CommentApi
import com.a10miaomiao.bilimiao.comm.apis.VideoAPI
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import org.json.JSONObject

/**
 * Routes BiliVdl's existing screens through the imported bilimiao API client.
 * Cookie transport is provided by BiliVdlApplication/PlatformProviders.
 */
object BilimiaoApiBridge {
    private val videoApi = VideoAPI()
    private val commentApi = CommentApi()

    private suspend fun execute(request: MiaoHttp): JSONObject {
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

    suspend fun likeVideo(aid: Long, liked: Boolean) {
        execute(videoApi.like(aid.toString(), dislike = 0, like = if (liked) 1 else 0))
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
                liked = item.optJSONObject("reply_control")?.optInt("action") == 1
            )
        }
        val cursor = data.optJSONObject("cursor")
        val pageInfo = data.optJSONObject("page")
        val hasMore = cursor?.optBoolean("is_end") == false || (cursor == null && result.size >= 20)
        return CommentPage(result, hasMore, cursor?.optInt("all_count") ?: pageInfo?.optInt("count") ?: result.size)
    }

    suspend fun replies(aid: Long, rootId: Long, page: Int = 1): List<VideoComment> {
        val root = execute(commentApi.replyList(aid.toString(), rootId.toString(), 1, page, 20))
        val replies = root.optJSONObject("data")?.optJSONArray("replies") ?: return emptyList()
        return (0 until replies.length()).mapNotNull { i ->
            val item = replies.optJSONObject(i) ?: return@mapNotNull null
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
