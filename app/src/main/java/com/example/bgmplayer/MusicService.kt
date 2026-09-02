package com.example.bgmplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class MusicService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private var notificationId = 1
    private var channelId = "bgm_playback"

    companion object {
        const val ACTION_SHUFFLE = "com.example.bgmplayer.ACTION_SHUFFLE"
        const val ACTION_PREV = "com.example.bgmplayer.ACTION_PREV"
        const val ACTION_PLAY_PAUSE = "com.example.bgmplayer.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.bgmplayer.ACTION_NEXT"
        const val ACTION_REPEAT = "com.example.bgmplayer.ACTION_REPEAT"
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PLAY_PAUSE -> {
                    if (player.isPlaying) player.pause() else player.play()
                }
                ACTION_NEXT -> player.seekToNext()
                ACTION_PREV -> player.seekToPrevious()
                ACTION_SHUFFLE -> {
                    player.shuffleModeEnabled = !player.shuffleModeEnabled
                }
                ACTION_REPEAT -> {
                    player.repeatMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }
            }
            updateNotificationProgress()
        }
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateNotificationProgress()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player).build()

        val filter = IntentFilter().apply {
            addAction(ACTION_SHUFFLE)
            addAction(ACTION_PREV)
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_REPEAT)
        }
        registerReceiver(controlReceiver, filter)
        handler.post(progressUpdater)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Music playback controls"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createAction(action: String): PendingIntent {
        val intent = Intent(action).setPackage(packageName)
        return PendingIntent.getBroadcast(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun updateNotificationProgress() {
        val title = player.currentMediaItem?.localConfiguration?.uri?.lastPathSegment ?: "No song"
        val position = player.currentPosition.toInt().coerceAtLeast(0)
        val duration = player.duration.toInt().coerceAtLeast(1)
        val isPlaying = player.isPlaying

        val shuffleIcon = if (player.shuffleModeEnabled) android.R.drawable.ic_media_play else android.R.drawable.ic_media_play
        val repeatIcon = when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> android.R.drawable.ic_media_play
            Player.REPEAT_MODE_ALL -> android.R.drawable.ic_media_play
            else -> android.R.drawable.ic_media_play
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(if (isPlaying) "Playing" else "Paused")
            .setProgress(duration, position, false)
            .setOngoing(true)
            .setSilent(true)
            .addAction(shuffleIcon, "Shuffle", createAction(ACTION_SHUFFLE))
            .addAction(android.R.drawable.ic_media_previous, "Previous", createAction(ACTION_PREV))
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                createAction(ACTION_PLAY_PAUSE)
            )
            .addAction(android.R.drawable.ic_media_next, "Next", createAction(ACTION_NEXT))
            .addAction(repeatIcon, "Repeat", createAction(ACTION_REPEAT))
            .build()

        notificationManager.notify(notificationId, notification)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(progressUpdater)
        handler.post(progressUpdater)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressUpdater)
        unregisterReceiver(controlReceiver)
        notificationManager.cancel(notificationId)
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
