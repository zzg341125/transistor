/*
 * CollectionHelper.kt
 * Implements the CollectionHelper object
 * A CollectionHelper provides helper methods for the collection of stations
 *
 * This file is part of
 * TRANSISTOR - Radio App for Android
 *
 * Copyright (c) 2015-22 - Y20K.org
 * Licensed under the MIT-License
 * http://opensource.org/licenses/MIT
 */

package org.y20k.transistor.helpers

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import androidx.core.net.toUri
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import org.y20k.transistor.Keys
import org.y20k.transistor.R
import org.y20k.transistor.core.Collection
import org.y20k.transistor.core.Station
import org.y20k.transistor.search.RadioBrowserResult
import java.io.File
import java.net.URL
import java.util.*

/*
 * CollectionHelper object
 */
object CollectionHelper {

    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(CollectionHelper::class.java)

    /* Checks if station is already in collection */
    fun isNewStation(collection: Collection, station: Station): Boolean {
        collection.stations.forEach {
            if (it.getStreamUri() == station.getStreamUri()) return false
        }
        return true
    }

    /* Checks if station is already in collection */
    fun isNewStation(collection: Collection, remoteStationLocation: String): Boolean {
        collection.stations.forEach {
            if (it.remoteStationLocation == remoteStationLocation) return false
        }
        return true
    }

    /* Checks if enough time passed since last update */
    fun hasEnoughTimePassedSinceLastUpdate(): Boolean {
        val lastUpdate: Date = PreferencesHelper.loadLastUpdateCollection()
        val currentDate: Date = Calendar.getInstance().time
        return currentDate.time - lastUpdate.time > Keys.MINIMUM_TIME_BETWEEN_UPDATES
    }

    /* Checks if a newer collection of radio stations is available on storage */
    fun isNewerCollectionAvailable(date: Date): Boolean {
        var newerCollectionAvailable = false
        val modificationDate: Date = PreferencesHelper.loadCollectionModificationDate()
        if (modificationDate.after(date) || date == Keys.DEFAULT_DATE) {
            newerCollectionAvailable = true
        }
        return newerCollectionAvailable
    }

    /* Creates station from previously downloaded playlist file */
    fun createStationFromPlaylistFile(context: Context, localFileUri: Uri, remoteFileLocation: String): Station {
        val station: Station = FileHelper.readStationPlaylist(context.contentResolver.openInputStream(localFileUri))
        if (station.name.isEmpty()) {
            station.name = FileHelper.getFileName(context, localFileUri).substringBeforeLast(".")
        }
        station.remoteStationLocation = remoteFileLocation
        station.remoteImageLocation = CollectionHelper.getFaviconAddress(remoteFileLocation)
        station.modificationDate = GregorianCalendar.getInstance().time
        return station
    }

    /* Updates radio station in collection */
    fun updateStation(context: Context, collection: Collection, station: Station): Collection {
        var updatedCollection: Collection = collection

        if (station.radioBrowserStationUuid.isNotEmpty()) {
            updatedCollection.stations.forEach { it ->
                if (it.radioBrowserStationUuid.equals(station.radioBrowserStationUuid)) {
                    it.streamUris[it.stream] = station.getStreamUri()
                    it.streamContent = station.streamContent
                    it.remoteImageLocation = station.remoteImageLocation
                    it.remoteStationLocation = station.remoteStationLocation
                    it.homepage = station.homepage
                    if (!it.nameManuallySet) it.name = station.name
                    if (!it.imageManuallySet && it.remoteImageLocation != station.remoteImageLocation) DownloadHelper.updateStationImage(context, it)
                }
            }
            updatedCollection = sortCollection(updatedCollection)
            saveCollection(context, updatedCollection, false)
        } else if (station.remoteStationLocation.isNotEmpty()) {
            updatedCollection.stations.forEach { it ->
                if (it.remoteStationLocation.equals(station.remoteStationLocation)) {
                    it.streamUris[it.stream] = station.getStreamUri()
                    it.streamContent = station.streamContent
                    it.remoteImageLocation = station.remoteImageLocation
                    if (!it.nameManuallySet) it.name = station.name
                    if (!it.imageManuallySet) DownloadHelper.updateStationImage(context, it)
                }
            }
            updatedCollection = sortCollection(updatedCollection)
            saveCollection(context, updatedCollection, false)
        }

        return updatedCollection
    }

