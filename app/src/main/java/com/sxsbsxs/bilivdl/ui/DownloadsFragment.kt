package com.sxsbsxs.bilivdl.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.sxsbsxs.bilivdl.R

class DownloadsFragment : Fragment(R.layout.fragment_simple) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.page_title).text = "下载"
        view.findViewById<TextView>(R.id.page_description).text =
            "下载队列、任务进度和本地文件将在第四部分实现。"
    }
}
