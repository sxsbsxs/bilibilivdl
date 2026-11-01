package com.sxsbsxs.bilivdl.ui

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

class HomeFragment : Fragment(R.layout.fragment_home) {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentHomeBinding.bind(view)

        val openSearch = {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, DiscoverFragment())
                .addToBackStack("discover")
                .commit()
        }
        binding.homeSearchButton.setOnClickListener { openSearch() }
        binding.homeSearchCta.setOnClickListener { openSearch() }

        binding.homeRecommendations.layoutManager = LinearLayoutManager(requireContext())
        binding.homeRecommendations.adapter = VideoAdapter { video ->
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(video.title)
                .setMessage("UP 主：${video.author}\nBV 号：${video.bvid}\nAV 号：av${video.aid}\n时长：${video.duration}\n\n${video.description}")
                .setPositiveButton("知道了", null)
                .show()
        }
        loadPopular()
    }

    private fun loadPopular() {
        binding.homeProgress.visibility = View.VISIBLE
        binding.homeFeedStatus.text = "正在加载热门视频…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val videos = withContext(Dispatchers.IO) { BiliPopularApi.loadPopular() }
                (binding.homeRecommendations.adapter as VideoAdapter).submitList(videos)
                binding.homeFeedStatus.text = if (videos.isEmpty()) "暂时没有可显示的视频" else "热门视频 · ${videos.size} 条"
            } catch (error: Exception) {
                binding.homeFeedStatus.text = "热门视频加载失败，可前往「发现」搜索"
            } finally {
                binding.homeProgress.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}