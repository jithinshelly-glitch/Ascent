package com.ascent.app.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ascent.app.data.AscentDb
import com.ascent.app.data.GOAL_KG
import com.ascent.app.data.Measurement
import com.ascent.app.data.START_KG
import com.ascent.app.data.Settings
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val FIELDS = listOf("weight" to "Weight kg", "waist" to "Waist cm", "chest" to "Chest cm",
    "hips" to "Hips cm", "arm" to "Arm cm", "thigh" to "Thigh cm")

fun targetWeight(start: LocalDate, d: LocalDate): Double =
    maxOf(GOAL_KG, START_KG - 0.5 * ChronoUnit.DAYS.between(start, d) / 7.0)

@Composable
fun LogScreen(settings: Settings) {
    val ctx = LocalContext.current
    val dao = remember { AscentDb.dao(ctx) }
    val ms by remember { dao.measurementsFlow() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    val values = remember { mutableStateOf(FIELDS.associate { it.first to "" }) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Weight vs target (−0.5 kg/week)")
        AscentCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                WeightChart(ms, LocalDate.parse(settings.startDate), Modifier.fillMaxWidth().height(220.dp))
                Row(Modifier.padding(top = 6.dp)) {
                    Text("● Weight  ", color = CAccent, fontSize = 12.sp)
                    Text("- - Target", color = CGold, fontSize = 12.sp)
                }
            }
        }

        SectionTitle("Weekly entry")
        AscentCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                OutlinedTextField(date, { date = it }, label = { Text("Date (yyyy-mm-dd)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                FIELDS.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { (k, label) ->
                            OutlinedTextField(
                                values.value[k] ?: "", { v -> values.value = values.value + (k to v) },
                                label = { Text(label) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                error?.let { Text(it, color = CRed, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
                Button(onClick = {
                    val d = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
                    val v = values.value.mapValues { it.value.trim().replace(',', '.').toDoubleOrNull() }
                    when {
                        d == null -> error = "Invalid date"
                        v.values.all { it == null } -> error = "Enter at least one value"
                        else -> {
                            error = null
                            scope.launch {
                                dao.insertMeasurement(Measurement(date = d.toString(), weight = v["weight"], waist = v["waist"],
                                    chest = v["chest"], hips = v["hips"], arm = v["arm"], thigh = v["thigh"]))
                            }
                            values.value = FIELDS.associate { it.first to "" }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Save entry") }
            }
        }

        SectionTitle("History")
        ms.reversed().forEach { m ->
            AscentCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text("${m.date}  ${m.weight?.let { "${fmtNum(it)} kg" } ?: ""}", color = CText)
                        val parts = FIELDS.drop(1).mapNotNull { (k, l) -> m.get(k)?.let { "${l.substringBefore(' ')} ${fmtNum(it)}" } }
                        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = CMuted, fontSize = 12.sp)
                    }
                    IconButton(onClick = { scope.launch { dao.deleteMeasurement(m) } }) {
                        Icon(Icons.Filled.Delete, "Delete", tint = CMuted)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun WeightChart(ms: List<Measurement>, start: LocalDate, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val pts = ms.filter { it.weight != null }.map { ChronoUnit.DAYS.between(start, LocalDate.parse(it.date)).toFloat() to it.weight!!.toFloat() }
    val goalDay = ((START_KG - GOAL_KG) / 0.5 * 7).toFloat()
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val left = 34.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val xMin = minOf(0f, pts.minOfOrNull { it.first } ?: 0f)
            val xMax = maxOf(goalDay + 7f, pts.maxOfOrNull { it.first } ?: 0f)
            val yMin = minOf(GOAL_KG.toFloat() - 1f, pts.minOfOrNull { it.second } ?: 99f)
            val yMax = maxOf(START_KG.toFloat() + 1f, pts.maxOfOrNull { it.second } ?: 0f)
            fun x(d: Float) = left + (d - xMin) / (xMax - xMin) * (size.width - left - 4f)
            fun y(w: Float) = bottom - (w - yMin) / (yMax - yMin) * (bottom - 6f)
            val label = TextStyle(color = CMuted, fontSize = 10.sp)
            var g = kotlin.math.ceil(yMin).toInt()
            while (g <= yMax) {
                drawLine(CSurface2, Offset(left, y(g.toFloat())), Offset(size.width, y(g.toFloat())), 1f)
                drawText(tm, "$g", Offset(0f, y(g.toFloat()) - 7.sp.toPx()), label)
                g += 1
            }
            var wk = 0
            while (wk * 7 <= xMax) {
                if (wk % 2 == 0) drawText(tm, "w$wk", Offset(x(wk * 7f) - 6f, bottom + 3.dp.toPx()), label)
                wk++
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
            drawLine(CGold, Offset(x(0f), y(START_KG.toFloat())), Offset(x(goalDay), y(GOAL_KG.toFloat())), 3f, pathEffect = dash)
            drawLine(CGold, Offset(x(goalDay), y(GOAL_KG.toFloat())), Offset(x(xMax), y(GOAL_KG.toFloat())), 3f, pathEffect = dash)
            val sorted = pts.sortedBy { it.first }
            for (i in 1 until sorted.size) {
                drawLine(CAccent, Offset(x(sorted[i - 1].first), y(sorted[i - 1].second)),
                    Offset(x(sorted[i].first), y(sorted[i].second)), 5f)
            }
            sorted.forEach {
                drawCircle(CAccent, 5.dp.toPx(), Offset(x(it.first), y(it.second)))
                drawCircle(CBg, 2.dp.toPx(), Offset(x(it.first), y(it.second)), style = Stroke(1f))
            }
        }
        if (pts.isEmpty()) Text("No weight entries yet", color = CMuted, modifier = Modifier.align(Alignment.Center))
    }
}
