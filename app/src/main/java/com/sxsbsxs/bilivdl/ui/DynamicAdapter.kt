package com.sxsbsxs.bilivdl.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliDynamicItem
import com.sxsbsxs.bilivdl.databinding.ItemDynamicBinding

class DynamicAdapter(
    private val onOpen: (BiliDynamicItem) -> Unit,
    private val onUser: (Long) -> Unit,
    private val onLike: (BiliDynamicItem) -> Unit,
    private val onReply: (BiliDynamicItem) -> Unit,
    private val onShare: (BiliDynamicItem) -> Unit
) : RecyclerView.Adapter<DynamicAdapter.Holder>() {

    private val items = mutableListOf<BiliDynamicItem>()

    fun submit(items: List<BiliDynamicItem>, append: Boolean = false) {
        if (!append) this.items.clear()
        val known = this.items.mapTo(hashSetOf()) { it.id }
        this.items.addAll(items.filter { it.id.isNotBlank() && known.add(it.id) })
        notifyDataSetChanged()
    }

    fun updateLike(id: String, liked: Boolean) {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        val old = items[index]
        if (old.liked == liked) return
        items[index] = old.copy(
            liked = liked,
            likeCount = (old.likeCount + if (liked) 1L else -1L).coerceAtLeast(0L)
        )
        notifyItemChanged(index)
    }

    fun count(): Int = items.size

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDynamicBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding, onOpen, onUser, onLike, onReply, onShare)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    class Holder(
        private val b: ItemDynamicBinding,
        private val onOpen: (BiliDynamicItem) -> Unit,
        private val onUser: (Long) -> Unit,
        private val onLike: (BiliDynamicItem) -> Unit,
        private val onReply: (BiliDynamicItem) -> Unit,
        private val onShare: (BiliDynamicItem) -> Unit
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(item: BiliDynamicItem) {
            b.dynamicAuthor.text = item.ownerName
            b.dynamicTime.text = item.timeLabel
            b.dynamicText.text = item.text
            b.dynamicText.visibility = if (item.text.isBlank()) View.GONE else View.VISIBLE
            b.dynamicPinned.visibility = if (item.pinned) View.VISIBLE else View.GONE

            val avatar = item.ownerFace
            if (avatar.isNotBlank()) {
                Glide.with(b.dynamicAvatar)
                    .load(GlideUrl(avatar, LazyHeaders.Builder()
                        .addHeader("Referer", "https://www.bilibili.com/").build()))
                    .placeholder(R.drawable.bg_comment_avatar)
                    .error(R.drawable.bg_comment_avatar)
                    .circleCrop()
                    .into(b.dynamicAvatar)
            } else {
                b.dynamicAvatar.setImageResource(R.drawable.bg_comment_avatar)
            }
            b.dynamicAvatar.setOnClickListener { onUser(item.ownerMid) }
            b.dynamicAuthor.setOnClickListener { onUser(item.ownerMid) }

            // 多图动态使用三列宫格；单图视频/番剧仍用紧凑封面卡。
            val imageUrls = item.images.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(9)
            val showGallery = imageUrls.size > 1
            b.dynamicGalleryTitle.visibility =
                if (showGallery && item.title.isNotBlank()) View.VISIBLE else View.GONE
            b.dynamicGalleryTitle.text = item.title
            b.dynamicImageGrid.visibility = if (showGallery) View.VISIBLE else View.GONE
            if (showGallery) {
                for (index in 0 until b.dynamicImageGrid.childCount) {
                    (b.dynamicImageGrid.getChildAt(index) as? android.widget.ImageView)?.let { old ->
                        Glide.with(old).clear(old)
                    }
                }
                b.dynamicImageGrid.removeAllViews()
                val columns = if (imageUrls.size == 2 || imageUrls.size == 4) 2 else 3
                b.dynamicImageGrid.columnCount = columns
                val density = b.root.resources.displayMetrics.density
                val cellSize = (104f * density).toInt()
                fun dp(value: Int) = (value * density).toInt()
                imageUrls.forEachIndexed { index, imageUrl ->
                    val imageView = android.widget.ImageView(b.root.context).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        contentDescription = "动态图片"
                        clipToOutline = true
                        background = b.root.context.getDrawable(R.drawable.bg_surface_card)
                        layoutParams = android.widget.GridLayout.LayoutParams(
                            android.widget.GridLayout.spec(index / columns),
                            android.widget.GridLayout.spec(index % columns, 1f)
                        ).apply {
                            width = 0
                            height = cellSize
                            setMargins(dp(2), dp(2), dp(2), dp(2))
                        }
                        setOnClickListener { onOpen(item) }
                    }
                    b.dynamicImageGrid.addView(imageView)
                    Glide.with(imageView)
                        .load(GlideUrl(imageUrl, LazyHeaders.Builder()
                            .addHeader("Referer", "https://www.bilibili.com/").build()))
                        .placeholder(R.drawable.bg_surface_card)
                        .error(R.drawable.bg_surface_card)
                        .centerCrop()
                        .into(imageView)
                }
                // Use the measured card width rather than a fixed 104dp height: B站图文宫格
                // should remain square on narrow phones, tablets and split-screen layouts.
                b.dynamicImageGrid.post {
                    if (b.dynamicImageGrid.childCount != imageUrls.size) return@post
                    val availableWidth = b.dynamicImageGrid.width -
                        b.dynamicImageGrid.paddingLeft - b.dynamicImageGrid.paddingRight
                    val cellHeight = ((availableWidth - dp(4 * columns)) / columns).coerceAtLeast(dp(64))
                    for (childIndex in 0 until b.dynamicImageGrid.childCount) {
                        val child = b.dynamicImageGrid.getChildAt(childIndex)
                        val params = child.layoutParams as? android.widget.GridLayout.LayoutParams ?: continue
                        if (params.height != cellHeight) {
                            params.height = cellHeight
                            child.layoutParams = params
                        }
                    }
                }
            } else {
                for (index in 0 until b.dynamicImageGrid.childCount) {
                    (b.dynamicImageGrid.getChildAt(index) as? android.widget.ImageView)?.let { old ->
                        Glide.with(old).clear(old)
                    }
                }
                b.dynamicImageGrid.removeAllViews()
            }

            val image = item.cover.ifBlank { imageUrls.firstOrNull().orEmpty() }
            val showMedia = !showGallery && (image.isNotBlank() || item.title.isNotBlank())
            b.dynamicMediaCard.visibility = if (showMedia) View.VISIBLE else View.GONE
            if (showMedia) {
                if (image.isNotBlank()) {
                    Glide.with(b.dynamicMediaCover)
                        .load(GlideUrl(image, LazyHeaders.Builder()
                            .addHeader("Referer", "https://www.bilibili.com/").build()))
                        .placeholder(R.drawable.bg_surface_card)
                        .error(R.drawable.bg_surface_card)
                        .centerCrop()
                        .into(b.dynamicMediaCover)
                } else {
                    Glide.with(b.dynamicMediaCover).clear(b.dynamicMediaCover)
                    b.dynamicMediaCover.setImageResource(R.drawable.bg_surface_card)
                }
                b.dynamicMediaTitle.text = item.title.ifBlank {
                    when {
                        item.episodeId > 0L -> "番剧动态"
                        else -> "查看动态内容"
                    }
                }
                b.dynamicMediaSubtitle.text = when {
                    item.episodeId > 0L -> "番剧 / 影视"
                    item.aid > 0L || item.bvid.isNotBlank() ->
                        listOf("视频", item.replyCount.takeIf { it > 0L }?.let { "${it} 条评论" }.orEmpty())
                            .filter { it.isNotBlank() }.joinToString(" · ")
                    imageUrls.isNotEmpty() -> "${imageUrls.size} 张图片"
                    else -> item.type.removePrefix("DYNAMIC_TYPE_").lowercase().replace('_', ' ')
                }
                b.dynamicMediaCard.setOnClickListener { onOpen(item) }
            } else {
                Glide.with(b.dynamicMediaCover).clear(b.dynamicMediaCover)
            }

            b.dynamicRepost.text = "转发 " + countLabel(item.repostCount)
            b.dynamicReply.text = "评论 " + countLabel(item.replyCount)
            b.dynamicLike.text = (if (item.liked) "已赞 " else "赞 ") + countLabel(item.likeCount)
            b.dynamicLike.setTextColor(
                b.root.context.getColor(if (item.liked) R.color.brand_dark else R.color.text_secondary)
            )
            b.dynamicRepost.setOnClickListener { onShare(item) }
            b.dynamicReply.setOnClickListener { onReply(item) }
            b.dynamicLike.setOnClickListener { onLike(item) }
        }

        private fun countLabel(value: Long): String = when {
            value >= 100_000_000L -> String.format("%.1f亿", value / 100_000_000.0)
            value >= 10_000L -> String.format("%.1f万", value / 10_000.0)
            value > 0L -> value.toString()
            else -> ""
        }
    }
}
