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
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.ui.PlayerNotificationManager
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
 * PlayerService class
 */
class PlayerService(): MediaBrowserServiceCompat() {


    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(PlayerService::class.java)


    /* Main class variables */
    private var collection: Collection = Collection()
    private var collectionProvider: CollectionProvider = CollectionProvider()
    private var station: Station = Station()
    private var isForegroundService: Boolean = false
    private lateinit var forwardingPlayer: ForwardingPlayer
    private lateinit var playerState: PlayerState
    private lateinit var metadataHistory: MutableList<String>
    private lateinit var packageValidator: PackageValidator
    protected lateinit var mediaSession: MediaSessionCompat
    protected lateinit var mediaSessionConnector: MediaSessionConnector
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var modificationDate: Date
    private lateinit var collectionChangedReceiver: BroadcastReceiver
    private lateinit var sleepTimer: CountDownTimer
    private var sleepTimerTimeRemaining: Long = 0L
    private var playbackRestartCounter: Int = 0

    // 👉 libVLC 核心实例
    private val libVLC: LibVLC by lazy {
        val options = ArrayList<String>()
        options.add("--no-audio-time-stretch")
        options.add("--avcodec-codec=any")
        LibVLC(this, options)
    }

    // 👉 libVLC 播放器实例
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
                            android.os.Handler(mainLooper).post {
                                Toast.makeText(applicationContext, "VLC 播放出错", Toast.LENGTH_LONG).show()
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


    /* Overrides onCreate from Service */
    override fun onCreate() {
        super.onCreate()

        // load modification date of collection
        modificationDate = PreferencesHelper.loadCollectionModificationDate()

        // get the package validator
        packageValidator = PackageValidator(this, R.xml.allowed_media_browser_callers)

        // fetch the player state
        playerState = PreferencesHelper.loadPlayerState()

        // fetch the metadata history
        metadataHistory = PreferencesHelper.loadMetadataHistory()

        // create a new MediaSession
        createMediaSession()

        // create custom ForwardingPlayer used in Notification and playback control
        forwardingPlayer = createForwardingPlayer()

        // MediaSessionConnector setup for libVLC integration
        mediaSessionConnector = MediaSessionConnector(mediaSession)
        mediaSessionConnector.setPlaybackPreparer(preparer)
        mediaSessionConnector.setQueueNavigator(object : TimelineQueueNavigator(mediaSession) {
            override fun getMediaDescription(player: Player, windowIndex: Int): MediaDescriptionCompat {
                return CollectionHelper.buildStationMediaDescription(this@PlayerService, station, getCurrentMetadata())
            }
        })

        // initialize notification helper
        notificationHelper = NotificationHelper(this, mediaSession.sessionToken, notificationListener)

        // create and register collection changed receiver
        collectionChangedReceiver = createCollectionChangedReceiver()
        LocalBroadcastManager.getInstance(application).registerReceiver(collectionChangedReceiver, IntentFilter(Keys.ACTION_COLLECTION_CHANGED))

        // load collection
        collection = FileHelper.readCollection(this)
    }


    /* Overrides onStartCommand from Service */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent != null && intent.action == Keys.ACTION_STOP) {
            vlcPlayer.stop()
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


    /* Overrides onDestroy from Service */
    override fun onDestroy() {
        if (vlcPlayer.isPlaying) {
            handlePlaybackChange(PlaybackStateCompat.STATE_STOPPED)
        }
        vlcPlayer.stop()
        vlcPlayer.release()
        libVLC.release()
        mediaSession.run {
            isActive = false
            release()
        }
        super.onDestroy()
    }


    /* Updates metadata */
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
        mediaSessionConnector.invalidateMediaSessionQueue()
        mediaSessionConnector.invalidateMediaSessionMetadata()
        notificationHelper.updateNotification()
        PreferencesHelper.saveMetadataHistory(metadataHistory)
    }


