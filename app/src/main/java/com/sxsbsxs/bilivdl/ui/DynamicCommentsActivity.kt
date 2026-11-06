package com.sxsbsxs.bilivdl.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliSession
import com.sxsbsxs.bilivdl.data.BilimiaoApiBridge
import com.sxsbsxs.bilivdl.data.CommentPage
import com.sxsbsxs.bilivdl.data.VideoComment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DynamicCommentsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_DYNAMIC_ID = "dynamic_comment_id"
        const val EXTRA_TITLE = "dynamic_comment_title"
        const val EXTRA_TEXT = "dynamic_comment_text"
        const val EXTRA_AUTHOR = "dynamic_comment_author"
        const val EXTRA_FACE = "dynamic_comment_face"
        const val EXTRA_COVER = "dynamic_comment_cover"
        private const val DYNAMIC_COMMENT_TYPE = 17
    }

    private var dynamicId = 0L
    private var accountMid = 0L
    private var page = 1
    private var sort = 2
    private var hasMore = true
    private var loading = false
    private var consecutiveEmptyPages = 0
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var countTitle: TextView
    private lateinit var input: EditText
    private lateinit var send: MaterialButton
    private lateinit var adapter: CommentAdapter
    private val ink = Color.rgb(37, 41, 48)
    private val muted = Color.rgb(132, 139, 150)
    private val backgroundColor = Color.rgb(247, 248, 250)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dynamicId = intent.getStringExtra(EXTRA_DYNAMIC_ID)?.toLongOrNull()
            ?: intent.getLongExtra(EXTRA_DYNAMIC_ID, 0L)
        if (dynamicId <= 0L) {
            finish()
            return
        }
        buildPage()
        loadAccount()
        loadComments(reset = true)
    }

    private fun buildPage() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(14), dp(4))
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(ink)
            setOnClickListener { finish() }
        }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(40), dp(48)))
        val title = TextView(this).apply {
            text = "动态详情"
            textSize = 18f
            setTextColor(ink)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        toolbar.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        val sortButton = TextView(this).apply {
            text = "按热度"
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                sort = if (sort == 2) 0 else 2
                text = if (sort == 2) "按热度" else "按时间"
                loadComments(reset = true)
            }
        }
        toolbar.addView(sortButton, LinearLayout.LayoutParams(-2, dp(42)))
        root.addView(toolbar)

        val summary = MaterialCardView(this).apply {
            radius = dp(13).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.WHITE)
            strokeWidth = dp(1)
            strokeColor = Color.rgb(235, 237, 240)
        }
        val summaryContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
        }
        val authorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "动态作者头像"
            setBackgroundResource(R.drawable.bg_comment_avatar)
            clipToOutline = true
        }
        authorRow.addView(avatar, LinearLayout.LayoutParams(dp(38), dp(38)))
        val author = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_AUTHOR).orEmpty()
            textSize = 13f
            setTextColor(ink)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(9), 0, 0, 0)
        }
        authorRow.addView(author, LinearLayout.LayoutParams(0, -2, 1f))
        summaryContent.addView(authorRow)
        val dynamicText = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val text = TextView(this).apply {
            this.text = dynamicText
            textSize = 13f
            setTextColor(ink)
            setLineSpacing(dp(2).toFloat(), 1f)
            visibility = if (dynamicText.isNotBlank()) View.VISIBLE else View.GONE
            setPadding(0, dp(9), 0, 0)
        }
        summaryContent.addView(text)
        val dynamicCover = intent.getStringExtra(EXTRA_COVER).orEmpty()
        if (dynamicCover.isNotBlank()) {
            val coverView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "动态内容封面"
            }
            summaryContent.addView(coverView, LinearLayout.LayoutParams(-1, dp(150)).apply {
                topMargin = dp(9)
            })
            Glide.with(this)
                .load(GlideUrl(dynamicCover, LazyHeaders.Builder()
                    .addHeader("Referer", "https://www.bilibili.com/").build()))
                .placeholder(R.drawable.bg_surface_card)
                .error(R.drawable.bg_surface_card)
                .centerCrop()
                .into(coverView)
        }
        val cardTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (cardTitle.isNotBlank()) {
            summaryContent.addView(TextView(this).apply {
                text = cardTitle
                textSize = 13f
                setTextColor(ink)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, dp(7), 0, 0)
            })
        }
        summary.addView(summaryContent)
        root.addView(summary, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(12), dp(3), dp(12), dp(7))
        })

        countTitle = TextView(this).apply {
            text = "全部评论"
            textSize = 15f
            setTextColor(ink)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(15), dp(10), dp(15), dp(8))
        }
        root.addView(countTitle)

        status = TextView(this).apply {
            text = "正在加载评论…"
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(status)

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DynamicCommentsActivity)
            isNestedScrollingEnabled = true
            clipToPadding = false
            setPadding(0, dp(3), 0, dp(8))
        }
        adapter = CommentAdapter(
            onReplies = { comment -> showReplies(comment, comment) },
            onLike = ::likeComment,
            onUser = ::openUser,
            onLongPress = ::handleLongPress
        )
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setBackgroundColor(Color.WHITE)
        }
        input = EditText(this).apply {
            hint = "说点什么…"
            maxLines = 3
            minLines = 1
            textSize = 13f
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = getDrawable(R.drawable.bg_empty_card)
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        send = MaterialButton(this).apply {
            text = "发送"
            isAllCaps = false
            textSize = 12f
            minHeight = dp(40)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { sendComment() }
        }
        composer.addView(send, LinearLayout.LayoutParams(dp(66), dp(40)).apply {
            marginStart = dp(8)
        })
        root.addView(composer)
        setContentView(root)

        val face = intent.getStringExtra(EXTRA_FACE).orEmpty()
        if (face.isNotBlank()) Glide.with(this)
            .load(GlideUrl(face, LazyHeaders.Builder()
                .addHeader("Referer", "https://www.bilibili.com/").build()))
            .placeholder(R.drawable.bg_comment_avatar)
            .error(R.drawable.bg_comment_avatar)
            .circleCrop().into(avatar)

        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (dy > 0 && hasMore && !loading &&
                    manager.findLastVisibleItemPosition() >= manager.itemCount - 4) {
                    loadComments(reset = false)
                }
            }
        })
    }

    private fun loadAccount() {
        lifecycleScope.launch {
            accountMid = runCatching {
                withContext(Dispatchers.IO) {
                    BiliSession.profile(applicationContext).optLong("mid")
                }
            }.getOrDefault(0L)
        }
    }

    private fun loadComments(reset: Boolean) {
        if (loading || (!hasMore && !reset)) return
        if (reset) {
            page = 1
            hasMore = true
            consecutiveEmptyPages = 0
            adapter.submit(emptyList())
        }
        loading = true
        status.visibility = View.VISIBLE
        status.text = if (reset) "正在加载评论…" else "正在加载更多…"
        lifecycleScope.launch {
            var continueAfterEmptyPage = false
            try {
                val requestedPage = page
                val result = withContext(Dispatchers.IO) {
                    BilimiaoApiBridge.commentsPage(
                        aid = dynamicId,
                        page = requestedPage,
                        sort = sort,
                        type = DYNAMIC_COMMENT_TYPE
                    )
                }
                adapter.submit(result.comments, append = !reset)
                page = requestedPage + 1
                consecutiveEmptyPages = if (result.comments.isEmpty() && result.hasMore) {
                    consecutiveEmptyPages + 1
                } else {
                    0
                }
                hasMore = result.hasMore && consecutiveEmptyPages < 3
                continueAfterEmptyPage = result.comments.isEmpty() && hasMore
                countTitle.text = "全部评论 " + formatCount(result.total)
                status.setOnClickListener(null)
                status.text = when {
                    result.comments.isNotEmpty() && hasMore -> "上滑加载更多"
                    result.comments.isNotEmpty() -> "没有更多评论了"
                    reset && result.total <= 0 -> "暂时没有评论"
                    continueAfterEmptyPage -> "正在跳过空页…"
                    else -> "暂时没有更多可展示的评论"
                }
                status.visibility = View.VISIBLE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.visibility = View.VISIBLE
                status.text = error.message ?: "评论加载失败，点击重试"
                status.setOnClickListener { loadComments(reset = true) }
            } finally {
                loading = false
                if (continueAfterEmptyPage) {
                    list.post {
                        if (!loading && hasMore) loadComments(reset = false)
                    }
                }
            }
        }
    }

    private fun likeComment(comment: VideoComment) {
        if (!adapter.beginLike(comment.id)) return
        lifecycleScope.launch {
            try {
                if (BiliSession.cookie(this@DynamicCommentsActivity).isBlank()) {
                    error("评论点赞需要先扫码登录")
                }
                withContext(Dispatchers.IO) {
                    BilimiaoApiBridge.likeComment(
                        dynamicId, comment.id, !comment.liked, DYNAMIC_COMMENT_TYPE
                    )
                }
                adapter.updateLike(comment.id, !comment.liked)
                status.visibility = View.VISIBLE
                status.text = "操作成功"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.visibility = View.VISIBLE
                status.text = error.message ?: "评论点赞失败"
            } finally {
                adapter.finishLike(comment.id)
            }
        }
    }

    private fun sendComment() {
        val message = input.text?.toString()?.trim().orEmpty()
        if (message.isBlank()) {
            input.error = "请输入评论内容"
            return
        }
        send.isEnabled = false
        lifecycleScope.launch {
            try {
                if (BiliSession.cookie(this@DynamicCommentsActivity).isBlank()) {
                    error("发表评论需要先扫码登录")
                }
                withContext(Dispatchers.IO) {
                    BilimiaoApiBridge.sendComment(dynamicId, message, DYNAMIC_COMMENT_TYPE)
                }
                input.setText("")
                loadComments(reset = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.visibility = View.VISIBLE
                status.text = error.message ?: "评论发送失败"
            } finally {
                send.isEnabled = true
            }
        }
    }

    private fun showReplies(rootComment: VideoComment, replyTo: VideoComment) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val statusView = TextView(this).apply {
            text = "正在加载回复…"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(5), 0, dp(7))
        }
        val replyList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DynamicCommentsActivity)
            isNestedScrollingEnabled = true
        }
        val replyInput = EditText(this).apply {
            hint = if (replyTo.id == rootComment.id) "回复这条评论…" else "回复 ${replyTo.user}…"
            maxLines = 3
            minLines = 1
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = getDrawable(R.drawable.bg_empty_card)
        }
        val sendReply = MaterialButton(this).apply {
            text = "发送回复"
            isAllCaps = false
            textSize = 12f
        }
        content.addView(statusView)
        content.addView(replyList, LinearLayout.LayoutParams(-1, 0, 1f))
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        composer.addView(replyInput, LinearLayout.LayoutParams(0, -2, 1f))
        composer.addView(sendReply, LinearLayout.LayoutParams(-2, dp(44)).apply {
            marginStart = dp(6)
        })
        content.addView(composer)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("全部回复 · ${rootComment.replyCount}")
            .setView(content)
            .setNegativeButton("关闭", null)
            .create()
        var activeReplyTarget = replyTo

        lateinit var replyAdapter: CommentAdapter
        replyAdapter = CommentAdapter(
            onReplies = { nested ->
                activeReplyTarget = nested
                replyInput.hint = if (nested.id == rootComment.id) "回复这条评论…" else "回复 ${nested.user}…"
                statusView.text = "正在回复 ${nested.user}"
            },
            onLike = { comment ->
                if (replyAdapter.beginLike(comment.id)) {
                    lifecycleScope.launch {
                        try {
                            if (BiliSession.cookie(this@DynamicCommentsActivity).isBlank()) {
                                error("评论点赞需要先扫码登录")
                            }
                            withContext(Dispatchers.IO) {
                                BilimiaoApiBridge.likeComment(
                                    dynamicId, comment.id, !comment.liked, DYNAMIC_COMMENT_TYPE
                                )
                            }
                            replyAdapter.updateLike(comment.id, !comment.liked)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            statusView.text = error.message ?: "回复点赞失败"
                        } finally {
                            replyAdapter.finishLike(comment.id)
                        }
                    }
                }
            },
            onUser = ::openUser,
            onLongPress = ::handleLongPress
        )
        replyList.adapter = replyAdapter
        var replyPage = 1
        var replyHasMore = true
        var replyLoading = false
        var replyEmptyPages = 0

        fun loadReplyPage(reset: Boolean) {
            if (replyLoading || (!replyHasMore && !reset)) return
            if (reset) {
                replyPage = 1
                replyEmptyPages = 0
                replyAdapter.submit(listOf(rootComment))
            }
            replyLoading = true
            val requestedPage = replyPage
            statusView.text = if (reset) "正在加载回复…" else "正在加载更多回复…"
            lifecycleScope.launch {
                var continueAfterEmptyPage = false
                try {
                    val result = withContext(Dispatchers.IO) {
                        BilimiaoApiBridge.replies(
                            dynamicId, rootComment.id, requestedPage, DYNAMIC_COMMENT_TYPE
                        )
                    }
                    if (reset) replyAdapter.submit(listOf(rootComment) + result.comments)
                    else replyAdapter.submit(result.comments, append = true)
                    replyPage = requestedPage + 1
                    replyEmptyPages = if (result.comments.isEmpty() && result.hasMore) {
                        replyEmptyPages + 1
                    } else {
                        0
                    }
                    replyHasMore = result.hasMore && replyEmptyPages < 3
                    continueAfterEmptyPage = result.comments.isEmpty() && replyHasMore
                    statusView.setOnClickListener(null)
                    statusView.text = when {
                        result.comments.isNotEmpty() && replyHasMore -> "上滑加载更多回复"
                        result.comments.isNotEmpty() -> "没有更多回复了"
                        reset && result.total <= 0 -> "暂时没有回复"
                        continueAfterEmptyPage -> "正在跳过空页…"
                        else -> "没有更多回复了"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    statusView.text = error.message ?: "加载回复失败，点击重试"
                    statusView.setOnClickListener { loadReplyPage(reset = true) }
                } finally {
                    replyLoading = false
                    if (continueAfterEmptyPage) {
                        replyList.post {
                            if (!replyLoading && replyHasMore) loadReplyPage(reset = false)
                        }
                    }
                }
            }
        }

        replyList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (dy > 0 && replyHasMore && !replyLoading &&
                    manager.findLastVisibleItemPosition() >= manager.itemCount - 3) {
                    loadReplyPage(false)
                }
            }
        })
        sendReply.setOnClickListener {
            val message = replyInput.text?.toString()?.trim().orEmpty()
            if (message.isBlank()) {
                replyInput.error = "请输入回复内容"
                return@setOnClickListener
            }
            sendReply.isEnabled = false
            lifecycleScope.launch {
                try {
                    if (BiliSession.cookie(this@DynamicCommentsActivity).isBlank()) {
                        error("回复评论需要先扫码登录")
                    }
                    withContext(Dispatchers.IO) {
                        BilimiaoApiBridge.sendReply(
                            dynamicId, rootComment.id, activeReplyTarget.id, message, DYNAMIC_COMMENT_TYPE
                        )
                    }
                    replyInput.setText("")
                    loadReplyPage(reset = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    statusView.text = error.message ?: "发送回复失败"
                } finally {
                    sendReply.isEnabled = true
                }
            }
        }
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.72f).toInt()
        )
        loadReplyPage(reset = true)
    }

    private fun handleLongPress(comment: VideoComment) {
        val canDelete = accountMid > 0L && comment.mid == accountMid
        val actions = if (canDelete) arrayOf("复制评论", "删除评论") else arrayOf("复制评论")
        MaterialAlertDialogBuilder(this)
            .setItems(actions) { _, which ->
                val deleteIndex = if (canDelete) 1 else -1
                if (which == deleteIndex) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("删除自己的评论")
                        .setMessage("确定删除这条评论吗？此操作无法撤销。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除") { _, _ ->
                            lifecycleScope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        BilimiaoApiBridge.deleteComment(
                                            dynamicId, comment.id, DYNAMIC_COMMENT_TYPE
                                        )
                                    }
                                    status.text = "评论已删除"
                                    loadComments(reset = true)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    status.text = error.message ?: "删除评论失败"
                                }
                            }
                        }.show()
                } else {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("B站评论", comment.text))
                    android.widget.Toast.makeText(this, "已复制评论", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openUser(mid: Long) {
        if (mid > 0L) startActivity(Intent(this, UserSpaceActivity::class.java)
            .putExtra(UserSpaceActivity.EXTRA_MID, mid))
    }

    private fun formatCount(value: Int): String =
        if (value >= 10000) String.format("%.1f万", value / 10000.0) else value.toString()

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
