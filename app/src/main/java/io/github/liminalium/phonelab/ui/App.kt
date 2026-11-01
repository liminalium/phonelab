package io.github.liminalium.phonelab.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.liminalium.phonelab.syscheck.ReportStore

private const val RECORDER = 0
private const val SYSTEM_CHECK = 1

/** Two tabs. The very first launch opens the system check, later ones the recorder. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneLabApp() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(if (ReportStore.latest(ctx) == null) SYSTEM_CHECK else RECORDER) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == RECORDER,
                    onClick = { tab = RECORDER },
                    icon = { Text("⏺") },
                    label = { Text("Recorder") },
                )
                NavigationBarItem(
                    selected = tab == SYSTEM_CHECK,
                    onClick = { tab = SYSTEM_CHECK },
                    icon = { Text("✓") },
                    label = { Text("System check") },
                )
            }
        },
    ) { padding ->
        // The bar handles the bottom inset; each screen handles the top itself.
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
        Box(Modifier.padding(bottom).consumeWindowInsets(bottom)) {
            when (tab) {
                RECORDER -> RecorderScreen()
                else -> SystemCheckScreen()
            }
        }
    }
}
