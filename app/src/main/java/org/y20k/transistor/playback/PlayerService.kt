/*
 * PlayerService.kt
 * Implements the PlayerService class
 * PlayerService is Transistor's foreground service that plays radio station audio
 *
 * This file is part of
 * TRANSISTOR - Radio App for Android
 *
 * Copyright (c) 2015-22 - Y20K.org
 * Licensed under the MIT-License
 * http://opensource.org/licenses/MIT
 */


package org.y20k.transistor.playback

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.ResultReceiver
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.MediaBrowserServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Main
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.y20k.transistor.Keys
import org.y20k.transistor.R
import org.y20k.transistor.collection.CollectionProvider
import org.y20k.transistor.core.Collection
import org.y20k.transistor.core.Station
import org.y20k.transistor.helpers.*
import org.y20k.transistor.ui.PlayerState
import java.util.*
import kotlin.math.min


/*
 * PlayerService class - 纯 libVLC 底层重构版
 */
class PlayerService(): MediaBrowserServiceCompat() {

    private val TAG: String = LogHelper.makeLogTag(PlayerService::class.java)

    private var collection: Collection = Collection()
    private var collectionProvider: CollectionProvider = CollectionProvider()
    private var station: Station = Station()
    private var isForegroundService: Boolean = false
    private lateinit var playerState: PlayerState
    private lateinit var metadataHistory: MutableList<String>
    private lateinit var packageValidator: PackageValidator
    protected lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var modificationDate: Date
    private lateinit var collectionChangedReceiver: BroadcastReceiver
    private lateinit var sleepTimer: CountDownTimer
    private var sleepTimerTimeRemaining: Long = 0L
    private var playbackRestartCounter: Int = 0

    // 👉 纯 libVLC 核心引擎（完全替代 ExoPlayer）
    private val libVLC: LibVLC by lazy {
        val options = ArrayList<String>()
        options.add("--no-audio-time-stretch")
        options.add("--avcodec-codec=any")
        options.add("--network-caching=3000") // 增加网络缓存应对直播流抖动
        options.add("--live-caching=3000")
        LibVLC(this, options)
    }

