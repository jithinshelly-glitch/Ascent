package com.ascent.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import com.ascent.app.core.JOURNEY_STEPS
import com.ascent.app.core.JourneyState
import com.ascent.app.core.journeyFrom
import com.ascent.app.data.Analytics
import com.ascent.app.data.AscentDb
import com.ascent.app.data.AutoSource
import com.ascent.app.data.DayRecord
import com.ascent.app.data.GOAL_KG
import com.ascent.app.data.HEIGHT_CM
import com.ascent.app.data.NonNeg
import com.ascent.app.data.START_KG
import com.ascent.app.data.Settings
import com.ascent.app.data.Sources
import com.ascent.app.data.complete
import com.ascent.app.data.flag
import com.ascent.app.data.withFlag
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

fun fmtNum(v: Double): String = if (v % 1.0 == 0.0) "%,d".format(v.toLong()) else "%.1f".format(v)
fun fmtMin(m: Long): String = "${m / 60}h %02dm".format(m % 60)
fun fmtSteps(s: Long): String = if (s >= 1000) "%.1fk".format(s / 1000.0) else s.toString()

fun nnTargetText(n: NonNeg): String = when (n.source) {
    AutoSource.STEPS -> "≥ ${fmtNum(n.target)} ${n.unit}"
    AutoSource.SLEEP -> "≥ ${fmtNum(n.target)} h sleep"
    AutoSource.SCREEN -> "≤ ${fmtNum(n.target)} min screen"
    AutoSource.NONE -> "${fmtNum(n.target)} ${n.unit}"
}

@Composable
fun SectionTitle(t: String) =
    Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = CText,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))

@Composable
fun AscentCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Card(modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = CSurface)) { content() }

@Composable
fun HomeScreen(settings: Settings, refreshKey: Int, onRefresh: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AscentDb.dao(ctx) }
    val days by remember { dao.daysFlow() }.collectAsState(initial = emptyList())
    val ms by remember { dao.measurementsFlow() }.collectAsState(initial = emptyList())
    val today = LocalDate.now()
    val todayRec = days.firstOrNull { it.date == today.toString() } ?: DayRecord(today.toString())
    val journey = remember(days, settings.startDate) {
        journeyFrom(days.associate { LocalDate.parse(it.date) to it.complete() }, LocalDate.parse(settings.startDate), today)
    }
    var stats by remember { mutableStateOf<Analytics?>(null) }
    LaunchedEffect(refreshKey) { stats = Sources.analytics(ctx) }
    val scope = rememberCoroutineScope()
    var hideReached by rememberSaveable { mutableStateOf(false) }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { onRefresh() }
    val requestHc = { runCatching { hcLauncher.launch(Sources.HC_REQUEST) }; Unit }
    val requestUsage = {
        runCatching {
            ctx.startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }.onFailure { ctx.startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS)) }
        Unit
    }
    val weight = ms.lastOrNull { it.weight != null }?.weight ?: START_KG
    val zones = remember(ms, settings.targets) { computeZones(ms, settings.targets) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ascent", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = CText, modifier = Modifier.weight(1f))
                Text(today.format(DateTimeFormatter.ofPattern("EEE, d MMM")), color = CMuted)
            }
            Spacer(Modifier.height(12.dp))
            BodyCard(settings, zones, weight)

            SectionTitle("Today & last 7 days")
            val s = stats
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("Screen", s?.screenMin?.last()?.let(::fmtMin), s?.screenMin, CGold, "Grant", requestUsage, Modifier.weight(1f))
                MetricCard("Sleep", s?.sleepMin?.last()?.let(::fmtMin), s?.sleepMin, Color(0xFF7C8CF0), "Grant", requestHc, Modifier.weight(1f))
                MetricCard("Steps", s?.steps?.last()?.let(::fmtSteps), s?.steps, CAccent, "Grant", requestHc, Modifier.weight(1f))
            }

            SectionTitle("Today's 3 Non-Negotiables")
            settings.nn.forEachIndexed { i, n ->
                val auto = when (n.source) {
                    AutoSource.STEPS -> s?.steps?.last()?.let { "${fmtNum(it.toDouble())} / ${fmtNum(n.target)}" }
                    AutoSource.SLEEP -> s?.sleepMin?.last()?.let { "${fmtMin(it)} / ${fmtNum(n.target)}h" }
                    AutoSource.SCREEN -> s?.screenMin?.last()?.let { "${fmtMin(it)} / ${fmtNum(n.target)}m" }
                    AutoSource.NONE -> null
                }
                NonNegCard(n, todayRec.flag(i), auto) {
                    scope.launch { dao.upsertDay(todayRec.withFlag(i, !todayRec.flag(i))) }
                }
                Spacer(Modifier.height(8.dp))
            }

            SectionTitle("Journey to ${fmtNum(GOAL_KG)} kg")
            JourneyCard(journey)
            Spacer(Modifier.height(24.dp))
        }
        if (journey.reached && !hideReached) ReachedOverlay { hideReached = true }
    }
}

