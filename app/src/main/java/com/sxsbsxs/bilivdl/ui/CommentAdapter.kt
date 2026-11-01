package com.sxsbsxs.bilivdl.ui
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sxsbsxs.bilivdl.data.VideoComment
import com.sxsbsxs.bilivdl.databinding.ItemCommentBinding
class CommentAdapter:RecyclerView.Adapter<CommentAdapter.Holder>(){
 private val data=mutableListOf<VideoComment>()
 fun submit(v:List<VideoComment>){data.clear();data.addAll(v);notifyDataSetChanged()}
 override fun onCreateViewHolder(p:ViewGroup,t:Int)=Holder(ItemCommentBinding.inflate(LayoutInflater.from(p.context),p,false))
 override fun getItemCount()=data.size
 override fun onBindViewHolder(h:Holder,p:Int)=h.bind(data[p])
 class Holder(private val b:ItemCommentBinding):RecyclerView.ViewHolder(b.root){fun bind(v:VideoComment){b.commentUser.text=v.user;b.commentText.text=v.text;b.commentLikes.text="👍 "+v.likes}}
}