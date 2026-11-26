package dev.horali.clipfix

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
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
    private const val VIDEO_BITRATE = 14_000_000

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
        // FrameDropEffect does the real 120 -> 60 fps work. It also forces a re-encode:
        // without a video effect, Transformer only copies the video stream (tested: output
        // stayed at 116 fps with setFrameRate alone). The default strategy reads timestamps,
        // so it also works on variable-frame-rate screen recordings.
        val videoEffects = buildList {
            add(FrameDropEffect.createDefaultFrameDropEffect(TARGET_FPS.toFloat()))
            if (shortSide > MAX_SHORT_SIDE) add(Presentation.createForShortSide(MAX_SHORT_SIDE))
        }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(input))
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val done = CompletableDeferred<ExportResult>()
        // The default bitrate was about 41 Mbit/s (2x the original). Discord compresses
        // the clip again anyway, so 14 Mbit/s keeps quality and halves the upload.
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(VIDEO_BITRATE).build(),
            )
            .build()
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(encoderFactory)
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
