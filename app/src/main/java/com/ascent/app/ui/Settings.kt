package com.ascent.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.ascent.app.data.AutoSource
import com.ascent.app.data.MEASURES
import com.ascent.app.data.NonNeg
import com.ascent.app.data.Settings
import com.ascent.app.data.Sources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

@Composable
fun SettingsScreen(settings: Settings, refreshKey: Int, onSave: (Settings) -> Unit, onPermsChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(settings) { mutableStateOf(settings) }
    var usageOk by remember { mutableStateOf(false) }
    var hcOk by remember { mutableStateOf(false) }
    val hcStatus = remember { Sources.hcStatus(ctx) }
    LaunchedEffect(refreshKey) {
        usageOk = Sources.hasUsage(ctx)
        hcOk = Sources.hasHc(ctx)
    }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch { hcOk = Sources.hasHc(ctx) }
        onPermsChanged()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    val out = File(ctx.filesDir, "body_${System.currentTimeMillis()}.glb")
                    ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
                    settings.glbPath?.let { File(it).delete() }
                    out
                }.getOrNull()
            }
            if (f == null) Toast.makeText(ctx, "Import failed", Toast.LENGTH_SHORT).show()
            else onSave(settings.copy(glbPath = f.absolutePath))
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Non-negotiables")
        draft.nn.forEachIndexed { i, n ->
            NonNegEditor(i, n) { upd -> draft = draft.copy(nn = draft.nn.toMutableList().also { it[i] = upd }) }
            Spacer(Modifier.height(8.dp))
        }

        SectionTitle("Measurement targets (cm)")
        AscentCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                MEASURES.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { k ->
                            NumberField(k.replaceFirstChar { it.uppercase() }, draft.targets.getValue(k), Modifier.weight(1f)) { v ->
                                draft = draft.copy(targets = draft.targets + (k to v))
                            }
                        }
                    }
                }
            }
        }
        Button(onClick = { onSave(draft); Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show() },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Save settings") }

        SectionTitle("3D body model")
        AscentCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(if (settings.glbPath != null) "Model imported" else "No model – showing placeholder figure",
                    color = CMuted, fontSize = 13.sp)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import .glb") }
                    if (settings.glbPath != null) OutlinedButton(onClick = {
                        File(settings.glbPath).delete(); onSave(settings.copy(glbPath = null))
                    }) { Text("Remove") }
                }
            }
        }

        SectionTitle("Permissions")
        AscentCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                PermRow("Usage Access (screen time)", usageOk) {
                    runCatching {
                        ctx.startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    }.onFailure { ctx.startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS)) }
                }
                Spacer(Modifier.height(8.dp))
                PermRow("Health Connect (steps, sleep)", hcOk) {
                    if (hcStatus == HealthConnectClient.SDK_AVAILABLE) runCatching { hcLauncher.launch(Sources.HC_REQUEST) }
                    else runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                    }
                }
                if (hcStatus != HealthConnectClient.SDK_AVAILABLE)
                    Text("Health Connect not available/needs update", color = CRed, fontSize = 12.sp)
            }
        }

        SectionTitle("Journey")
        AscentCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Started ${settings.startDate}", color = CMuted)
                var confirm by remember { mutableStateOf(false) }
                OutlinedButton(onClick = {
                    if (confirm) { onSave(settings.copy(startDate = LocalDate.now().toString())); confirm = false } else confirm = true
                }, modifier = Modifier.padding(top = 8.dp)) { Text(if (confirm) "Tap again to restart from today" else "Restart journey") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = CText)
            Text(if (ok) "Granted" else "Not granted", color = if (ok) CAccent else CRed, fontSize = 12.sp)
        }
        if (!ok) Button(onClick = onGrant) { Text("Grant") }
    }
}

@Composable
private fun NumberField(label: String, value: Double, modifier: Modifier, onChange: (Double) -> Unit) {
    var text by remember { mutableStateOf(fmtNum(value).replace(",", "")) }
    OutlinedTextField(
        text, { t -> text = t; t.replace(',', '.').toDoubleOrNull()?.let(onChange) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@Composable
private fun NonNegEditor(i: Int, n: NonNeg, onChange: (NonNeg) -> Unit) {
    AscentCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("#${i + 1}", color = CGold, fontSize = 12.sp)
            OutlinedTextField(n.name, { onChange(n.copy(name = it)) }, label = { Text("Name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    when (n.source) { AutoSource.SLEEP -> "Target h"; AutoSource.SCREEN -> "Max min"; else -> "Target" },
                    n.target, Modifier.weight(1f),
                ) { onChange(n.copy(target = it)) }
                OutlinedTextField(n.unit, { onChange(n.copy(unit = it)) }, label = { Text("Unit") }, singleLine = true,
                    modifier = Modifier.weight(1f))
            }
            Text("Auto-source", color = CMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AutoSource.entries.forEach { s ->
                    FilterChip(selected = n.source == s, onClick = { onChange(n.copy(source = s)) }, label = { Text(s.label) })
                }
            }
        }
    }
}
