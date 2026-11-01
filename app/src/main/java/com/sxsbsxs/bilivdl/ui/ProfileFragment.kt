package com.sxsbsxs.bilivdl.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.sxsbsxs.bilivdl.R

class ProfileFragment : Fragment(R.layout.fragment_simple) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.page_title).text = "我的"
        view.findViewById<TextView>(R.id.page_description).text =
            "账号、收藏、历史记录和设置将在第五部分完善。"
    }
}
