package com.alexvas.rtsp.demo.live

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider

@SuppressLint("LogNotTimber")
class RawFragment : Fragment() {

    private lateinit var liveViewModel: LiveViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (DEBUG) Log.v(TAG, "onCreateView()")

        liveViewModel = ViewModelProvider(this)[LiveViewModel::class.java]

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                RawScreen(liveViewModel = liveViewModel)
            }
        }
    }

    override fun onResume() {
        if (DEBUG) Log.v(TAG, "onResume()")
        super.onResume()
        liveViewModel.loadParams(requireContext())
    }

    override fun onPause() {
        if (DEBUG) Log.v(TAG, "onPause()")
        super.onPause()
        liveViewModel.saveParams(requireContext())
    }

    companion object {
        private val TAG: String = RawFragment::class.java.simpleName
        private const val DEBUG = true
    }
}
