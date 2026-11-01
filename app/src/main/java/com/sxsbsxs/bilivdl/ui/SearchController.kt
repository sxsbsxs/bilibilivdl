package com.sxsbsxs.bilivdl.ui

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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
    private lateinit var adapter: VideoAdapter

    fun bind() {
        adapter = VideoAdapter(::showVideoInfo)
        binding.searchResults.layoutManager = LinearLayoutManager(fragment.requireContext())
        binding.searchResults.adapter = adapter
        binding.searchButton.setOnClickListener { search() }
        binding.searchInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                search()
                true
            } else false
        }
    }

    private fun search() {
        val keyword = binding.searchInput.text?.toString()?.trim().orEmpty()
        if (keyword.isBlank()) {
            binding.searchInputLayout.error = "请输入搜索内容"
            return
        }
        binding.searchInputLayout.error = null
        (fragment.requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        binding.searchProgress.visibility = android.view.View.VISIBLE
        binding.searchButton.isEnabled = false
        binding.searchStatus.text = "正在搜索…"

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    if (BiliApi.resolveVideoId(keyword) != null) {
                        listOf(BiliVideoLookup.get(keyword))
                    } else {
                        BiliApi.searchVideos(keyword)
                    }
                }
                adapter.submitList(results)
                binding.searchStatus.text = if (results.isEmpty()) "没有找到相关视频" else "找到 ${results.size} 个视频"
            } catch (error: Exception) {
                binding.searchStatus.text = "搜索失败：${error.message ?: "网络连接异常"}"
                Toast.makeText(fragment.requireContext(), "请检查网络后重试", Toast.LENGTH_SHORT).show()
            } finally {
                binding.searchProgress.visibility = android.view.View.GONE
                binding.searchButton.isEnabled = true
            }
        }
    }

    private fun showVideoInfo(video: BiliVideo) {
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(video.title)
            .setMessage("UP 主：${video.author}\nBV 号：${video.bvid}\nAV 号：av${video.aid}\n时长：${video.duration}\n\n${video.description}")
            .setPositiveButton("知道了", null)
            .show()
    }
}