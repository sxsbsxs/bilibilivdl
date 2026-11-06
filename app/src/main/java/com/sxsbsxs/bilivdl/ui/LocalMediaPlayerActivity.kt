package com.sxsbsxs.bilivdl.ui

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.sxsbsxs.bilivdl.R

class LocalMediaPlayerActivity : AppCompatActivity() {
    companion object { const val EXTRA_URI = "local_uri"; const val EXTRA_TITLE = "local_title" }
    private var player: ExoPlayer? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "本地视频" }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.black)) }
        root.addView(TextView(this).apply { text = "‹  $title"; textSize = 16f; maxLines = 2; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); setOnClickListener { finish() } })
        val view = PlayerView(this).apply { useController = true; resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply { text = "本地文件播放"; textSize = 12f; setTextColor(0xFFBFC3CA.toInt()); setPadding(dp(16), dp(10), dp(16), dp(14)) })
        setContentView(root)
        player = ExoPlayer.Builder(this).build().also {
            view.player = it
            it.setMediaItem(MediaItem.fromUri(uri))
            it.prepare()
            it.playWhenReady = true
        }
    }
    override fun onStop() { player?.pause(); super.onStop() }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
