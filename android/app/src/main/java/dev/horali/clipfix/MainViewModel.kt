package dev.horali.clipfix

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** A ready file that the UI must share. */
data class ShareRequest(val uri: Uri, val mimeType: String)

data class UiState(
    val clips: List<Clip> = emptyList(),
    /** Videos the user picked in the photo picker, newest pick first. */
    val picked: List<Clip> = emptyList(),
    val probes: Map<Long, ClipProbe> = emptyMap(),
    val screenRecordingsOnly: Boolean = true,
    val loading: Boolean = false,
    val selected: Set<Long> = emptySet(),
    val busyClipId: Long? = null,
    val progress: Int = 0,
    /** 1-based position in the running batch, and its size. */
    val batchIndex: Int = 0,
    val batchTotal: Int = 0,
    /** Per clip: the last result line, for example the conversion time. */
    val results: Map<Long, String> = emptyMap(),
    val message: String? = null,
) {
    val busy get() = batchTotal > 0
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _shareRequests = Channel<ShareRequest>(Channel.BUFFERED)
    val shareRequests = _shareRequests.receiveAsFlow()

    private var observerRegistered = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    init {
        CacheFiles.cleanOld(app)
        viewModelScope.launch(Dispatchers.IO) {
            val next = GalleryFiles.deleteOld(app)
            if (next != null) CleanupWorker.schedule(app, next + 60_000, ExistingWorkPolicy.REPLACE)
        }
    }

    /** Call when the video permission is granted. */
    fun onPermissionGranted() {
        if (!observerRegistered) {
            getApplication<Application>().contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer,
            )
            observerRegistered = true
        }
        refresh()
    }

    fun setScreenRecordingsOnly(value: Boolean) {
        _state.update { it.copy(screenRecordingsOnly = value, selected = emptySet()) }
        refresh()
    }

    fun toggleSelected(clip: Clip) = _state.update {
        it.copy(selected = if (clip.id in it.selected) it.selected - clip.id else it.selected + clip.id)
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private var refreshJob: Job? = null

    fun refresh() {
        // MediaStore sends many change events while a file is written. Keep only the last one.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            delay(300)
            _state.update { it.copy(loading = true) }
            val ctx = getApplication<Application>()
            val clips = try {
                ClipRepository.loadClips(ctx, _state.value.screenRecordingsOnly)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot list videos", e)
                emptyList()
            }
            _state.update { it.copy(clips = clips, loading = false) }
            for (clip in clips) probeIfNeeded(clip)
        }
    }

    private suspend fun probeIfNeeded(clip: Clip): ClipProbe {
        _state.value.probes[clip.id]?.let { return it }
        val probe = ClipRepository.probe(getApplication(), clip.uri)
        _state.update { it.copy(probes = it.probes + (clip.id to probe)) }
        return probe
    }

    /** Videos from the photo picker: show them on top and save them all. */
    fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.busy) return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val clips = uris.map { ClipRepository.fromPickedUri(ctx, it) }
            _state.update { s ->
                val newIds = clips.map { it.id }.toSet()
                s.copy(picked = clips + s.picked.filter { it.id !in newIds })
            }
            runSaveBatch(clips)
        }
    }

    /** Saves the selected list clips to the gallery album. */
    fun saveSelected() {
        val s = _state.value
        val clips = (s.picked + s.clips).filter { it.id in s.selected }.distinctBy { it.id }
        saveToGallery(clips)
        clearSelection()
    }

    fun saveToGallery(clips: List<Clip>) {
        if (clips.isEmpty() || _state.value.busy) return
        viewModelScope.launch { runSaveBatch(clips) }
    }

    private suspend fun runSaveBatch(clips: List<Clip>) {
        val ctx = getApplication<Application>()
        var saved = 0
        clips.forEachIndexed { index, clip ->
            _state.update { it.copy(batchIndex = index + 1, batchTotal = clips.size) }
            try {
                val file = convertToCache(clip)
                if (file != null) {
                    GalleryFiles.save(ctx, file, galleryName(clip))
                    file.delete()
                    appendResult(clip.id, "Saved to the ClipFix album. It deletes itself after 1 hour.")
                    saved++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Save failed for ${clip.name}", e)
                setResult(clip.id, "Failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        _state.update { it.copy(busyClipId = null, batchIndex = 0, batchTotal = 0) }
        if (saved > 0) {
            showMessage(
                "Saved $saved clip(s). In Discord, tap + and select them at the top of the gallery.",
            )
        }
    }

    /** Converts one clip and opens the share screen (Discord first). */
    fun send(clip: Clip) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(batchIndex = 1, batchTotal = 1) }
            try {
                val file = convertToCache(clip, reuse = true)
                val ctx = getApplication<Application>()
                val request = if (file == null) {
                    ShareRequest(clip.uri, clip.mimeType)
                } else {
                    ShareRequest(
                        FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file),
                        "video/mp4",
                    )
                }
                _shareRequests.send(request)
            } catch (e: Exception) {
                Log.e(TAG, "Send failed", e)
                setResult(clip.id, "Failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _state.update { it.copy(busyClipId = null, batchIndex = 0, batchTotal = 0) }
            }
        }
    }

    /**
     * Converts [clip] to 60 fps into the private cache and returns the file.
     * Returns null when the clip is already 61 fps or less (use the original).
     */
    private suspend fun convertToCache(clip: Clip, reuse: Boolean = false): File? {
        val ctx = getApplication<Application>()
        val probe = probeIfNeeded(clip)
        val fps = probe.fps
        if (fps != null && fps <= SKIP_AT_OR_BELOW_FPS) {
            setResult(clip.id, "Clip is ${fmtFps(fps)}. No conversion needed. Use the original.")
            return null
        }
        val finalFile = CacheFiles.outputFor(ctx, clip.id)
        if (reuse && finalFile.exists() && finalFile.length() > 0) {
            setResult(clip.id, "Already converted. Used the cached 60 fps file.")
            return finalFile
        }
        CacheFiles.clearAll(ctx) // Keep at most one converted file in the cache.
        val part = CacheFiles.partFor(ctx, clip.id)
        _state.update { it.copy(busyClipId = clip.id, progress = 0) }
        val shortSide = minOf(probe.width, probe.height).takeIf { it > 0 }
            ?: minOf(clip.width, clip.height)
        val start = SystemClock.elapsedRealtime()
        try {
            Converter.convert(ctx, clip.uri, shortSide, part) { p ->
                _state.update { it.copy(progress = p) }
            }
            withContext(Dispatchers.IO) {
                if (!part.renameTo(finalFile)) error("Cannot rename the converted file")
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
        val clipSeconds = clip.durationMs / 1000.0
        val mb = finalFile.length() / 1_000_000.0
        val line = String.format(
            Locale.US,
            "Converted %.1f s clip in %.1f s (%.1fx real time), %.1f MB.",
            clipSeconds, seconds, if (seconds > 0) clipSeconds / seconds else 0.0, mb,
        )
        Log.i(TAG, "${clip.name}: $line")
        setResult(clip.id, line)
        return finalFile
    }

    private fun galleryName(clip: Clip): String =
        "ClipFix_" + clip.name.substringBeforeLast('.').take(60) + "_60fps.mp4"

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    private fun setResult(clipId: Long, line: String) =
        _state.update { it.copy(results = it.results + (clipId to line)) }

    private fun appendResult(clipId: Long, line: String) = _state.update {
        val old = it.results[clipId]
        it.copy(results = it.results + (clipId to if (old == null) line else "$old $line"))
    }

    override fun onCleared() {
        if (observerRegistered) {
            getApplication<Application>().contentResolver.unregisterContentObserver(observer)
        }
    }

    companion object {
        private const val TAG = "ClipFix"
        const val SKIP_AT_OR_BELOW_FPS = 61f

        fun fmtFps(fps: Float) = String.format(Locale.US, "%.0f fps", fps)
    }
}
