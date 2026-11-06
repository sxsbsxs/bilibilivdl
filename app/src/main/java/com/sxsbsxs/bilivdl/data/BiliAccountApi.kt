package com.sxsbsxs.bilivdl.data

import com.a10miaomiao.bilimiao.comm.apis.MessageAPI
import com.a10miaomiao.bilimiao.comm.apis.BangumiAPI
import com.a10miaomiao.bilimiao.comm.apis.UserApi
import com.a10miaomiao.bilimiao.comm.apis.UserRelationApi
import com.a10miaomiao.bilimiao.comm.apis.VideoAPI
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import org.json.JSONArray
import org.json.JSONObject

data class AccountCenterItem(
    val kind: String, val title: String, val subtitle: String = "", val cover: String = "",
    val aid: Long = 0L, val bvid: String = "", val mid: Long = 0L, val mediaId: String = "",
    val url: String = "", val targetId: Long = 0L, val rootId: Long = 0L
)
data class AccountCenterPage(val items: List<AccountCenterItem>, val hasMore: Boolean, val nextCursor: String = "")

/** Data layer for the signed-in account library; UI/navigation remain BiliVdl-owned. */
object BiliAccountApi {
    private val userApi = UserApi()
    private val relationApi = UserRelationApi()
    private val messageApi = MessageAPI()
    private val bangumiApi = BangumiAPI()
    private val videoApi = VideoAPI()

    private suspend fun root(request: MiaoHttp): JSONObject {
        val body = request.awaitString()
        if (body.isBlank()) error("B站接口没有返回内容")
        val result = JSONObject(body)
        val code = result.optInt("code", -1)
        if (code != 0) {
            val message = result.optString("message").ifBlank { "B站接口请求失败" }
            error(when (code) {
                -101 -> "账号未登录，请先登录 B站"
                -352 -> "B站风控拦截（-352），请稍后重试"
                -412 -> "请求被拦截（-412），请稍后重试"
                else -> message + "（" + code + "）"
            })
        }
        return result
    }

    private fun data(result: JSONObject) = result.optJSONObject("data") ?: JSONObject()
    private fun array(data: JSONObject, vararg keys: String): JSONArray? {
        for (key in keys) {
            data.optJSONArray(key)?.let { return it }
            data.optJSONObject(key)?.let { nested ->
                nested.optJSONArray("list")?.let { return it }
                nested.optJSONArray("items")?.let { return it }
            }
        }
        return null
    }
    private fun text(item: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = item.optString(key)
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }
    private fun video(value: JSONObject): AccountCenterItem? {
        val item = value.optJSONObject("archive") ?: value.optJSONObject("item")
            ?: value.optJSONObject("video") ?: value
        val history = value.optJSONObject("history") ?: JSONObject()
        val aid = item.optLong("aid").takeIf { it > 0L }
            ?: item.optLong("id").takeIf { it > 0L }
            ?: item.optString("id").toLongOrNull()?.takeIf { it > 0L }
            ?: value.optLong("aid").takeIf { it > 0L }
            ?: history.optLong("oid").takeIf { it > 0L } ?: value.optLong("oid")
        val bvid = text(item, "bvid", "bv_id").ifBlank { text(value, "bvid", "bv_id") }
        if (aid <= 0L && bvid.isBlank()) return null
        val owner = item.optJSONObject("owner") ?: item.optJSONObject("upper")
            ?: value.optJSONObject("owner") ?: value.optJSONObject("upper") ?: JSONObject()
        val stat = item.optJSONObject("stat") ?: item.optJSONObject("cnt_info")
            ?: value.optJSONObject("stat") ?: value.optJSONObject("cnt_info") ?: JSONObject()
        val author = text(owner, "name", "uname").ifBlank { text(item, "author", "name") }.ifBlank { "UP主" }
        val title = text(item, "title").ifBlank { "未命名视频" }
        val cover = text(item, "pic", "cover").ifBlank { text(value, "pic", "cover") }
            .let { if (it.startsWith("//")) "https:" + it else it }
        val pieces = mutableListOf(author)
        val views = stat.optLong("view").takeIf { it > 0L } ?: stat.optLong("play")
        if (views > 0L) pieces += formatCount(views) + " 播放"
        text(item, "duration", "length").takeIf { it.isNotBlank() }?.let(pieces::add)
        val progress = history.optLong("progress").takeIf { it > 0L } ?: value.optLong("progress").takeIf { it > 0L }
        if (progress != null) pieces += "已观看 " + (progress / 60) + " 分钟"
        return AccountCenterItem("video", title, pieces.joinToString(" · "), cover, aid, bvid)
    }
    private fun formatCount(value: Long): String = when {
        value >= 100_000_000L -> String.format("%.1f亿", value / 100_000_000.0)
        value >= 10_000L -> String.format("%.1f万", value / 10_000.0)
        else -> value.toString()
    }
    private fun hasMore(data: JSONObject, count: Int, pageSize: Int): Boolean {
        if (data.has("has_more")) return data.optBoolean("has_more")
        if (data.has("hasMore")) return data.optBoolean("hasMore")
        val page = data.optJSONObject("page")
        if (page != null) {
            val current = page.optInt("num", page.optInt("pn", 1))
            val size = page.optInt("size", pageSize).coerceAtLeast(1)
            val total = page.optInt("count", page.optInt("total", -1))
            if (total >= 0) return current * size < total
        }
        return count >= pageSize
    }
    private fun videoPage(payload: JSONObject, pageSize: Int): AccountCenterPage {
        val values = array(payload, "list", "archives", "items", "vlist") ?: JSONArray()
        val items = (0 until values.length()).mapNotNull { values.optJSONObject(it)?.let(::video) }
        return AccountCenterPage(items, hasMore(payload, items.size, pageSize))
    }