    private val vlcPlayer: MediaPlayer by lazy {
        MediaPlayer(libVLC).apply {
            setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        handlePlaybackChange(PlaybackStateCompat.STATE_PLAYING)
                    }
                    MediaPlayer.Event.Stopped, MediaPlayer.Event.EncounteredError -> {
                        handlePlaybackChange(PlaybackStateCompat.STATE_STOPPED)
                        if (event.type == MediaPlayer.Event.EncounteredError) {
                            LogHelper.e(TAG, "libVLC encountered playback error.")
                            android.os.Handler(mainLooper).post {
                                Toast.makeText(applicationContext, "播放失败: 无法解析该流", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    MediaPlayer.Event.Buffering -> {
                        handlePlaybackChange(PlaybackStateCompat.STATE_BUFFERING)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        modificationDate = PreferencesHelper.loadCollectionModificationDate()
        packageValidator = PackageValidator(this, R.xml.allowed_media_browser_callers)
        playerState = PreferencesHelper.loadPlayerState()
        metadataHistory = PreferencesHelper.loadMetadataHistory()

        createMediaSession()
        notificationHelper = NotificationHelper(this, mediaSession.sessionToken, null)
        collectionChangedReceiver = createCollectionChangedReceiver()
        LocalBroadcastManager.getInstance(application).registerReceiver(collectionChangedReceiver, IntentFilter(Keys.ACTION_COLLECTION_CHANGED))

        collection = FileHelper.readCollection(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent != null && intent.action == Keys.ACTION_STOP) {
            stopPlayback()
        }
        if (intent != null && intent.action == Keys.ACTION_START) {
            if (intent.hasExtra(Keys.EXTRA_STATION_UUID)) {
                val stationUuid: String = intent.getStringExtra(Keys.EXTRA_STATION_UUID) ?: String()
                station = CollectionHelper.getStation(collection, stationUuid)
            } else if(intent.hasExtra(Keys.EXTRA_STREAM_URI)) {
                val streamUri: String = intent.getStringExtra(Keys.EXTRA_STREAM_URI) ?: String()
                station = CollectionHelper.getStationWithStreamUri(collection, streamUri)
            } else {
                station = CollectionHelper.getStation(collection, playerState.stationUuid)
            }
            if (station.isValid()) {
                preparePlayer(true)
            }
        }
        return Service.START_STICKY_COMPATIBILITY
    }

    override fun onDestroy() {
        stopPlayback()
        vlcPlayer.release()
        libVLC.release()
        mediaSession.run {
            isActive = false
            release()
        }
        super.onDestroy()
    }

    private fun stopPlayback() {
        if (vlcPlayer.isPlaying) {
            vlcPlayer.stop()
        }
        handlePlaybackChange(PlaybackStateCompat.STATE_STOPPED)
    }

    private fun isPlaying(): Boolean {
        return vlcPlayer.isPlaying
    }

    private fun updateMetadata(metadata: String?) {
        val metadataString: String = if (metadata != null && metadata.isNotEmpty()) {
            metadata.substring(0, min(metadata.length, Keys.DEFAULT_MAX_LENGTH_OF_METADATA_ENTRY))
        } else {
            station.name
        }
        if (metadataHistory.contains(metadataString)) {
            metadataHistory.removeIf { it == metadataString }
        }
        metadataHistory.add(metadataString)
        if (metadataHistory.size > Keys.DEFAULT_SIZE_OF_METADATA_HISTORY) {
            metadataHistory.removeAt(0)
        }
        notificationHelper.updateNotification()
        PreferencesHelper.saveMetadataHistory(metadataHistory)
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        if (!packageValidator.isKnownCaller(clientPackageName, clientUid)) {
            return BrowserRoot(Keys.MEDIA_BROWSER_ROOT_EMPTY, null)
        } else {
            return BrowserRoot(Keys.MEDIA_BROWSER_ROOT, null)
        }
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        if (!collectionProvider.isInitialized()) {
            result.detach()
            collectionProvider.retrieveMedia(this, collection, object: CollectionProvider.CollectionProviderCallback {
                override fun onStationListReady(success: Boolean) {
                    if (success) {
                        loadChildren(parentId, result)
                    }
                }
            })
        } else {
            loadChildren(parentId, result)
        }
    }

    private fun handlePlaybackChange(playbackState: Int) {
        playbackRestartCounter = 0
        collection = CollectionHelper.savePlaybackState(this, collection, station, playbackState)
        updatePlayerState(station, playbackState)
        if (isPlaying()) {
            // 触发前台通知展示
        } else {
            updateMetadata(null)
        }
    }

    private fun createMediaSession() {
        val sessionActivityPendingIntent = packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
            PendingIntent.getActivity(this, 0, sessionIntent, PendingIntent.FLAG_IMMUTABLE)
        }
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setSessionActivity(sessionActivityPendingIntent)
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
    }

    /* 底层完全交给 libVLC 解析：完美注入 UA、防盗链 Referer 并支持任意刁钻的 TS/M3U8 切片 */
    private fun preparePlayer(playWhenReady: Boolean) {
        if (!station.isValid()) {
            LogHelper.e(TAG, "Unable to start playback. No radio station has been loaded.")
            return
        }

        if (vlcPlayer.isPlaying) {
            vlcPlayer.stop()
        }

        val streamUriString = station.getStreamUri()
        try {
            val media = Media(libVLC, Uri.parse(streamUriString))
            
            // 核心反防盗链与 UA 伪装参数
            val customUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            media.addOption(":http-user-agent=$customUserAgent")
            media.addOption(":http-referrer=https://v.myalicdn.com/")
            media.addOption(":network-caching=3000")
            media.addOption(":rtsp-tcp") // 如果涉及底层封装容错
            
            vlcPlayer.media = media
            media.release()

            if (playWhenReady) {
                vlcPlayer.play()
            }
            updateMetadata(station.name)
        } catch (e: Exception) {
            LogHelper.e(TAG, "Failed to prepare pure libVLC media: $e")
        }
    }

    private fun startSleepTimer() {
        if (sleepTimerTimeRemaining > 0L && this::sleepTimer.isInitialized) {
            sleepTimer.cancel()
        }
        sleepTimer = object:CountDownTimer(Keys.SLEEP_TIMER_DURATION + sleepTimerTimeRemaining, Keys.SLEEP_TIMER_INTERVAL) {
            override fun onFinish() {
                sleepTimerTimeRemaining = 0L
                stopPlayback()
            }
            override fun onTick(millisUntilFinished: Long) {
                sleepTimerTimeRemaining = millisUntilFinished
            }
        }
        sleepTimer.start()
    }

    private fun cancelSleepTimer() {
        if (this::sleepTimer.isInitialized) {
            sleepTimerTimeRemaining = 0L
            sleepTimer.cancel()
        }
    }

    private fun loadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val mediaItems = ArrayList<MediaBrowserCompat.MediaItem>()
        when (parentId) {
            Keys.MEDIA_BROWSER_ROOT -> {
                collectionProvider.stationListByName.forEach { item ->
                    mediaItems.add(item)
                }
            }
        }
        result.sendResult(mediaItems)
    }

    private fun createCollectionChangedReceiver(): BroadcastReceiver {
        return object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.hasExtra(Keys.EXTRA_COLLECTION_MODIFICATION_DATE)) {
                    val date: Date = Date(intent.getLongExtra(Keys.EXTRA_COLLECTION_MODIFICATION_DATE, 0L))
                    if (date.after(collection.modificationDate)) {
                        loadCollection(context)
                    }
                }
            }
        }
    }

    private fun loadCollection(context: Context) {
        CoroutineScope(Main).launch {
            val deferred: Deferred<Collection> = async(Dispatchers.Default) { FileHelper.readCollectionSuspended(context) }
            collection = deferred.await()
            if (playerState.playbackState == PlaybackState.STATE_PLAYING && station.name == getCurrentMetadata()) {
                station = CollectionHelper.getStation(collection, station.uuid)
                updateMetadata(null)
            }
        }
    }

    private fun updatePlayerState(station: Station, playbackState: Int) {
        if (station.isValid()) {
            playerState.stationUuid = station.uuid
        }
        playerState.playbackState = playbackState
        PreferencesHelper.savePlayerState(playerState)
    }

    private fun getCurrentMetadata(): String {
        return if (metadataHistory.isEmpty()) {
            station.name
        } else {
            metadataHistory.last()
        }
    }
}
