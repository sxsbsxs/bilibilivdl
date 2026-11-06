package com.sxsbsxs.bilivdl.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sxsbsxs.bilivdl.data.BangumiApiBridge
import com.sxsbsxs.bilivdl.data.BangumiSearchResult
import com.sxsbsxs.bilivdl.data.BiliApi
import com.sxsbsxs.bilivdl.data.BiliVideo
import com.sxsbsxs.bilivdl.databinding.ActivitySearchResultsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchResultsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_QUERY = "search_query"
        const val EXTRA_BANGUMI = "search_bangumi"
        const val EXTRA_VIDEO_RESULTS = "search_video_results"
        const val EXTRA_BANGUMI_RESULTS = "search_bangumi_results"
        private const val PAGE_SIZE = 20
    }

    private lateinit var b: ActivitySearchResultsBinding
    private var query = ""
    private var page = 1
    private var loadingMore = false
    private var hasMore = true
    private var videoAdapter: VideoAdapter? = null
    private val seenIds = hashSetOf<String>()

    @Suppress("DEPRECATION")
    private fun <T : java.io.Serializable> readExtra(key: String): T? {
        val value = if (Build.VERSION.SDK_INT >= 33) {
            intent.getSerializableExtra(key, java.io.Serializable::class.java)
        } else intent.getSerializableExtra(key)
        @Suppress("UNCHECKED_CAST")
        return value as? T
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySearchResultsBinding.inflate(layoutInflater)
        setContentView(b.root)
        query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        val bangumi = intent.getBooleanExtra(EXTRA_BANGUMI, false)
        b.searchResultsTitle.text = if (bangumi) "番剧搜索结果" else "视频搜索结果"
        b.searchResultsQuery.text = "“$query”"
        b.searchResultsBack.setOnClickListener { finish() }
        if (bangumi) {
            b.searchResultsList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
            val results = readExtra<ArrayList<BangumiSearchResult>>(EXTRA_BANGUMI_RESULTS).orEmpty()
            b.searchResultsCount.text = "找到 ${results.size} 部番剧"
            b.searchResultsList.adapter = BangumiResultAdapter(::openBangumi).also { it.submit(results) }
        } else {
            b.searchResultsList.layoutManager = GridLayoutManager(this, 2)
            val results = readExtra<ArrayList<BiliVideo>>(EXTRA_VIDEO_RESULTS).orEmpty()
            seenIds.addAll(results.map { it.bvid.ifBlank { it.aid.toString() } })
            hasMore = results.size >= PAGE_SIZE
            b.searchResultsCount.text = "找到 ${results.size} 个视频"
            videoAdapter = VideoAdapter(::openVideo).also { it.submitList(results) }
            b.searchResultsList.adapter = videoAdapter
            b.searchResultsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    val manager = recyclerView.layoutManager as? GridLayoutManager ?: return
                    if (dy > 0 && !loadingMore && hasMore &&
                        manager.findLastVisibleItemPosition() >= (manager.itemCount - 5).coerceAtLeast(0)) {
                        loadNextPage()
                    }
                }
            })
        }
    }

    private fun openVideo(video: BiliVideo) {
        startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_BVID, video.bvid)
            putExtra(VideoPlayerActivity.EXTRA_AID, video.aid)
            putExtra(VideoPlayerActivity.EXTRA_TITLE, video.title)
            putExtra(VideoPlayerActivity.EXTRA_COVER, video.cover)
            putExtra(VideoPlayerActivity.EXTRA_AUTHOR, video.author)
        })
    }

    private fun loadNextPage() {
        if (loadingMore || !hasMore || query.isBlank()) return
        loadingMore = true
        val nextPage = page + 1
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { BiliApi.searchVideos(query, nextPage, PAGE_SIZE) }
                val fresh = result.filter { seenIds.add(it.bvid.ifBlank { it.aid.toString() }) }
                if (fresh.isNotEmpty()) {
                    page = nextPage
                    val adapter = videoAdapter ?: return@launch
                    adapter.submitList(adapter.currentList + fresh)
                }
                hasMore = result.size >= PAGE_SIZE && fresh.isNotEmpty()
                val count = videoAdapter?.itemCount ?: 0
                b.searchResultsCount.text = if (hasMore) "已加载 $count 个视频" else "共找到 $count 个视频"
            } catch (error: Exception) {
                b.searchResultsCount.text = "已加载 ${videoAdapter?.itemCount ?: 0} 个视频 · 加载更多失败"
            } finally {
                loadingMore = false
            }
        }
    }

    private fun openBangumi(item: BangumiSearchResult) {
        lifecycleScope.launch {
            try {
                val season = withContext(Dispatchers.IO) { BangumiApiBridge.season(item.seasonId) }
                if (season.episodes.isEmpty()) error("该番剧暂时没有可用剧集")
                val labels = season.episodes.map {
                    listOf(it.title, it.longTitle).filter(String::isNotBlank).joinToString(" · ")
                }.toTypedArray()
                MaterialAlertDialogBuilder(this@SearchResultsActivity)
                    .setTitle(season.title.ifBlank { item.title })
                    .setItems(labels) { _, index ->
                        val episode = season.episodes[index]
                        startActivity(Intent(this@SearchResultsActivity, VideoPlayerActivity::class.java).apply {
                            putExtra(VideoPlayerActivity.EXTRA_EP_ID, episode.id)
                            putExtra(VideoPlayerActivity.EXTRA_AID, episode.aid)
                            putExtra(VideoPlayerActivity.EXTRA_BVID, episode.bvid)
                            putExtra(VideoPlayerActivity.EXTRA_COVER, episode.cover.ifBlank { season.cover })
                            putExtra(VideoPlayerActivity.EXTRA_AUTHOR, "番剧 · ${season.title}")
                            putExtra(VideoPlayerActivity.EXTRA_TITLE, listOf(season.title, episode.title, episode.longTitle)
                                .filter(String::isNotBlank).joinToString(" · "))
                        })
                    }.setNegativeButton("关闭", null).show()
            } catch (error: Exception) {
                MaterialAlertDialogBuilder(this@SearchResultsActivity)
                    .setMessage("番剧详情加载失败：${error.message ?: "网络异常"}")
                    .setPositiveButton("知道了", null).show()
            }
        }
    }
}