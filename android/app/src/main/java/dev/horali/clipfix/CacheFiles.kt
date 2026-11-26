package dev.horali.clipfix

import android.content.Context
import java.io.File

/**
 * Converted clips live only in the app's private cache, never in the gallery.
 * At most one converted file exists at a time.
 */
object CacheFiles {

    private const val MAX_AGE_MS = 60 * 60 * 1000L // 1 hour

    fun dir(context: Context): File = File(context.cacheDir, "converted").apply { mkdirs() }

    /** The finished file for a clip. */
    fun outputFor(context: Context, clipId: Long): File =
        File(dir(context), "clipfix_${clipId}_60fps.mp4")

    /** The file the converter writes to. It gets the final name only when the export is complete. */
    fun partFor(context: Context, clipId: Long): File =
        File(dir(context), "clipfix_${clipId}_60fps.part.mp4")

    /** Deletes every converted file. Call this before a new conversion. */
    fun clearAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /** Deletes converted files older than 1 hour. Call this on app start. */
    fun cleanOld(context: Context) {
        val limit = System.currentTimeMillis() - MAX_AGE_MS
        dir(context).listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
    }
}