    suspend fun history(page: Int, pageSize: Int = 20): AccountCenterPage =
        videoPage(data(root(userApi.videoHistory(page, pageSize))), pageSize)

    suspend fun watchLater(startKey: String = "", pageSize: Int = 20): AccountCenterPage {
        val payload = data(root(userApi.videoToview(sortField = 1, asc = false, startKey = startKey)))
        val values = array(payload, "list", "items") ?: JSONArray()
        val items = (0 until values.length()).mapNotNull { values.optJSONObject(it)?.let(::video) }
        val next = payload.optString("next_key").takeIf { it != "null" }.orEmpty()
        val more = payload.optBoolean("has_more", next.isNotBlank()) && next.isNotBlank()
        return AccountCenterPage(items, more, next)
    }

    suspend fun likedVideos(mid: Long, page: Int, pageSize: Int = 20): AccountCenterPage {
        require(mid > 0L) { "账号 UID 无效" }
        return videoPage(data(root(userApi.likeVideoList(mid.toString(), page, pageSize))), pageSize)
    }

    suspend fun favoriteFolders(mid: Long, page: Int, pageSize: Int = 20): AccountCenterPage {
        require(mid > 0L) { "账号 UID 无效" }
        val created = data(root(userApi.favCreatedList(mid.toString(), page, pageSize)))
        val collected = data(root(userApi.favCollectedList(mid.toString(), page, pageSize)))
        val items = ArrayList<AccountCenterItem>()
        fun append(payload: JSONObject) {
            val values = array(payload, "list", "items") ?: return
            for (i in 0 until values.length()) {
                val item = values.optJSONObject(i) ?: continue
                val id = item.optString("id").ifBlank { item.optString("media_id") }
                if (id.isBlank()) continue
                val count = item.optInt("media_count", item.optInt("count", 0))
                val owner = item.optJSONObject("upper") ?: item.optJSONObject("owner") ?: JSONObject()
                val subtitle = mutableListOf<String>()
                if (count > 0) subtitle += count.toString() + " 个视频"
                text(owner, "name", "uname").takeIf { it.isNotBlank() }?.let(subtitle::add)
                if (item.optInt("attr") and 1 != 0) subtitle += "私有收藏夹"
                val cover = text(item, "cover")
                items += AccountCenterItem("folder", text(item, "title").ifBlank { "未命名收藏夹" },
                    subtitle.joinToString(" · "), if (cover.startsWith("//")) "https:" + cover else cover,
                    mediaId = id)
            }
        }
        append(created)
        append(collected)
        val createdCount = array(created, "list", "items")?.length() ?: 0
        val collectedCount = array(collected, "list", "items")?.length() ?: 0
        return AccountCenterPage(items.distinctBy { it.mediaId },
            hasMore(created, createdCount, pageSize) || hasMore(collected, collectedCount, pageSize))
    }

