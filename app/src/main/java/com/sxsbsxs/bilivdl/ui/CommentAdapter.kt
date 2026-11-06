package com.sxsbsxs.bilivdl.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.VideoComment
import com.sxsbsxs.bilivdl.databinding.ItemCommentBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared video/dynamic comment row.
 *
 * DiffUtil keeps page appends cheap, while the pending-like set prevents rapid
 * repeated requests from one row. Both the video and dynamic comment screens
 * use this adapter so their basic interaction feedback stays consistent.
 */
class CommentAdapter(
    private val onReplies: (VideoComment) -> Unit,
    private val onLike: (VideoComment) -> Unit,
    private val onUser: (Long) -> Unit,
    private val onLongPress: (VideoComment) -> Unit = {}
) : RecyclerView.Adapter<CommentAdapter.Holder>() {

    private val data = mutableListOf<VideoComment>()
    private val pendingLikeIds = hashSetOf<Long>()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = data[position].id

    fun beginLike(commentId: Long): Boolean {
        if (!pendingLikeIds.add(commentId)) return false
        val index = data.indexOfFirst { it.id == commentId }
        if (index >= 0) notifyItemChanged(index)
        return true
    }

    fun finishLike(commentId: Long) {
        if (!pendingLikeIds.remove(commentId)) return
        val index = data.indexOfFirst { it.id == commentId }
        if (index >= 0) notifyItemChanged(index)
    }

    fun updateLike(commentId: Long, liked: Boolean) {
        val next = data.map { old ->
            if (old.id != commentId || old.liked == liked) old
            else old.copy(
                liked = liked,
                likes = (old.likes + if (liked) 1 else -1).coerceAtLeast(0)
            )
        }
        pendingLikeIds.remove(commentId)
        replaceData(next)
    }

    fun submit(items: List<VideoComment>, append: Boolean = false) {
        val next = if (append) data.toMutableList() else mutableListOf()
        val known = next.mapTo(hashSetOf()) { it.id }
        items.forEach { item ->
            if (item.id > 0L && known.add(item.id)) next.add(item)
        }
        replaceData(next)
    }

    private fun replaceData(next: List<VideoComment>) {
        val previous = data.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = previous.size
            override fun getNewListSize(): Int = next.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = previous[oldItemPosition]
                val new = next[newItemPosition]
                return old.id == new.id && old.mid == new.mid
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition] == next[newItemPosition]
        })
        data.clear()
        data.addAll(next)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemCommentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return Holder(binding, onReplies, onLike, onUser, onLongPress)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val comment = data[position]
        holder.bind(comment, pendingLikeIds.contains(comment.id))
    }

    class Holder(
        private val binding: ItemCommentBinding,
        private val onReplies: (VideoComment) -> Unit,
        private val onLike: (VideoComment) -> Unit,
        private val onUser: (Long) -> Unit,
        private val onLongPress: (VideoComment) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(comment: VideoComment, likePending: Boolean) {
            binding.commentUser.text = comment.user
            binding.commentBadge.visibility = if (comment.likedByUp) View.VISIBLE else View.GONE
            val face = comment.face.trim().let {
                if (it.startsWith("//")) "https:$it" else it
            }
            val image = face.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?.let {
                    GlideUrl(
                        it,
                        LazyHeaders.Builder()
                            .addHeader("Referer", "https://www.bilibili.com/")
                            .build()
                    )
                }
            Glide.with(binding.commentAvatar)
                .load(image)
                .placeholder(R.drawable.bg_comment_avatar)
                .error(R.drawable.bg_comment_avatar)
                .circleCrop()
                .into(binding.commentAvatar)

            binding.commentTime.text = formatTime(comment.ctime)
            binding.commentText.text = comment.text
            binding.commentLikes.text = when {
                likePending -> "处理中…"
                comment.liked -> "♥  " + formatCount(comment.likes)
                else -> "♡  " + formatCount(comment.likes)
            }
            binding.commentLikes.isEnabled = !likePending
            binding.commentLikes.alpha = if (likePending) 0.55f else 1f
            binding.commentReplies.text = if (comment.replyCount > 0) {
                "回复 · 查看 " + formatCount(comment.replyCount)
            } else {
                "回复"
            }

            binding.commentAvatar.setOnClickListener {
                if (comment.mid > 0L) onUser(comment.mid)
            }
            binding.commentUser.setOnClickListener {
                if (comment.mid > 0L) onUser(comment.mid)
            }
            binding.commentReplies.setOnClickListener { onReplies(comment) }
            binding.commentLikes.setOnClickListener {
                if (!likePending) onLike(comment)
            }
            binding.root.setOnLongClickListener {
                onLongPress(comment)
                true
            }
        }

        private fun formatTime(seconds: Long): String {
            if (seconds <= 0L) return ""
            val delta = (System.currentTimeMillis() / 1000L - seconds).coerceAtLeast(0L)
            return when {
                delta < 60L -> "刚刚"
                delta < 3600L -> "${delta / 60L}分钟前"
                delta < 86400L -> "${delta / 3600L}小时前"
                delta < 7L * 86400L -> "${delta / 86400L}天前"
                else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .format(Date(seconds * 1000L))
            }
        }

        private fun formatCount(value: Int): String = when {
            value >= 10000 -> String.format(Locale.getDefault(), "%.1f万", value / 10000.0)
            value >= 1000 -> String.format(Locale.getDefault(), "%.1千", value / 1000.0)
            else -> value.toString()
        }
    }
}
