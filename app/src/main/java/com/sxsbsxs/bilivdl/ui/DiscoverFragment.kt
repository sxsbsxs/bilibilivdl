package com.sxsbsxs.bilivdl.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.sxsbsxs.bilivdl.R

class DiscoverFragment : Fragment(R.layout.fragment_simple) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.page_title).text = "发现"
        view.findViewById<TextView>(R.id.page_description).text =
            "搜索视频、UP主和番剧将在第二部分接入。"
    }
}
