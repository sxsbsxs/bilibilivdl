package com.sxsbsxs.bilivdl.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliPopularApi
import com.sxsbsxs.bilivdl.data.BiliSession
import com.sxsbsxs.bilivdl.databinding.FragmentHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment:Fragment(R.layout.fragment_home) {
    private var _binding:FragmentHomeBinding?=null
    private val b get()=_binding!!
    private var page=1
    private var loading=false
    private var hasMore=true
    private val videos=mutableListOf<com.sxsbsxs.bilivdl.data.BiliVideo>()
    private lateinit var adapter:VideoAdapter
    override fun onViewCreated(view:View,savedInstanceState:Bundle?) {
        super.onViewCreated(view,savedInstanceState); _binding=FragmentHomeBinding.bind(view)
        val search={parentFragmentManager.beginTransaction().replace(R.id.fragment_container,DiscoverFragment()).addToBackStack("search").commit()}
        b.homeSearchButton.setOnClickListener{search()}; b.homeSearchEntry.setOnClickListener{search()}
        adapter=VideoAdapter{v->startActivity(Intent(requireContext(),VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_BVID,v.bvid); putExtra(VideoPlayerActivity.EXTRA_AID,v.aid); putExtra(VideoPlayerActivity.EXTRA_TITLE,v.title)
        })}
        b.homeRecommendations.layoutManager=LinearLayoutManager(requireContext()); b.homeRecommendations.adapter=adapter
        b.homeSwipe.setOnRefreshListener{refresh()}
        b.homeScroll.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener{scroll,_,y,_,_->
            if(!loading&&hasMore&&y+scroll.height>=scroll.getChildAt(0).measuredHeight-450) loadPage(page+1)
        })
        refresh()
    }
    private fun refresh() {
        page=1; hasMore=true; videos.clear(); adapter.submitList(emptyList()); b.homeSwipe.isRefreshing=true
        loadPage(1)
    }
    private fun loadPage(target:Int) {
        if(loading||(!hasMore&&target!=1)) return
        loading=true
        if(target==1&&!b.homeSwipe.isRefreshing) b.homeProgress.visibility=View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result=withContext(Dispatchers.IO){BiliPopularApi.loadPopular(target,BiliSession.cookie(requireContext()))}
                val seen=videos.map{it.bvid}.toHashSet()
                videos.addAll(result.filter{it.bvid.isNotBlank()&&seen.add(it.bvid)})
                page=target; hasMore=result.size>=20; adapter.submitList(videos.toList())
                b.homeFeedStatus.text=if(videos.isEmpty())"暂时没有推荐视频" else "已加载 ${videos.size} 条 · 下滑继续加载"
            } catch(e:Exception) { b.homeFeedStatus.text="加载失败：${e.message?: "网络异常"} · 下拉重试" }
            finally { loading=false; b.homeProgress.visibility=View.GONE; b.homeSwipe.isRefreshing=false }
        }
    }
    override fun onDestroyView(){_binding=null;super.onDestroyView()}
}
