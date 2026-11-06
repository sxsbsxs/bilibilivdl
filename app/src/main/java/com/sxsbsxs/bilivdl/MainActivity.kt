package com.sxsbsxs.bilivdl

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.sxsbsxs.bilivdl.databinding.ActivityMainBinding
import com.sxsbsxs.bilivdl.ui.DownloadsFragment
import com.sxsbsxs.bilivdl.ui.DynamicFragment
import com.sxsbsxs.bilivdl.ui.HomeFragment
import com.sxsbsxs.bilivdl.ui.ProfileFragment
import com.sxsbsxs.bilivdl.ui.theme.BiliVdlTheme

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val tabFragments = mutableMapOf<String, Fragment>()
    private var selectedTabId by mutableIntStateOf(R.id.nav_home)

    private data class Tab(val id: Int, val title: String, val icon: Int)

    private val tabs by lazy {
        listOf(
            Tab(R.id.nav_home, "首页", R.drawable.ic_home),
            Tab(R.id.nav_dynamic, "动态", R.drawable.ic_dynamic),
            Tab(R.id.nav_downloads, "下载", R.drawable.ic_download),
            Tab(R.id.nav_profile, "我的", R.drawable.ic_profile)
        )
    }

    companion object {
        private const val STATE_SELECTED_TAB = "selected_tab"
        private const val TAG_HOME = "tab_home"
        private const val TAG_DYNAMIC = "tab_dynamic"
        private const val TAG_DOWNLOADS = "tab_downloads"
        private const val TAG_PROFILE = "tab_profile"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectedTabId = savedInstanceState?.getInt(STATE_SELECTED_TAB, R.id.nav_home)
            ?: R.id.nav_home

        binding.bottomNavigationCompose.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.bottomNavigationCompose.setContent {
            BiliVdlTheme {
                NavigationBar(
                    modifier = Modifier.height(64.dp),
                    containerColor = Color.White,
                    contentColor = Color(0xFF34363C),
                    tonalElevation = 0.dp
                ) {
                    tabs.forEach { tab ->
                        val selected = selectedTabId == tab.id
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (selectedTabId != tab.id) {
                                    selectedTabId = tab.id
                                    showPage(tab.id)
                                }
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(tab.icon),
                                    contentDescription = tab.title,
                                    modifier = Modifier.size(21.dp)
                                )
                            },
                            label = { Text(tab.title, fontSize = 10.sp) },
                            alwaysShowLabel = true,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFFFB7299),
                                selectedTextColor = Color(0xFFFB7299),
                                indicatorColor = Color.Transparent,
                                unselectedIconColor = Color(0xFF777D87),
                                unselectedTextColor = Color(0xFF777D87)
                            )
                        )
                    }
                }
            }
        }

        showPage(selectedTabId)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_TAB, selectedTabId)
        super.onSaveInstanceState(outState)
    }

    fun openAccountPage() {
        selectedTabId = R.id.nav_profile
        showPage(R.id.nav_profile)
    }

    /**
     * Keep one Fragment instance per destination. The new Compose navigation bar
     * only owns navigation chrome; legacy pages keep their existing business logic
     * while each screen is migrated independently.
     */
    private fun showPage(itemId: Int) {
        val manager = supportFragmentManager
        manager.executePendingTransactions()
        val (tag, create) = when (itemId) {
            R.id.nav_dynamic -> TAG_DYNAMIC to { DynamicFragment() }
            R.id.nav_downloads -> TAG_DOWNLOADS to { DownloadsFragment() }
            R.id.nav_profile -> TAG_PROFILE to { ProfileFragment() }
            else -> TAG_HOME to { HomeFragment() }
        }
        val target = tabFragments[tag] ?: manager.findFragmentByTag(tag)
            ?: create().also { tabFragments[tag] = it }
        val transaction = manager.beginTransaction().setReorderingAllowed(true)

        manager.fragments.filter { it.isAdded && it !== target }.forEach { fragment ->
            transaction.hide(fragment)
            transaction.setMaxLifecycle(fragment, Lifecycle.State.STARTED)
        }

        if (!target.isAdded) {
            transaction.add(R.id.fragment_container, target, tag)
        } else {
            transaction.show(target)
        }
        transaction.setMaxLifecycle(target, Lifecycle.State.RESUMED)
        transaction.setPrimaryNavigationFragment(target)
        transaction.commit()
    }
}