    /* Adds new radio station to collection */
    fun addStation(context: Context, collection: Collection, newStation: Station): Collection {
        val streamUri = newStation.getStreamUri()
        if (streamUri.contains(".m3u8", ignoreCase = true)) {
            newStation.streamContent = Keys.MIME_TYPE_HLS
            if (newStation.modificationDate == Keys.DEFAULT_DATE) {
                newStation.modificationDate = GregorianCalendar.getInstance().time
            }
            if (newStation.name.isEmpty()) {
                newStation.name = "CCTV-13"
            }
        }

        if (!newStation.isValid()) {
            Toast.makeText(context, R.string.toastmessage_station_not_valid, Toast.LENGTH_LONG).show()
            return collection
        } else if (!isNewStation(collection, newStation)) {
            Toast.makeText(context, R.string.toastmessage_station_duplicate, Toast.LENGTH_LONG).show()
            return collection
        } else {
            var updatedCollection: Collection = collection
            val updatedStationList: MutableList<Station> = collection.stations.toMutableList()
            updatedStationList.add(newStation)
            updatedCollection.stations = updatedStationList
            updatedCollection = sortCollection(updatedCollection)
            saveCollection(context, updatedCollection, false)
            DownloadHelper.updateStationImage(context, newStation)
            return updatedCollection
        }
    }

    /* Sets station image - determines station by remote image file location */
    fun setStationImageWithRemoteLocation(context: Context, collection: Collection, tempImageFileUri: String, remoteFileLocation: String, imageManuallySet: Boolean = false): Collection {
        collection.stations.forEach { station ->
            if (station.remoteImageLocation.substringAfter(":") == remoteFileLocation.substringAfter(":")) {
                station.smallImage = FileHelper.saveStationImage(context, station.uuid, tempImageFileUri.toString(), Keys.SIZE_STATION_IMAGE_CARD, Keys.STATION_SMALL_IMAGE_FILE).toString()
                station.image = FileHelper.saveStationImage(context, station.uuid, tempImageFileUri, Keys.SIZE_STATION_IMAGE_MAXIMUM, Keys.STATION_IMAGE_FILE).toString()
                station.imageColor = ImageHelper.getMainColor(context, tempImageFileUri)
                station.imageManuallySet = imageManuallySet
            }
        }
        saveCollection(context, collection)
        return collection
    }

    /* Sets station image - determines station by remote image file location */
    fun setStationImageWithStationUuid(context: Context, collection: Collection, tempImageFileUri: String, stationUuid: String, imageManuallySet: Boolean = false): Collection {
        collection.stations.forEach { station ->
            if (station.uuid == stationUuid) {
                station.smallImage = FileHelper.saveStationImage(context, station.uuid, tempImageFileUri, Keys.SIZE_STATION_IMAGE_CARD, Keys.STATION_SMALL_IMAGE_FILE).toString()
                station.image = FileHelper.saveStationImage(context, station.uuid, tempImageFileUri, Keys.SIZE_STATION_IMAGE_MAXIMUM, Keys.STATION_IMAGE_FILE).toString()
                station.imageColor = ImageHelper.getMainColor(context, tempImageFileUri)
                station.imageManuallySet = imageManuallySet
            }
        }
        saveCollection(context, collection)
        return collection
    }

    /* Clears an image folder for a given station */
    fun clearImagesFolder(context: Context, station: Station) {
        val imagesFolder: File = File(context.getExternalFilesDir(""), FileHelper.determineDestinationFolderPath(Keys.FILE_TYPE_IMAGE, station.uuid))
        FileHelper.clearFolder(imagesFolder, 0)
    }

    /* Deletes Images of a given station */
    fun deleteStationImages(context: Context, station: Station) {
        val imagesFolder: File = File(context.getExternalFilesDir(""), FileHelper.determineDestinationFolderPath(Keys.FILE_TYPE_IMAGE, station.uuid))
        FileHelper.clearFolder(imagesFolder, 0, true)
    }