    suspend fun favoriteItems(mediaId: String, page: Int, pageSize: Int = 20): AccountCenterPage {
        require(mediaId.isNotBlank()) { "收藏夹编号无效" }
        val payload = data(root(userApi.mediaDetail(mediaId, page, pageSize)))
        val medialist = payload.optJSONObject("medialist") ?: payload
        val values = array(medialist, "resources", "list", "items") ?: JSONArray()
        val items = (0 until values.length()).mapNotNull { index ->
            values.optJSONObject(index)?.let(::video)?.copy(mediaId = mediaId)
        }
        return AccountCenterPage(items, hasMore(medialist, items.size, pageSize))
    }

    suspend fun followings(mid: Long, page: Int, pageSize: Int = 20): AccountCenterPage {
        require(mid > 0L) { "账号 UID 无效" }
        val payload = data(root(relationApi.followings(mid.toString(), page, pageSize)))
        val values = array(payload, "list", "items") ?: JSONArray()
        val items = (0 until values.length()).mapNotNull { index ->
            val item = values.optJSONObject(index) ?: return@mapNotNull null
            val userMid = item.optLong("mid")
            if (userMid <= 0L) return@mapNotNull null
            val face = text(item, "face")
            AccountCenterItem("user", text(item, "uname", "name").ifBlank { "B站用户" },
                text(item, "sign"), if (face.startsWith("//")) "https:" + face else face, mid = userMid)
        }
        return AccountCenterPage(items, hasMore(payload, items.size, pageSize))
    }

    suspend fun unreadMessages(): List<AccountCenterItem> {
        val payload = data(root(messageApi.unread()))
        return listOf("收到的赞" to "like", "回复我的" to "reply", "@我的" to "at")
            .map { (label, key) ->
                val count = payload.optInt(key)
                AccountCenterItem("message_category", label,
                    if (count > 0) count.toString() + " 条未读" else "暂无未读消息",
                    mediaId = key)
            }
    }

