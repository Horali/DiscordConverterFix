package dev.horali.clipfix

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx)
            else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) {
                ClipFixApp()
            }
        }
    }
}

private const val VIDEO_PERMISSION = Manifest.permission.READ_MEDIA_VIDEO
private const val DISCORD_PACKAGE = "com.discord"
private const val MAX_PICK = 30

private fun hasVideoPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, VIDEO_PERMISSION) ==
        PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipFixApp(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(hasVideoPermission(context)) }
    val snackbar = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok }

    // Android's photo picker: select many old clips at once.
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK),
    ) { uris -> vm.onPicked(uris) }
    val openPicker = {
        pickLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
        )
    }

    // Check again when the user comes back, for example from the app settings.
    LifecycleResumeEffect(Unit) {
        granted = hasVideoPermission(context)
        if (granted) vm.onPermissionGranted()
        onPauseOrDispose { }
    }
    LaunchedEffect(granted) { if (granted) vm.onPermissionGranted() }

    // Open the share screen when a file is ready.
    LaunchedEffect(Unit) {
        vm.shareRequests.collect { req -> sendToDiscord(context, req.uri, req.mimeType) }
    }
    state.pendingPick?.let { pending ->
        val newCount = pending.clips.size - pending.alreadySaved
        AlertDialog(
            onDismissRequest = { vm.answerPendingPick(null) },
            title = { Text("Already saved") },
            text = {
                Text(
                    "${pending.alreadySaved} of the ${pending.clips.size} clips already have " +
                        "a 60 fps copy in the ClipFix album. A new copy makes a duplicate.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.answerPendingPick(false) }) {
                    Text(if (newCount > 0) "Save only the $newCount new" else "OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.answerPendingPick(true) }) { Text("Make new copies") }
            },
        )
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("ClipFix") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (granted && state.selected.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = vm::saveSelected,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("Save ${state.selected.size} for Discord") }
                        OutlinedButton(onClick = vm::clearSelection) { Text("Clear") }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (!granted) {
                PermissionScreen(
                    onRequest = { permissionLauncher.launch(VIDEO_PERMISSION) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    },
                )
            } else {
                ClipList(state, vm, openPicker)
            }
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "ClipFix needs access to your videos to list your screen recordings.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Select \"Allow all\". \"Selected videos only\" does not show new clips.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text("Allow video access") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenSettings) { Text("Open app settings") }
    }
}

@Composable
private fun ClipList(state: UiState, vm: MainViewModel, openPicker: () -> Unit) {
    @Composable
    fun Card(clip: Clip) = ClipCard(
        clip = clip,
        probe = state.probes[clip.id],
        savedCopy = state.copyOf(clip),
        selected = clip.id in state.selected,
        busy = state.busyClipId == clip.id,
        anyBusy = state.busy,
        progress = state.progress,
        result = state.results[clip.id],
        onToggle = { vm.toggleSelected(clip) },
        onSave = { vm.saveToGallery(listOf(clip)) },
        onSend = { vm.send(clip) },
    )

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Button(
                onClick = openPicker,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Pick videos from gallery") }
        }
        if (state.busy) {
            item {
                Text(
                    "Working on ${state.batchIndex} of ${state.batchTotal}… ${state.progress}%. " +
                        "Keep ClipFix open.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (state.picked.isNotEmpty()) {
            item {
                Text(
                    "Picked (ClipFix saves picked clips at once)",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(state.picked, key = { "p" + it.id }) { Card(it) }
            item { Text("Newest", style = MaterialTheme.typography.titleSmall) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.screenRecordingsOnly,
                    onClick = { vm.setScreenRecordingsOnly(true) },
                    label = { Text("Screen recordings") },
                )
                FilterChip(
                    selected = !state.screenRecordingsOnly,
                    onClick = { vm.setScreenRecordingsOnly(false) },
                    label = { Text("All videos") },
                )
            }
        }
        if (state.clips.isEmpty() && !state.loading) {
            item {
                Text(
                    if (state.screenRecordingsOnly) {
                        "No screen recordings found. Try \"All videos\"."
                    } else {
                        "No videos found."
                    },
                    Modifier.padding(16.dp),
                )
            }
        }
        items(state.clips, key = { it.id }) { Card(it) }
    }
}

@Composable
private fun ClipCard(
    clip: Clip,
    probe: ClipProbe?,
    savedCopy: GalleryFiles.SavedCopy?,
    selected: Boolean,
    busy: Boolean,
    anyBusy: Boolean,
    progress: Int,
    result: String?,
    onToggle: () -> Unit,
    onSave: () -> Unit,
    onSend: () -> Unit,
) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, clip.id) {
        value = ClipRepository.thumbnail(context, clip.uri)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = selected && savedCopy == null,
                    onCheckedChange = { onToggle() },
                    enabled = !anyBusy && savedCopy == null,
                )
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    thumb?.let {
                        Image(
                            it.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        clip.name,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val fpsText = when {
                        probe == null -> "reading fps…"
                        probe.fps == null -> "fps unknown"
                        else -> MainViewModel.fmtFps(probe.fps)
                    }
                    val w = probe?.width?.takeIf { it > 0 } ?: clip.width
                    val h = probe?.height?.takeIf { it > 0 } ?: clip.height
                    Text(
                        "${fmtDuration(clip.durationMs)} · ${w}×${h} · $fpsText",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        String.format(Locale.US, "%.1f MB", clip.sizeBytes / 1_000_000.0),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (savedCopy == null) {
                    Button(onClick = onSave, enabled = !anyBusy, modifier = Modifier.weight(1f)) {
                        Text("Save for Discord")
                    }
                } else {
                    val minutes = ((savedCopy.expiresAtMs - System.currentTimeMillis()) / 60_000)
                        .coerceAtLeast(0)
                    Text(
                        "Saved ✓ in the ClipFix album. Deletes in about $minutes min.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                    )
                }
                OutlinedButton(onClick = onSend, enabled = !anyBusy, modifier = Modifier.weight(1f)) {
                    Text("Send")
                }
            }
            if (busy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Converting to 60 fps… $progress%", style = MaterialTheme.typography.bodySmall)
            }
            result?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** Opens Discord's share screen directly. Falls back to the Android share sheet. */
private fun sendToDiscord(context: Context, uri: Uri, mimeType: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("ClipFix video", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent(send).setPackage(DISCORD_PACKAGE))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent.createChooser(send, "Send clip"))
    }
}
