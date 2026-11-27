package com.king.oneplusota.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.king.oneplusota.data.LinkStatus
import androidx.compose.ui.unit.dp
import com.king.oneplusota.data.CheckResult
import com.king.oneplusota.data.OtaPackage
import com.king.oneplusota.device.DeviceInfo
import com.king.oneplusota.download.DlState

private fun fmt(b: Long) = if (b <= 0) "?" else if (b >= 1L shl 30) "%.2f GB".format(b / 1073741824.0) else "%.0f MB".format(b / 1048576.0)

/** Asks for notification permission once (Android 13+), then starts the download service via the VM. */
@Composable
private fun rememberStarter(vm: OtaViewModel): (OtaPackage, DeviceInfo) -> Unit {
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return { pkg, spec ->
        if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
        vm.download(pkg, spec)
    }
}

// =================================================================================================
// Tab 1: Update (this phone)
// =================================================================================================
@Composable
fun UpdateTab(vm: OtaViewModel) {
    val dl by vm.dl.collectAsState()
    val start = rememberStarter(vm)

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Update", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp)) }

        if (!vm.device.isOnePlus) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text("This app only works on OnePlus devices. Detected manufacturer: ${vm.device.manufacturer}",
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
        item { CheckCard(vm, vm.check, false) { start(it, vm.updateSpec) } }
        item { DownloadCard(vm, dl) }
        vm.message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.primary) } }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp)) }
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
            Text("Region (auto: ${regionName(d.regionId)})", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = vm.regionId == null, onClick = { vm.setRegion(null) }, label = { Text("Auto") })
                listOf(0 to "Global", 1 to "CN", 2 to "IN", 3 to "EU").forEach { (id, name) ->
                    FilterChip(selected = vm.regionId == id, onClick = { vm.setRegion(id) }, label = { Text(name) })
                }
            }
            FullOnlyRow(vm)
        }
    }
}

private fun regionName(id: Int) = when (id) { 1 -> "CN"; 2 -> "IN"; 3 -> "EU"; else -> "Global" }

@Composable
private fun FullOnlyRow(vm: OtaViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = vm.fullOnly, onCheckedChange = { vm.fullOnly = it })
        Text("Full OTA only")
    }
}

// =================================================================================================
// Tab 2: Other (any OnePlus device)
// =================================================================================================
@Composable
fun OtherTab(vm: OtaViewModel) {
    val dl by vm.dl.collectAsState()
    val start = rememberStarter(vm)
    LaunchedEffect(Unit) { vm.loadCatalog() }
    val sel = vm.selected

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Other devices", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp)) }

        if (sel == null) {
            item {
                OutlinedTextField(
                    value = vm.query, onValueChange = { vm.query = it }, singleLine = true,
                    label = { Text("OnePlus device search (13R, 12, Nord…)") }, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (vm.catalogLoading) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
            vm.catalogError?.let { e ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(e, color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = vm::reloadCatalog) { Text("Retry") }
                        }
                    }
                }
            }
            val q = vm.query.trim().lowercase()
            val order = listOf("GLO", "IN", "EU", "CN")
            // One row per device AND region, like Oxygen Updater's picker.
            val rows = vm.catalog.flatMap { d -> d.variants.sortedBy { v -> order.indexOf(v.region.uppercase()).let { if (it < 0) 99 else it } }.map { v -> d to v } }
                .filter { (d, v) ->
                    q.isEmpty() || "${d.name} ${v.code} ${v.label} ${v.productModel} ${v.productName.orEmpty()}".lowercase().contains(q)
                }
            items(rows) { (d, v) ->
                Card(Modifier.fillMaxWidth().clickable { vm.pick(d, v) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${d.name} (${v.code})", style = MaterialTheme.typography.titleMedium)
                        Text("${v.modelShown} · ${d.android}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (!vm.catalogLoading && vm.catalogError == null && rows.isEmpty()) item { Text("No devices found") }
        } else {
            item { OutlinedButton(onClick = { vm.select(null) }) { Text("← Back to list") } }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.variant?.let { v ->
                            Text("${sel.name} (${v.code})", style = MaterialTheme.typography.titleMedium)
                            Text("Model ${v.modelShown} · Region: ${v.label} · ${sel.android}", style = MaterialTheme.typography.bodySmall)
                        }
                        FullOnlyRow(vm)
                    }
                }
            }
            item {
                Button(onClick = vm::checkOther, modifier = Modifier.fillMaxWidth(), enabled = vm.otherCheck != CheckUi.Loading) {
                    Text("Find latest full OTA")
                }
            }
            item {
                CheckCard(vm, vm.otherCheck, true) { pkg -> vm.otherSpec?.let { start(pkg, it) } }
            }
            item { DownloadCard(vm, dl) }
            vm.message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.primary) } }
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp)) }
    }
}

