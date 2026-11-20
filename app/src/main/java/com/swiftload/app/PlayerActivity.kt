package com.swiftload.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture

/**
 * Built-in player.
 *  - Video: fullscreen, seek bar, playback speed (⚙ in the controls), next/previous in the Videos list.
 *  - Music: plays through PlaybackService, so it keeps going in the background with notification controls.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MUSIC = "music"
        private const val EXTRA_IDS = "ids"
        private const val EXTRA_INDEX = "index"

        fun playVideos(context: Context, items: List<DownloadItem>, index: Int) = context.startActivity(
            Intent(context, PlayerActivity::class.java)
                .putStringArrayListExtra(EXTRA_IDS, ArrayList(items.map { it.id }))
                .putExtra(EXTRA_INDEX, index)
        )

        fun playMusic(context: Context, items: List<DownloadItem>, index: Int) = context.startActivity(
            Intent(context, PlayerActivity::class.java)
                .putStringArrayListExtra(EXTRA_IDS, ArrayList(items.map { it.id }))
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_MUSIC, true)
        )

        fun toMediaItem(item: DownloadItem): MediaItem {
            val uri = Uri.parse(item.fileUri)
            return MediaItem.Builder()
                .setMediaId(item.id)
                .setUri(uri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtworkUri(item.thumbnailUrl?.let(Uri::parse))
                        .build()
                )
                .build()
        }
    }

    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var topBar: View
    private var videoPlayer: ExoPlayer? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var music = false

    private val listener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            mediaMetadata.title?.let { titleView.text = it }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        titleView = findViewById(R.id.playerTitle)
        topBar = findViewById(R.id.topBar)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        music = intent.getBooleanExtra(EXTRA_MUSIC, false)
        val items = intent.getStringArrayListExtra(EXTRA_IDS).orEmpty()
            .mapNotNull { DownloadStore.get(it) }
            .filter { it.fileUri != null }
        val index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        titleView.text = items.getOrNull(index)?.title ?: ""

        if (music) setupMusic(items, index) else setupVideo(items, index)
    }

    private fun setupVideo(items: List<DownloadItem>, index: Int) {
        if (items.isEmpty()) { toast("Video not found"); finish(); return }
        enterImmersive()
        val p = ExoPlayer.Builder(this).build()
        p.setMediaItems(items.map(::toMediaItem), index, 0)
        p.prepare()
        p.playWhenReady = true
        p.addListener(listener)
        videoPlayer = p
        playerView.player = p
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> topBar.visibility = v })
        playerView.setFullscreenButtonClickListener { fullscreen ->
            requestedOrientation = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun setupMusic(items: List<DownloadItem>, index: Int) {
        // Controls stay visible for music
        playerView.setControllerShowTimeoutMs(0)
        playerView.setControllerHideOnTouch(false)
        playerView.setDefaultArtwork(ContextCompat.getDrawable(this, R.drawable.ic_nav_play))
        playerView.showController()

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (isDestroyed) return@addListener
            val controller = runCatching { future.get() }.getOrNull() ?: run { toast("Player could not start"); return@addListener }
            if (items.isNotEmpty()) {
                // The whole Music list becomes the playlist -> next / previous work
                controller.setMediaItems(items.map(::toMediaItem), index, 0)
                controller.prepare()
                controller.play()
            } else if (controller.mediaItemCount == 0) {
                toast("Nothing is playing"); finish(); return@addListener
            }
            controller.addListener(listener)
            controller.mediaMetadata.title?.let { titleView.text = it }
            playerView.player = controller
            playerView.showController()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, playerView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStop() {
        super.onStop()
        // Videos pause when you leave; music keeps playing in the background.
        videoPlayer?.pause()
    }

    override fun onDestroy() {
        playerView.player = null
        videoPlayer?.release()
        videoPlayer = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
