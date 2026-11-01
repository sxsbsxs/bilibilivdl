package com.sxsbsxs.bilivdl.ui
import android.content.Context
import android.content.Intent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sxsbsxs.bilivdl.data.BiliApi
import com.sxsbsxs.bilivdl.data.BiliVideo
import com.sxsbsxs.bilivdl.data.BiliVideoLookup
import com.sxsbsxs.bilivdl.databinding.FragmentSearchBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
class SearchController(private val fragment:DiscoverFragment,private val binding:FragmentSearchBinding){
 private lateinit var adapter:VideoAdapter
 fun bind(){
  adapter=VideoAdapter(::openPlayer);binding.searchResults.layoutManager=LinearLayoutManager(fragment.requireContext());binding.searchResults.adapter=adapter
  binding.searchButton.setOnClickListener{search()}
  binding.searchInput.setOnEditorActionListener{_,a,_->if(a==EditorInfo.IME_ACTION_SEARCH){search();true}else false}
 }
 private fun search(){
  val key=binding.searchInput.text?.toString()?.trim().orEmpty()
  if(key.isBlank()){binding.searchInputLayout.error="请输入搜索内容";return}
  binding.searchInputLayout.error=null
  (fragment.requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(binding.searchInput.windowToken,0)
  binding.searchProgress.visibility=android.view.View.VISIBLE;binding.searchButton.isEnabled=false;binding.searchStatus.text="正在搜索…"
  fragment.viewLifecycleOwner.lifecycleScope.launch{
   try{val result=withContext(Dispatchers.IO){if(BiliApi.resolveVideoId(key)!=null)listOf(BiliVideoLookup.get(key))else BiliApi.searchVideos(key)}
    adapter.submitList(result);binding.searchStatus.text=if(result.isEmpty())"没有找到相关视频" else "找到 "+result.size+" 个视频"
   }catch(e:Exception){binding.searchStatus.text="搜索失败："+(e.message?:"网络连接异常");Toast.makeText(fragment.requireContext(),"请检查网络后重试",Toast.LENGTH_SHORT).show()}
   finally{binding.searchProgress.visibility=android.view.View.GONE;binding.searchButton.isEnabled=true}
  }
 }
 private fun openPlayer(v:BiliVideo){fragment.startActivity(Intent(fragment.requireContext(),VideoPlayerActivity::class.java).apply{
  putExtra(VideoPlayerActivity.EXTRA_BVID,v.bvid);putExtra(VideoPlayerActivity.EXTRA_AID,v.aid);putExtra(VideoPlayerActivity.EXTRA_TITLE,v.title)
 })}
}