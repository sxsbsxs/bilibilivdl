package com.sxsbsxs.bilivdl.data

import com.a10miaomiao.bilimiao.comm.apis.CommentApi
import com.a10miaomiao.bilimiao.comm.apis.VideoAPI
import com.a10miaomiao.bilimiao.comm.apis.UserApi
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp
import org.json.JSONObject
import bilibili.app.dynamic.v2.DynamicGRPC
import bilibili.app.dynamic.v2.DynSpaceReq
import bilibili.app.dynamic.v2.DynamicItem
import bilibili.app.dynamic.v2.Module
import bilibili.app.dynamic.v2.ModuleDynamic
import bilibili.app.dynamic.v2.Paragraph
import bilibili.app.dynamic.v2.TextNode
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp

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
    /**
     * 公共 UP 主空间动态，使用 bilimiao2 与 B站 APP 相同的 gRPC 接口。
     * 不要求登录 Cookie；公开动态在游客状态下也可读取。
     */
    suspend fun userDynamics(
        mid: Long,
        historyOffset: String = "",
        page: Int = 1
    ): BiliDynamicPage {
        require(mid > 0L) { "UP主 UID 无效" }
        val request = DynSpaceReq(
            hostUid = mid,
            historyOffset = historyOffset,
            localTime = 8,
            page = page.coerceAtLeast(1).toLong(),
            from = "space"
        )
        val response = BiliGRPCHttp.request {
            DynamicGRPC.dynSpace(request)
        }.awaitCall()
        return BiliDynamicPage(
            items = response.list.mapNotNull(::parseSpaceDynamic),
            offset = response.historyOffset,
            updateBaseline = "",
            hasMore = response.hasMore
        )
    }

    /**
     * Parse both legacy dynamic cards (ModuleDesc/DynDraw) and the current Opus
     * paragraph layout (ModuleOpusSummary/ModuleParagraph). B站逐步切换新卡片后，
     * 只解析旧字段会把图文动态渲染成一张没有正文的空卡片。
     */
    private fun paragraphText(paragraph: Paragraph?): String {
        val content = paragraph?.content
        return when (content) {
            is Paragraph.Content.Text -> content.value.nodes.joinToString("") { node ->
                // Match bilimiao2's rich-text parser: raw_text is not populated
                // consistently for WORD/EMOTE/LINK nodes in every API response.
                when (val text = node.text) {
                    is TextNode.Text.Word -> text.value.words
                    is TextNode.Text.Link -> text.value.showText?.words.orEmpty()
                    is TextNode.Text.Emote ->
                        text.value.rawText?.words.orEmpty().ifBlank { "[表情]" }
                    else -> node.rawText
                }
            }
            else -> ""
        }
    }

    private fun parseSpaceDynamic(item: DynamicItem, depth: Int = 0): BiliDynamicItem? {
        if (depth > 1) return null
        var authorModule: bilibili.app.dynamic.v2.ModuleAuthor? = null
        var description = ""
        var paragraphTitle = ""
        val paragraphTexts = mutableListOf<String>()
        val paragraphImages = mutableListOf<String>()
        var opusSummary: bilibili.app.dynamic.v2.ModuleOpusSummary? = null
        var dynamicModule: ModuleDynamic? = null
        var statModule: bilibili.app.dynamic.v2.ModuleStat? = null
        item.modules.forEach { module ->
            when (val part = module.moduleItem) {
                is Module.ModuleItem.ModuleAuthor -> authorModule = part.value
                is Module.ModuleItem.ModuleDesc -> description = part.value.text
                is Module.ModuleItem.ModuleDynamic -> dynamicModule = part.value
                is Module.ModuleItem.ModuleStat -> statModule = part.value
                is Module.ModuleItem.ModuleStatForward -> statModule = part.value
                is Module.ModuleItem.ModuleOpusSummary -> opusSummary = part.value
                is Module.ModuleItem.ModuleParagraph -> {
                    val value = part.value
                    val paragraph = value.paragraph
                    val text = paragraphText(paragraph).trim()
                    if (text.isNotBlank()) {
                        if (value.isArticleTitle && paragraphTitle.isBlank()) paragraphTitle = text
                        else paragraphTexts += text
                    }
                    when (val content = paragraph?.content) {
                        is Paragraph.Content.Pic -> {
                            content.value.pics?.items?.mapNotNull { it.src.takeIf(String::isNotBlank) }
                                ?.let(paragraphImages::addAll)
                        }
                        else -> Unit
                    }
                }
                else -> Unit
            }
        }
        val author = authorModule?.author
        val payload = dynamicModule?.moduleItem
        val archive = (payload as? ModuleDynamic.ModuleItem.DynArchive)?.value
        val pgc = (payload as? ModuleDynamic.ModuleItem.DynPgc)?.value
        val draw = (payload as? ModuleDynamic.ModuleItem.DynDraw)?.value
        val article = (payload as? ModuleDynamic.ModuleItem.DynArticle)?.value
        val ugcSeason = (payload as? ModuleDynamic.ModuleItem.DynUgcSeason)?.value
        val course = (payload as? ModuleDynamic.ModuleItem.DynCourBatchUp)?.value
        // 转发动态的正文属于外层，媒体卡属于被转发的内层动态。
        val forwarded = (payload as? ModuleDynamic.ModuleItem.DynForward)
            ?.value?.item?.let { parseSpaceDynamic(it, depth + 1) }

        val aid = archive?.avid?.takeIf { it > 0L }
            ?: pgc?.aid?.takeIf { it > 0L }
            ?: ugcSeason?.avid?.takeIf { it > 0L }
            ?: course?.avid?.takeIf { it > 0L }
            ?: forwarded?.aid?.takeIf { it > 0L }
            ?: 0L
        val bvid = archive?.bvid.orEmpty().ifBlank { forwarded?.bvid.orEmpty() }
        val episodeId = pgc?.epid?.takeIf { it > 0L }
            ?: archive?.episodeId?.takeIf { it > 0L }
            ?: course?.epid?.takeIf { it > 0L }
            ?: forwarded?.episodeId?.takeIf { it > 0L }
            ?: 0L
        val seasonId = pgc?.seasonId?.takeIf { it > 0L }
            ?: archive?.pgcSeasonId?.takeIf { it > 0L }
            ?: course?.seasonId?.takeIf { it > 0L }
            ?: forwarded?.seasonId?.takeIf { it > 0L }
            ?: 0L
        val opusTitle = paragraphText(opusSummary?.title)
        val opusSummaryText = paragraphText(opusSummary?.summary)
        val title = archive?.title.orEmpty().ifBlank {
            pgc?.title.orEmpty().ifBlank {
                article?.title.orEmpty().ifBlank {
                    ugcSeason?.title.orEmpty().ifBlank {
                        course?.title.orEmpty().ifBlank {
                            paragraphTitle.ifBlank { opusTitle.ifBlank { forwarded?.title.orEmpty() } }
                        }
                    }
                }
            }
        }
        val cover = archive?.cover.orEmpty().ifBlank {
            pgc?.cover.orEmpty().ifBlank {
                article?.covers?.firstOrNull().orEmpty().ifBlank {
                    ugcSeason?.cover.orEmpty().ifBlank {
                        course?.cover.orEmpty().ifBlank {
                            draw?.items?.firstOrNull()?.src.orEmpty().ifBlank {
                                opusSummary?.covers?.firstOrNull()?.src.orEmpty().ifBlank {
                                    paragraphImages.firstOrNull().orEmpty().ifBlank { forwarded?.cover.orEmpty() }
                                }
                            }
                        }
                    }
                }
            }
        }.let { value -> if (value.startsWith("//")) "https:$value" else value }
        val images = buildList {
            draw?.items?.mapNotNull { it.src.takeIf(String::isNotBlank) }?.let(::addAll)
            article?.covers?.filter(String::isNotBlank)?.let(::addAll)
            opusSummary?.covers?.mapNotNull { it.src.takeIf(String::isNotBlank) }?.let(::addAll)
            paragraphImages.let(::addAll)
            if (isEmpty()) forwarded?.images?.let(::addAll)
        }.map { if (it.startsWith("//")) "https:$it" else it }.distinct().take(9)
        val extend = item.extend
        val id = extend?.dynIdStr.orEmpty().ifBlank { extend?.businessId.orEmpty() }
        if (id.isBlank()) return null
        val url = extend?.cardUrl.orEmpty().ifBlank {
            forwarded?.url.orEmpty().ifBlank {
                when {
                    bvid.isNotBlank() -> "https://www.bilibili.com/video/$bvid"
                    episodeId > 0L -> "https://www.bilibili.com/bangumi/play/ep$episodeId"
                    else -> "https://t.bilibili.com/$id"
                }
            }
        }
        val ownText = buildList {
            description.takeIf { it.isNotBlank() }?.let(::add)
            paragraphTexts.filter { it.isNotBlank() }.let(::addAll)
            opusSummaryText.takeIf { it.isNotBlank() }?.let(::add)
        }.distinct().joinToString("\n")
        val stats = statModule
        return BiliDynamicItem(
            id = id,
            type = item.cardType.toString(),
            ownerMid = author?.mid?.takeIf { it > 0L } ?: authorModule?.mid ?: forwarded?.ownerMid ?: 0L,
            ownerName = author?.name.orEmpty().ifBlank { forwarded?.ownerName ?: "B站用户" },
            ownerFace = author?.face.orEmpty().ifBlank { forwarded?.ownerFace.orEmpty() }
                .let { if (it.startsWith("//")) "https:$it" else it },
            timeLabel = authorModule?.ptimeLabelText.orEmpty().ifBlank { forwarded?.timeLabel.orEmpty() },
            text = ownText.ifBlank { forwarded?.text.orEmpty() },
            title = title,
            cover = cover,
            images = if (images.isEmpty() && cover.isNotBlank()) listOf(cover) else images,
            aid = aid,
            bvid = bvid,
            episodeId = episodeId,
            seasonId = seasonId,
            url = url,
            repostCount = stats?.repost ?: forwarded?.repostCount ?: 0L,
            replyCount = stats?.reply ?: forwarded?.replyCount ?: 0L,
            likeCount = stats?.like ?: forwarded?.likeCount ?: 0L,
            liked = stats?.likeInfo?.isLike ?: forwarded?.liked ?: false,
            pinned = authorModule?.isTop == true
        )
    }

    suspend fun userSpace(mid: Long): JSONObject {
        require(mid > 0L) { "UP主 UID 无效" }
        return execute(userApi.space(mid.toString()))
            .optJSONObject("data") ?: error("B站没有返回 UP 主资料")
    }

    suspend fun userVideos(
        mid: Long,
        cursorAid: Long = 0L,
        pageSize: Int = 30,
        order: String = "pubdate"
    ): List<BiliVideo> {
        require(mid > 0L) { "UP主 UID 无效" }
        require(order == "pubdate" || order == "click") { "投稿排序方式无效" }
        val root = execute(userApi.upperVideoList(
            vmid = mid.toString(),
            aid = cursorAid.takeIf { it > 0L }?.toString().orEmpty(),
            pageSize = pageSize.coerceIn(1, 50),
            order = order
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

    suspend fun commentsPage(aid: Long, page: Int, sort: Int, type: Int = 1): CommentPage {
        val root = execute(commentApi.mainList(aid.toString(), sort, type, page, 20))
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
                // up_action means the UP owner liked this comment; it does not
                // identify the comment author as the UP owner.
                isUp = false,
                level = member?.optJSONObject("level_info")?.optInt("current_level") ?: 0,
                likedByUp = item.optJSONObject("reply_control")?.optInt("up_action") == 1
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

    suspend fun replies(aid: Long, rootId: Long, page: Int = 1, type: Int = 1): CommentPage {
        val root = execute(commentApi.replyList(aid.toString(), rootId.toString(), type, page.coerceAtLeast(1), 20))
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
                // up_action means the UP owner liked this comment; it does not
                // identify the comment author as the UP owner.
                isUp = false,
                level = member?.optJSONObject("level_info")?.optInt("current_level") ?: 0,
                likedByUp = item.optJSONObject("reply_control")?.optInt("up_action") == 1
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

    suspend fun sendReply(aid: Long, rootId: Long, parentId: Long, message: String, type: Int = 1): String {
        require(message.isNotBlank()) { "回复内容不能为空" }
        val root = execute(commentApi.add(message.trim(), type, aid.toString(), rootId.toString(), parentId.toString()))
        return root.optString("message").ifBlank { "回复发送成功" }
    }
    suspend fun likeComment(aid: Long, rpid: Long, liked: Boolean, type: Int = 1): String {
        val root = execute(commentApi.action(type, aid.toString(), rpid.toString(), if (liked) 1 else 0))
        return root.optString("message").ifBlank { "评论点赞操作成功" }
    }

    suspend fun sendComment(aid: Long, message: String, type: Int = 1): String {
        val root = execute(commentApi.add(message, type, aid.toString()))
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
    
    suspend fun deleteComment(aid: Long, rpid: Long, type: Int = 1): String {
        require(aid > 0L && rpid > 0L) { "评论编号无效" }
        val root = execute(commentApi.del(type, aid.toString(), rpid.toString()))
        return root.optString("message").ifBlank { "评论已删除" }
    }
}
