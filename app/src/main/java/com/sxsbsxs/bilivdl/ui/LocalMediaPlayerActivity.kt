package com.sxsbsxs.bilivdl.ui

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
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
    private var playbackSpeed = 1f
    private lateinit var uri: Uri
    private val speeds = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "本地视频" }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.black)) }
        root.addView(TextView(this).apply { text = "‹  $title"; textSize = 16f; maxLines = 2; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); setOnClickListener { finish() } })
        val view = PlayerView(this).apply { useController = true; resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT }
        view.keepScreenOn = true
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        val speedButton = TextView(this).apply {
            text = "倍速 1.0x  ·  本地文件播放"
            textSize = 12f; setTextColor(0xFFBFC3CA.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(14))
            setOnClickListener {
                AlertDialog.Builder(this@LocalMediaPlayerActivity).setTitle("播放速度")
                    .setItems(speeds.map { "${it}x" }.toTypedArray()) { _, which ->
                        playbackSpeed = speeds[which]
                        player?.setPlaybackSpeed(playbackSpeed)
                        text = "倍速 ${playbackSpeed}x  ·  本地文件播放"
                    }.show()
            }
        }
        root.addView(speedButton)
        setContentView(root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("local_playback_positions", MODE_PRIVATE)
        player = ExoPlayer.Builder(this).build().also {
            view.player = it
            it.setMediaItem(MediaItem.fromUri(uri))
            it.prepare()
            val saved = prefs.getLong(uri.toString(), 0L)
            if (saved > 0L) it.seekTo(saved)
            it.playWhenReady = true
        }
    }
    override fun onStop() {
        player?.let { current ->
            getSharedPreferences("local_playback_positions", MODE_PRIVATE).edit()
                .putLong(uri.toString(), current.currentPosition.coerceAtLeast(0L)).apply()
            current.pause()
        }
        super.onStop()
    }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
