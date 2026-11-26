package dev.horali.clipfix

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Converts a clip to the "Discord-safe profile" (plan.md section 6.0):
 * H.264, constant 60 fps, short side at most 1080 px, audio kept, MP4.
 * Uses the phone's hardware encoder through Media3 Transformer.
 */
@OptIn(UnstableApi::class)
object Converter {

    const val TARGET_FPS = 60
    private const val MAX_SHORT_SIDE = 1080

    /**
     * Runs the export. [onProgress] gets 0..100.
     * Transformer needs a thread with a Looper, so this runs on the main thread.
     */
    suspend fun convert(
        context: Context,
        input: Uri,
        shortSide: Int,
        output: File,
        onProgress: (Int) -> Unit,
    ): ExportResult = withContext(Dispatchers.Main) {
        val videoEffects = if (shortSide > MAX_SHORT_SIDE) {
            listOf(Presentation.createForShortSide(MAX_SHORT_SIDE))
        } else {
            emptyList()
        }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(input))
            .setFrameRate(TARGET_FPS) // Resamples variable 120 fps to constant 60 fps.
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val done = CompletableDeferred<ExportResult>()
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done.complete(exportResult)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    done.completeExceptionally(exportException)
                }
            })
            .build()

        output.delete()
        transformer.start(item, output.absolutePath)

        val poller = launch {
            val holder = ProgressHolder()
            while (isActive) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress)
                }
                delay(150)
            }
        }
        try {
            done.await()
        } catch (e: CancellationException) {
            transformer.cancel()
            output.delete()
            throw e
        } finally {
            poller.cancel()
        }
    }
}
