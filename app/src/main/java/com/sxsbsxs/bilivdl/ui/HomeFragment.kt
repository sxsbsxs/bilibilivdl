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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment(R.layout.fragment_home) {
    private var _binding: FragmentHomeBinding? = null
    private val b get() = checkNotNull(_binding) { "HomeFragment view is not available" }

    private var page = 0
    private var loading = false
    private var hasMore = true
    private var loadJob: Job? = null
    private val videos = mutableListOf<com.sxsbsxs.bilivdl.data.BiliVideo>()
    private lateinit var adapter: VideoAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentHomeBinding.bind(view)

        val openSearch = {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, DiscoverFragment())
                .addToBackStack("search")
                .commit()
        }
        b.homeSearchButton.setOnClickListener { openSearch() }
        b.homeSearchEntry.setOnClickListener { openSearch() }

        adapter = VideoAdapter { video ->
            startActivity(Intent(requireContext(), VideoPlayerActivity::class.java).apply {
                putExtra(VideoPlayerActivity.EXTRA_BVID, video.bvid)
                putExtra(VideoPlayerActivity.EXTRA_AID, video.aid)
                putExtra(VideoPlayerActivity.EXTRA_TITLE, video.title)
            })
        }
        b.homeRecommendations.layoutManager = LinearLayoutManager(requireContext())
        b.homeRecommendations.adapter = adapter
        b.homeSwipe.setOnRefreshListener { refresh() }
        b.homeScroll.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { scroll, _, y, _, _ ->
                val child = scroll.getChildAt(0) ?: return@OnScrollChangeListener
                if (!loading && hasMore && y + scroll.height >= child.measuredHeight - 700) {
                    loadPage(page + 1)
                }
            }
        )
        refresh()
    }

    private fun refresh() {
        val binding = _binding ?: return
        loadJob?.cancel()
        page = 0
        hasMore = true
        loading = false
        videos.clear()
        adapter.submitList(emptyList())
        binding.homeSwipe.isRefreshing = true
        loadPage(1)
    }

    private fun loadPage(target: Int) {
        val binding = _binding ?: return
        if (loading || (!hasMore && target > 1)) return

        loading = true
        if (target == 1 && !binding.homeSwipe.isRefreshing) {
            binding.homeProgress.visibility = View.VISIBLE
        }
        val appContext = requireContext().applicationContext
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            var added = 0
            try {
                val cookie = withContext(Dispatchers.IO) { BiliSession.cookie(appContext) }
                val result = withContext(Dispatchers.IO) {
                    BiliPopularApi.loadPopular(target, cookie)
                }
                val current = _binding ?: return@launch
                val known = videos.mapTo(hashSetOf()) { it.bvid }
                val fresh = result.filter { it.bvid.isNotBlank() && known.add(it.bvid) }
                added = fresh.size
                videos.addAll(fresh)
                page = target
                hasMore = result.size >= 20
                adapter.submitList(videos.toList())
                current.homeFeedStatus.text = when {
                    videos.isEmpty() -> "暂时没有推荐视频 · 下拉重试"
                    hasMore -> "已加载 ${videos.size} 条 · 下滑继续加载"
                    else -> "已加载全部推荐 · 下拉刷新换一批"
                }

                // NestedScrollView does not emit a scroll callback when the first
                // page is shorter than the viewport. Fill the screen by requesting
                // the next page once the adapter has been measured.
                current.homeRecommendations.post {
                    val live = _binding ?: return@post
                    val scroll = live.homeScroll
                    if (!loading && hasMore && added > 0 && !scroll.canScrollVertically(1)) {
                        loadPage(page + 1)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _binding?.homeFeedStatus?.text =
                    "加载失败：${e.message ?: "网络异常"} · 下拉重试"
            } finally {
                _binding?.let {
                    it.homeProgress.visibility = View.GONE
                    it.homeSwipe.isRefreshing = false
                }
                loading = false
            }
        }
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        loadJob = null
        loading = false
        _binding = null
        super.onDestroyView()
    }
}