@Composable
fun BodyCard(settings: Settings, zones: List<Zone>, weight: Double) {
    var showZones by rememberSaveable { mutableStateOf(true) }
    val file = settings.glbPath?.let(::File)?.takeIf { it.exists() }
    AscentCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Box(Modifier.fillMaxWidth().height(360.dp).background(CSurface2, RoundedCornerShape(12.dp))) {
                if (file != null) {
                    key(file.path) { GlbViewer(file, Modifier.fillMaxSize()) }
                    if (showZones) ZoneOverlay(zones, Modifier.fillMaxSize())
                } else {
                    PlaceholderBody(zones, Modifier.fillMaxSize())
                }
                Text("Drag to rotate · pinch to zoom", color = CMuted, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
                if (file != null) TextButton(onClick = { showZones = !showZones }, modifier = Modifier.align(Alignment.TopEnd)) {
                    Text(if (showZones) "Hide zones" else "Show zones", fontSize = 12.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                zones.forEach { z ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(zoneColor(z.score), CircleShape))
                        Text(" ${z.name}", fontSize = 12.sp, color = CMuted)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Current", "${fmtNum(weight)} kg", CText)
                Stat("Start", "${fmtNum(START_KG)} kg", CMuted)
                Stat("Goal", "${fmtNum(GOAL_KG)} kg", CGold)
                Stat("Height", "$HEIGHT_CM cm", CMuted)
                val bmi = weight / ((HEIGHT_CM / 100.0) * (HEIGHT_CM / 100.0))
                Stat("BMI", "%.1f".format(bmi), CMuted)
            }
        }
    }
}

@Composable
fun Stat(label: String, value: String, color: Color) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, color = color, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    Text(label, color = CMuted, fontSize = 11.sp)
}

@Composable
fun MetricCard(
    title: String, today: String?, values: List<Long>?, color: Color,
    grantLabel: String, onGrant: () -> Unit, modifier: Modifier,
) {
    AscentCard(modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(title, color = CMuted, fontSize = 12.sp)
            if (values == null) {
                Text("No access", color = CText, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
                Button(onClick = onGrant, contentPadding = ButtonDefaults.TextButtonContentPadding,
                    modifier = Modifier.height(32.dp)) { Text(grantLabel, fontSize = 12.sp) }
            } else {
                Text(today ?: "–", color = CText, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(6.dp))
                MiniBars(values.map { it.toFloat() }, color, Modifier.fillMaxWidth().height(40.dp))
            }
        }
    }
}

@Composable
fun MiniBars(values: List<Float>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
        val slot = size.width / values.size
        val bw = slot * 0.6f
        values.forEachIndexed { i, v ->
            val h = (v / max) * size.height
            val c = if (i == values.lastIndex) color else color.copy(alpha = 0.4f)
            drawRoundRect(c, Offset(i * slot + (slot - bw) / 2, size.height - h.coerceAtLeast(2f)),
                androidx.compose.ui.geometry.Size(bw, h.coerceAtLeast(2f)),
                androidx.compose.ui.geometry.CornerRadius(3f))
        }
    }
}

