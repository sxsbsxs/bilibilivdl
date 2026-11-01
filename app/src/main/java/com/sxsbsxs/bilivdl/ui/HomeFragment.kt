package com.sxsbsxs.bilivdl.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliPopularApi
import com.sxsbsxs.bilivdl.data.BiliApi
import com.sxsbsxs.bilivdl.data.BiliVideoLookup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
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
    private var duplicatePages = 0
    private var loadJob: Job? = null
    private val videos = mutableListOf<com.sxsbsxs.bilivdl.data.BiliVideo>()
    private lateinit var adapter: VideoAdapter
    private lateinit var searchAdapter: VideoAdapter
    private var searchJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentHomeBinding.bind(view)

        val openSearch = {
            b.homeSearchInput.requestFocus()
            val keyboard = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            keyboard?.showSoftInput(b.homeSearchInput, InputMethodManager.SHOW_IMPLICIT)
        }
        b.homeLoginButton.setOnClickListener {
            (activity as? com.sxsbsxs.bilivdl.MainActivity)?.openAccountPage()
        }
        b.homeSearchButton.setOnClickListener { openSearch() }
        searchAdapter = VideoAdapter { video ->
            startActivity(Intent(requireContext(), VideoPlayerActivity::class.java).apply {
                putExtra(VideoPlayerActivity.EXTRA_BVID, video.bvid)
                putExtra(VideoPlayerActivity.EXTRA_AID, video.aid)
                putExtra(VideoPlayerActivity.EXTRA_TITLE, video.title)
                putExtra(VideoPlayerActivity.EXTRA_COVER, video.cover)
                putExtra(VideoPlayerActivity.EXTRA_AUTHOR, video.author)
            })
        }
        b.homeSearchResults.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        b.homeSearchResults.adapter = searchAdapter
        b.homeSearchSubmit.setOnClickListener { searchOnHome() }
        b.homeSearchInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                searchOnHome()
                true
            } else false
        }

        adapter = VideoAdapter { video ->
            startActivity(Intent(requireContext(), VideoPlayerActivity::class.java).apply {
                putExtra(VideoPlayerActivity.EXTRA_BVID, video.bvid)
                putExtra(VideoPlayerActivity.EXTRA_AID, video.aid)
                putExtra(VideoPlayerActivity.EXTRA_TITLE, video.title)
                putExtra(VideoPlayerActivity.EXTRA_COVER, video.cover)
                putExtra(VideoPlayerActivity.EXTRA_AUTHOR, video.author)
            })
        }
        b.homeRecommendations.layoutManager = GridLayoutManager(requireContext(), 2).apply {
            // A two-column cover feed, like the reference Bilibili discovery layout.
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = 1
            }
        }
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

    private fun searchOnHome() {
        val keyword = b.homeSearchInput.text?.toString()?.trim().orEmpty()
        if (keyword.isBlank()) {
            b.homeSearchInput.error = "请输入关键词、BV号或AV号"
            return
        }
        b.homeSearchInput.error = null
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(b.homeSearchInput.windowToken, 0)
        searchJob?.cancel()
        b.homeSearchProgress.visibility = View.VISIBLE
        b.homeSearchStatus.visibility = View.VISIBLE
        b.homeSearchStatus.text = "正在搜索…"
        b.homeSearchSubmit.isEnabled = false
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (BiliApi.resolveVideoId(keyword) != null) listOf(BiliVideoLookup.get(keyword))
                    else BiliApi.searchVideos(keyword)
                }
                searchAdapter.submitList(result)
                b.homeSearchResults.visibility = if (result.isEmpty()) View.GONE else View.VISIBLE
                b.homeSearchStatus.text = if (result.isEmpty()) "没有找到相关视频" else "找到 ${result.size} 个视频 · 首页推荐仍在下方"
            } catch (e: Exception) {
                b.homeSearchStatus.text = "搜索失败：${e.message ?: "网络异常"}"
            } finally {
                b.homeSearchProgress.visibility = View.GONE
                b.homeSearchSubmit.isEnabled = true
            }
        }
    }

    private fun refresh() {
        val binding = _binding ?: return
        loadJob?.cancel()
        page = 0
        hasMore = true
        duplicatePages = 0
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
                duplicatePages = if (added == 0 && result.size >= 20) duplicatePages + 1 else 0
                hasMore = result.size >= 20 && duplicatePages < 3
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
                    val needsAnotherPage = added == 0 || !scroll.canScrollVertically(1)
                    if (!loading && hasMore && needsAnotherPage) loadPage(page + 1)
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
        searchJob?.cancel()
        loadJob = null
        searchJob = null
        loading = false
        _binding = null
        super.onDestroyView()
    }
}