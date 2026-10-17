package com.zhuoying.ariaedgevlm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun PhoneOriginalMemoryCard(inlet: PhoneJpegIngress) {
    val context = LocalContext.current.applicationContext
    val mem = remember { PhoneOriginalMemoryClips.get(context) }
    var show by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Block 12B · Original MemoryWriter 30s Clips", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Automatically write visual clips: ${if (mem.enabled) "ON" else "OFF"}")
            Switch(checked = mem.enabled, onCheckedChange = { mem.setMonitoringEnabled(it); if (it) inlet.start() })
        }
        Text("Original repository protocol: sample each second, 30s segment, up to 8 frames, " +
            "video_description + high_level_conclusions. No per-object subscription. " +
            "Foreground pilot; StreamMeCo graph, YOLO spatial index and identity consolidation are NOT migrated yet.",
            style = MaterialTheme.typography.bodySmall)
        // ORIGINAL_MEMORY12B1_UI: audit incomplete windows and short motion evidence.
        Text("Saved clips: ${mem.written} · Skipped: ${mem.skipped}", style = MaterialTheme.typography.bodySmall)
        Text("Collecting: ${mem.sampledFrames} / ~61 sampled frames (2 FPS, 30s)",
            style = MaterialTheme.typography.bodySmall)
        mem.lastPeakDiff?.let { peak ->
            Text("Previous clip's largest visual change: ${"%.1f".format(peak)}",
                style = MaterialTheme.typography.bodySmall)
        }
        Text(mem.status, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { show = !show }, modifier = Modifier.fillMaxWidth()) {
            Text(if (show) "Hide recent automatic clip notes" else "Show recent automatic clip notes")
        }
        if (show) Text(mem.preview(), style = MaterialTheme.typography.bodySmall)
        if (!confirm) {
            OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Clear automatic clip history…")
            }
        } else {
            Text("Delete all automatically saved clip descriptions and evidence?", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { mem.clear(); confirm = false }, modifier = Modifier.fillMaxWidth()) {
                Text("Confirm delete automatic clip history")
            }
            OutlinedButton(onClick = { confirm = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}
