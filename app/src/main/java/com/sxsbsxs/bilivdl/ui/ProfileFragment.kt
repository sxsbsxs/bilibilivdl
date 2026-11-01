package com.sxsbsxs.bilivdl.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        status = view.findViewById(R.id.account_status)
        accountName = view.findViewById(R.id.account_name)
        view.findViewById<MaterialButton>(R.id.account_login).setOnClickListener {
            startActivity(android.content.Intent(requireContext(), WebLoginActivity::class.java))
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
                status?.text = if (logged) "账号已连接 · UID ${data.optLong("mid")}"
                    else "未检测到有效登录。点击下方按钮选择 BVD 网页登录或 APP 扫码登录。"
            } catch (e: Exception) {
                accountName?.text = if (BiliSession.cookie(requireContext()).isBlank()) "未登录" else "待验证"
                status?.text = "账号状态获取失败：${e.message ?: "网络异常"}"
            }
        }
    }

    override fun onDestroyView() {
        status = null
        accountName = null
        super.onDestroyView()
    }
}
