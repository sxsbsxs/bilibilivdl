package com.sxsbsxs.bilivdl.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.sxsbsxs.bilivdl.R

class HomeFragment : Fragment(R.layout.fragment_home) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.home_search_button).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, DiscoverFragment())
                .addToBackStack(null)
                .commit()
        }
    }
}
