package dev.horali.clipfix

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One video from the phone's MediaStore. */
data class Clip(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val mimeType: String,
)

/** Facts read from inside the video file. */
data class ClipProbe(
    val fps: Float?,
    val width: Int,
    val height: Int,
)

object ClipRepository {

    private const val MAX_CLIPS = 30

    /**
     * Lists the newest videos, newest first.
     * When [screenRecordingsOnly] is true, only videos in a "Screen recordings" folder show.
     */
    suspend fun loadClips(context: Context, screenRecordingsOnly: Boolean): List<Clip> =
        withContext(Dispatchers.IO) {
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.MIME_TYPE,
            )
            val args = Bundle().apply {
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.Video.Media.DATE_ADDED),
                )
                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
                )
                putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_CLIPS)
                val path = MediaStore.Video.Media.RELATIVE_PATH
                // Never list our own 60 fps copies.
                var selection = "$path NOT LIKE ?"
                val selectionArgs = mutableListOf(GalleryFiles.RELATIVE_DIR + "%")
                if (screenRecordingsOnly) {
                    // Samsung writes to "DCIM/Screen recordings/". LIKE ignores case here.
                    selection += " AND ($path LIKE ? OR $path LIKE ?)"
                    selectionArgs += listOf("%screen record%", "%screenrecord%")
                }
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    selectionArgs.toTypedArray(),
                )
            }
            val clips = mutableListOf<Clip>()
            context.contentResolver.query(collection, projection, args, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    clips += Clip(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(nameCol) ?: "video",
                        durationMs = c.getLong(durCol),
                        width = c.getInt(wCol),
                        height = c.getInt(hCol),
                        sizeBytes = c.getLong(sizeCol),
                        mimeType = c.getString(mimeCol) ?: "video/mp4",
                    )
                }
            }
            clips
        }

    /** Reads one video from MediaStore by its id, or null. */
    private fun loadById(context: Context, id: Long): Clip? {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val uri = ContentUris.withAppendedId(collection, id)
        val projection = arrayOf(
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
        )
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                Clip(
                    id = id,
                    uri = uri,
                    name = c.getString(0) ?: return null,
                    durationMs = c.getLong(1),
                    width = c.getInt(2),
                    height = c.getInt(3),
                    sizeBytes = c.getLong(4),
                    mimeType = c.getString(5) ?: "video/mp4",
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Makes a [Clip] from a video the user picked in the photo picker.
     * The picker gives a number as the name. The last part of its link is the MediaStore id,
     * so we read the real gallery entry. Then the picked clip and the list clip are the same
     * clip, with the same copy name.
     */
    suspend fun fromPickedUri(context: Context, uri: Uri): Clip = withContext(Dispatchers.IO) {
        uri.lastPathSegment?.toLongOrNull()?.let { id ->
            loadById(context, id)?.let { return@withContext it }
        }
        var name = "video.mp4"
        var size = 0L
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                size = c.getLong(1)
            }
        }
        var durationMs = 0L
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
        } finally {
            retriever.release()
        }
        Clip(
            id = uri.toString().hashCode().toLong(),
            uri = uri,
            name = name,
            durationMs = durationMs,
            width = 0,
            height = 0,
            sizeBytes = size,
            mimeType = context.contentResolver.getType(uri) ?: "video/mp4",
        )
    }

    /**
     * Reads the real average frame rate: frame count divided by length.
     * Screen recordings use a variable frame rate, so the header value is often wrong or missing.
     */
    suspend fun probe(context: Context, uri: Uri): ClipProbe = withContext(Dispatchers.IO) {
        var fps: Float? = null
        var width = 0
        var height = 0
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val frames = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toLongOrNull()
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
            if (frames != null && durationMs != null && frames > 0 && durationMs > 0) {
                fps = frames * 1000f / durationMs
            }
        } catch (_: Exception) {
            // Fall back to the header value below.
        } finally {
            retriever.release()
        }
        if (fps == null) fps = headerFrameRate(context, uri)
        ClipProbe(fps, width, height)
    }

    private fun headerFrameRate(context: Context, uri: Uri): Float? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?.takeIf { it.containsKey(MediaFormat.KEY_FRAME_RATE) }
                ?.getNumber(MediaFormat.KEY_FRAME_RATE)
                ?.toFloat()
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    suspend fun thumbnail(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.loadThumbnail(uri, Size(320, 320), null)
        } catch (_: Exception) {
            null
        }
    }
}
