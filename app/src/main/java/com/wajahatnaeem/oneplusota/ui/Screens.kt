package com.wajahatnaeem.oneplusota.ui

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.wajahatnaeem.oneplusota.data.LinkStatus
import androidx.compose.ui.unit.dp
import com.wajahatnaeem.oneplusota.data.CheckResult
import com.wajahatnaeem.oneplusota.data.OtaPackage
import com.wajahatnaeem.oneplusota.data.SourceType
import com.wajahatnaeem.oneplusota.device.DeviceInfo
import com.wajahatnaeem.oneplusota.download.DlState

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
        item { Text("OnePlus OTA", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
        if (vm.device.isOnePlus) {
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(vm.device.marketName.ifBlank { vm.device.model }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip(vm.device.model)
                            Chip("Region ${regionName(vm.device.regionId)}")
                        }
                        Column {
                            Text("Current version", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(vm.device.displayVersion.ifBlank { "unknown" }, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }

        if (!vm.device.isOnePlus) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text("This app only works on OnePlus devices. Detected manufacturer: ${vm.device.manufacturer}",
                        Modifier.padding(16.dp))
                }
            }
            return@LazyColumn
        }

        item {
            Button(onClick = vm::checkUpdate, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp),
                enabled = vm.check != CheckUi.Loading) {
                Icon(AppIcons.Refresh, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                Text(if (vm.check is CheckUi.Result) "Check again" else "Check for update")
            }
        }
        item { CheckCard(vm, vm.check, false) { start(it, vm.updateSpec) } }
        item { DownloadCard(vm, dl) }
        vm.message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.primary) } }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp)) }
    }
}

private fun regionName(id: Int) = when (id) { 1 -> "CN"; 2 -> "IN"; 3 -> "EU"; else -> "Global" }


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
        item { Text("OnePlus OTA", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }

        if (sel == null) {
            item {
                OutlinedTextField(
                    value = vm.query, onValueChange = { vm.query = it }, singleLine = true,
                    label = { Text("Search devices") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
                    leadingIcon = { Icon(AppIcons.Search, null, Modifier.size(22.dp)) },
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
            // One row per device AND region.
            val rows = vm.catalog.flatMap { d -> d.variants.sortedBy { v -> order.indexOf(v.region.uppercase()).let { if (it < 0) 99 else it } }.map { v -> d to v } }
                .filter { (d, v) ->
                    q.isEmpty() || "${d.name} ${v.code} ${v.label} ${v.productModel} ${v.productName.orEmpty()}".lowercase().contains(q)
                }
            items(rows) { (d, v) ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { vm.pick(d, v) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Chip(v.code)
                    }
                    Text(v.modelShown, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text("Model ${v.modelShown} · Region: ${v.label}", style = MaterialTheme.typography.bodySmall)
                        }
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
private fun InfoLine(label: String, value: String) {
    Text("$label $value", fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun LinkSection(vm: OtaViewModel, pkg: OtaPackage, other: Boolean, ctx: android.content.Context) {
    val status = if (vm.linkFor == pkg.url) vm.linkStatus else LinkStatus.Idle
    val shown = (status as? LinkStatus.Live)?.finalUrl ?: pkg.url

    Text("Download link", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(shown, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(14.dp)).padding(12.dp))
    FilledTonalButton(onClick = { vm.copyFreshLink(pkg, other) { LinkTools.copy(ctx, "Fresh link", it) } },
        enabled = status != LinkStatus.Checking, shape = RoundedCornerShape(23.dp), modifier = Modifier.fillMaxWidth().height(46.dp)) {
        Icon(AppIcons.Copy, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
        Text(if (status == LinkStatus.Checking) "Getting fresh link…" else "Copy link")
    }
    Column {
        pkg.fileName.takeIf { !it.equals("downloadCheck", ignoreCase = true) }?.let { InfoLine("File name:", it) }
        pkg.md5?.let { InfoLine("MD5:", it) }
    }
    Text("Source: ${pkg.source.label} · Link may expire in 15–30 minutes. Tap Check again for a fresh one.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Chip(text: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
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
            is CheckResult.Found -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = cardColors) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Latest full OTA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(r.pkg.version, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.padding(vertical = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip(fmt(r.pkg.sizeBytes))
                            Chip(if (r.pkg.source == SourceType.OXYGEN_UPDATER) "Backup" else "Official")
                            r.pkg.securityPatch?.let { Chip("Patch $it") }
                        }
                        Button(onClick = { onDownload(r.pkg) }, shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Icon(AppIcons.Download, null, Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
                            Text("Download")
                        }
                    }
                }
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = cardColors) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { LinkSection(vm, r.pkg, other, ctx) }
                }
                if (r.notes.isNotEmpty() || r.pkg.changelog != null) {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = cardColors) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            if (r.notes.isNotEmpty()) Collapsible("Source log", r.notes.joinToString("\n") { "• $it" })
                            r.pkg.changelog?.let { Expandable(cleanChangelog(it)) }
                        }
                    }
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
