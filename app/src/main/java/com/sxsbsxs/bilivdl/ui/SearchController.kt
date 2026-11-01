package com.sxsbsxs.bilivdl.ui

import android.content.Context
import android.content.Intent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sxsbsxs.bilivdl.data.BangumiApiBridge
import com.sxsbsxs.bilivdl.data.BangumiSearchResult
import com.sxsbsxs.bilivdl.data.BiliApi
import com.sxsbsxs.bilivdl.data.BiliVideo
import com.sxsbsxs.bilivdl.data.BiliVideoLookup
import com.sxsbsxs.bilivdl.databinding.FragmentSearchBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchController(
    private val fragment: DiscoverFragment,
    private val binding: FragmentSearchBinding
) {
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var bangumiAdapter: BangumiResultAdapter
    private var bangumiMode = false

    fun bind() {
        videoAdapter = VideoAdapter(::openPlayer)
        bangumiAdapter = BangumiResultAdapter(::openBangumi)
        binding.searchResults.layoutManager = LinearLayoutManager(fragment.requireContext())
        binding.searchResults.adapter = videoAdapter
        binding.searchVideoMode.setOnClickListener { setMode(false) }
        binding.searchBangumiMode.setOnClickListener { setMode(true) }
        binding.searchButton.setOnClickListener { search() }
        binding.searchInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                search()
                true
            } else false
        }
        setMode(false)
    }

    private fun setMode(bangumi: Boolean) {
        bangumiMode = bangumi
        binding.searchVideoMode.isSelected = !bangumi
        binding.searchBangumiMode.isSelected = bangumi
        binding.searchVideoMode.alpha = if (bangumi) 0.65f else 1f
        binding.searchBangumiMode.alpha = if (bangumi) 1f else 0.65f
        binding.searchInputLayout.hint = if (bangumi) "输入番剧名称" else "输入关键词、BV号或AV号"
        binding.searchStatus.text = if (bangumi) "搜索番剧名称，选择番剧后可查看剧集并播放" else "搜索视频、UP 主，或直接输入 BV / AV 号"
        binding.searchResults.adapter = if (bangumi) bangumiAdapter else videoAdapter
    }

    private fun search() {
        val key = binding.searchInput.text?.toString()?.trim().orEmpty()
        if (key.isBlank()) {
            binding.searchInputLayout.error = if (bangumiMode) "请输入番剧名称" else "请输入搜索内容"
            return
        }
        binding.searchInputLayout.error = null
        (fragment.requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        binding.searchProgress.visibility = android.view.View.VISIBLE
        binding.searchButton.isEnabled = false
        binding.searchStatus.text = if (bangumiMode) "正在搜索番剧…" else "正在搜索视频…"
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (bangumiMode) {
                    val result = withContext(Dispatchers.IO) { BangumiApiBridge.search(key) }
                    bangumiAdapter.submit(result)
                    binding.searchStatus.text = if (result.isEmpty()) "没有找到相关番剧" else "找到 ${result.size} 部番剧"
                } else {
                    val result = withContext(Dispatchers.IO) {
                        if (BiliApi.resolveVideoId(key) != null) listOf(BiliVideoLookup.get(key))
                        else BiliApi.searchVideos(key)
                    }
                    videoAdapter.submitList(result)
                    binding.searchStatus.text = if (result.isEmpty()) "没有找到相关视频" else "找到 ${result.size} 个视频"
                }
            } catch (error: Exception) {
                binding.searchStatus.text = "搜索失败：" + (error.message ?: "网络连接异常")
            } finally {
                binding.searchProgress.visibility = android.view.View.GONE
                binding.searchButton.isEnabled = true
            }
        }
    }

    private fun openPlayer(video: BiliVideo) {
        fragment.startActivity(Intent(fragment.requireContext(), VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_BVID, video.bvid)
            putExtra(VideoPlayerActivity.EXTRA_AID, video.aid)
            putExtra(VideoPlayerActivity.EXTRA_TITLE, video.title)
            putExtra(VideoPlayerActivity.EXTRA_COVER, video.cover)
            putExtra(VideoPlayerActivity.EXTRA_AUTHOR, video.author)
        })
    }

    private fun openBangumi(item: BangumiSearchResult) {
        binding.searchStatus.text = "正在获取《${item.title}》的剧集…"
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val season = withContext(Dispatchers.IO) { BangumiApiBridge.season(item.seasonId) }
                if (season.episodes.isEmpty()) error("该番剧暂时没有可用剧集")
                val labels: Array<CharSequence> = season.episodes.map { episode ->
                    listOf(episode.title, episode.longTitle).filter { it.isNotBlank() }.joinToString(" · ")
                }.toTypedArray()
                AlertDialog.Builder(fragment.requireContext())
                    .setTitle(season.title.ifBlank { item.title })
                    .setItems(labels) { _, index ->
                        val episode = season.episodes[index]
                        fragment.startActivity(Intent(fragment.requireContext(), VideoPlayerActivity::class.java).apply {
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
                binding.searchStatus.text = "已加载 ${season.episodes.size} 集，选择剧集即可播放"
            } catch (error: Exception) {
                binding.searchStatus.text = "番剧详情加载失败：" + (error.message ?: "接口异常")
            }
        }
    }
}
