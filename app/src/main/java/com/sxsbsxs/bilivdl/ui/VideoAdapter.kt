package com.sxsbsxs.bilivdl.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliVideo
import com.sxsbsxs.bilivdl.databinding.ItemVideoBinding

class VideoAdapter(private val onClick: (BiliVideo) -> Unit) : RecyclerView.Adapter<VideoAdapter.Holder>() {
    private val items = mutableListOf<BiliVideo>()
    fun submitList(list: List<BiliVideo>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun onViewRecycled(holder: Holder) {
        Glide.with(holder.itemView).clear(holder.binding.videoCover)
        super.onViewRecycled(holder)
    }
    private fun formatCount(value: Long): String = when {
        value >= 100_000_000L -> String.format(java.util.Locale.getDefault(), "%.1f亿", value / 100_000_000.0)
        value >= 10_000L -> String.format(java.util.Locale.getDefault(), "%.1f万", value / 10_000.0)
        else -> value.toString()
    }
    inner class Holder(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(v: BiliVideo) {
            binding.videoTitle.text = v.title
            binding.videoAuthor.text = "UP主 · " + v.author
            binding.videoMeta.text = listOf(
                "播放 " + v.play,
                if (v.danmaku != null) "弹幕 " + formatCount(v.danmaku) else "",
                v.duration.takeIf { it.isNotBlank() } ?: ""
            ).filter { it.isNotBlank() }.joinToString("  ·  ")
            binding.videoExtraMeta.text = listOf(
                v.likes?.let { "点赞 " + formatCount(it) }.orEmpty(),
                v.favorites?.let { "收藏 " + formatCount(it) }.orEmpty(),
                v.coins?.let { "投币 " + formatCount(it) }.orEmpty(),
                v.shares?.let { "分享 " + formatCount(it) }.orEmpty(),
                v.comments?.let { "评论 " + formatCount(it) }.orEmpty()
            ).filter { it.isNotBlank() }.joinToString("  ·  ")
            binding.videoExtraMeta.visibility = if (binding.videoExtraMeta.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
            val imageUrl = v.cover.trim().let {
                when {
                    it.startsWith("//") -> "https:$it"
                    it.startsWith("http://") -> "https://" + it.removePrefix("http://")
                    else -> it
                }
            }
            val headers = LazyHeaders.Builder()
                .addHeader("Referer", "https://www.bilibili.com/")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                .build()
            binding.videoCover.setImageResource(R.drawable.bg_empty_card)
            Glide.with(binding.videoCover)
                .load(GlideUrl(imageUrl, headers))
                .placeholder(R.drawable.bg_empty_card)
                .error(R.drawable.bg_empty_card)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(480, 320)
                .centerCrop()
                .into(binding.videoCover)
            binding.root.setOnClickListener { onClick(v) }
        }
    }
}