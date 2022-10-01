/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@criptext.com)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package site.leos.apps.lespas.helper

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.preference.PreferenceManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textview.MaterialTextView
import org.json.JSONException
import org.json.JSONObject
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.cameraroll.CameraRollFragment
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoMeta
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.SyncAdapter
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Paths
import java.text.CharacterIterator
import java.text.Collator
import java.text.DecimalFormat
import java.text.StringCharacterIterator
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.regex.Pattern
import kotlin.math.*

object Tools {
    val SUPPORTED_PICTURE_FORMATS = arrayOf("jpeg", "png", "gif", "webp", "bmp", "heif", "heic")
    private val FORMATS_WITH_EXIF = arrayOf("jpeg", "png", "webp", "heif", "heic")

    @SuppressLint("RestrictedApi")
    fun getPhotoParams(
        metadataRetriever: MediaMetadataRetriever?, exifInterface: ExifInterface?,
        localPath: String, mimeType: String, fileName: String,
        updateCreationDate: Boolean = false, keepOriginalOrientation: Boolean = false,
        uri: Uri? = null, cr: ContentResolver? = null,
    ): Photo {
        var mMimeType = mimeType
        var width = 0
        var height = 0
        var latlong: DoubleArray = doubleArrayOf(Photo.NO_GPS_DATA, Photo.NO_GPS_DATA)
        var altitude = Photo.NO_GPS_DATA
        var bearing = Photo.NO_GPS_DATA
        var caption = ""
        var orientation = 0
        val isLocalFileExist = localPath.isNotEmpty()
        var dateTaken: LocalDateTime = LocalDateTime.now()
        val lastModified = LocalDateTime.ofInstant(Instant.ofEpochMilli(if (isLocalFileExist) File(localPath).lastModified() else System.currentTimeMillis()), ZoneId.systemDefault())

        if (mimeType.startsWith("video/", true)) {
            metadataRetriever?.run {
                getVideoDateAndLocation(this, fileName).let {
                    dateTaken = it.first ?: lastModified
                    latlong = it.second
                }

                extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.let { rotate ->
                    orientation = rotate.toInt()

                    if (rotate == "90" || rotate == "270") {
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.let { height = it.toInt() }
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.let { width = it.toInt() }
                    } else {
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.let { width = it.toInt() }
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.let { height = it.toInt() }
                    }
                }
            }
        } else {
            // Get default date taken value
            dateTaken = parseDateFromFileName(fileName) ?: lastModified

            when(val imageFormat = mimeType.substringAfter("image/", "")) {
                in FORMATS_WITH_EXIF-> {
                    // Try extracting photo's capture date from EXIF, try rotating the photo if EXIF tell us to, save EXIF if we rotated the photo
                    var saveExif = false

                    exifInterface?.let { exif->
                        exif.getAttribute(ExifInterface.TAG_USER_COMMENT)?.let { caption = it }
                        if (caption.isBlank()) exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)?.let { caption = it }

                        // GPS data
                        exif.latLong?.let { latlong = it }
                        altitude = exif.getAltitude(Photo.NO_GPS_DATA)
                        bearing = getBearing(exif)

                        // Taken date
                        getImageTakenDate(exif)?.let { dateTaken = it }
                        if (updateCreationDate) {
                            exif.setDateTime(dateTaken.toInstant(OffsetTime.now().offset).toEpochMilli())
                            saveExif = true
                        }

                        width = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                        height = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)

                        orientation = exif.rotationDegrees
                        if (orientation != 0 && !keepOriginalOrientation) {
                            if (isLocalFileExist) {
                                // Either by acquiring file from local or downloading media file from server for Local album, must rotate file
                                try {
                                    // TODO what if rotation fails?
                                    BitmapFactory.decodeFile(localPath)?.let {
                                        Bitmap.createBitmap(it, 0, 0, it.width, it.height, Matrix().apply { preRotate(orientation.toFloat()) }, true).apply {
                                            if (compress(Bitmap.CompressFormat.JPEG, 95, File(localPath).outputStream())) {
                                                mMimeType = Photo.DEFAULT_MIMETYPE

                                                // Swap width and height value, write back to exif and save in Room (see return value at the bottom)
                                                if (orientation == 90 || orientation == 270) {
                                                    val t = width
                                                    width = height
                                                    height = t
                                                }
                                                exif.resetOrientation()
                                                exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, "$width")
                                                exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, "$height")
                                                saveExif = true
                                            }
                                            recycle()
                                        }
                                    }
                                } catch (_: Exception) {}
                            } else {
                                // Swap width and height value if needed and save it to Room
                                if (orientation == 90 || orientation == 270) {
                                    val t = width
                                    width = height
                                    height = t
                                }
                            }
                        }

                        if (saveExif) {
                            try { exif.saveAttributes() }
                            catch (e: Exception) {
                                // TODO: better way to handle this
                                Log.e("****Exception", e.stackTraceToString())
                            }
                        }
                    }

