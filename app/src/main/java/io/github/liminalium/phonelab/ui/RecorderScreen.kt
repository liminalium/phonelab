package io.github.liminalium.phonelab.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.liminalium.phonelab.recorder.RecorderService
import io.github.liminalium.phonelab.recorder.RecorderState
import io.github.liminalium.phonelab.recorder.Recordings
import io.github.liminalium.phonelab.syscheck.fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Android 14+: ask for the whole screen, so the camera bubbles end up in the video. */
private fun captureIntent(ctx: Context): Intent {
    val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
    return if (Build.VERSION.SDK_INT >= 34) {
        mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    } else {
        mpm.createScreenCaptureIntent()
    }
}

private fun granted(ctx: Context, permission: String) =
    ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

private fun elapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return fmt("%d:%02d", s / 60, s % 60)
}

@Composable
fun RecorderScreen() {
    val ctx = LocalContext.current
    val state by RecorderState.state.collectAsState()
    val events by RecorderState.events.collectAsState()
    var mic by rememberSaveable { mutableStateOf(true) }
    var bubbles by rememberSaveable { mutableStateOf(true) }
    var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var recordings by remember { mutableStateOf(emptyList<Recordings.Item>()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { overlayAllowed = Settings.canDrawOverlays(ctx) }
    LaunchedEffect(state) {
        recordings = withContext(Dispatchers.IO) { Recordings.list(ctx) }
        while (state is RecorderState.State.Recording) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val askCapture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            RecorderService.start(ctx, result.resultCode, data, mic, bubbles)
        } else {
            Toast.makeText(ctx, "Screen capture wasn't allowed", Toast.LENGTH_SHORT).show()
        }
    }
    // Ask for what's missing, then for screen capture; anything refused is simply left out.
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askCapture.launch(captureIntent(ctx))
    }
    fun startRecording() {
        val needed = buildList {
            if (mic) add(Manifest.permission.RECORD_AUDIO)
            if (bubbles) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filterNot { granted(ctx, it) }
        if (needed.isEmpty()) askCapture.launch(captureIntent(ctx)) else askPermissions.launch(needed.toTypedArray())
    }

    val recording = state as? RecorderState.State.Recording
    Scaffold(
        topBar = {
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars),
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column {
                    Text("Recorder", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Records the screen, the microphone and both cameras into one video. " +
                            "Start here, switch to any app, and stop from the notification.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                if (recording != null) {
                    Button(
                        onClick = { RecorderService.stop(ctx) },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = StatusColors.fail, contentColor = Color.White),
                    ) { Text("Stop recording · ${elapsed(now - recording.startedAt)}") }
                } else {
                    Button(onClick = { startRecording() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text("Start recording")
                    }
                }
            }
            item { StatusCard(state) }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        SwitchRow("Microphone", "Your voice and the room", mic, enabled = recording == null) { mic = it }
                        SwitchRow("Camera bubbles", "Front and back camera, floating over the screen", bubbles, enabled = recording == null) { bubbles = it }
                        if (bubbles && !overlayAllowed) {
                            Text(
                                "Camera bubbles need \"Display over other apps\". Without it the video has no bubbles.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusColors.warn,
                            )
                            TextButton(onClick = {
                                try {
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")),
                                    )
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, "Open Settings › Apps › PhoneLab to allow it", Toast.LENGTH_LONG).show()
                                }
                            }) { Text("Allow display over other apps") }
                        }
                    }
                }
            }
            item {
                Column {
                    Text(
                        "Recordings",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (recordings.isEmpty()) {
                        Text("None yet. Videos are saved to Movies/PhoneLab.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(recordings, key = { it.uri.toString() }) { item -> RecordingRow(item) }
            if (events.isNotEmpty()) {
                item {
                    Column {
                        Text(
                            "Log",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        events.forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: RecorderState.State) {
    val ctx = LocalContext.current
    val (text, color) = when (state) {
        RecorderState.State.Idle -> "Ready. Android will ask to share your screen each time." to MaterialTheme.colorScheme.onSurfaceVariant
        is RecorderState.State.Recording -> "Recording. You can leave the app; stop from the notification or here." to StatusColors.fail
        is RecorderState.State.Saved -> "Saved ${state.name}" to StatusColors.ok
        is RecorderState.State.Failed -> state.message to StatusColors.fail
    }
    Column {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
        if (state is RecorderState.State.Saved) {
            TextButton(onClick = { play(ctx, state.uri) }) { Text("Play") }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun RecordingRow(item: Recordings.Item) {
    val ctx = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { play(ctx, item.uri) }.padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium)
            val duration = if (item.durationMs > 0) elapsed(item.durationMs) else "—"
            Text(
                "$duration · ${fmt("%.1f MB", item.sizeBytes / 1e6)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("▶", color = MaterialTheme.colorScheme.primary)
    }
}

private fun play(ctx: Context, uri: Uri) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    } catch (e: Exception) {
        Toast.makeText(ctx, "No app found to play the video", Toast.LENGTH_SHORT).show()
    }
}
