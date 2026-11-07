package com.sxsbsxs.bilivdl.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import cc.kafuu.bilidownload.feature.viewbinding.view.fragment.HomeFragment as BvdHomeFragment
import cc.kafuu.bilidownload.service.DownloadService as BvdDownloadService
import com.sxsbsxs.bilivdl.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Hosts BiliDownload's original Room-backed download manager. */
class DownloadsFragment : Fragment(R.layout.fragment_download) {
    private var recoveryRequested = false
    private var recoveryJob: Job? = null
    private var recoveryStatus: TextView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recoveryStatus = view.findViewById(R.id.download_recovery_status)
        recoveryStatus?.setOnClickListener { recoverDownloads() }

        if (childFragmentManager.findFragmentById(R.id.bvd_download_history_host) == null) {
            childFragmentManager.commit {
                replace(R.id.bvd_download_history_host, BvdHomeFragment())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!recoveryRequested) recoverDownloads()
    }

    private fun recoverDownloads() {
        if (recoveryJob?.isActive == true) return
        recoveryRequested = true
        val appContext = requireContext().applicationContext
        recoveryJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                BvdDownloadService.resumeDownload(appContext)
                recoveryStatus?.visibility = View.GONE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Do not silently discard a failed recovery request: let the user retry
                // from the download screen without clearing the Room task history.
                recoveryRequested = false
                val reason = error.localizedMessage
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "：$it" }
                    .orEmpty()
                recoveryStatus?.text = "下载任务恢复失败$reason · 点击重试"
                recoveryStatus?.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        recoveryJob?.cancel()
        recoveryJob = null
        recoveryStatus = null
        recoveryRequested = false
        super.onDestroyView()
    }
}
