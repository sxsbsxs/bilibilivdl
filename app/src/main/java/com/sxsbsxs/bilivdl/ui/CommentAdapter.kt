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

    fun updateLike(commentId: Long, liked: Boolean) {
        val index = data.indexOfFirst { it.id == commentId }
        if (index < 0) return

        val old = data[index]
        if (old.liked == liked) return

        data[index] = old.copy(
            liked = liked,
            likes = (old.likes + if (liked) 1 else -1).coerceAtLeast(0)
        )
        notifyItemChanged(index)
    }

    fun submit(items: List<VideoComment>, append: Boolean = false) {
        if (!append) data.clear()
        val known = data.mapTo(hashSetOf()) { it.id }
        data.addAll(items.filter { it.id !in known })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemCommentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return Holder(binding, onReplies, onLike)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(data[position])
    }

    class Holder(
        private val binding: ItemCommentBinding,
        private val onReplies: (VideoComment) -> Unit,
        private val onLike: (VideoComment) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(comment: VideoComment) {
            binding.commentUser.text = comment.user
            binding.commentText.text = comment.text
            binding.commentLikes.text =
                (if (comment.liked) "👍 已赞 " else "👍 ") + comment.likes
            binding.commentReplies.text =
                if (comment.replyCount > 0) "查看 ${comment.replyCount} 条回复" else "回复"

            binding.commentReplies.setOnClickListener { onReplies(comment) }
            binding.commentLikes.setOnClickListener { onLike(comment) }
        }
    }
}
