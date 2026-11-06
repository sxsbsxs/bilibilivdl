package com.sxsbsxs.bilivdl.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliDynamicApi
import com.sxsbsxs.bilivdl.data.BiliDynamicItem
import com.sxsbsxs.bilivdl.data.BiliSession
import com.sxsbsxs.bilivdl.databinding.FragmentDynamicBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DynamicFragment : Fragment(R.layout.fragment_dynamic) {
    private var _binding: FragmentDynamicBinding? = null
    private val b get() = checkNotNull(_binding)
    private lateinit var adapter: DynamicAdapter
    private var cookie = ""
    private var offset = ""
    private var updateBaseline = ""
    private var filter = "all"
    private var hasMore = true
    private var loading = false
    private var generation = 0
    private var loadJob: Job? = null
    private var likeJobs = mutableSetOf<String>()

    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && _binding != null) refresh()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDynamicBinding.bind(view)
        adapter = DynamicAdapter(
            onOpen = ::openDynamic,
            onUser = ::openUser,
            onLike = ::toggleLike,
            onReply = ::openReplies,
            onShare = ::shareDynamic
        )
        b.dynamicList.layoutManager = LinearLayoutManager(requireContext())
        b.dynamicList.adapter = adapter
        b.dynamicSwipe.setOnRefreshListener { refresh() }
        b.dynamicRefresh.setOnClickListener { refresh() }

        bindFilter(b.dynamicFilterAll, "all")
        bindFilter(b.dynamicFilterVideo, "video")
        bindFilter(b.dynamicFilterPgc, "pgc")
        bindFilter(b.dynamicFilterArticle, "article")
        b.dynamicStatus.setOnClickListener {
            if (cookie.isBlank() || b.dynamicStatus.text.contains("登录")) {
                loginLauncher.launch(Intent(requireContext(), QrLoginActivity::class.java))
            } else {
                refresh()
            }
        }

        b.dynamicList.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy <= 0 || loading || !hasMore) return
                val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (manager.findLastVisibleItemPosition() >= manager.itemCount - 5) {
                    loadPage(reset = false)
                }
            }
        })
        refresh()
    }

    private fun bindFilter(view: TextView, value: String) {
        view.setOnClickListener {
            if (filter != value) {
                filter = value
                renderFilterState()
                refresh()
            }
        }
    }

    private fun renderFilterState() {
        val selected = R.drawable.bg_pill_brand
        val idle = R.drawable.bg_pill_soft
        val all = listOf(
            b.dynamicFilterAll to "all",
            b.dynamicFilterVideo to "video",
            b.dynamicFilterPgc to "pgc",
            b.dynamicFilterArticle to "article"
        )
        all.forEach { (view, key) ->
            val active = filter == key
            view.setBackgroundResource(if (active) selected else idle)
            view.setTextColor(requireContext().getColor(
                if (active) R.color.white else R.color.text_secondary
            ))
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            val current = BiliSession.cookie(requireContext().applicationContext)
            if (current.isNotBlank() && current != cookie) refresh()
        }
    }

    private fun refresh() {
        generation++
        loadJob?.cancel()
        offset = ""
        updateBaseline = ""
        hasMore = true
        loading = false
        adapter.submit(emptyList())
        b.dynamicSwipe.isRefreshing = true
        renderFilterState()
        loadPage(reset = true)
    }

    private fun loadPage(reset: Boolean) {
        val binding = _binding ?: return
        if (loading || (!hasMore && !reset)) return
        loading = true
        val currentGeneration = generation
        binding.dynamicStatus.visibility = View.VISIBLE
        binding.dynamicStatus.text = if (reset) "正在加载关注动态…" else "正在加载更多动态…"
        binding.dynamicProgress.visibility = if (reset) View.VISIBLE else View.GONE
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val activeCookie = withContext(Dispatchers.IO) {
                    BiliSession.cookie(requireContext().applicationContext)
                }
                if (activeCookie.isBlank()) {
                    cookie = ""
                    hasMore = false
                    _binding?.let { live ->
                        live.dynamicStatus.visibility = View.VISIBLE
                        live.dynamicStatus.text = "登录 B站后查看你关注的 UP 主动态，点击此处扫码登录"
                    }
                    return@launch
                }
                cookie = activeCookie
                val page = withContext(Dispatchers.IO) {
                    BiliDynamicApi.feed(
                        cookie = activeCookie,
                        offset = if (reset) "" else offset,
                        updateBaseline = if (reset) "" else updateBaseline,
                        type = filter
                    )
                }
                if (currentGeneration != generation || _binding == null) return@launch
                if (reset) {
                    updateBaseline = page.updateBaseline
                    offset = page.offset
                    adapter.submit(page.items)
                } else {
                    offset = page.offset
                    adapter.submit(page.items, append = true)
                }
                hasMore = page.hasMore && page.items.isNotEmpty()
                val count = adapter.count()
                b.dynamicStatus.visibility = View.VISIBLE
                b.dynamicStatus.text = when {
                    count == 0 -> "暂时没有动态，试试切换分类或刷新"
                    hasMore -> "已加载 $count 条动态 · 继续下滑"
                    else -> "已加载全部 $count 条动态"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (currentGeneration == generation) {
                    b.dynamicStatus.visibility = View.VISIBLE
                    b.dynamicStatus.text = (error.message ?: "动态加载失败") + " · 点击重试"
                    hasMore = false
                }
            } finally {
                if (currentGeneration == generation) {
                    _binding?.let { live ->
                        live.dynamicProgress.visibility = View.GONE
                        live.dynamicSwipe.isRefreshing = false
                    }
                    loading = false
                }
            }
        }
    }

    private fun openDynamic(item: BiliDynamicItem) {
        when {
            item.episodeId > 0L -> startActivity(Intent(requireContext(), VideoPlayerActivity::class.java).apply {
                putExtra(VideoPlayerActivity.EXTRA_EP_ID, item.episodeId)
                putExtra(VideoPlayerActivity.EXTRA_SEASON_ID, item.seasonId)
                putExtra(VideoPlayerActivity.EXTRA_TITLE, item.title)
                putExtra(VideoPlayerActivity.EXTRA_COVER, item.cover)
                putExtra(VideoPlayerActivity.EXTRA_AUTHOR, item.ownerName)
            })
            item.aid > 0L || item.bvid.isNotBlank() -> startActivity(Intent(requireContext(), VideoPlayerActivity::class.java).apply {
                putExtra(VideoPlayerActivity.EXTRA_AID, item.aid)
                putExtra(VideoPlayerActivity.EXTRA_BVID, item.bvid)
                putExtra(VideoPlayerActivity.EXTRA_TITLE, item.title)
                putExtra(VideoPlayerActivity.EXTRA_COVER, item.cover)
                putExtra(VideoPlayerActivity.EXTRA_AUTHOR, item.ownerName)
            })
            item.url.isNotBlank() -> openUrl(item.url)
            else -> Unit
        }
    }

    private fun openReplies(item: BiliDynamicItem) {
        startActivity(Intent(requireContext(), DynamicCommentsActivity::class.java).apply {
            putExtra(DynamicCommentsActivity.EXTRA_DYNAMIC_ID, item.id)
            putExtra(DynamicCommentsActivity.EXTRA_TITLE, item.title)
            putExtra(DynamicCommentsActivity.EXTRA_TEXT, item.text)
            putExtra(DynamicCommentsActivity.EXTRA_AUTHOR, item.ownerName)
            putExtra(DynamicCommentsActivity.EXTRA_FACE, item.ownerFace)
            putExtra(DynamicCommentsActivity.EXTRA_COVER, item.cover)
        })
    }

    private fun openUser(mid: Long) {
        if (mid > 0L) {
            startActivity(Intent(requireContext(), UserSpaceActivity::class.java)
                .putExtra(UserSpaceActivity.EXTRA_MID, mid))
        }
    }

    private fun toggleLike(item: BiliDynamicItem) {
        if (likeJobs.contains(item.id)) return
        if (cookie.isBlank()) {
            b.dynamicStatus.visibility = View.VISIBLE
            b.dynamicStatus.text = "动态点赞需要先登录，点击此处扫码登录"
            return
        }
        likeJobs += item.id
        val target = !item.liked
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    BiliDynamicApi.toggleLike(cookie, item.id, target)
                }
                adapter.updateLike(item.id, target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _binding?.let { live ->
                    live.dynamicStatus.visibility = View.VISIBLE
                    live.dynamicStatus.text = error.message ?: "动态点赞失败"
                }
            } finally {
                likeJobs.remove(item.id)
            }
        }
    }

    private fun shareDynamic(item: BiliDynamicItem) {
        val url = item.url.ifBlank { "https://t.bilibili.com/${item.id}" }
        val content = buildString {
            if (item.text.isNotBlank()) append(item.text.take(160)).append("\n")
            if (item.title.isNotBlank()) append(item.title).append("\n")
            append(url)
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
        }, "分享动态"))
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.onFailure {
            _binding?.dynamicStatus?.text = "无法打开动态链接"
        }
    }

    override fun onDestroyView() {
        generation++
        loadJob?.cancel()
        loadJob = null
        likeJobs.clear()
        _binding = null
        super.onDestroyView()
    }
}
