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
    inner class Holder(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(v: BiliVideo) {
            binding.videoTitle.text = v.title
            binding.videoAuthor.text = "UP主 · " + v.author
            binding.videoMeta.text = "播放 " + v.play + " · " + v.duration
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