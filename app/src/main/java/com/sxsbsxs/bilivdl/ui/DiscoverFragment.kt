package com.sxsbsxs.bilivdl.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.databinding.FragmentSearchBinding

class DiscoverFragment : Fragment(R.layout.fragment_search) {
    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSearchBinding.bind(view)
        SearchController(this, binding).bind()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}