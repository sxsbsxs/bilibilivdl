package com.sxsbsxs.bilivdl

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.sxsbsxs.bilivdl.databinding.ActivityMainBinding
import com.sxsbsxs.bilivdl.ui.DiscoverFragment
import com.sxsbsxs.bilivdl.ui.DownloadsFragment
import com.sxsbsxs.bilivdl.ui.HomeFragment
import com.sxsbsxs.bilivdl.ui.ProfileFragment

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showPage(HomeFragment())
                R.id.nav_discover -> showPage(DiscoverFragment())
                R.id.nav_downloads -> showPage(DownloadsFragment())
                R.id.nav_profile -> showPage(ProfileFragment())
            }
            true
        }

        if (savedInstanceState == null) {
            binding.bottomNavigation.selectedItemId = R.id.nav_home
        }
    }

    private fun showPage(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}