    /* Get station from collection for given UUID */
    fun getStation(collection: Collection, stationUuid: String): Station {
        collection.stations.forEach { station ->
            if (station.uuid == stationUuid) {
                return station
            }
        }
        if (collection.stations.isNotEmpty()) {
            return collection.stations.first()
        } else {
            return Station()
        }
    }

    /* Get station from collection for given Stream Uri */
    fun getStationWithStreamUri(collection: Collection, streamUri: String): Station {
        collection.stations.forEach { station ->
            if (station.getStreamUri() == streamUri) {
                return station
            }
        }
        if (collection.stations.isNotEmpty()) {
            return collection.stations.first()
        } else {
            return Station()
        }
    }

    /* Gets next station within collection */
    fun getNextStation(collection: Collection, stationUuid: String): Station {
        val currentStationPosition: Int = getStationPosition(collection, stationUuid)
        if (collection.stations.isEmpty() || currentStationPosition == -1) {
            return Station()
        } else if (currentStationPosition < collection.stations.size - 1) {
            return collection.stations[currentStationPosition + 1]
        } else {
            return collection.stations.first()
        }
    }

    /* Gets previous station within collection */
    fun getPreviousStation(collection: Collection, stationUuid: String): Station {
        val currentStationPosition: Int = getStationPosition(collection, stationUuid)
        if (collection.stations.isEmpty() || currentStationPosition == -1) {
            return Station()
        } else if (currentStationPosition > 0) {
            return collection.stations[currentStationPosition - 1]
        } else {
            return collection.stations.last()
        }
    }

    /* Get the position from collection for given UUID */
    fun getStationPosition(collection: Collection, stationUuid: String): Int {
        collection.stations.forEachIndexed { stationId, station ->
            if (station.uuid == stationUuid) {
                return stationId
            }
        }
        return -1
    }

    /* Get the position from collection for given radioBrowserStationUuid */
    fun getStationPositionFromRadioBrowserStationUuid(collection: Collection, radioBrowserStationUuid: String): Int {
        collection.stations.forEachIndexed { stationId, station ->
            if (station.radioBrowserStationUuid == radioBrowserStationUuid) {
                return stationId
            }
        }
        return -1
    }

    /* Get name of station from collection for given UUID */
    fun getStationName(collection: Collection, stationUuid: String): String {
        collection.stations.forEach { station ->
            if (station.uuid == stationUuid) {
                return station.name
            }
        }
        return String()
    }

    /* Saves the playback state of a given station */
    fun savePlaybackState(context: Context, collection: Collection, station: Station, playbackState: Int): Collection {
        collection.stations.forEach { it ->
            it.playbackState = PlaybackStateCompat.STATE_STOPPED
            if (it.uuid == station.uuid) {
                it.playbackState = playbackState
            }
        }
        collection.modificationDate = saveCollection(context, collection)
        PreferencesHelper.savePlayerPlaybackState(playbackState)
        return collection
    }

    /* Saves collection of radio stations */
    fun saveCollection(context: Context, collection: Collection, async: Boolean = true): Date {
        val date: Date = Calendar.getInstance().time
        collection.modificationDate = date
        when (async) {
            true -> {
                CoroutineScope(IO).launch {
                    FileHelper.saveCollectionSuspended(context, collection, date)
                    sendCollectionBroadcast(context, date)
                }
            }
            false -> {
                FileHelper.saveCollection(context, collection, date)
                sendCollectionBroadcast(context, date)
            }
        }
        return date
    }

    /* Export collection of stations as M3U */
    fun exportCollectionM3u(context: Context, collection: Collection) {
        if (collection.stations.size > 0) {
            CoroutineScope(IO).launch { FileHelper.backupCollectionAsM3uSuspended(context, collection) }
        }
    }

    /* Create M3U string from collection of stations */
    fun createM3uString(collection: Collection): String {
        val m3uString = StringBuilder()
        m3uString.append("#EXTM3U\n")
        collection.stations.forEach { station ->
            m3uString.append("\n#EXTINF:-1,")
            m3uString.append(station.name)
            m3uString.append("\n")
            m3uString.append(station.getStreamUri())
            m3uString.append("\n")
        }
        return m3uString.toString()
    }

