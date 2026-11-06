package com.sxsbsxs.bilivdl.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliVideo

class VideoAdapter(
    private val onClick: (BiliVideo) -> Unit,
    private val gridMode: Boolean = false
) : RecyclerView.Adapter<VideoAdapter.Holder>() {
    private val items = mutableListOf<BiliVideo>()

    fun submitList(list: List<BiliVideo>) {
        val seen = hashSetOf<String>()
        val next = list.filter { seen.add(stableKey(it)) }
        if (items == next) return
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int): Boolean {
                val old = items[oldPosition]
                val new = next[newPosition]
                return when {
                    old.bvid.isNotBlank() && new.bvid.isNotBlank() -> old.bvid == new.bvid
                    old.aid > 0L && new.aid > 0L -> old.aid == new.aid
                    else -> old.title == new.title && old.author == new.author
                }
            }
            override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                items[oldPosition] == next[newPosition]
        })
        items.clear()
        items.addAll(next)
        diff.dispatchUpdatesTo(this)
    }

    fun appendList(list: List<BiliVideo>) {
        if (list.isEmpty()) return
        val keys = items.mapTo(hashSetOf()) { stableKey(it) }
        val additions = list.filter { keys.add(stableKey(it)) }
        if (additions.isEmpty()) return
        val start = items.size
        items.addAll(additions)
        notifyItemRangeInserted(start, additions.size)
    }

    private fun stableKey(video: BiliVideo): String = when {
        video.bvid.isNotBlank() -> "bv:${video.bvid}"
        video.aid > 0L -> "av:${video.aid}"
        else -> "title:${video.author}:${video.title}"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = if (gridMode) R.layout.item_video_grid else R.layout.item_video
        return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun onViewRecycled(holder: Holder) {
        Glide.with(holder.itemView).clear(holder.videoCover)
        Glide.with(holder.itemView).clear(holder.videoAuthorAvatar)
        super.onViewRecycled(holder)
    }

    private fun formatCount(value: Long): String = when {
        value >= 100_000_000L -> String.format(java.util.Locale.getDefault(), "%.1f亿", value / 100_000_000.0)
        value >= 10_000L -> String.format(java.util.Locale.getDefault(), "%.1f万", value / 10_000.0)
        else -> value.toString()
    }

    inner class Holder(root: View) : RecyclerView.ViewHolder(root) {
        val videoCover = root.findViewById<ImageView>(R.id.video_cover)
        val videoAuthorAvatar = root.findViewById<ImageView>(R.id.video_author_avatar)
        private val coverFrame = root.findViewById<FrameLayout>(R.id.video_cover_frame)
        private val durationBadge = root.findViewById<TextView>(R.id.video_duration_badge)
        private val title = root.findViewById<TextView>(R.id.video_title)
        private val author = root.findViewById<TextView>(R.id.video_author)
        private val meta = root.findViewById<TextView>(R.id.video_meta)
        private val extraMeta = root.findViewById<TextView>(R.id.video_extra_meta)

        fun bind(v: BiliVideo) {
            coverFrame.post {
                val width = coverFrame.width
                if (width > 0) {
                    val height = (width * 9f / 16f).toInt()
                    val params = coverFrame.layoutParams
                    if (params.height != height) {
                        params.height = height
                        coverFrame.layoutParams = params
                    }
                }
            }
            title.text = v.title
            author.text = v.author
            durationBadge.text = v.duration
            meta.text = buildString {
                append("播放 ")
                append(v.play)
                v.danmaku?.takeIf { it > 0L }?.let {
                    append("  ·  弹幕 ")
                    append(formatCount(it))
                }
            }
            extraMeta.visibility = View.GONE

            val headers = LazyHeaders.Builder()
                .addHeader("Referer", "https://www.bilibili.com/")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                .build()
            val face = v.authorFace.trim().let { if (it.startsWith("//")) "https:$it" else it }
            if (face.startsWith("https://") || face.startsWith("http://")) {
                Glide.with(videoAuthorAvatar).load(GlideUrl(face, headers))
                    .placeholder(R.drawable.bg_comment_avatar).error(R.drawable.bg_comment_avatar)
                    .circleCrop().into(videoAuthorAvatar)
            } else {
                Glide.with(videoAuthorAvatar).clear(videoAuthorAvatar)
                videoAuthorAvatar.setImageResource(R.drawable.bg_comment_avatar)
            }

            val imageUrl = v.cover.trim().let {
                when {
                    it.startsWith("//") -> "https:$it"
                    it.startsWith("http://") -> "https://" + it.removePrefix("http://")
                    else -> it
                }
            }
            durationBadge.visibility = if (v.duration.isBlank()) View.GONE else View.VISIBLE
            if (imageUrl.startsWith("https://") || imageUrl.startsWith("http://")) {
                Glide.with(videoCover).load(GlideUrl(imageUrl, headers))
                    .placeholder(R.drawable.bg_empty_card).error(R.drawable.bg_empty_card)
                    .diskCacheStrategy(DiskCacheStrategy.ALL).override(480, 270).centerCrop()
                    .into(videoCover)
            } else {
                Glide.with(videoCover).clear(videoCover)
                videoCover.setImageResource(R.drawable.bg_empty_card)
            }
            itemView.setOnClickListener { onClick(v) }
        }
    }
}