    /* Overrides onGetRoot from MediaBrowserService */
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        if (!packageValidator.isKnownCaller(clientPackageName, clientUid)) {
            return BrowserRoot(Keys.MEDIA_BROWSER_ROOT_EMPTY, null)
        } else {
            val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
            val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
            val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
            val CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1
            val CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 2
            val rootExtras = bundleOf(
                    CONTENT_STYLE_SUPPORTED to true,
                    CONTENT_STYLE_BROWSABLE_HINT to CONTENT_STYLE_GRID_ITEM_HINT_VALUE,
                    CONTENT_STYLE_PLAYABLE_HINT to CONTENT_STYLE_LIST_ITEM_HINT_VALUE
            )
            val isRecentRequest = rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT) ?: false
            val browserRootPath: String = if (isRecentRequest) Keys.MEDIA_BROWSER_ROOT_RECENT else Keys.MEDIA_BROWSER_ROOT
            return BrowserRoot(browserRootPath, rootExtras)
        }
    }


    /* Overrides onLoadChildren from MediaBrowserService */
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


    /* Updates media session and save state */
    private fun handlePlaybackChange(playbackState: Int) {
        playbackRestartCounter = 0
        collection = CollectionHelper.savePlaybackState(this, collection, station, playbackState)
        updatePlayerState(station, playbackState)
        if (vlcPlayer.isPlaying) {
            notificationHelper.showNotificationForPlayer(forwardingPlayer)
        } else {
            updateMetadata(null)
        }
    }


    /* Try to restart Playback */
    private fun handlePlaybackEnded() {
        if (playbackRestartCounter < 5) {
            playbackRestartCounter++
            vlcPlayer.stop()
            vlcPlayer.play()
        } else {
            vlcPlayer.stop()
            Toast.makeText(this, this.getString(R.string.toastmessage_error_restart_playback_failed), Toast.LENGTH_LONG).show()
        }
    }


    /* Creates a forwardingPlayer adapter for libVLC */
    private fun createForwardingPlayer() : ForwardingPlayer {
        // 使用一个轻量适配层让 ForwardingPlayer 能够控制 libVLC 播放器
        return object : ForwardingPlayer(PlayerStub(vlcPlayer)) {
            override fun stop(reset: Boolean) {
                stop()
            }
            override fun stop() {
                vlcPlayer.stop()
                notificationHelper.hideNotification()
            }
            override fun pause() {
                vlcPlayer.stop()
            }
            override fun seekForward() {
                seekToNext()
            }
            override fun seekBack() {
                seekToPrevious()
            }
            override fun seekToNext() {
                if (vlcPlayer.isPlaying) vlcPlayer.stop()
                station = CollectionHelper.getNextStation(collection, station.uuid)
                preparer.onPrepare(true)
            }
            override fun seekToPrevious() {
                if (vlcPlayer.isPlaying) vlcPlayer.stop()
                station = CollectionHelper.getPreviousStation(collection, station.uuid)
                preparer.onPrepare(true)
            }
        }
    }


    /* Creates a new MediaSession */
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


    /* Prepares player with media source created from current station using libVLC */
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
            
            // 注入浏览器 User-Agent 以及阿里云 CDN 防盗链 Referer/Origin 请求头
            val customUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            media.addOption(":http-user-agent=$customUserAgent")
            media.addOption(":http-referrer=https://v.myalicdn.com/")
            media.addOption(":network-caching=1500")
            
            vlcPlayer.media = media
            media.release()

            if (playWhenReady) {
                vlcPlayer.play()
            }
            updateMetadata(station.name)
        } catch (e: Exception) {
            LogHelper.e(TAG, "Failed to prepare libVLC media: $e")
        }
    }


    /* Starts sleep timer */
    private fun startSleepTimer() {
        if (sleepTimerTimeRemaining > 0L && this::sleepTimer.isInitialized) {
            sleepTimer.cancel()
        }
        sleepTimer = object:CountDownTimer(Keys.SLEEP_TIMER_DURATION + sleepTimerTimeRemaining, Keys.SLEEP_TIMER_INTERVAL) {
            override fun onFinish() {
                sleepTimerTimeRemaining = 0L
                vlcPlayer.stop()
            }
            override fun onTick(millisUntilFinished: Long) {
                sleepTimerTimeRemaining = millisUntilFinished
            }
        }
        sleepTimer.start()
    }


    /* Cancels sleep timer */
    private fun cancelSleepTimer() {
        if (this::sleepTimer.isInitialized) {
            sleepTimerTimeRemaining = 0L
            sleepTimer.cancel()
        }
    }


    /* Loads media items into result */
    private fun loadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val mediaItems = ArrayList<MediaBrowserCompat.MediaItem>()
        when (parentId) {
            Keys.MEDIA_BROWSER_ROOT -> {
                collectionProvider.stationListByName.forEach { item ->
                    mediaItems.add(item)
                }
            }
            Keys.MEDIA_BROWSER_ROOT_RECENT -> {}
            Keys.MEDIA_BROWSER_ROOT_EMPTY -> {}
            else -> {}
        }
        result.sendResult(mediaItems)
    }


    /* Creates collection changed receiver */
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


    /* Reads collection */
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


    /* Updates player state */
    private fun updatePlayerState(station: Station, playbackState: Int) {
        if (station.isValid()) {
            playerState.stationUuid = station.uuid
        }
        playerState.playbackState = playbackState
        PreferencesHelper.savePlayerState(playerState)
    }


    /* Gets current metadata */
    private fun getCurrentMetadata(): String {
        return if (metadataHistory.isEmpty()) {
            station.name
        } else {
            metadataHistory.last()
        }
    }


    /* Notification listener */
    private val notificationListener = object : PlayerNotificationManager.NotificationListener {
        override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
            super.onNotificationCancelled(notificationId, dismissedByUser)
            stopForeground(true)
            isForegroundService = false
            stopSelf()
        }

        override fun onNotificationPosted(notificationId: Int, notification: Notification, ongoing: Boolean) {
            super.onNotificationPosted(notificationId, notification, ongoing)
            if (ongoing && !isForegroundService) {
                ContextCompat.startForegroundService(applicationContext, Intent(applicationContext, this@PlayerService.javaClass))
                startForeground(Keys.NOW_PLAYING_NOTIFICATION_ID, notification)
                isForegroundService = true
            }
        }
    }


    /* PlaybackPreparer */
    private val preparer = object : MediaSessionConnector.PlaybackPreparer {
        override fun getSupportedPrepareActions(): Long =
                PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
                        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                        PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH or
                        PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH

        override fun onPrepareFromUri(uri: Uri, playWhenReady: Boolean, extras: Bundle?) = Unit

        override fun onPrepare(playWhenReady: Boolean) {
            if (station.isValid()) {
                preparePlayer(playWhenReady)
            } else {
                val currentStationUuid: String = PreferencesHelper.loadLastPlayedStationUuid()
                onPrepareFromMediaId(currentStationUuid, playWhenReady, null)
            }
        }

        override fun onPrepareFromMediaId(mediaId: String, playWhenReady: Boolean, extras: Bundle?) {
            station = CollectionHelper.getStation(collection, mediaId ?: String())
            preparePlayer(playWhenReady)
        }

        override fun onPrepareFromSearch(query: String, playWhenReady: Boolean, extras: Bundle?) {
            if (query.isEmpty()) {
                val stationMediaItem: MediaBrowserCompat.MediaItem? = collectionProvider.getFirstStation()
                if (stationMediaItem != null) {
                    onPrepareFromMediaId(stationMediaItem.mediaId!!, playWhenReady = true, extras = null)
                }
            } else {
                val queryLowercase: String = query.lowercase(Locale.getDefault())
                collectionProvider.stationListByName.forEach { mediaItem ->
                    val stationName: String = mediaItem.description.title.toString().lowercase(Locale.getDefault())
                    if (stationName == queryLowercase) {
                        onPrepareFromMediaId(mediaItem.description.mediaId!!, playWhenReady = true, extras = null)
                        return
                    }
                }
            }
        }

        override fun onCommand(player: Player, command: String, extras: Bundle?, cb: ResultReceiver?): Boolean {
            when (command) {
                Keys.CMD_RELOAD_PLAYER_STATE -> {
                    playerState = PreferencesHelper.loadPlayerState()
                    return true
                }
                Keys.CMD_REQUEST_PROGRESS_UPDATE -> {
                    if (cb != null) {
                        if (station.isValid()) {
                            val playbackProgressBundle: Bundle = bundleOf(Keys.RESULT_DATA_METADATA to metadataHistory)
                            if (sleepTimerTimeRemaining > 0L) {
                                playbackProgressBundle.putLong(Keys.RESULT_DATA_SLEEP_TIMER_REMAINING, sleepTimerTimeRemaining)
                            }
                            cb.send(Keys.RESULT_CODE_PERIODIC_PROGRESS_UPDATE, playbackProgressBundle)
                            return true
                        }
                    }
                    return false
                }
                Keys.CMD_START_SLEEP_TIMER -> {
                    startSleepTimer()
                    return true
                }
                Keys.CMD_CANCEL_SLEEP_TIMER -> {
                    cancelSleepTimer()
                    return true
                }
                Keys.CMD_PLAY_STREAM -> {
                    val streamUri: String = extras?.getString(Keys.KEY_STREAM_URI) ?: String()
                    station = CollectionHelper.getStationWithStreamUri(collection, streamUri)
                    preparePlayer(true)
                    return true
                }
                else -> return false
            }
        }
    }


    /* AudioFX Analytics */
    private var analyticsListener = object: AnalyticsListener {
        override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
            super.onAudioSessionIdChanged(eventTime, audioSessionId)
            val intent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
            intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            sendBroadcast(intent)
        }
    }
}