                    if (imageFormat == "webp") {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            try {
                                // Set my own image/awebp mimetype for animated WebP
                                if (isLocalFileExist) if (ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(localPath))) is AnimatedImageDrawable) mMimeType = "image/awebp"
                                else uri?.let { if (ImageDecoder.decodeDrawable(ImageDecoder.createSource(cr!!, it)) is AnimatedImageDrawable) mMimeType = "image/awebp" }
                            } catch (_: Exception) {}
                        }
                    }
                }
                "gif"-> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        // Set my own image/agif mimetype for animated GIF
                        try {
                            if (isLocalFileExist) if (ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(localPath))) is AnimatedImageDrawable) mMimeType = "image/agif"
                            else uri?.let { if (ImageDecoder.decodeDrawable(ImageDecoder.createSource(cr!!, it)) is AnimatedImageDrawable) mMimeType = "image/agif" }
                        } catch (_: Exception) {}
                    }
                }
                else-> {}
            }

            // Get image width and height for local album if they can't fetched from EXIF
            if (width == 0) try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    if (isLocalFileExist) BitmapFactory.decodeFile(localPath, this)
                    else uri?.let { BitmapFactory.decodeStream(cr!!.openInputStream(it), null, this) }
                }
                width = options.outWidth
                height = options.outHeight
            } catch (_: Exception) {}
        }

        return Photo(
            mimeType = mMimeType,
            dateTaken = dateTaken, lastModified = lastModified,
            width = width, height = height,
            caption = caption,
            latitude = latlong[0], longitude = latlong[1], altitude = altitude, bearing = bearing,
            orientation = if (keepOriginalOrientation) orientation else 0
        )
    }

    private const val ISO_6709_PATTERN = "([+-][0-9]{2}.[0-9]{4})([+-][0-9]{3}.[0-9]{4})/"
    fun getVideoLocation(extractor: MediaMetadataRetriever): DoubleArray {
        val latLong = doubleArrayOf(Photo.NO_GPS_DATA, Photo.NO_GPS_DATA)
        extractor.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let {
            val matcher = Pattern.compile(ISO_6709_PATTERN).matcher(it)
            if (matcher.matches()) {
                try {
                    latLong[0] = matcher.group(1)?.toDouble() ?: Photo.NO_GPS_DATA
                    latLong[1] = matcher.group(2)?.toDouble() ?: Photo.NO_GPS_DATA
                } catch (_: Exception) {}
            }
        }
        return latLong
    }

    fun getVideoDateAndLocation(extractor: MediaMetadataRetriever, fileName: String): Pair<LocalDateTime?, DoubleArray> {
        val latLong = getVideoLocation(extractor)

        // For video file produced by phone camera, MediaMetadataRetriever.METADATA_KEY_DATE always return date value in UTC since there is no safe way to determine the timezone
        // However file name usually has a pattern of yyyyMMdd_HHmmss which can be parsed to a date adjusted by correct timezone
        var videoDate = parseDateFromFileName(fileName)

        // If date can't be parsed from file name, try get creation date from metadata
        if (videoDate == null) extractor.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { cDate->
            try {
                videoDate = LocalDateTime.parse(cDate, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'"))
                // If metadata tells a funky date, reset it. extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) return 1904/01/01 as default if it can't find "creation_time" tag in the video file
                if (videoDate?.year == 1904) videoDate = null
                // Try adjust date according to timezone derived from longitude, although it's not always correct, especially for countries observe only one timezone adjustment, like China
                if (videoDate != null && latLong[1] != Photo.NO_GPS_DATA) videoDate = videoDate?.plusHours((latLong[1]/15).toLong())
            } catch (e: Exception) { e.printStackTrace() }
        }
        // Eventually user can always use changing name function to manually adjust media's creation date information
/*
        // If metadata tells a funky date, reset it. extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) return 1904/01/01 as default if it can't find "creation_time" tag in the video file
        // Could not get creation date from metadata, try guessing from file name
        if (videoDate?.year == 1904 || videoDate == LocalDateTime.MIN) videoDate = parseDateFromFileName(fileName)
*/

        return Pair(videoDate, latLong)
    }

    @SuppressLint("RestrictedApi")
    fun getImageTakenDate(exif: ExifInterface, applyTZOffset: Boolean = false): LocalDateTime? =
        try {
            exif.dateTimeOriginal?.let {
                LocalDateTime.ofInstant(Instant.ofEpochMilli(it), if (applyTZOffset) exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)?.let { offsetZone -> ZoneId.of(offsetZone) } ?: ZoneId.of("UTC") else ZoneId.systemDefault())
            } ?: run {
                exif.dateTimeDigitized?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), if (applyTZOffset) exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED)?.let { offsetZone -> ZoneId.of(offsetZone) } ?: ZoneId.of("UTC") else ZoneId.systemDefault()) }
            }
        } catch (e: Exception) { null }

    // Match Wechat export file name, the 13 digits suffix is the export time in epoch millisecond
    private const val wechatPattern = "^mmexport([0-9]{13}).*"
    // Match file name of yyyyMMddHHmmss or yyyyMMdd_HHmmss or yyyyMMdd-HHmmss
    private const val timeStampPattern = ".*([12][0-9]{3})(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])[_-]?([01][0-9]|2[0-3])([0-5][0-9])([0-5][0-9]).*"
    fun parseDateFromFileName(fileName: String): LocalDateTime? {
        return try {
            var matcher = Pattern.compile(wechatPattern).matcher(fileName)
            if (matcher.matches()) matcher.group(1)?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.toLong()), ZoneId.systemDefault()) }
            else {
                matcher = Pattern.compile(timeStampPattern).matcher(fileName)
                if (matcher.matches()) LocalDateTime.parse(matcher.run { "${group(1)}:${group(2)}:${group(3)} ${group(4)}:${group(5)}:${group(6)}" }, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
                else null
            }
        } catch (e: Exception) { null }
    }

    fun epochToLocalDateTime(epoch: Long): LocalDateTime =
        try {
            // Always display time in current timezone
            if (epoch > 9999999999) Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).toLocalDateTime() else Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).toLocalDateTime()
        } catch (e: DateTimeException) { LocalDateTime.now() }

    fun isMediaPlayable(mimeType: String): Boolean = (mimeType == "image/agif") || (mimeType == "image/awebp") || (mimeType.startsWith("video/", true))

    fun hasExif(mimeType: String): Boolean = mimeType.substringAfter("image/", "") in FORMATS_WITH_EXIF

    @SuppressLint("DefaultLocale")
    fun humanReadableByteCountSI(size: Long): String {
        var bytes = size
        if (-1000 < bytes && bytes < 1000) return "$bytes B"
        val ci: CharacterIterator = StringCharacterIterator("kMGTPE")
        while (bytes <= -999950 || bytes >= 999950) {
            bytes /= 1000
            ci.next()
        }
        return java.lang.String.format("%s%cB", DecimalFormat("###.#").format(bytes/1000.0), ci.current())
    }

    fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.lowercase()
        var model = Build.MODEL.lowercase()

        if (model.startsWith(manufacturer)) model = model.substring(manufacturer.length).trim()

        return "${manufacturer}_${model}"
    }

    fun getCameraRoll(cr: ContentResolver, imageOnly: Boolean): MutableList<Photo> = listMediaContent("DCIM", cr, imageOnly, false)
    fun listMediaContent(folder: String, cr: ContentResolver, imageOnly: Boolean, strict: Boolean): MutableList<Photo> {
        val medias = mutableListOf<Photo>()
        val externalStorageUri = MediaStore.Files.getContentUri("external")

        @Suppress("DEPRECATION")
        val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
        val dateSelection = "datetaken"     // MediaStore.MediaColumns.DATE_TAKEN, hardcoded here since it's only available in Android Q or above
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            pathSelection,
            dateSelection,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            "orientation",                  // MediaStore.Files.FileColumns.ORIENTATION, hardcoded here since it's only available in Android Q or above
        )
        val selection = if (imageOnly) "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}) AND ($pathSelection LIKE '%${folder}%')"
            else "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND ($pathSelection LIKE '%${folder}%')"

        try {
            cr.query(externalStorageUri, projection, selection, null, "$dateSelection DESC")?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                //val pathColumn = cursor.getColumnIndexOrThrow(pathSelection)
                val dateColumn = cursor.getColumnIndexOrThrow(dateSelection)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                val orientationColumn = cursor.getColumnIndexOrThrow("orientation")    // MediaStore.Files.FileColumns.ORIENTATION, hardcoded here since it's only available in Android Q or above
                val defaultZone = ZoneId.systemDefault()
                var mimeType: String
                var date: Long
                var reSort = false
                var contentUri: Uri

                cursorLoop@ while (cursor.moveToNext()) {
                    if ((strict) && (cursor.getString(cursor.getColumnIndexOrThrow(pathSelection)) ?: folder).substringAfter(folder).contains('/')) continue

                    // Insert media
                    mimeType = cursor.getString(typeColumn)
                    // Make sure image type is supported
                    contentUri = if (mimeType.startsWith("image")) {
                        if (mimeType.substringAfter("image/", "") !in SUPPORTED_PICTURE_FORMATS) continue@cursorLoop
                        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    } else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

                    date = cursor.getLong(dateColumn)
                    if (date == 0L) {
                        // Sometimes dateTaken is not available from system, use dateAdded instead
                        date = cursor.getLong(dateAddedColumn) * 1000
                        reSort = true
                    }
                    medias.add(
                        Photo(
                            id = ContentUris.withAppendedId(contentUri, cursor.getString(idColumn).toLong()).toString(),
                            albumId = CameraRollFragment.FROM_CAMERA_ROLL,
                            name = cursor.getString(nameColumn) ?: "",
                            dateTaken = LocalDateTime.ofInstant(Instant.ofEpochMilli(date), defaultZone),     // DATE_TAKEN has nano adjustment
                            lastModified = LocalDateTime.MIN,
                            width = cursor.getInt(widthColumn),
                            height = cursor.getInt(heightColumn),
                            mimeType = mimeType,
                            shareId = cursor.getInt(sizeColumn),                  // Saving photo size value in shareId property
                            orientation = cursor.getInt(orientationColumn)        // Saving photo orientation value in shareId property, keep original orientation, CameraRollFragment will handle the rotation
                        )
                    )
                }

                // Resort the list if dateAdded used
                if (reSort) medias.sortWith(compareByDescending { it.dateTaken })
            }
        } catch (_: Exception) {}

        return medias
    }

    fun getCameraRollAlbum(cr: ContentResolver, albumName: String): Album {
        val externalStorageUri = MediaStore.Files.getContentUri("external")
        var startDate = LocalDateTime.MIN
        var endDate: LocalDateTime
        var coverId: String
        val coverBaseline = 0   // TODO better default baseline
        var coverWidth: Int
        var coverHeight: Int
        var coverFileName: String
        var coverMimeType: String
        var orientation: Int

        @Suppress("DEPRECATION")
        val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
        val dateSelection = "datetaken"     // MediaStore.MediaColumns.DATE_TAKEN, hardcoded here since it's only available in Android Q or above
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            dateSelection,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            "orientation",                  // MediaStore.Files.FileColumns.ORIENTATION, hardcoded here since it's only available in Android Q or above
        )
        val selection ="(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND ($pathSelection LIKE '%DCIM%') AND (${MediaStore.Files.FileColumns.WIDTH}!=0)"

        try {
            cr.query(externalStorageUri, projection, selection, null, "$dateSelection DESC")?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dateColumn = cursor.getColumnIndex(dateSelection)
                    val defaultZone = ZoneId.systemDefault()
                    coverMimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE))
                    val externalUri = if (coverMimeType.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

                    // Get album's end date, cover
                    endDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(cursor.getLong(dateColumn)), defaultZone)
                    coverId = ContentUris.withAppendedId(externalUri, cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)).toLong()).toString()
                    coverFileName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME))
                    coverWidth = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH))
                    coverHeight = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT))
                    orientation = cursor.getInt(cursor.getColumnIndexOrThrow("orientation"))

                    // Get album's start date
                    if (cursor.moveToLast()) startDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(cursor.getLong(dateColumn)), defaultZone)

                    // Cover's mimetype passed in property eTag, cover's orientation passed in property shareId
                    //return Album(CameraRollFragment.FROM_CAMERA_ROLL, albumName, startDate, endDate, coverId, coverBaseline, coverWidth, coverHeight, endDate, Album.BY_DATE_TAKEN_DESC, mimeType, orientation, 1.0F)
                    return Album(
                        id = CameraRollFragment.FROM_CAMERA_ROLL, name = albumName,
                        startDate = startDate, endDate = endDate, lastModified = endDate,
                        cover = coverId, coverFileName = coverFileName, coverBaseline = coverBaseline, coverWidth = coverWidth, coverHeight = coverHeight, coverMimeType = coverMimeType,
                        sortOrder = Album.BY_DATE_TAKEN_DESC,
                        eTag = Album.ETAG_CAMERA_ROLL_ALBUM,
                        shareId = Album.NULL_ALBUM,
                        coverOrientation = orientation,
                    )
                }
            }
        } catch (_: Exception) {}

        return Album(
            id = CameraRollFragment.FROM_CAMERA_ROLL, name = albumName,
            lastModified = LocalDateTime.now(), startDate = LocalDateTime.now(), endDate = LocalDateTime.now(),
            sortOrder = Album.BY_DATE_TAKEN_DESC, eTag = Album.ETAG_CAMERA_ROLL_ALBUM, shareId = Album.NULL_ALBUM,
            cover = CameraRollFragment.EMPTY_ROLL_COVER_ID, coverWidth = 192, coverHeight = 108,
        )
    }

    fun getCameraRollStatistic(cr: ContentResolver): Pair<Int, Long> {
        var itemCount = 0
        var totalSize = 0L

        @Suppress("DEPRECATION")
        val externalStorageUri = MediaStore.Files.getContentUri("external")
        val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
        val projection = arrayOf(
            pathSelection,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.SIZE,
        )
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND ($pathSelection LIKE '%DCIM%')"

        try {
            cr.query(externalStorageUri, projection, selection, null, null)?.use { cursor ->
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)

                while(cursor.moveToNext()) totalSize += cursor.getLong(sizeColumn)

                itemCount = cursor.count
            }
        } catch (_: Exception) {}

        return Pair(itemCount, totalSize)
    }

    fun getFolderFromUri(uriString: String, contentResolver: ContentResolver): Pair<String, String>? {
        val colon = "%3A"
        val storageUriSignature = "com.android.externalstorage.documents"
        val mediaProviderUriSignature = "com.android.providers.media.documents"
        val downloadProviderUriSignature = "com.android.providers.downloads.documents"

        //Log.e(">>>>>", "input: $uriString")
        return try {
            when {
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && uriString.contains(downloadProviderUriSignature)) || uriString.contains(storageUriSignature) -> {
                    var id: String? = null
                    val folder = URLDecoder.decode(uriString.substringAfter(colon), "UTF-8").substringBeforeLast("/")
                    val externalStorageUri = MediaStore.Files.getContentUri("external")
                    @Suppress("DEPRECATION")
                    val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
                    val projection = arrayOf(
                        MediaStore.Files.FileColumns._ID,
                        MediaStore.Files.FileColumns.DISPLAY_NAME,
                        pathColumn,
                    )
                    val selection = "($pathColumn LIKE '%${folder}%') AND (${MediaStore.Files.FileColumns.DISPLAY_NAME}='${URLDecoder.decode(uriString, "UTF-8").substringAfterLast('/')}')"

                    contentResolver.query(externalStorageUri, projection, selection, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) id = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                    }

                    id?.let { Pair("$folder/", id!!) }
                }
                uriString.contains(mediaProviderUriSignature) || uriString.contains(downloadProviderUriSignature) -> {
                    var folderName: String? = null
                    val id = uriString.substringAfter(colon)
                    val externalStorageUri = MediaStore.Files.getContentUri("external")
                    @Suppress("DEPRECATION")
                    val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
                    val projection = arrayOf(
                        MediaStore.Files.FileColumns._ID,
                        pathSelection,
                    )
                    val selection = "${MediaStore.Files.FileColumns._ID} = $id"

                    contentResolver.query(externalStorageUri, projection, selection, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) folderName = cursor.getString(cursor.getColumnIndexOrThrow(pathSelection))
                    }

                    folderName?.let { Pair(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) folderName!! else "${folderName!!.substringAfter("/storage/emulated/0/").substringBeforeLast('/')}/", id) }
                }
                else -> null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getLocalRoot(context: Context): String {
        return "${if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(SettingsFragment.KEY_STORAGE_LOCATION, true)) "${context.filesDir}" else "${context.getExternalFilesDirs(null)[1]}"}${context.getString(R.string.lespas_base_folder_name)}"
    }

    fun getStorageSize(context: Context): Long {
        var totalBytes = 0L
        val path = getLocalRoot(context)

        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { File(path).listFiles()?.forEach { file -> totalBytes += file.length() }}
            else { totalBytes = Files.walk(Paths.get(path)).mapToLong { p -> p.toFile().length() }.sum() }
        } catch (e: Exception) { e.printStackTrace() }

        return totalBytes
    }

    fun getRoundBitmap(context: Context, bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply {
                clipPath(Path().apply { addCircle((width.toFloat() / 2), (height.toFloat() / 2), min(width.toFloat(), (height.toFloat() / 2)), Path.Direction.CCW) })
                drawPaint(Paint().apply {
                    color = ContextCompat.getColor(context, R.color.color_avatar_default_background)
                    style = Paint.Style.FILL
                })
                drawBitmap(bitmap, 0f, 0f, null)
            }
        }
    }

    fun getSelectedMarkDrawable(context: Activity, scale: Float): Drawable = getScaledDrawable(context, scale, R.drawable.ic_baseline_selected_24)
    fun getPlayMarkDrawable(context: Activity, scale: Float): Drawable = getScaledDrawable(context, scale, R.drawable.ic_baseline_play_mark_24)
    private fun getScaledDrawable(context: Activity, scale: Float, resId: Int): Drawable {
        val size: Int = (scale * getDisplayDimension(context).first).toInt()

        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        ContextCompat.getDrawable(context, resId)?.apply {
            setBounds(0, 0, size, size)
            draw(Canvas(bmp))
        }

        return BitmapDrawable(context.resources, bmp)
    }

    @Suppress("DEPRECATION")
    fun goImmersive(window: Window, delayTranslucentEffect: Boolean = false) {
        window.apply {
/*
            val systemBarBackground = ContextCompat.getColor(requireContext(), R.color.dark_gray_overlay_background)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                previousNavBarColor = navigationBarColor
                navigationBarColor = systemBarBackground
                statusBarColor = systemBarBackground
                insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                setDecorFitsSystemWindows(false)
            } else {
                addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            }
*/
            //previousNavBarColor = navigationBarColor
            //navigationBarColor = Color.TRANSPARENT
            //statusBarColor = Color.TRANSPARENT
            if (delayTranslucentEffect) Handler(Looper.getMainLooper()).postDelayed({ addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS) }, 1000) else addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        }
        if (window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != View.SYSTEM_UI_FLAG_FULLSCREEN) window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE or
            // Set the content to appear under the system bars so that the
            // content doesn't resize when the system bars hide and show.
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            // Hide the nav bar and status bar
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN
        )