@Composable
fun NonNegCard(n: NonNeg, checked: Boolean, auto: String?, onToggle: () -> Unit) {
    AscentCard(Modifier.fillMaxWidth().clickable { onToggle() }) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, { onToggle() }, colors = CheckboxDefaults.colors(checkedColor = CAccent, uncheckedColor = CMuted))
            Column(Modifier.weight(1f)) {
                Text(n.name, color = CText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(nnTargetText(n), color = CMuted, fontSize = 13.sp)
            }
            if (n.source != AutoSource.NONE) Column(horizontalAlignment = Alignment.End) {
                Text("AUTO", color = CAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(auto ?: "no access", color = CMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun JourneyCard(j: JourneyState) {
    AscentCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Position", "${j.position}/$JOURNEY_STEPS", CGold)
                Stat("Streak", "${j.streak} d", CAccent)
                Stat("Missed", "${j.missedDays} d", if (j.missedDays > 0) CRed else CMuted)
                Stat("Next miss", "−${3 * (j.consecutiveMisses + 1)}", CMuted)
            }
            if (j.consecutiveMisses > 0) Text(
                "Paused: ${j.consecutiveMisses} missed day(s) in a row. Complete today to reset the penalty.",
                color = CRed, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
            )
            Text("🏁 ${fmtNum(GOAL_KG)} kg", color = CGold, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            JourneyPath(j.position, Modifier.fillMaxWidth().height(480.dp).padding(vertical = 8.dp))
            Text("Start · ${fmtNum(START_KG)} kg", color = CMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun JourneyPath(position: Int, modifier: Modifier) {
    Canvas(modifier) {
        val cols = 9
        val rows = JOURNEY_STEPS / cols
        val padX = 16.dp.toPx()
        val padY = 12.dp.toPx()
        val dx = (size.width - 2 * padX) / (cols - 1)
        val dy = (size.height - 2 * padY) / rows
        val pts = ArrayList<Offset>(JOURNEY_STEPS + 1)
        pts += Offset(padX, size.height - padY)
        for (j in 0 until JOURNEY_STEPS) {
            val r = j / cols
            val c = if (r % 2 == 0) j % cols else cols - 1 - j % cols
            pts += Offset(padX + c * dx, size.height - padY - (r + 1) * dy)
        }
        for (i in 1 until pts.size) {
            val done = i <= position
            drawLine(if (done) CAccent else CSurface2, pts[i - 1], pts[i], strokeWidth = if (done) 4.dp.toPx() else 3.dp.toPx())
        }
        pts.forEachIndexed { i, p ->
            val milestone = i % 10 == 0 && i > 0
            val r = if (milestone) 6.dp.toPx() else 4.dp.toPx()
            when {
                i == 0 -> drawCircle(CMuted, 5.dp.toPx(), p)
                i <= position -> drawCircle(if (milestone) CGold else CAccent, r, p)
                else -> drawCircle(if (milestone) CGold else CMuted, r, p, style = Stroke(1.5.dp.toPx()))
            }
        }
        val me = pts[position.coerceIn(0, JOURNEY_STEPS)]
        drawCircle(CGold.copy(alpha = 0.3f), 14.dp.toPx(), me)
        drawCircle(Color.White, 9.dp.toPx(), me)
        drawCircle(CGold, 7.dp.toPx(), me)
    }
}

@Composable
fun ReachedOverlay(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(CBg.copy(alpha = 0.97f)).clickable {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("🏔", fontSize = 72.sp)
            Text("Destination reached", color = CGold, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("${fmtNum(GOAL_KG)} kg · $JOURNEY_STEPS steps completed", color = CText, fontSize = 16.sp)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onClose, modifier = Modifier.width(180.dp)) { Text("Continue") }
        }
    }
}
