package com.sxsbsxs.bilivdl.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileFragment : Fragment(R.layout.fragment_profile) {
    private var status: TextView? = null
    private var accountName: TextView? = null
    private var accountAvatar: ImageView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        status = view.findViewById(R.id.account_status)
        accountName = view.findViewById(R.id.account_name)
        accountAvatar = view.findViewById(R.id.account_avatar)
        accountAvatar?.setOnClickListener {
            val mid = BiliSession.profile(requireContext()).optLong("mid")
            if (mid > 0L) startActivity(android.content.Intent(requireContext(), UserSpaceActivity::class.java).putExtra(UserSpaceActivity.EXTRA_MID, mid))
        }
        view.findViewById<MaterialButton>(R.id.account_qr_login).setOnClickListener {
            startActivity(android.content.Intent(requireContext(), QrLoginActivity::class.java))
        }
        view.findViewById<MaterialButton>(R.id.account_refresh).setOnClickListener { refresh() }
        view.findViewById<MaterialButton>(R.id.account_logout).setOnClickListener {
            BiliSession.clear(requireContext())
            accountName?.text = "未登录"
            status?.text = "登录凭据已清除"
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        status?.text = "正在验证账号状态…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val context = requireContext().applicationContext
                val data = withContext(Dispatchers.IO) { BiliSession.profile(context) }
                val logged = data.optBoolean("isLogin")
                accountName?.text = if (logged) data.optString("uname", "已登录") else "未登录"
                val face = data.optString("face").let { if (it.startsWith("//")) "https:$it" else it }
                if (logged && face.isNotBlank()) Glide.with(this@ProfileFragment)
                    .load(GlideUrl(face, LazyHeaders.Builder().addHeader("Referer", "https://www.bilibili.com/").build()))
                    .placeholder(R.drawable.bg_comment_avatar).error(R.drawable.bg_comment_avatar).circleCrop()
                    .into(accountAvatar ?: return@launch)
                accountAvatar?.setOnClickListener {
                    val mid = data.optLong("mid")
                    if (mid > 0L) startActivity(android.content.Intent(requireContext(), UserSpaceActivity::class.java).putExtra(UserSpaceActivity.EXTRA_MID, mid))
                }
                status?.text = if (logged) "账号已连接 · UID ${data.optLong("mid")}"
                    else "未检测到有效登录。点击下方按钮使用哔哩哔哩 APP 扫码登录。"
            } catch (e: Exception) {
                accountName?.text = if (BiliSession.cookie(requireContext()).isBlank()) "未登录" else "待验证"
                status?.text = "账号状态获取失败：${e.message ?: "网络异常"}"
            }
        }
    }

    override fun onDestroyView() {
        status = null
        accountName = null
        accountAvatar = null
        super.onDestroyView()
    }
}
