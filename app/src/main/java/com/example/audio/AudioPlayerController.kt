package com.example.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import com.example.model.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlayerState(
    val currentItem: MediaItem? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val isPitchPreserved: Boolean = true,
    val isLooping: Boolean = false
)

class AudioPlayerController(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState = _playerState.asStateFlow()

    fun playItem(item: MediaItem) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                val file = File(item.filePath)
                if (file.exists()) {
                    setDataSource(file.absolutePath)
                } else if (item.sourceUrl.isNotEmpty()) {
                    setDataSource(context, Uri.parse(item.sourceUrl))
                } else {
                    return
                }

                setOnPreparedListener { mp ->
                    applyPlaybackParams(mp, _playerState.value.speed)
                    mp.start()
                    val dur = mp.duration.toLong().coerceAtLeast(item.durationMs)
                    _playerState.value = _playerState.value.copy(
                        currentItem = item,
                        isPlaying = true,
                        durationMs = dur,
                        currentPositionMs = 0L
                    )
                    startProgressTracker()
                }

                setOnCompletionListener {
                    if (_playerState.value.isLooping) {
                        it.seekTo(0)
                        it.start()
                    } else {
                        _playerState.value = _playerState.value.copy(
                            isPlaying = false,
                            currentPositionMs = 0L
                        )
                    }
                }

                setOnErrorListener { _, _, _ ->
                    _playerState.value = _playerState.value.copy(isPlaying = false)
                    true
                }

                prepareAsync()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun playUri(uri: Uri, title: String) {
        val tempItem = MediaItem(
            id = "preview_${System.currentTimeMillis()}",
            title = title,
            filePath = "",
            durationMs = 0,
            format = "Audio",
            fileSize = 0,
            sourceUrl = uri.toString()
        )
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                setOnPreparedListener { mp ->
                    applyPlaybackParams(mp, _playerState.value.speed)
                    mp.start()
                    _playerState.value = _playerState.value.copy(
                        currentItem = tempItem,
                        isPlaying = true,
                        durationMs = mp.duration.toLong(),
                        currentPositionMs = 0L
                    )
                    startProgressTracker()
                }
                setOnCompletionListener {
                    _playerState.value = _playerState.value.copy(isPlaying = false)
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            _playerState.value = _playerState.value.copy(isPlaying = false)
        } else {
            mp.start()
            _playerState.value = _playerState.value.copy(isPlaying = true)
            startProgressTracker()
        }
    }

    fun seekTo(positionMs: Long) {
        mediaPlayer?.let { mp ->
            mp.seekTo(positionMs.toInt())
            _playerState.value = _playerState.value.copy(currentPositionMs = positionMs)
        }
    }

    fun skipBy(deltaMs: Long) {
        mediaPlayer?.let { mp ->
            val current = mp.currentPosition.toLong()
            val target = (current + deltaMs).coerceIn(0L, _playerState.value.durationMs)
            seekTo(target)
        }
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        _playerState.value = _playerState.value.copy(speed = clamped)
        mediaPlayer?.let { mp ->
            applyPlaybackParams(mp, clamped)
        }
    }

    fun toggleLoop() {
        val nextLoop = !_playerState.value.isLooping
        _playerState.value = _playerState.value.copy(isLooping = nextLoop)
        mediaPlayer?.isLooping = nextLoop
    }

    private fun applyPlaybackParams(mp: MediaPlayer, speed: Float) {
        try {
            val params = PlaybackParams()
            params.speed = speed
            params.pitch = 1.0f // Preserve natural pitch
            mp.playbackParams = params
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val mp = mediaPlayer
                if (mp != null && mp.isPlaying) {
                    val pos = mp.currentPosition.toLong()
                    _playerState.value = _playerState.value.copy(currentPositionMs = pos)
                }
                delay(300)
            }
        }
    }

    fun release() {
        progressJob?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
