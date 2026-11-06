package com.sxsbsxs.bilivdl

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.sxsbsxs.bilivdl.databinding.ActivityMainBinding
import com.sxsbsxs.bilivdl.ui.DownloadsFragment
import com.sxsbsxs.bilivdl.ui.HomeFragment
import com.sxsbsxs.bilivdl.ui.ProfileFragment

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    companion object {
        private const val STATE_SELECTED_TAB = "selected_tab"
        private const val TAG_HOME = "tab_home"
        private const val TAG_DOWNLOADS = "tab_downloads"
        private const val TAG_PROFILE = "tab_profile"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            showPage(item.itemId)
            true
        }

        val selected = savedInstanceState?.getInt(STATE_SELECTED_TAB, R.id.nav_home)
            ?: R.id.nav_home
        binding.bottomNavigation.selectedItemId = selected
        showPage(selected)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_TAB, binding.bottomNavigation.selectedItemId)
        super.onSaveInstanceState(outState)
    }

    fun openAccountPage() {
        binding.bottomNavigation.selectedItemId = R.id.nav_profile
        showPage(R.id.nav_profile)
    }

    /**
     * Keep one Fragment instance per bottom-navigation destination.
     * Switching tabs must not recreate the home feed or discard the download list scroll.
     */
    private fun showPage(itemId: Int) {
        val manager = supportFragmentManager
        val (tag, create) = when (itemId) {
            R.id.nav_downloads -> TAG_DOWNLOADS to { DownloadsFragment() }
            R.id.nav_profile -> TAG_PROFILE to { ProfileFragment() }
            else -> TAG_HOME to { HomeFragment() }
        }
        val target = manager.findFragmentByTag(tag) ?: create()
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
