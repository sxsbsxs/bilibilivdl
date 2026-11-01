package com.sxsbsxs.bilivdl.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sxsbsxs.bilivdl.data.VideoComment
import com.sxsbsxs.bilivdl.databinding.ItemCommentBinding

class CommentAdapter(
    private val onReplies: (VideoComment) -> Unit,
    private val onLike: (VideoComment) -> Unit
) : RecyclerView.Adapter<CommentAdapter.Holder>() {
    private val data = mutableListOf<VideoComment>()
    fun updateLike(commentId: Long, liked: Boolean) {\n        val index = data.indexOfFirst { it.id == commentId }\n        if (index >= 0) { data[index] = data[index].copy(liked = liked, likes = (data[index].likes + if (liked) 1 else -1).coerceAtLeast(0)); notifyItemChanged(index) }\n    }\n    fun submit(items: List<VideoComment>, append: Boolean = false) {
        if (!append) data.clear()
        val known = data.mapTo(hashSetOf()) { it.id }
        data.addAll(items.filter { it.id !in known })
        notifyDataSetChanged()
    }
    override fun onCreateViewHolder(parent: ViewGroup, type: Int) =
        Holder(ItemCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false), onReplies, onLike)
    override fun getItemCount() = data.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(data[position])

    class Holder(
        private val b: ItemCommentBinding,
        private val onReplies: (VideoComment) -> Unit,
        private val onLike: (VideoComment) -> Unit
    ) : RecyclerView.ViewHolder(b.root) {
        fun bind(v: VideoComment) {
            b.commentUser.text = v.user
            b.commentText.text = v.text
            b.commentLikes.text = (if (v.liked) "👍 已赞 " else "👍 ") + v.likes
            b.commentReplies.text = if (v.replyCount > 0) "查看 ${v.replyCount} 条回复" else "回复"
            b.commentReplies.setOnClickListener { onReplies(v) }
            b.commentLikes.setOnClickListener { onLike(v) }
        }
    }
}
