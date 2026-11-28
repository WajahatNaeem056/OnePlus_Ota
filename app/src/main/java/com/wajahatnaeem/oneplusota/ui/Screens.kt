@file:OptIn(ExperimentalLayoutApi::class)

package com.wajahatnaeem.oneplusota.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
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

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            Text("OS version", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(vm.device.fullOsVersion.ifBlank { vm.device.displayVersion }.ifBlank { "unknown" }, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        Column {
                            Text("OTA version", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(vm.device.otaVersion.ifBlank { "unknown" }, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }

        if (!vm.device.isOnePlus) {
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
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
        item { CheckCard(vm, vm.check, false, vm.checkedAt, current = vm.device) { start(it, vm.updateSpec) } }
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

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
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
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
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
                CheckCard(vm, vm.otherCheck, true, vm.otherCheckedAt) { pkg -> vm.otherSpec?.let { start(pkg, it) } }
            }
            item { DownloadCard(vm, dl) }
            vm.message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.primary) } }
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp)) }
    }
}

// =================================================================================================
// Tab 3: Settings
// =================================================================================================
@Composable
fun SettingsTab(vm: OtaViewModel) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "System", 1 to "Light", 2 to "Dark").forEach { (mode, label) ->
                            FilterChip(selected = vm.themeMode == mode, onClick = { vm.setTheme(mode) }, label = { Text(label) })
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Auto check for updates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text("Check by itself when the app opens. When off, it checks only when you press Check for update.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = vm.autoCheck, onCheckedChange = { vm.setAutoCheck(it) })
                }
            }
        }
    }
}

// =================================================================================================
// Shared cards
// =================================================================================================
@Composable
private fun InfoLine(text: String, maxLines: Int = 1) {
    Text(text, fontSize = 12.sp, lineHeight = 17.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun LinkSection(vm: OtaViewModel, pkg: OtaPackage, other: Boolean, ctx: android.content.Context) {
    val status = if (vm.linkFor == pkg.url) vm.linkStatus else LinkStatus.Idle
    val shown = (status as? LinkStatus.Live)?.finalUrl ?: pkg.url

    Text("Download link", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(shown, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp))
        FilledTonalButton(onClick = { vm.copyFreshLink(pkg, other) { LinkTools.copy(ctx, "Fresh link", it) } },
            enabled = status != LinkStatus.Checking, shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.height(48.dp)) {
            Icon(AppIcons.Copy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(if (status == LinkStatus.Checking) "…" else "Copy")
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        pkg.fileName.takeIf { !it.equals("downloadCheck", ignoreCase = true) }?.let { InfoLine("File name: $it") }
        pkg.md5?.let { InfoLine("MD5: $it") }
        InfoLine("Source: ${pkg.source.label} · Link may expire in 15–30 minutes. Tap Check again for a fresh one.", maxLines = 3)
    }
}

@Composable
private fun Chip(text: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

private fun agoLabel(at: Long, now: Long): String {
    val sec = ((now - at) / 1000).coerceAtLeast(0)
    return when {
        sec < 60 -> "Checked just now"
        sec < 3600 -> "Checked ${sec / 60} min ago"
        else -> "Checked ${sec / 3600} h ago"
    }
}

/** Changelog text: heading lines (## or a short line without end punctuation) become bold titles, the rest is body text. */
@Composable
private fun ChangelogBody(text: String) {
    val lines = text.lines().map { it.trim() }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEachIndexed { i, raw ->
            val line = raw.trimStart('#').trim()
            if (line.isBlank()) { if (raw.isBlank()) Spacer(Modifier.height(4.dp)); return@forEachIndexed }
            val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() }
            val isTitle = raw.startsWith("#") || (line.length <= 40 && line.last() !in ".:;,!" &&
                !line.startsWith("•") && !line.startsWith("-") && next != null)
            if (isTitle) Text(line, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = if (i > 0) 10.dp else 0.dp))
            else Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Card with a title row + chevron; the body shows only when opened. */
@Composable
private fun FoldCard(title: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(if (open) AppIcons.ExpandLess else AppIcons.ExpandMore, null, Modifier.size(24.dp))
        }
        if (open) Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp)) { content() }
    }
}

