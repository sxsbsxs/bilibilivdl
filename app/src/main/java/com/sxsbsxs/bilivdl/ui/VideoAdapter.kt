package com.sxsbsxs.bilivdl.ui
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliVideo
import com.sxsbsxs.bilivdl.databinding.ItemVideoBinding
class VideoAdapter(private val onClick:(BiliVideo)->Unit):RecyclerView.Adapter<VideoAdapter.Holder>(){
 private val items=mutableListOf<BiliVideo>()
 fun submitList(list:List<BiliVideo>){items.clear();items.addAll(list);notifyDataSetChanged()}
 override fun onCreateViewHolder(parent:ViewGroup,viewType:Int)=Holder(ItemVideoBinding.inflate(LayoutInflater.from(parent.context),parent,false))
 override fun getItemCount()=items.size
 override fun onBindViewHolder(holder:Holder,position:Int)=holder.bind(items[position])
 override fun onViewRecycled(holder:Holder){Glide.with(holder.itemView).clear(holder.binding.videoCover);super.onViewRecycled(holder)}
 inner class Holder(val binding:ItemVideoBinding):RecyclerView.ViewHolder(binding.root){
  fun bind(v:BiliVideo){
   binding.videoTitle.text=v.title;binding.videoAuthor.text="UP主 · "+v.author;binding.videoMeta.text="播放 "+v.play+" · "+v.duration
   Glide.with(binding.videoCover).load(v.cover).placeholder(R.drawable.bg_empty_card).error(R.drawable.bg_empty_card).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).dontAnimate().centerCrop().into(binding.videoCover)
   binding.root.setOnClickListener{onClick(v)}
  }
 }
}