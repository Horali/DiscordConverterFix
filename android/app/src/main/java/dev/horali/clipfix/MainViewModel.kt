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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

enum class ShareAction { COPY, SEND }

/** A ready file and what the UI must do with it. */
data class ShareRequest(val uri: Uri, val mimeType: String, val action: ShareAction)

data class UiState(
    val clips: List<Clip> = emptyList(),
    val probes: Map<Long, ClipProbe> = emptyMap(),
    val screenRecordingsOnly: Boolean = true,
    val loading: Boolean = false,
    val busyClipId: Long? = null,
    val progress: Int = 0,
    /** Per clip: the last result line, for example the conversion time. */
    val results: Map<Long, String> = emptyMap(),
    val message: String? = null,
)

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
        _state.update { it.copy(screenRecordingsOnly = value) }
        refresh()
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private var refreshJob: Job? = null

    fun refresh() {
        // MediaStore sends many change events while a recording is written. Keep only the last one.
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
            // Read the frame rate of each clip that we have not read yet.
            for (clip in clips) {
                if (_state.value.probes.containsKey(clip.id)) continue
                val probe = ClipRepository.probe(ctx, clip.uri)
                _state.update { it.copy(probes = it.probes + (clip.id to probe)) }
            }
        }
    }

    /** Converts the clip if needed, then asks the UI to copy or share it. */
    fun prepare(clip: Clip, action: ShareAction) {
        if (_state.value.busyClipId != null) return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val probe = _state.value.probes[clip.id] ?: ClipRepository.probe(ctx, clip.uri)
                .also { p -> _state.update { it.copy(probes = it.probes + (clip.id to p)) } }

            val fps = probe.fps
            if (fps != null && fps <= SKIP_AT_OR_BELOW_FPS) {
                setResult(clip.id, "Clip is ${fmtFps(fps)}. No conversion needed. Original used.")
                _shareRequests.send(ShareRequest(clip.uri, clip.mimeType, action))
                return@launch
            }

            val finalFile = CacheFiles.outputFor(ctx, clip.id)
            if (finalFile.exists() && finalFile.length() > 0) {
                setResult(clip.id, "Already converted. Used the cached 60 fps file.")
            } else {
                CacheFiles.clearAll(ctx) // Keep at most one converted file.
                val part = CacheFiles.partFor(ctx, clip.id)
                _state.update { it.copy(busyClipId = clip.id, progress = 0) }
                val shortSide = minOf(probe.width, probe.height).takeIf { it > 0 }
                    ?: minOf(clip.width, clip.height)
                val start = SystemClock.elapsedRealtime()
                try {
                    Converter.convert(ctx, clip.uri, shortSide, part) { p ->
                        _state.update { it.copy(progress = p) }
                    }
                    if (!part.renameTo(finalFile)) error("Cannot rename the converted file")
                } catch (e: Exception) {
                    Log.e(TAG, "Conversion failed", e)
                    part.delete()
                    _state.update { it.copy(busyClipId = null) }
                    setResult(clip.id, "Conversion failed: ${e.message ?: e.javaClass.simpleName}")
                    return@launch
                }
                val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0
                val clipSeconds = clip.durationMs / 1000.0
                val mb = finalFile.length() / 1_000_000.0
                val line = String.format(
                    Locale.US,
                    "Converted %.1f s clip in %.1f s (%.1fx real time), %.1f MB.",
                    clipSeconds, seconds, clipSeconds / seconds, mb,
                )
                Log.i(TAG, "${clip.name}: $line")
                setResult(clip.id, line)
                _state.update { it.copy(busyClipId = null) }
            }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", finalFile)
            _shareRequests.send(ShareRequest(uri, "video/mp4", action))
        }
    }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    private fun setResult(clipId: Long, line: String) =
        _state.update { it.copy(results = it.results + (clipId to line)) }

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
