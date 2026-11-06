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
import com.sxsbsxs.bilivdl.data.BangumiApiBridge
import com.sxsbsxs.bilivdl.data.BangumiSearchResult
import androidx.appcompat.app.AlertDialog
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
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
    private lateinit var bangumiSearchAdapter: BangumiResultAdapter
    private var bangumiSearchMode = false
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
        refreshAccountAvatar()
        b.homeSearchButton.setOnClickListener { openSearch() }
        b.homeSearchEntry.setOnClickListener { openSearch() }
        b.homeSearchInput.setOnClickListener { b.homeSearchInput.requestFocus() }
        b.homeSearchVideoMode.setOnClickListener { setHomeSearchMode(false) }
        b.homeSearchBangumiMode.setOnClickListener { setHomeSearchMode(true) }
        setHomeSearchMode(false)
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

    private fun refreshAccountAvatar() {
        val avatar = b.homeLoginButton
        viewLifecycleOwner.lifecycleScope.launch {
            val profile = runCatching {
                withContext(Dispatchers.IO) { BiliSession.profile(requireContext().applicationContext) }
            }.getOrNull() ?: return@launch
            val face = profile.optString("face").let { if (it.startsWith("//")) "https:$it" else it }
            if (profile.optBoolean("isLogin") && face.isNotBlank()) {
                Glide.with(this@HomeFragment)
                    .load(GlideUrl(face, LazyHeaders.Builder().addHeader("Referer", "https://www.bilibili.com/").build()))
                    .placeholder(R.drawable.bg_comment_avatar)
                    .error(R.drawable.bg_comment_avatar)
                    .circleCrop()
                    .into(avatar)
            } else {
                avatar.setImageResource(R.drawable.bg_comment_avatar)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) refreshAccountAvatar()
    }

    private fun setHomeSearchMode(bangumi: Boolean) {
        bangumiSearchMode = bangumi
        b.homeSearchVideoMode.isSelected = !bangumi
        b.homeSearchBangumiMode.isSelected = bangumi
        b.homeSearchVideoMode.alpha = if (bangumi) 0.65f else 1f
        b.homeSearchBangumiMode.alpha = if (bangumi) 1f else 0.65f
        b.homeSearchInput.hint = if (bangumi) "搜索番剧名称" else "搜索视频、UP主、BV号或AV号"
    }

    private fun openBangumiFromHome(item: BangumiSearchResult) {
        b.homeSearchStatus.text = "正在获取《${item.title}》的剧集…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val season = withContext(Dispatchers.IO) { BangumiApiBridge.season(item.seasonId) }
                if (season.episodes.isEmpty()) error("该番剧暂时没有可用剧集")
                val labels: Array<CharSequence> = season.episodes.map { episode ->
                    listOf(episode.title, episode.longTitle).filter { it.isNotBlank() }.joinToString(" · ")
                }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle(season.title.ifBlank { item.title })
                    .setItems(labels) { _, index ->
                        val episode = season.episodes[index]
                        startActivity(Intent(requireContext(), VideoPlayerActivity::class.java).apply {
                            putExtra(VideoPlayerActivity.EXTRA_EP_ID, episode.id)
                            putExtra(VideoPlayerActivity.EXTRA_AID, episode.aid)
                            putExtra(VideoPlayerActivity.EXTRA_BVID, episode.bvid)
                            putExtra(VideoPlayerActivity.EXTRA_COVER, episode.cover.ifBlank { season.cover })
                            putExtra(VideoPlayerActivity.EXTRA_AUTHOR, "番剧 · ${season.title}")
                            putExtra(VideoPlayerActivity.EXTRA_TITLE, listOf(season.title, episode.title, episode.longTitle)
                                .filter { it.isNotBlank() }.joinToString(" · "))
                        })
                    }
                    .setNegativeButton("关闭", null)
                    .show()
                b.homeSearchStatus.text = "已加载 ${season.episodes.size} 集，选择剧集即可播放"
            } catch (e: Exception) {
                b.homeSearchStatus.text = "番剧详情加载失败：${e.message ?: "网络异常"}"
            }
        }
    }

    private fun searchOnHome() {
        val keyword = b.homeSearchInput.text?.toString()?.trim().orEmpty()
        if (keyword.isBlank()) {
            b.homeSearchInput.error = if (bangumiSearchMode) "请输入番剧名称" else "请输入关键词、BV号或AV号"
            return
        }
        b.homeSearchInput.error = null
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(b.homeSearchInput.windowToken, 0)
        searchJob?.cancel()
        b.homeSearchProgress.visibility = View.VISIBLE
        b.homeSearchStatus.visibility = View.VISIBLE
        b.homeSearchStatus.text = if (bangumiSearchMode) "正在搜索番剧…" else "正在搜索视频…"
        b.homeSearchSubmit.isEnabled = false
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (bangumiSearchMode) {
                    val result = withContext(Dispatchers.IO) { BangumiApiBridge.search(keyword) }
                    if (result.isEmpty()) b.homeSearchStatus.text = "没有找到相关番剧"
                    else {
                        b.homeSearchStatus.visibility = View.GONE
                        startActivity(Intent(requireContext(), SearchResultsActivity::class.java).apply {
                            putExtra(SearchResultsActivity.EXTRA_QUERY, keyword)
                            putExtra(SearchResultsActivity.EXTRA_BANGUMI, true)
                            putExtra(SearchResultsActivity.EXTRA_BANGUMI_RESULTS, ArrayList(result))
                        })
                    }
                } else {
                    val result = withContext(Dispatchers.IO) {
                        if (BiliApi.resolveVideoId(keyword) != null) listOf(BiliVideoLookup.get(keyword))
                        else BiliApi.searchVideos(keyword)
                    }
                    if (result.isEmpty()) b.homeSearchStatus.text = "没有找到相关视频"
                    else {
                        b.homeSearchStatus.visibility = View.GONE
                        startActivity(Intent(requireContext(), SearchResultsActivity::class.java).apply {
                            putExtra(SearchResultsActivity.EXTRA_QUERY, keyword)
                            putExtra(SearchResultsActivity.EXTRA_BANGUMI, false)
                            putExtra(SearchResultsActivity.EXTRA_VIDEO_RESULTS, ArrayList(result))
                        })
                    }
                }
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