@file:OptIn(ExperimentalMaterial3Api::class)

package com.wajahatnaeem.oneplusota.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.wajahatnaeem.oneplusota.extract.ExtractPaths
import com.wajahatnaeem.oneplusota.extract.PartitionExtractorWorker

// =================================================================================================
// Tab 3: Partition Extraction
// =================================================================================================
@Composable
fun ExtractTab(evm: ExtractViewModel = viewModel()) {
    val ctx = LocalContext.current

    LaunchedEffect(evm.message) {
        evm.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); evm.message = null }
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            evm.pickLocal(uri)
        }
    }

    // Follow the background job
    val id = evm.workId
    val info by produceState<WorkInfo?>(initialValue = null, id) {
        if (id == null) value = null
        else WorkManager.getInstance(ctx).getWorkInfoByIdFlow(id).collect { value = it }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Partition Extraction", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        }
        item {
            Text("Pull single partition images (boot, init_boot, vendor_boot …) out of a full OTA zip. " +
                "With a link only the needed parts are downloaded, not the whole zip.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp))
        }

        if (id != null) item { StatusCard(evm, info) }

        // ---- from link ----
        item {
            ExtractCard {
                Text("From link", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = evm.url, onValueChange = { evm.url = it },
                    label = { Text("Full OTA zip link") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (evm.url.isNotEmpty()) {
                            TextButton(onClick = { evm.url = "" }) { Text("Clear") }
                        } else {
                            TextButton(onClick = {
                                val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
                                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                                if (text.isNullOrBlank()) Toast.makeText(ctx, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                else evm.url = text
                            }) { Text("Paste") }
                        }
                    },
                )
                val busy = evm.loadingSource == evm.url.trim() && evm.url.isNotBlank()
                Button(
                    onClick = { evm.loadFromUrl() }, enabled = evm.loadingSource == null,
                    shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    else { Icon(AppIcons.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                    Text(if (busy) "Reading partitions…" else "Load partitions")
                }
            }
        }

        // ---- from local zip ----
        item {
            ExtractCard {
                Text("From local zip", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                if (evm.localUri == null) {
                    FilledTonalButton(
                        onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream")) },
                        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Icon(AppIcons.Extract, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Choose OTA .zip") }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(evm.localName.ifBlank { "Selected zip" }, fontWeight = FontWeight.Medium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        TextButton(onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Change") }
                        TextButton(onClick = { evm.pickLocal(null) }) { Text("Remove") }
                    }
                    val busy = evm.loadingSource == evm.localUri.toString()
                    Button(
                        onClick = { evm.loadFromLocal() }, enabled = evm.loadingSource == null,
                        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        else { Icon(AppIcons.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                        Text(if (busy) "Scanning partitions…" else "Load partitions")
                    }
                }
            }
        }

        // ---- output folder ----
        item { OutputCard(ctx) }
    }

    evm.sheet?.let { s ->
        PartitionPicker(
            sheet = s,
            onDismiss = { evm.closeSheet() },
            onExtract = { names ->
                if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                evm.extract(names)
                Toast.makeText(ctx, "Extraction started", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun ExtractCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun StatusCard(evm: ExtractViewModel, info: WorkInfo?) {
    val state = info?.state
    val finished = state?.isFinished == true
    val progress = info?.progress?.getInt(PartitionExtractorWorker.PROGRESS_KEY, -1)?.takeIf { it >= 0 }
    val current = info?.progress?.getString(PartitionExtractorWorker.CURRENT_PARTITION_KEY)
    val outDir = info?.outputData?.getString(PartitionExtractorWorker.OUTPUT_DIR_KEY)
    val error = info?.outputData?.getString("error")

    ExtractCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Extraction", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                when (state) {
                    WorkInfo.State.SUCCEEDED -> "Completed"
                    WorkInfo.State.FAILED -> "Failed"
                    WorkInfo.State.CANCELLED -> "Cancelled"
                    else -> "In progress"
                },
                fontWeight = FontWeight.SemiBold, color = when (state) {
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
            )
        }
        Text(evm.workNames.joinToString(", "), fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (!finished) {
            if (progress != null) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                (current?.let { "$it · " } ?: "") + (progress?.let { "$it%" } ?: "Starting…"),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { evm.cancel() }, shape = RoundedCornerShape(20.dp)) { Text("Cancel") }
        } else {
            if (state == WorkInfo.State.SUCCEEDED && outDir != null)
                Text("Saved to $outDir", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state == WorkInfo.State.FAILED && error != null)
                Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            FilledTonalButton(onClick = { evm.dismissStatus() }, shape = RoundedCornerShape(20.dp)) { Text("Dismiss") }
        }
    }
}

@Composable
private fun OutputCard(ctx: Context) {
    var granted by remember { mutableStateOf(ExtractPaths.hasAllFilesAccess()) }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        granted = ExtractPaths.hasAllFilesAccess()
    }
    ExtractCard {
        Text("Output folder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text(ExtractPaths.extractedDir(ctx).absolutePath, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !granted) {
            Text("Without all-files access the images go to the app's own folder, which file managers can't open on newer Android.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = {
                val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                settings.launch(i)
            }, shape = RoundedCornerShape(20.dp)) { Text("Allow all-files access") }
        }
    }
}

@Composable
private fun PartitionPicker(sheet: PartitionSheet, onDismiss: () -> Unit, onExtract: (List<String>) -> Unit) {
    var selected by remember(sheet) { mutableStateOf(setOf<String>()) }
    var query by remember(sheet) { mutableStateOf("") }
    val shown = remember(sheet, query) {
        sheet.partitions.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Select partitions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(sheet.versionName, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search partitions") }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${selected.size} selected", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = selected + shown.map { it.name } }) { Text("Select all") }
                TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
            }
            LazyColumn(Modifier.height(380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.name }) { p ->
                    val on = p.name in selected
                    val toggle = { selected = if (on) selected - p.name else selected + p.name }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { toggle() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = { toggle() })
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                            Text("${p.formattedSize} · ${p.opCount} ops", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Button(
                onClick = { onExtract(sheet.partitions.map { it.name }.filter { it in selected }) },
                enabled = selected.isNotEmpty(), shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (selected.isEmpty()) "Extract" else "Extract ${selected.size}") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