/*
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE or
                // Set the content to appear under the system bars so that the
                // content doesn't resize when the system bars hide and show.
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                // Hide the nav bar and status bar
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        } else {
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        }
*/
    }

    fun getPreparingSharesSnackBar(anchorView: View, strip: Boolean, cancelAction: View.OnClickListener?): Snackbar {
        val ctx = anchorView.context
        return Snackbar.make(anchorView, if (strip) R.string.striping_exif else R.string.preparing_shares, Snackbar.LENGTH_INDEFINITE).apply {
            try {
                (view.findViewById<MaterialTextView>(com.google.android.material.R.id.snackbar_text).parent as ViewGroup).addView(ProgressBar(ctx).apply {
                    // Android Snackbar text size is 14sp
                    val pbHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics).roundToInt()
                    layoutParams = (LinearLayout.LayoutParams(pbHeight, pbHeight)).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
                    indeterminateTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.color_text_light))
                }, 0)
            } catch (_: Exception) {}
            cancelAction?.let { setAction(android.R.string.cancel, it) }
        }
    }

    fun getDisplayDimension(context: Activity): Pair<Int, Int> = getDisplayDimension(context.windowManager)
    fun getDisplayDimension(wm: WindowManager): Pair<Int, Int> {
        return DisplayMetrics().run {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(this)
                Pair(widthPixels, heightPixels)
            } else {
                wm.currentWindowMetrics.bounds.let { return Pair(it.width(), it.height()) }
            }
        }
    }

    fun isRemoteAlbum(album: Album): Boolean = (album.shareId and Album.REMOTE_ALBUM) == Album.REMOTE_ALBUM
    fun isExcludedAlbum(album: Album): Boolean = (album.shareId and Album.EXCLUDED_ALBUM) == Album.EXCLUDED_ALBUM
    fun isWideListAlbum(sortOrder: Int): Boolean = sortOrder in Album.BY_DATE_TAKEN_ASC_WIDE..200

    private const val PI = 3.1415926535897932384626
    private const val EE = 0.00669342162296594323
    private const val A = 6378245.0
    fun wGS84ToGCJ02(latLong: DoubleArray): DoubleArray {
        // Out of China
        if (latLong[0] < 0.8293 || latLong[0] > 55.8271) return latLong
        if (latLong[1] < 72.004 || latLong[1] > 137.8347) return latLong

        var dLat = translateLat(latLong[1] - 105.0, latLong[0] - 35.0)
        var dLong = translateLong(latLong[1] - 105.0, latLong[0] - 35.0)
        val radLat = latLong[0] / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLong = (dLong * 180.0) / (A / sqrtMagic * cos(radLat) * PI)

        return doubleArrayOf(latLong[0] + dLat, latLong[1] + dLong)
    }

    private fun translateLat(x: Double, y: Double): Double {
        var lat = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        lat += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        lat += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        lat += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0

        return lat
    }

    private fun translateLong(x: Double, y: Double): Double {
        var long = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        long += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        long += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        long += (150.0 * sin( x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0

        return long
    }

    fun readContentMeta(inputStream: InputStream, sharePath: String, sortOrder: Int = Album.BY_DATE_TAKEN_DESC): List<NCShareViewModel.RemotePhoto> {
        val result = mutableListOf<NCShareViewModel.RemotePhoto>()

        val lespasJson = try {
            JSONObject(inputStream.reader().readText()).getJSONObject("lespas")
        } catch (e: JSONException) { return result }

        val version = try {
            lespasJson.getInt("version")
        } catch (e: JSONException) {
            1
        }

        var mDate: LocalDateTime
        val photos = lespasJson.getJSONArray("photos")
        for (i in 0 until photos.length()) {
            photos.getJSONObject(i).apply {
                mDate = try { epochToLocalDateTime(getLong("stime")) } catch (e: DateTimeException) { LocalDateTime.now() }
                when {
                    // TODO make sure later version json file downward compatible
                    version >= 2 -> {
                        try {
                            // Version checking, trigger exception
                            getInt("orientation")

                            result.add(
                                NCShareViewModel.RemotePhoto(
                                    Photo(
                                        id = getString("id"), name = getString("name"), mimeType = getString("mime"), width = getInt("width"), height = getInt("height"), lastModified = LocalDateTime.MIN, dateTaken = mDate,
                                        // Version 2 additions
                                        orientation = getInt("orientation"), caption = getString("caption"), latitude = getDouble("latitude"), longitude = getDouble("longitude"), altitude = getDouble("altitude"), bearing = getDouble("bearing"),
                                        // Should set eTag to value not as Photo.ETAG_NOT_YET_UPLOADED
                                        eTag = Photo.ETAG_FAKE
                                    ), sharePath
                                )
                            )
                        } catch (e: JSONException) {
                            try {
                                result.add(
                                    NCShareViewModel.RemotePhoto(
                                        Photo(
                                            id = getString("id"), name = getString("name"), mimeType = getString("mime"), width = getInt("width"), height = getInt("height"), lastModified = LocalDateTime.MIN, dateTaken = mDate,
                                            // Should set eTag to value not as Photo.ETAG_NOT_YET_UPLOADED
                                            eTag = Photo.ETAG_FAKE
                                        ), sharePath
                                    )
                                )
                            } catch (_: JSONException) {}
                        }
                    }
                    // Version 1 of content meta json
                    else -> {
                        try {
                            result.add(
                                NCShareViewModel.RemotePhoto(
                                    Photo(
                                        id = getString("id"), name = getString("name"), mimeType = getString("mime"), width = getInt("width"), height = getInt("height"), lastModified = LocalDateTime.MIN, dateTaken = mDate,
                                        // Should set eTag to value not as Photo.ETAG_NOT_YET_UPLOADED
                                        eTag = Photo.ETAG_FAKE
                                    ), sharePath
                                )
                            )
                        } catch (_: JSONException) {}
                    }
                }
            }
        }
        when (sortOrder % 100) {
            Album.BY_DATE_TAKEN_ASC -> result.sortWith(compareBy { it.photo.dateTaken })
            Album.BY_DATE_TAKEN_DESC -> result.sortWith(compareByDescending { it.photo.dateTaken })
            Album.BY_NAME_ASC -> result.sortWith(compareBy(Collator.getInstance().apply { strength = Collator.PRIMARY }) { it.photo.name })
            Album.BY_NAME_DESC -> result.sortWith(compareByDescending(Collator.getInstance().apply { strength = Collator.PRIMARY }) { it.photo.name })
        }

        return result
    }

    fun photosToMetaJSONString(photos: List<Photo>): String {
        var content = SyncAdapter.PHOTO_META_HEADER

        photos.forEach { photo ->
            with(photo) {
                content += String.format(Locale.ROOT, SyncAdapter.PHOTO_META_JSON_V2, id, name, dateTaken.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), mimeType, width, height, orientation, caption, latitude, longitude, altitude, bearing)
            }
        }

        return content.dropLast(1) + "]}}"
    }

    fun remotePhotosToMetaJSONString(remotePhotos: List<NCShareViewModel.RemotePhoto>): String {
        var content = SyncAdapter.PHOTO_META_HEADER

        remotePhotos.forEach {
            //content += String.format(PHOTO_META_JSON, it.fileId, it.path.substringAfterLast('/'), it.timestamp, it.mimeType, it.width, it.height)
            with(it.photo) {
                content += String.format(Locale.ROOT, SyncAdapter.PHOTO_META_JSON_V2, id, name, dateTaken.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), mimeType, width, height, orientation, caption, latitude, longitude, altitude, bearing)
            }
        }

        return content.dropLast(1) + "]}}"
    }

    fun metasToJSONString(photoMeta: List<PhotoMeta>): String {
        var content = SyncAdapter.PHOTO_META_HEADER

        photoMeta.forEach {
            //content += String.format(PHOTO_META_JSON, it.id, it.name, it.dateTaken.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), it.mimeType, it.width, it.height)
            content += String.format(Locale.ROOT, SyncAdapter.PHOTO_META_JSON_V2, it.id, it.name, it.dateTaken.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), it.mimeType, it.width, it.height, it.orientation, it.caption, it.latitude, it.longitude, it.altitude, it.bearing)
        }

        return content.dropLast(1) + "]}}"
    }

    fun getPhotosWithCoordinate(list: List<Photo>, autoConvergent: Boolean, albumSortOrder: Int): List<Photo> {
        val result = mutableListOf<Photo>()

        mutableListOf<Photo>().run {
            val photos = (if (albumSortOrder % 100 == Album.BY_DATE_TAKEN_ASC) list else list.sortedWith(compareBy { it.dateTaken })).filter { !isMediaPlayable(it.mimeType) }

            photos.forEach { if (it.mimeType.startsWith("image/") && it.latitude != Photo.NO_GPS_DATA) add(it) }

            if (autoConvergent) {
                add(0, Photo(dateTaken = LocalDateTime.MIN, lastModified = LocalDateTime.MIN))
                add(Photo(dateTaken = LocalDateTime.MAX, lastModified = LocalDateTime.MAX))

                var start: Photo = get(0)
                var end: Photo = get(1)
                var index = 1

                val offset = OffsetDateTime.now().offset
                val maximum = 20 * 60   // 20 minutes maximum
                var secondsToStart: Long
                var secondsToEnd: Long
                var startEpochSecond = start.dateTaken.toEpochSecond(offset)
                var endEpochSecond = end.dateTaken.toEpochSecond(offset)

                photos.forEach { photo ->
                    when(photo.id) {
                        start.id -> {}
                        end.id -> {
                            result.add(end)
                            start = end
                            end = get(++index)
                            startEpochSecond = endEpochSecond
                            endEpochSecond = end.dateTaken.toEpochSecond(offset)
                        }
                        else -> {
                            photo.dateTaken.toEpochSecond(offset).run {
                                secondsToStart = abs(this - startEpochSecond)
                                secondsToEnd = abs(endEpochSecond - this)
                            }
                            when {
                                (secondsToStart < maximum) && (secondsToEnd > maximum) ->
                                    result.add(photo.copy(latitude = start.latitude, longitude = start.longitude, altitude = start.altitude, bearing = start.bearing).apply {
                                        start = this
                                        startEpochSecond = start.dateTaken.toEpochSecond(offset)
                                    })
                                (secondsToStart > maximum) && (secondsToEnd < maximum) -> result.add(photo.copy(latitude = end.latitude, longitude = end.longitude, altitude = end.altitude, bearing = end.bearing))
                                (secondsToStart < maximum) && (secondsToEnd < maximum) -> result.add(
                                    if (secondsToStart < secondsToEnd) photo.copy(latitude = start.latitude, longitude = start.longitude, altitude = start.altitude, bearing = start.bearing).apply {
                                        start = this
                                        startEpochSecond = start.dateTaken.toEpochSecond(offset)
                                    }
                                    else photo.copy(latitude = end.latitude, longitude = end.longitude, altitude = end.altitude, bearing = end.bearing)
                                )
                                else -> {}
                            }
                        }
                    }
                }
            } else result.addAll(this)
        }

        when(albumSortOrder % 100) {
            Album.BY_DATE_TAKEN_ASC -> result.sortWith(compareBy { it.dateTaken })
            Album.BY_DATE_TAKEN_DESC -> result.sortWith(compareByDescending { it.dateTaken })
            Album.BY_NAME_ASC -> result.sortWith(compareBy(Collator.getInstance().apply { strength = Collator.PRIMARY }) { it.name })
            Album.BY_NAME_DESC -> result.sortWith(compareByDescending(Collator.getInstance().apply { strength = Collator.PRIMARY }) { it.name })
        }

        return result
    }

    fun getAttributeResourceId(context: Context, attrId: Int): Int {
        TypedValue().let {
            context.theme.resolveAttribute(attrId, it, true)
            return it.resourceId
        }
    }

    fun getAttributeColor(context: Context, attrId: Int): Int {
        TypedValue().let {
            context.theme.resolveAttribute(attrId, it, true)
            return ContextCompat.getColor(context, it.resourceId)
        }
    }

    fun applyTheme(context: AppCompatActivity, normalThemeId: Int, trueBlackThemeId: Int) {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        sp.getString(context.getString(R.string.auto_theme_perf_key), context.getString(R.string.theme_auto_values))?.let { AppCompatDelegate.setDefaultNightMode(it.toInt()) }
        if (sp.getBoolean(context.getString(R.string.true_black_pref_key), false) && (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            context.setTheme(trueBlackThemeId)
        } else context.setTheme(normalThemeId)
    }

    fun getBearing(exif: ExifInterface): Double {
        var bearing = Photo.NO_GPS_DATA
        exif.getAttribute(ExifInterface.TAG_GPS_DEST_BEARING)?.let { try { bearing = it.toDouble() } catch (_: NumberFormatException) {} }
        if (bearing == Photo.NO_GPS_DATA) exif.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION)?.let { try { bearing = it.toDouble() } catch (_: java.lang.NumberFormatException) {} }
        return bearing
    }

    fun keepScreenOn(window: Window, on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun getStoragePermissionsArray(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun shouldRequestStoragePermission(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        else -> ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    }
}