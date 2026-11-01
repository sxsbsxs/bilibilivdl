package com.sxsbsxs.bilivdl.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileFragment:Fragment(R.layout.fragment_profile) {
    private var status:TextView?=null
    override fun onViewCreated(view:View,savedInstanceState:Bundle?) {
        super.onViewCreated(view,savedInstanceState)
        status=view.findViewById(R.id.account_status)
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.account_login).setOnClickListener{importCookie()}
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.account_refresh).setOnClickListener{refresh()}
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.account_logout).setOnClickListener{
            BiliSession.clear(requireContext())
            view.findViewById<TextView>(R.id.account_name).text="未登录"
            status?.text="登录凭据已清除"
        }
        refresh()
    }
    private fun importCookie() {
        val input=TextInputEditText(requireContext()).apply { hint="粘贴 Cookie（至少包含 SESSDATA=）"; minLines=3; setText(BiliSession.cookie(requireContext())) }
        MaterialAlertDialogBuilder(requireContext()).setTitle("导入 B 站账号")
            .setMessage("粘贴你本人 B 站账号的 Cookie。Cookie 等同于登录凭据，请勿分享给他人。")
            .setView(input).setNegativeButton("取消",null)
            .setPositiveButton("验证并保存"){_,_->
                val value=input.text?.toString().orEmpty().trim()
                if(!value.contains("SESSDATA=")){status?.text="Cookie 不完整：缺少 SESSDATA";return@setPositiveButton}
                BiliSession.saveCookie(requireContext(),value); refresh()
            }.show()
    }
    private fun refresh() {
        status?.text="正在验证账号状态…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data=withContext(Dispatchers.IO){BiliSession.profile(requireContext())}
                val logged=data.optBoolean("isLogin")
                view?.findViewById<TextView>(R.id.account_name)?.text=if(logged)data.optString("uname","已登录") else "未登录"
                status?.text=if(logged)"账号已连接 · UID ${data.optLong("mid")}" else "未检测到有效登录。可导入自己的 B 站 Cookie。"
            } catch(e:Exception) { status?.text="账号状态获取失败：${e.message?: "网络异常"}" }
        }
    }
    override fun onDestroyView(){status=null;super.onDestroyView()}
}
