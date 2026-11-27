package com.king.oneplusota.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.king.oneplusota.data.CheckResult
import com.king.oneplusota.data.OtaPackage
import com.king.oneplusota.download.DlState

class MainActivity : ComponentActivity() {
    private val vm: OtaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { OtaScreen(vm) }
            }
        }
    }
}

private fun fmt(b: Long) = if (b <= 0) "?" else if (b >= 1L shl 30) "%.2f GB".format(b / 1073741824.0) else "%.0f MB".format(b / 1048576.0)

@Composable
fun OtaScreen(vm: OtaViewModel) {
    val dl by vm.dl.collectAsState()
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun startDownload(p: OtaPackage) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        vm.download(p)
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("OnePlus OTA", style = MaterialTheme.typography.headlineMedium) }

        if (!vm.device.isOnePlus) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text("Ye app sirf OnePlus devices ke liye hai. Is device ka manufacturer: ${vm.device.manufacturer}",
                        Modifier.padding(16.dp))
                }
            }
            return@LazyColumn
        }

        item { DeviceCard(vm) }
        item {
            Button(onClick = vm::checkUpdate, modifier = Modifier.fillMaxWidth(), enabled = vm.check != CheckUi.Loading) {
                Text("Check for update")
            }
        }
        item { CheckCard(vm, ::startDownload) }
        item { DownloadCard(vm, dl) }
        vm.message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.primary) } }
    }
}

@Composable
private fun DeviceCard(vm: OtaViewModel) {
    val d = vm.device
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(d.marketName.ifBlank { d.model }, style = MaterialTheme.typography.titleMedium)
            Text("Model ${d.model} · Android ${d.androidRelease}")
            Text("OTA: ${d.otaVersion.ifBlank { "unknown" }}", style = MaterialTheme.typography.bodySmall)
            Text("Region", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = vm.regionId == null, onClick = { vm.setRegion(null) }, label = { Text("Auto") })
                listOf(0 to "Global", 1 to "CN", 2 to "IN", 3 to "EU").forEach { (id, name) ->
                    FilterChip(selected = vm.regionId == id, onClick = { vm.setRegion(id) }, label = { Text(name) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = vm.fullOnly, onCheckedChange = { vm.fullOnly = it })
                Text("Sirf Full OTA (bada package)")
            }
        }
    }
}

@Composable
private fun CheckCard(vm: OtaViewModel, onDownload: (OtaPackage) -> Unit) {
    when (val c = vm.check) {
        CheckUi.Idle -> {}
        CheckUi.Loading -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        is CheckUi.Result -> when (val r = c.r) {
            is CheckResult.Found -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(r.pkg.version, style = MaterialTheme.typography.titleMedium)
                    Text("${fmt(r.pkg.sizeBytes)} · ${if (r.pkg.isFull) "FULL" else "incremental"} · MD5 ${if (r.pkg.md5 != null) "available" else "n/a"}")
                    Text("Source: ${r.pkg.source.label}", style = MaterialTheme.typography.bodySmall)
                    r.pkg.changelog?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    r.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { onDownload(r.pkg) }) { Text("Download") }
                }
            }
            is CheckResult.NoPackage, is CheckResult.Failed -> {
                val notes = if (r is CheckResult.NoPackage) r.notes else (r as CheckResult.Failed).notes
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (r is CheckResult.NoPackage) "Koi full package nahi mila" else "Sab sources fail hue",
                            style = MaterialTheme.typography.titleMedium)
                        notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        OutlinedButton(onClick = vm::copyRawResponse) { Text("Raw server response copy karo") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(vm: OtaViewModel, s: DlState) {
    if (s is DlState.Idle) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (s) {
                is DlState.Active -> {
                    Text(s.pkg.fileName, style = MaterialTheme.typography.titleSmall)
                    Progress(s.done, s.total)
                    Text("${fmt(s.done)} / ${fmt(s.total)} · ${if (s.bps > 0) "%.1f MB/s".format(s.bps / 1048576.0) else "…"}")
                    s.note?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::pause) { Text("Pause") }
                        OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                    }
                }
                is DlState.Paused -> {
                    Text(s.pkg.fileName, style = MaterialTheme.typography.titleSmall)
                    Progress(s.done, s.total)
                    Text("Paused at ${fmt(s.done)} / ${fmt(s.total)} — ${s.reason}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::resume) { Text("Resume") }
                        OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                    }
                }
                is DlState.Failed -> {
                    Text(s.pkg.fileName, style = MaterialTheme.typography.titleSmall)
                    if (s.done > 0) Progress(s.done, s.total)
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    if (s.done > 0) Text("${fmt(s.done)} safe hai — Retry wahin se continue karega.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (s.done > 0) vm.resume() else vm.download(s.pkg) }) { Text("Retry") }
                        OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                    }
                }
                is DlState.Verifying -> {
                    Text("MD5 verify ho raha hai…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is DlState.Done -> {
                    Text("Download complete ✓", style = MaterialTheme.typography.titleSmall)
                    Text(when (s.md5Ok) { true -> "MD5 verified"; false -> "MD5 mismatch"; null -> "MD5 available nahi tha" })
                    Text(s.path, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.exportToDownloads(s.path) }) { Text("Downloads folder mein save karo") }
                }
                DlState.Idle -> {}
            }
        }
    }
}

@Composable
private fun Progress(done: Long, total: Long) {
    if (total > 0) LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    else LinearProgressIndicator(Modifier.fillMaxWidth())
}
