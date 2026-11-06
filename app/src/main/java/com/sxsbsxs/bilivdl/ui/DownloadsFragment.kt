package com.sxsbsxs.bilivdl.ui
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import cc.kafuu.bilidownload.feature.viewbinding.view.fragment.HomeFragment as BvdHomeFragment
import cc.kafuu.bilidownload.service.DownloadService as BvdDownloadService
import com.sxsbsxs.bilivdl.R
import kotlinx.coroutines.launch

/** Hosts BiliDownload original Room-backed download manager. */
class DownloadsFragment : Fragment(R.layout.fragment_download) {
    private var recoveryRequested = false
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(R.id.bvd_download_history_host) == null) {
            childFragmentManager.commit { replace(R.id.bvd_download_history_host, BvdHomeFragment()) }
        }
    }
    override fun onResume() {
        super.onResume()
        if (!recoveryRequested) {
            recoveryRequested = true
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching { BvdDownloadService.resumeDownload(requireContext().applicationContext) }
            }
        }
    }
}