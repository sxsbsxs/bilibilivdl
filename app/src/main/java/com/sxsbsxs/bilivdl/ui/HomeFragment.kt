package com.sxsbsxs.bilivdl.ui
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliPopularApi
import com.sxsbsxs.bilivdl.databinding.FragmentHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
class HomeFragment:Fragment(R.layout.fragment_home){
 private var _binding:FragmentHomeBinding?=null
 private val binding get()=_binding!!
 override fun onViewCreated(view:View,savedInstanceState:Bundle?){
  super.onViewCreated(view,savedInstanceState);_binding=FragmentHomeBinding.bind(view)
  val openSearch={parentFragmentManager.beginTransaction().replace(R.id.fragment_container,DiscoverFragment()).addToBackStack("discover").commit()}
  binding.homeSearchButton.setOnClickListener{openSearch()};binding.homeSearchEntry.setOnClickListener{openSearch()}
  binding.homeRecommendations.layoutManager=LinearLayoutManager(requireContext())
  binding.homeRecommendations.adapter=VideoAdapter{v->startActivity(Intent(requireContext(),VideoPlayerActivity::class.java).apply{
   putExtra(VideoPlayerActivity.EXTRA_BVID,v.bvid);putExtra(VideoPlayerActivity.EXTRA_AID,v.aid);putExtra(VideoPlayerActivity.EXTRA_TITLE,v.title)
  })}
  viewLifecycleOwner.lifecycleScope.launch{
   binding.homeProgress.visibility=View.VISIBLE
   try{val list=withContext(Dispatchers.IO){BiliPopularApi.loadPopular()};(binding.homeRecommendations.adapter as VideoAdapter).submitList(list);binding.homeFeedStatus.text=if(list.isEmpty())"暂无视频" else "热门视频 · "+list.size+" 条"}
   catch(_:Exception){binding.homeFeedStatus.text="加载失败，请检查网络后重试"}
   finally{binding.homeProgress.visibility=View.GONE}
  }
 }
 override fun onDestroyView(){_binding=null;super.onDestroyView()}
}