// =================================================================================================
// Shared cards
// =================================================================================================
@Composable
private fun LinkSection(vm: OtaViewModel, pkg: OtaPackage, other: Boolean, ctx: android.content.Context) {
    val status = if (vm.linkFor == pkg.url) vm.linkStatus else LinkStatus.Idle
    val shown = (status as? LinkStatus.Live)?.finalUrl ?: pkg.url

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Download link", style = MaterialTheme.typography.labelMedium)
    Text(shown, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp))
    Button(onClick = { vm.copyFreshLink(pkg, other) { LinkTools.copy(ctx, "Fresh link", it) } },
        enabled = status != LinkStatus.Checking, modifier = Modifier.fillMaxWidth()) {
        Text(if (status == LinkStatus.Checking) "Getting fresh link…" else "Copy link")
    }
    pkg.md5?.let { md5 ->
        Text("MD5: $md5", fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp))
    }
    Text("Links can expire after some hours or days.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Removes markdown heading marks (##) that some changelogs contain. */
private fun cleanChangelog(s: String) = s.lines().joinToString("\n") { it.trimStart('#').trim() }

/** Long text collapsed to a few lines with a Show more / Show less toggle. */
@Composable
private fun Expandable(text: String, collapsedLines: Int = 5) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(text, style = MaterialTheme.typography.bodySmall,
            maxLines = if (open) Int.MAX_VALUE else collapsedLines, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = { open = !open }) { Text(if (open) "Show less" else "Show more") }
    }
}

@Composable
private fun Collapsible(title: String, body: String) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = !open }) { Text(if (open) "▾ $title" else "▸ $title") }
        if (open) Text(body, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CheckCard(vm: OtaViewModel, state: CheckUi, other: Boolean, onDownload: (OtaPackage) -> Unit) {
    val ctx = LocalContext.current
    when (state) {
        CheckUi.Idle -> {}
        CheckUi.Loading -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        is CheckUi.Result -> when (val r = state.r) {
            is CheckResult.Found -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(r.pkg.version, style = MaterialTheme.typography.titleMedium)
                    Text("${fmt(r.pkg.sizeBytes)} · ${if (r.pkg.isFull) "FULL" else "incremental"} · MD5 ${if (r.pkg.md5 != null) "available" else "n/a"}")
                    Text("Source: ${r.pkg.source.label}", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onDownload(r.pkg) }, modifier = Modifier.fillMaxWidth()) { Text("Download") }
                    LinkSection(vm, r.pkg, other, ctx)
                    if (r.notes.isNotEmpty()) Collapsible("Source log", r.notes.joinToString("\n") { "• $it" })
                    r.pkg.changelog?.let { Expandable(cleanChangelog(it)) }
                }
            }
            is CheckResult.NoPackage, is CheckResult.Failed -> {
                val notes = if (r is CheckResult.NoPackage) r.notes else (r as CheckResult.Failed).notes
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (r is CheckResult.NoPackage) "No full package found" else "All sources failed",
                            style = MaterialTheme.typography.titleMedium)
                        notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        OutlinedButton(onClick = vm::copyRawResponse) { Text("Copy raw server response") }
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
                    if (s.done > 0) Text("${fmt(s.done)} is saved. Retry continues from there.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (s.done > 0) vm.resume() else vm.restart(s.pkg) }) { Text("Retry") }
                        OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                    }
                }
                is DlState.Verifying -> {
                    Text("Verifying MD5…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is DlState.Done -> {
                    Text("Download complete ✓", style = MaterialTheme.typography.titleSmall)
                    Text(when (s.md5Ok) { true -> "MD5 verified"; false -> "MD5 mismatch"; null -> "No MD5 available" })
                    Text(s.path, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.exportToDownloads(s.path) }) { Text("Save to Downloads") }
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
