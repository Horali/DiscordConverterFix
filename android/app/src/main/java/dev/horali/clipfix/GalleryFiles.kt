package dev.horali.clipfix

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Short-lived 60 fps copies in the gallery album "Movies/ClipFix".
 * Discord's own media picker shows them, so you can attach many clips at once.
 * ClipFix deletes its own copies after 1 hour. It only deletes files that it created itself.
 */
object GalleryFiles {

    const val RELATIVE_DIR = "Movies/ClipFix/"
    const val MAX_AGE_MS = 60 * 60 * 1000L

    private fun collection(): Uri =
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Copies [source] into the ClipFix album and schedules the clean-up. */
    suspend fun save(context: Context, source: File, displayName: String): Uri =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection(), values)
                ?: error("Cannot create the gallery file")
            try {
                resolver.openOutputStream(uri)!!.use { out ->
                    source.inputStream().use { it.copyTo(out, 1 shl 20) }
                }
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            CleanupWorker.schedule(context, MAX_AGE_MS, ExistingWorkPolicy.KEEP)
            uri
        }

    /**
     * Deletes ClipFix copies older than 1 hour.
     * Returns the time in ms until the next copy expires, or null if no copy is left.
     */
    fun deleteOld(context: Context): Long? {
        val resolver = context.contentResolver
        val now = System.currentTimeMillis()
        var nextExpiry: Long? = null
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED)
        // Only our own files: the album path AND our package as the owner.
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} = ? AND " +
            "${MediaStore.Video.Media.OWNER_PACKAGE_NAME} = ?"
        val args = arrayOf(RELATIVE_DIR, context.packageName)
        resolver.query(collection(), projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val addedMs = c.getLong(1) * 1000
                val expiresIn = addedMs + MAX_AGE_MS - now
                if (expiresIn <= 0) {
                    try {
                        resolver.delete(ContentUris.withAppendedId(collection(), id), null, null)
                    } catch (_: Exception) {
                        // A file we cannot delete stays. Never ask the user from the background.
                    }
                } else {
                    nextExpiry = minOf(nextExpiry ?: expiresIn, expiresIn)
                }
            }
        }
        return nextExpiry
    }
}

/** Runs [GalleryFiles.deleteOld] and plans the next run while copies are left. */
class CleanupWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val next = GalleryFiles.deleteOld(applicationContext)
        if (next != null) {
            schedule(applicationContext, next + 60_000, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        Result.success()
    }

    companion object {
        private const val NAME = "gallery-cleanup"

        fun schedule(context: Context, delayMs: Long, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<CleanupWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request)
        }
    }
}
