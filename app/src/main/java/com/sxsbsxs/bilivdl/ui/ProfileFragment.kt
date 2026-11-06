package com.sxsbsxs.bilivdl.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliAccount
import com.sxsbsxs.bilivdl.data.BiliSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileFragment : Fragment(R.layout.fragment_profile) {
    private var status: TextView? = null
    private var accountName: TextView? = null
    private var accountAvatar: ImageView? = null
    private var accountHeader: View? = null
    private var openProfileJob: Job? = null
    private var loginEntry: TextView? = null
    private var refreshEntry: TextView? = null
    private var logoutEntry: TextView? = null
    private var refreshJob: Job? = null
    private var logoutJob: Job? = null
    private var accountMid = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        status = view.findViewById(R.id.account_status)
        accountName = view.findViewById(R.id.account_name)
        accountAvatar = view.findViewById(R.id.account_avatar)
        accountHeader = view.findViewById(R.id.account_header_card)
        loginEntry = view.findViewById(R.id.account_qr_login)
        refreshEntry = view.findViewById(R.id.account_refresh)
        logoutEntry = view.findViewById(R.id.account_logout)
        loginEntry?.setOnClickListener { startActivity(Intent(requireContext(), QrLoginActivity::class.java)) }
        accountHeader?.setOnClickListener { openOwnSpace() }
        accountAvatar?.setOnClickListener { openOwnSpace() }
        refreshEntry?.setOnClickListener { refresh(showLoading = true) }
        logoutEntry?.setOnClickListener {
            AlertDialog.Builder(requireContext()).setTitle("退出登录")
                .setMessage("确定退出当前哔哩哔哩账号吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出") { _, _ -> logout() }.show()
        }
        bindLibraryEntry(view, R.id.account_history, AccountLibraryActivity.TYPE_HISTORY)
        bindLibraryEntry(view, R.id.account_watch_later, AccountLibraryActivity.TYPE_WATCH_LATER)
        bindLibraryEntry(view, R.id.account_favorites, AccountLibraryActivity.TYPE_FAVORITES)
        bindLibraryEntry(view, R.id.account_following, AccountLibraryActivity.TYPE_FOLLOWING)
        bindLibraryEntry(view, R.id.account_liked, AccountLibraryActivity.TYPE_LIKED)
        bindLibraryEntry(view, R.id.account_messages, AccountLibraryActivity.TYPE_MESSAGES)
        bindLibraryEntry(view, R.id.account_bangumi, AccountLibraryActivity.TYPE_BANGUMI)
        bindLibraryEntry(view, R.id.account_cinema, AccountLibraryActivity.TYPE_CINEMA)
        refresh()
    }

    private fun bindLibraryEntry(view: View, viewId: Int, type: String) {
        view.findViewById<TextView>(viewId)?.setOnClickListener {
            if (BiliSession.cookie(requireContext()).isBlank()) {
                startActivity(Intent(requireContext(), QrLoginActivity::class.java))
                return@setOnClickListener
            }
            startActivity(Intent(requireContext(), AccountLibraryActivity::class.java).apply {
                putExtra(AccountLibraryActivity.EXTRA_TYPE, type)
                putExtra(AccountLibraryActivity.EXTRA_MID, accountMid)
            })
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh(showLoading: Boolean = false) {
        refreshJob?.cancel()
        val context = context?.applicationContext ?: return
        if (BiliSession.cookie(context).isBlank()) { renderLoggedOut(); return }
        if (showLoading) status?.text = "正在刷新账号信息…"
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val account = withContext(Dispatchers.IO) { BiliSession.account(context) }
                if (!isAdded) return@launch
                renderAccount(account)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!isAdded) return@launch
                accountName?.text = "账号状态待验证"
                status?.text = "暂时无法获取账号资料，请检查网络后刷新"
                loginEntry?.visibility = View.GONE
                refreshEntry?.visibility = View.VISIBLE
                logoutEntry?.visibility = View.VISIBLE
                accountAvatar?.setImageResource(R.drawable.bg_comment_avatar)
            }
        }
    }

    private fun openOwnSpace() {
        val ctx = context?.applicationContext ?: return
        if (BiliSession.cookie(ctx).isBlank()) {
            startActivity(Intent(requireContext(), QrLoginActivity::class.java))
            return
        }
        if (accountMid > 0L) {
            startActivity(Intent(requireContext(), UserSpaceActivity::class.java)
                .putExtra(UserSpaceActivity.EXTRA_MID, accountMid))
            return
        }
        if (openProfileJob?.isActive == true) return
        openProfileJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val account = withContext(Dispatchers.IO) { BiliSession.account(ctx) }
                if (!isAdded) return@launch
                if (account.isLogin && account.mid > 0L) {
                    accountMid = account.mid
                    startActivity(Intent(requireContext(), UserSpaceActivity::class.java)
                        .putExtra(UserSpaceActivity.EXTRA_MID, account.mid))
                } else {
                    startActivity(Intent(requireContext(), QrLoginActivity::class.java))
                }
            } catch (error: Exception) {
                if (isAdded) status?.text = error.message ?: "暂时无法打开个人空间，请刷新账号"
            }
        }
    }

    private fun renderAccount(account: BiliAccount) {
        if (!account.isLogin) { renderLoggedOut(); return }
        accountMid = account.mid
        loginEntry?.visibility = View.GONE
        refreshEntry?.visibility = View.VISIBLE
        logoutEntry?.visibility = View.VISIBLE
        accountName?.text = account.username
        val vip = if (account.vipStatus == 1 && account.vipType > 0) " · 大会员" else ""
        status?.text = "UID " + account.mid + " · LV" + account.level + vip
        if (account.avatar.isNotBlank()) {
            Glide.with(this).load(GlideUrl(account.avatar,
                LazyHeaders.Builder().addHeader("Referer", "https://www.bilibili.com/").build()))
                .placeholder(R.drawable.bg_comment_avatar).error(R.drawable.bg_comment_avatar)
                .circleCrop().into(accountAvatar ?: return)
        } else accountAvatar?.setImageResource(R.drawable.bg_comment_avatar)
        accountHeader?.isClickable = true
        accountAvatar?.setOnClickListener { openOwnSpace() }
    }

    private fun renderLoggedOut() {
        accountMid = 0L
        loginEntry?.visibility = View.VISIBLE
        refreshEntry?.visibility = View.GONE
        logoutEntry?.visibility = View.GONE
        accountName?.text = "未登录"
        status?.text = "浏览、搜索和游客可用的播放功能无需登录"
        accountAvatar?.setImageResource(R.drawable.bg_comment_avatar)
        accountAvatar?.setOnClickListener { openOwnSpace() }
        accountHeader?.isClickable = true
    }

    private fun logout() {
        logoutJob?.cancel()
        val context = context?.applicationContext ?: return
        logoutEntry?.isEnabled = false
        status?.text = "正在安全退出账号…"
        logoutJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { BiliSession.logout(context) }
                if (!isAdded) return@launch
                renderLoggedOut()
                Toast.makeText(requireContext(), "已退出登录", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isAdded) return@launch
                status?.text = error.message ?: "退出失败，请稍后重试"
                Toast.makeText(requireContext(), "退出失败：" + (error.message ?: "网络异常"), Toast.LENGTH_LONG).show()
            } finally { logoutEntry?.isEnabled = true }
        }
    }

    override fun onDestroyView() {
        refreshJob?.cancel(); logoutJob?.cancel(); openProfileJob?.cancel()
        refreshJob = null; logoutJob = null; openProfileJob = null
        status = null; accountName = null; accountAvatar = null; accountHeader = null
        loginEntry = null; refreshEntry = null; logoutEntry = null
        super.onDestroyView()
    }
}