private enum class UpdateState { UpToDate, Available, Unknown }

/** First number group of a version like CPH2691_17.0.0.105(EX01C30P01) -> [17, 0, 0, 105]. The "(EX01...)" tail is ignored. */
private fun versionCore(v: String): List<Long> =
    Regex("\\d+").findAll(v.substringAfter('_').substringBefore('(')).map { it.value.toLongOrNull() ?: 0L }.toList()

/** Is the newest full package newer than what this phone runs? Unknown when either side has no readable version. */
private fun updateState(dev: DeviceInfo, pkg: OtaPackage): UpdateState {
    if (pkg.otaVersion?.equals(dev.otaVersion, ignoreCase = true) == true) return UpdateState.UpToDate
    val cur = versionCore(dev.fullOsVersion.ifBlank { dev.displayVersion })
    val new = versionCore(pkg.version)
    if (cur.isEmpty() || new.isEmpty()) return UpdateState.Unknown
    for (i in 0 until maxOf(cur.size, new.size)) {
        val c = cur.getOrElse(i) { 0L }; val n = new.getOrElse(i) { 0L }
        if (n != c) return if (n > c) UpdateState.Available else UpdateState.UpToDate
    }
    return UpdateState.UpToDate
}

@Composable
private fun CheckCard(vm: OtaViewModel, state: CheckUi, other: Boolean, checkedAt: Long, current: DeviceInfo? = null, onDownload: (OtaPackage) -> Unit) {
    val ctx = LocalContext.current
    when (state) {
        CheckUi.Idle -> {}
        CheckUi.Loading -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        is CheckUi.Result -> when (val r = state.r) {
            is CheckResult.Found -> {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(checkedAt) { now = System.currentTimeMillis(); while (true) { delay(20_000); now = System.currentTimeMillis() } }
                val inner = MaterialTheme.colorScheme.surfaceContainerLow
                val us = if (other || current == null) UpdateState.Unknown else updateState(current, r.pkg)
                val green = if (isSystemInDarkTheme()) Color(0xFF81C995) else Color(0xFF1E8E3E)
                val headColor = if (us == UpdateState.UpToDate) green else MaterialTheme.colorScheme.primary
                val headText = when (us) {
                    UpdateState.UpToDate -> "Your system is up to date"
                    UpdateState.Available -> "System update available"
                    UpdateState.Unknown -> "Update found"
                }
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.CheckCircle, null, Modifier.size(22.dp), tint = headColor)
                            Spacer(Modifier.width(10.dp))
                            Text(headText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                color = headColor, modifier = Modifier.weight(1f))
                            if (checkedAt > 0) Text(agoLabel(checkedAt, now), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(inner).padding(18.dp)) {
                            Text("Latest full OTA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(r.pkg.version, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            FlowRow(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip(fmt(r.pkg.sizeBytes))
                                Chip(if (r.pkg.source == SourceType.OXYGEN_UPDATER) "Backup" else "Official")
                                r.pkg.securityPatch?.let { Chip("Patch $it") }
                            }
                            Button(onClick = { onDownload(r.pkg) }, shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                Icon(AppIcons.Download, null, Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
                                Text("Download")
                            }
                        }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(inner).padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) { LinkSection(vm, r.pkg, other, ctx) }
                        r.pkg.changelog?.let { cl -> FoldCard("Changelog") { ChangelogBody(cl) } }
                        if (r.notes.isNotEmpty()) FoldCard("Source log") {
                            Text(r.notes.joinToString("\n") { "• $it" }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            is CheckResult.NoPackage, is CheckResult.Failed -> {
                val notes = if (r is CheckResult.NoPackage) r.notes else (r as CheckResult.Failed).notes
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
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
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
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
