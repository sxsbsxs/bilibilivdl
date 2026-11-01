package com.sxsbsxs.bilivdl.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.sxsbsxs.bilivdl.data.BiliVideo
import com.sxsbsxs.bilivdl.databinding.ItemVideoBinding

class VideoAdapter(private val onClick: (BiliVideo) -> Unit) :
    RecyclerView.Adapter<VideoAdapter.VideoHolder>() {
    private val items = mutableListOf<BiliVideo>()

    fun submitList(videos: List<BiliVideo>) {
        items.clear()
        items.addAll(videos)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoHolder {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VideoHolder(binding)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VideoHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class VideoHolder(private val binding: ItemVideoBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(video: BiliVideo) {
            binding.videoTitle.text = video.title
            binding.videoAuthor.text = "UP 主 · ${video.author}"
            binding.videoMeta.text = "播放 ${video.play}  ·  ${video.duration}"
            Glide.with(binding.videoCover).load(video.cover).centerCrop().into(binding.videoCover)
            binding.root.setOnClickListener { onClick(video) }
        }
    }
}