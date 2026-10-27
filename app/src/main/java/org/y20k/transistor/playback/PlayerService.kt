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
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.metadata.Metadata
import com.google.android.exoplayer2.metadata.icy.IcyHeaders
import com.google.android.exoplayer2.metadata.icy.IcyInfo
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.util.Util
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Main
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
    private lateinit var userAgent: String
    private lateinit var modificationDate: Date
    private lateinit var collectionChangedReceiver: BroadcastReceiver
    private lateinit var sleepTimer: CountDownTimer
    private var sleepTimerTimeRemaining: Long = 0L
    private var playbackRestartCounter: Int = 0

    private val attributes = AudioAttributes.Builder()
            .setContentType(C.CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

    // 👉 已集成 DefaultRenderersFactory 并开启 FFmpeg 软解扩展优先，确保万能解码兼容性
    private val player: ExoPlayer by lazy {
        val renderersFactory = DefaultRenderersFactory(this).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }
        ExoPlayer.Builder(this, renderersFactory).build().apply {
            setAudioAttributes(attributes, true)
            setHandleAudioBecomingNoisy(true)
            repeatMode = Player.REPEAT_MODE_ALL
            addListener(playerListener)
            addAnalyticsListener(analyticsListener)
        }
    }


    /* Overrides onCreate from Service */
    override fun onCreate() {
        super.onCreate()
        // set user agent
        userAgent = Util.getUserAgent(this, Keys.APPLICATION_NAME)

        // load modification date of collection
        modificationDate = PreferencesHelper.loadCollectionModificationDate()

        // get the package validator // todo can be local?
        packageValidator = PackageValidator(this, R.xml.allowed_media_browser_callers)

        // fetch the player state
        playerState = PreferencesHelper.loadPlayerState()

        // fetch the metadata history
        metadataHistory = PreferencesHelper.loadMetadataHistory()

        // create a new MediaSession
        createMediaSession()

        // create custom ForwardingPlayer used in Notification and playback control
        forwardingPlayer = createForwardingPlayer()

        // ExoPlayer manages MediaSession
        mediaSessionConnector = MediaSessionConnector(mediaSession)
        mediaSessionConnector.setPlaybackPreparer(preparer)
        mediaSessionConnector.setQueueNavigator(object : TimelineQueueNavigator(mediaSession) {
            override fun getMediaDescription(player: Player, windowIndex: Int): MediaDescriptionCompat {
                // create media description - used in notification
                 return CollectionHelper.buildStationMediaDescription(this@PlayerService, station, getCurrentMetadata())
            }
        })

        // initialize notification helper
        notificationHelper = NotificationHelper(this, mediaSession.sessionToken, notificationListener)
//        notificationHelper.showNotificationForPlayer(forwardingPlayer)

        // create and register collection changed receiver
        collectionChangedReceiver = createCollectionChangedReceiver()
        LocalBroadcastManager.getInstance(application).registerReceiver(collectionChangedReceiver, IntentFilter(Keys.ACTION_COLLECTION_CHANGED))

        // load collection
        collection = FileHelper.readCollection(this)
    }


    /* Overrides onStartCommand from Service */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // handle start/stop requests issued via Intent - used for example by the home screen shortcuts
        if (intent != null && intent.action == Keys.ACTION_STOP) {
            player.stop()
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


    /* Overrides onTaskRemoved from Service */
    override fun onTaskRemoved(rootIntent: Intent) {
        super.onTaskRemoved(rootIntent)
        // kill service, if MainActivity was canceled through task switcher
        //stopSelf()
    }


    /* Overrides onDestroy from Service */
    override fun onDestroy() {
        // set playback state if necessary
        if (player.isPlaying) {
            handlePlaybackChange(PlaybackStateCompat.STATE_STOPPED)
        }
        // release media session
        mediaSession.run {
            isActive = false
            release()
        }
        // release player
        player.removeAnalyticsListener(analyticsListener)
        player.removeListener(playerListener)
        player.release()
    }


    /* Updates metadata */
    private fun updateMetadata(metadata: String?) {
        // get metadata string
        val metadataString: String
        if (metadata != null && metadata.isNotEmpty()) {
            metadataString = metadata.substring(0, min(metadata.length, Keys.DEFAULT_MAX_LENGTH_OF_METADATA_ENTRY))
        } else {
            metadataString = station.name
        }
        // append metadata to metadata history
        if (metadataHistory.contains(metadataString)) {
            metadataHistory.removeIf { it == metadataString }
        }
        metadataHistory.add(metadataString)
        // trim metadata list
        if (metadataHistory.size > Keys.DEFAULT_SIZE_OF_METADATA_HISTORY) {
            metadataHistory.removeAt(0)
        }
        // update notification
        // see: https://github.com/google/ExoPlayer/issues/5494#issuecomment-462476576
        mediaSessionConnector.invalidateMediaSessionQueue()
        mediaSessionConnector.invalidateMediaSessionMetadata()
        notificationHelper.updateNotification()
        // save history
        PreferencesHelper.saveMetadataHistory(metadataHistory)
    }


    /* Overrides onGetRoot from MediaBrowserService */ // todo: implement a hierarchical structure -> https://github.com/googlesamples/android-UniversalMusicPlayer/blob/47da058112cee0b70442bcd0370c1e46e830c66b/media/src/main/java/com/example/android/uamp/media/library/BrowseTree.kt
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        // Credit: https://github.com/googlesamples/android-UniversalMusicPlayer (->  MusicService)
        // LogHelper.d(TAG, "OnGetRoot: clientPackageName=$clientPackageName; clientUid=$clientUid ; rootHints=$rootHints")
        // to ensure you are not allowing any arbitrary app to browse your app's contents, you need to check the origin
        if (!packageValidator.isKnownCaller(clientPackageName, clientUid)) {
            // request comes from an untrusted package
            LogHelper.i(TAG, "OnGetRoot: Browsing NOT ALLOWED for unknown caller. "
                    + "Returning empty browser root so all apps can use MediaController."
                    + clientPackageName)
            return BrowserRoot(Keys.MEDIA_BROWSER_ROOT_EMPTY, null)
        } else {
            // content style extras: see https://developer.android.com/training/cars/media#apply_content_style
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
            // check if rootHints contained EXTRA_RECENT - return BrowserRoot with MEDIA_BROWSER_ROOT_RECENT in that case
            val isRecentRequest = rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT) ?: false
            val browserRootPath: String = if (isRecentRequest) Keys.MEDIA_BROWSER_ROOT_RECENT else Keys.MEDIA_BROWSER_ROOT
            return BrowserRoot(browserRootPath, rootExtras)
        }
    }


    /* Overrides onLoadChildren from MediaBrowserService */
    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        if (!collectionProvider.isInitialized()) {
            // use result.detach to allow calling result.sendResult from another thread:
            result.detach()
            collectionProvider.retrieveMedia(this, collection, object: CollectionProvider.CollectionProviderCallback {
                override fun onStationListReady(success: Boolean) {
                    if (success) {
                        loadChildren(parentId, result)
                    }
                }
            })
        } else {
            // if music catalog is already loaded/cached, load them into result immediately
            loadChildren(parentId, result)
        }
    }


    /* Updates media session and save state */
    private fun handlePlaybackChange(playbackState: Int) {
        // reset restart counter
        playbackRestartCounter = 0
        // save collection state and player state
        collection = CollectionHelper.savePlaybackState(this, collection, station, playbackState)
        updatePlayerState(station, playbackState)
        if (player.isPlaying) {
            notificationHelper.showNotificationForPlayer(forwardingPlayer)
        } else {
            updateMetadata(null)
        }
    }


    /* Try to restart Playback */
    private fun handlePlaybackEnded() {
        // restart playback for up to five times
        if (playbackRestartCounter < 5) {
            playbackRestartCounter++
            player.stop()
            player.play()
        } else {
            player.stop()
            Toast.makeText(this, this.getString(R.string.toastmessage_error_restart_playback_failed), Toast.LENGTH_LONG).show()
        }
    }


    /* Creates a forwardingPlayer that overrides default exoplayer behavior */
    private fun createForwardingPlayer() : ForwardingPlayer {
        return object : ForwardingPlayer(player) {
            // emulate headphone buttons
            // start/pause: adb shell input keyevent 85
            // next: adb shell input keyevent 87
            // prev: adb shell input keyevent 88
            override fun stop(reset: Boolean) {
                stop()
            }
            override fun stop() {
                player.stop()
                notificationHelper.hideNotification()
            }
            override fun pause() {
                player.stop() /* pause command translates internally to stop() */
            }
            override fun seekForward() {
                seekToNext()
            }
            override fun seekBack() {
                seekToPrevious()
            }
            override fun seekToNext() {
                // stop current playback, if necessary
                if (player.isPlaying) player.stop()
                // get station start playback
                station = CollectionHelper.getNextStation(collection, station.uuid)
                preparer.onPrepare(true)
            }
            override fun seekToPrevious() {
                // stop current playback, if necessary
                if (player.isPlaying) player.stop()
                // get station start playback
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


    /* Prepares player with media source created from current station */
    private fun preparePlayer(playWhenReady: Boolean) {
        // sanity check
        if (!station.isValid()) {
            LogHelper.e(TAG, "Unable to start playback. No radio station has been loaded.")
            return
        }

        // stop playback if necessary
        if (player.isPlaying) { player.stop() }

        // build media item with explicit mime type for m3u8 streams to avoid detection issues
        val streamUriString = station.getStreamUri()
        val mediaItemBuilder = MediaItem.Builder().setUri(streamUriString)
        if (streamUriString.contains(".m3u8", ignoreCase = true)) {
            mediaItemBuilder.setMimeType(com.google.android.exoplayer2.util.MimeTypes.APPLICATION_M3U8)
        }
        val mediaItem: MediaItem = mediaItemBuilder.build()

        // create DataSource.Factory with browser User-Agent and CDN Referer anti-leech headers
        val customUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)