    /* Sends a broadcast that the radio station collection has changed */
    fun sendCollectionBroadcast(context: Context, modificationDate: Date) {
        val collectionChangedIntent = Intent()
        collectionChangedIntent.action = Keys.ACTION_COLLECTION_CHANGED
        collectionChangedIntent.putExtra(Keys.EXTRA_COLLECTION_MODIFICATION_DATE, modificationDate.time)
        LocalBroadcastManager.getInstance(context).sendBroadcast(collectionChangedIntent)
    }

    /* Creates MediaMetadata for a single station - used in media session*/
    fun buildStationMediaMetadata(context: Context, station: Station, metadata: String): MediaMetadataCompat {
        return MediaMetadataCompat.Builder().apply {
            putString(MediaMetadataCompat.METADATA_KEY_ARTIST, station.name)
            putString(MediaMetadataCompat.METADATA_KEY_TITLE, metadata)
            putString(MediaMetadataCompat.METADATA_KEY_ALBUM, context.getString(R.string.app_name))
            putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, station.getStreamUri())
            putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, ImageHelper.getScaledStationImage(context, station.image, Keys.SIZE_COVER_LOCK_SCREEN))
        }.build()
    }

    /* Creates MediaItem for a station - used by collection provider */
    fun buildStationMediaMetaItem(context: Context, station: Station): MediaBrowserCompat.MediaItem {
        val mediaDescriptionBuilder = MediaDescriptionCompat.Builder()
        mediaDescriptionBuilder.setMediaId(station.uuid)
        mediaDescriptionBuilder.setTitle(station.name)
        mediaDescriptionBuilder.setIconBitmap(ImageHelper.getScaledStationImage(context, station.image, Keys.SIZE_COVER_LOCK_SCREEN))
        return MediaBrowserCompat.MediaItem(mediaDescriptionBuilder.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    /* Creates description for a station - used in MediaSessionConnector */
    fun buildStationMediaDescription(context: Context, station: Station, metadata: String): MediaDescriptionCompat {
        val coverBitmap: Bitmap = ImageHelper.getScaledStationImage(context, station.image, Keys.SIZE_COVER_LOCK_SCREEN)
        val extras: Bundle = Bundle()
        extras.putParcelable(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, coverBitmap)
        extras.putParcelable(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, coverBitmap)
        return MediaDescriptionCompat.Builder().apply {
            setMediaId(station.uuid)
            setIconBitmap(coverBitmap)
            setIconUri(station.image.toUri())
            setTitle(metadata)
            setSubtitle(station.name)
            setExtras(extras)
        }.build()
    }

    /* Creates a fallback station */
    fun createFallbackStation(): Station {
        return Station(name = "KCSB", streamUris = mutableListOf("http://live.kcsb.org:80/KCSB_128"), streamContent = Keys.MIME_TYPE_MPEG)
    }

    /* Sorts radio stations by name */
    fun sortCollection(collection: Collection): Collection {
        collection.stations = collection.stations.sortedWith(compareByDescending<Station> { it.starred }.thenBy { it.name.lowercase(Locale.getDefault()) }) as MutableList<Station>
        return collection
    }

    /* Get favicon address */
    fun getFaviconAddress(urlString: String): String {
        var faviconAddress: String = String()
        try {
            var host: String = URL(urlString).host
            if (!host.startsWith("www")) {
                val index = host.indexOf(".")
                host = "www" + host.substring(index)
            }
            faviconAddress = "http://$host/favicon.ico"
        } catch (e: Exception) {
            LogHelper.e(TAG, "Unable to get base URL from $urlString.\n$e ")
        }
        return faviconAddress
    }

    /* Converts search result JSON string */
    fun createRadioBrowserResult(result: String): Array<RadioBrowserResult> {
        val gsonBuilder = GsonBuilder()
        gsonBuilder.setDateFormat("M/d/yy hh:mm a")
        val gson = gsonBuilder.create()
        return gson.fromJson(result, Array<RadioBrowserResult>::class.java)
    }
}
