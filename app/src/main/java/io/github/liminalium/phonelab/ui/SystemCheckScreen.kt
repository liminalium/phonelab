package io.github.liminalium.phonelab.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.liminalium.phonelab.syscheck.Capability
import io.github.liminalium.phonelab.syscheck.Check
import io.github.liminalium.phonelab.syscheck.CheckState
import io.github.liminalium.phonelab.syscheck.Checks
import io.github.liminalium.phonelab.syscheck.Kind
import io.github.liminalium.phonelab.syscheck.ReportStore
import io.github.liminalium.phonelab.syscheck.Status
import io.github.liminalium.phonelab.syscheck.SystemCheckViewModel
import io.github.liminalium.phonelab.syscheck.Verdict
import io.github.liminalium.phonelab.syscheck.miuiVersion
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SystemCheckScreen(vm: SystemCheckViewModel = viewModel()) {
    val ctx = LocalContext.current
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.runTests()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshReadiness() }
    vm.message?.let { msg ->
        LaunchedEffect(msg) {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            vm.message = null
        }
    }
    val info = vm.checks.filter { it.kind == Kind.INFO }
    val tests = vm.checks.filter { it.kind == Kind.TEST }

    Scaffold { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Header(vm) }
            item {
                Actions(vm, onRunTests = { askPermissions.launch(Checks.testPermissions) })
            }
            item { GroupTitle("Phone info", "Read from the system on every check. No permissions needed.") }
            items(info, key = { it.id }) { c ->
                CheckCard(c, vm.states[c.id], readinessActions = c.id == "readiness")
            }
            item {
                GroupTitle(
                    "Hardware tests",
                    "Uses the cameras, mic, location and Bluetooth for about 30 s. GPS works best near a window.",
                )
            }
            items(tests, key = { it.id }) { c -> CheckCard(c, vm.states[c.id]) }
            item {
                GroupTitle("What this phone can do", "The build plan's modules, checked against this phone.")
            }
            items(vm.capabilities(), key = { it.area + it.module }) { CapabilityRow(it) }
            item {
                Text(
                    "No identifiers are saved: no IMEI, serial number, MAC address, network names or coordinates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())

@Composable
private fun Header(vm: SystemCheckViewModel) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Text("System check", style = MaterialTheme.typography.headlineMedium)
        (vm.states["device"] as? CheckState.Done)?.let {
            Text(it.outcome.headline, style = MaterialTheme.typography.bodyMedium)
        }
        val status = when {
            vm.readingInfo -> "Reading phone info…"
            vm.testing -> "Running hardware tests…"
            else -> vm.createdAt?.let { "Checked ${timeFormat.format(it)}" } ?: ""
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val saved = vm.exportedPath ?: vm.savedFile?.let { "app storage" }
        if (saved != null) {
            Text("Saved to $saved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Actions(vm: SystemCheckViewModel, onRunTests: () -> Unit) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRunTests, enabled = !vm.busy, modifier = Modifier.weight(1f)) {
                Text("Run hardware tests")
            }
            OutlinedButton(onClick = { vm.runInfo() }, enabled = !vm.busy) { Text("Check again") }
        }
        Row {
            TextButton(onClick = { copy(ctx, vm.summaryText(), "Summary copied") }, modifier = Modifier.weight(1f)) {
                Text("Copy summary")
            }
            TextButton(onClick = { copy(ctx, vm.buildReport().toString(1), "Full report copied") }, modifier = Modifier.weight(1f)) {
                Text("Copy JSON")
            }
            TextButton(
                onClick = {
                    val file = vm.savedFile
                    if (file == null) toast(ctx, "Nothing saved yet")
                    else ctx.startActivity(ReportStore.shareIntent(ctx, file, vm.summaryText()))
                },
                modifier = Modifier.weight(1f),
            ) { Text("Share") }
        }
    }
}

private fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

private fun copy(ctx: Context, text: String, done: String) {
    try {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PhoneLab system check", text))
        toast(ctx, done)
    } catch (e: Exception) {
        toast(ctx, "Too large for the clipboard; use Share")
    }
}

@Composable
private fun GroupTitle(title: String, subtitle: String) {
    Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusBadge(state: CheckState?) {
    if (state is CheckState.Running) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        return
    }
    val (symbol, color) = when ((state as? CheckState.Done)?.outcome?.status) {
        Status.OK -> "✓" to StatusColors.ok
        Status.WARN -> "!" to StatusColors.warn
        Status.FAIL -> "✗" to StatusColors.fail
        Status.SKIPPED -> "–" to StatusColors.muted
        null -> "·" to StatusColors.muted
    }
    Box(Modifier.size(20.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(symbol, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CheckCard(check: Check, state: CheckState?, readinessActions: Boolean = false) {
    var expanded by rememberSaveable(check.id) { mutableStateOf(false) }
    val done = state as? CheckState.Done
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(enabled = done != null) { expanded = !expanded },
            ) {
                StatusBadge(state)
                Spacer(Modifier.width(10.dp))
                Text(check.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (done != null) Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.titleSmall)
            }
            val headline = when (state) {
                is CheckState.Done -> state.outcome.headline
                CheckState.Running -> "running…"
                else -> if (check.kind == Kind.TEST) "not run yet" else "waiting"
            }
            Text(headline, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 30.dp, top = 2.dp))
            if (readinessActions) ReadinessActions()
            if (expanded && done != null) {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                JsonTree(done.outcome.data)
            }
        }
    }
}

@Composable
private fun CapabilityRow(c: Capability) {
    val color = when (c.verdict) {
        Verdict.YES -> StatusColors.ok
        Verdict.PARTIAL -> StatusColors.warn
        Verdict.NO -> StatusColors.fail
        Verdict.UNKNOWN -> StatusColors.muted
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(c.verdict.symbol, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text("${c.module}  ·  ${c.area}", style = MaterialTheme.typography.bodyMedium)
            c.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------- Shortcuts to the settings the recorder needs ----------

private fun openFirst(ctx: Context, vararg intents: Intent) {
    for (intent in intents) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // Try the next screen; phone makers move these around.
        }
    }
    toast(ctx, "Couldn't open that settings screen")
}

private fun appDetails(ctx: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

@Composable
private fun ReadinessActions() {
    val ctx = LocalContext.current
    val miui = remember { miuiVersion() != null }
    Column(Modifier.padding(start = 22.dp, top = 4.dp)) {
        Row {
            TextButton(
                onClick = {
                    openFirst(ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")), appDetails(ctx))
                },
                modifier = Modifier.weight(1f),
            ) { Text("Display over apps") }
            TextButton(
                onClick = {
                    openFirst(
                        ctx,
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    )
                },
                modifier = Modifier.weight(1f),
            ) { Text("Battery: no limits") }
        }
        Row {
            if (miui) {
                TextButton(
                    onClick = {
                        openFirst(
                            ctx,
                            Intent("miui.intent.action.APP_PERM_EDITOR")
                                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                                .putExtra("extra_pkgname", ctx.packageName),
                            Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", ctx.packageName),
                            appDetails(ctx),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("MIUI permissions") }
                TextButton(
                    onClick = {
                        openFirst(
                            ctx,
                            Intent().setComponent(
                                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                            ),
                            appDetails(ctx),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("MIUI autostart") }
            } else {
                TextButton(onClick = { openFirst(ctx, appDetails(ctx)) }) { Text("App settings") }
            }
        }
    }
}