    suspend fun messages(category: String, cursorId: Long = 0L, cursorTime: Long = 0L): AccountCenterPage {
        require(category in setOf("like", "reply", "at")) { "消息类型无效" }
        val response = root(when (category) {
            "like" -> messageApi.like(cursorId, cursorTime)
            "reply" -> messageApi.reply(cursorId, cursorTime)
            else -> messageApi.at(cursorId, cursorTime)
        })
        val payload = data(response)
        val total = payload.optJSONObject("total") ?: payload
        val values = total.optJSONArray("items") ?: payload.optJSONArray("items") ?: JSONArray()
        val items = (0 until values.length()).mapNotNull { index ->
            val item = values.optJSONObject(index) ?: return@mapNotNull null
            val content = item.optJSONObject("item") ?: JSONObject()
            val user = item.optJSONObject("user") ?: JSONObject()
            val users = item.optJSONArray("users")
            val firstUser = users?.optJSONObject(0) ?: user
            val mid = firstUser.optLong("mid")
            val nickname = text(firstUser, "nickname", "uname").ifBlank { "B站用户" }
            val face = text(firstUser, "avatar", "face").let { if (it.startsWith("//")) "https:" + it else it }
            val kindLabel = when (category) {
                "like" -> "赞了你的内容"
                "at" -> "在内容中提到了你"
                else -> "回复了你的内容"
            }
            val uri = text(content, "uri")
            val bvid = Regex("BV[0-9A-Za-z]+").find(uri)?.value.orEmpty()
            val avAid = Regex("av([0-9]+)", RegexOption.IGNORE_CASE).find(uri)
                ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val itemAid = if (text(content, "type") == "video") {
                content.optLong("item_id").takeIf { it > 0L }
            } else null
            val aid = content.optLong("subject_id").takeIf { it > 0L }
                ?: itemAid
                ?: avAid.takeIf { it > 0L }
                ?: 0L
            val rootId = content.optLong("root_id")
            val targetId = content.optLong("target_id").takeIf { it > 0L }
                ?: content.optLong("source_id")
            val title = text(content, "title").ifBlank { "互动消息" }
            val sourceText = text(content, "source_content", "desc")
            val atTime = item.optLong("at_time")
            val replyTime = item.optLong("reply_time")
            val likeTime = item.optLong("like_time")
            val time = maxOf(atTime, replyTime, likeTime)
            val count = item.optInt("counts")
            val subtitle = buildList {
                add(nickname + " · " + kindLabel)
                if (count > 1) add(count.toString() + " 人")
                if (sourceText.isNotBlank()) add(sourceText)
                if (time > 0L) add(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                    java.util.Locale.getDefault()).format(java.util.Date(time * 1000L)))
            }.joinToString(" · ")
            AccountCenterItem(
                kind = "message",
                title = title,
                subtitle = subtitle,
                cover = text(content, "image").let { if (it.startsWith("//")) "https:" + it else it },
                aid = aid,
                bvid = bvid,
                mid = mid,
                mediaId = category,
                url = text(content, "uri"),
                targetId = targetId,
                rootId = rootId
            )
        }
        val cursor = total.optJSONObject("cursor") ?: payload.optJSONObject("cursor")
        val nextId = cursor?.optLong("id") ?: 0L
        val nextTime = cursor?.optLong("time") ?: 0L
        val ended = cursor?.optBoolean("isEnd") ?: cursor?.optBoolean("is_end") ?: items.isEmpty()
        return AccountCenterPage(items, !ended && items.isNotEmpty(), nextId.toString() + "|" + nextTime)
    }


    suspend fun bangumi(type: String, status: Int, page: Int, pageSize: Int = 20): AccountCenterPage {
        require(type == "bangumi" || type == "cinema") { "番剧类型无效" }
        require(status in 0..3) { "追番状态无效" }
        val response = root(bangumiApi.followList(type, status, page, pageSize))
        val payload = response.optJSONObject("result") ?: response.optJSONObject("data") ?: JSONObject()
        val values = payload.optJSONArray("follow_list") ?: JSONArray()
        val items = (0 until values.length()).mapNotNull { index ->
            val item = values.optJSONObject(index) ?: return@mapNotNull null
            val seasonId = item.optString("season_id")
            if (seasonId.isBlank()) return@mapNotNull null
            val cover = text(item, "cover", "square_cover").let { if (it.startsWith("//")) "https:" + it else it }
            val newest = item.optJSONObject("new_ep")?.optString("index_show").orEmpty()
            val progress = item.optJSONObject("progress")?.optString("index_show").orEmpty()
            val subtitle = listOf(item.optString("season_type_name"), newest, progress)
                .filter { it.isNotBlank() && it != "null" }.joinToString(" · ")
            AccountCenterItem("bangumi", text(item, "title").ifBlank { "未命名番剧" },
                subtitle, cover, mediaId = seasonId)
        }
        return AccountCenterPage(items, payload.optInt("has_next") == 1)
    }

    suspend fun changeBangumiStatus(seasonId: String, status: Int) {
        require(seasonId.isNotBlank()) { "番剧编号无效" }
        require(status in 0..3) { "追番状态无效" }
        val request = if (status == 0) bangumiApi.cancelFollow(seasonId)
            else bangumiApi.setFollowStatus(seasonId, status)
        root(request)
    }

    suspend fun addToWatchLater(aid: Long) {
        require(aid > 0L) { "视频 AV 号无效" }
        root(userApi.videoToviewAdd(aid.toString()))
    }
    suspend fun removeFromWatchLater(aid: Long) {
        require(aid > 0L) { "视频 AV 号无效" }
        root(userApi.videoToviewDels(listOf(aid.toString())))
    }
    suspend fun createFavoriteFolder(title: String) {
        require(title.isNotBlank()) { "收藏夹名称不能为空" }
        root(userApi.favAddFolder(title.trim(), "", "", 0))
    }
    suspend fun deleteFavoriteFolder(mediaId: String) {
        require(mediaId.isNotBlank()) { "收藏夹编号无效" }
        root(userApi.favDeleteFolder(mediaId))
    }
    suspend fun removeFavoriteItem(aid: Long, mediaId: String) {
        require(aid > 0L && mediaId.isNotBlank()) { "收藏内容编号无效" }
        root(videoApi.favoriteDeal(aid.toString(), emptyList(), listOf(mediaId)))
    }
    suspend fun follow(mid: Long, followed: Boolean) {
        require(mid > 0L) { "UP主 UID 无效" }
        root(relationApi.modify(mid.toString(), if (followed) 1 else 2))
    }
    suspend fun isFollowing(mid: Long): Boolean {
        require(mid > 0L) { "UP主 UID 无效" }
        val payload = data(root(relationApi.interrelations(listOf(mid.toString()))))
        val values = array(payload, "list", "items") ?: return false
        val item = (0 until values.length()).firstNotNullOfOrNull { values.optJSONObject(it) } ?: return false
        return item.optInt("attribute") and 2 != 0
    }
}
