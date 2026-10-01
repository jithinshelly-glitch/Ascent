package com.ascent.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import com.ascent.app.data.Measurement
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos

data class Zone(val name: String, val key: String, val score: Float?)

fun computeZones(ms: List<Measurement>, targets: Map<String, Double>): List<Zone> {
    val sorted = ms.sortedBy { it.date }
    fun score(k: String): Float? {
        val s = sorted.mapNotNull { it.get(k) }
        if (s.isEmpty()) return null
        val t = targets[k] ?: return null
        val cur = s.last()
        if (cur <= t) return 0f
        val span = s.first() - t
        return if (span <= 0) 1f else ((cur - t) / span).toFloat().coerceIn(0f, 1f)
    }
    return listOf(
        Zone("Belly", "waist", score("waist")), Zone("Chest", "chest", score("chest")),
        Zone("Hips", "hips", score("hips")), Zone("Arms", "arm", score("arm")), Zone("Thighs", "thigh", score("thigh")),
    )
}

private val ZGreen = Color(0xFF4CC38A)
private val ZAmber = Color(0xFFE4B15A)
private val ZRed = Color(0xFFE05A5A)

fun zoneColor(s: Float?): Color = when {
    s == null -> Color(0xFF5A606B)
    s < 0.5f -> lerp(ZGreen, ZAmber, s * 2f)
    else -> lerp(ZAmber, ZRed, (s - 0.5f) * 2f)
}

private fun DrawScope.oval(c: Color, x: Float, y: Float, w: Float, h: Float) =
    drawOval(c, Offset(x - w / 2, y - h / 2), Size(w, h))

fun DrawScope.drawZones(z: List<Zone>, cx: Float, top: Float, u: Float, alpha: Float) {
    fun c(k: String) = zoneColor(z.firstOrNull { it.key == k }?.score).copy(alpha = alpha)
    oval(c("chest"), cx, top + 2.0f * u, 1.9f * u, 0.8f * u)
    oval(c("waist"), cx, top + 3.05f * u, 1.8f * u, 0.9f * u)
    oval(c("hips"), cx, top + 4.15f * u, 2.2f * u, 0.75f * u)
    for (sg in listOf(-1f, 1f)) {
        oval(c("arm"), cx + sg * 1.375f * u, top + 2.3f * u, 0.5f * u, 1.3f * u)
        oval(c("thigh"), cx + sg * 0.55f * u, top + 5.4f * u, 0.85f * u, 1.6f * u)
    }
}

private fun DrawScope.drawFigure(cx: Float, top: Float, u: Float) {
    val b = Color(0xFF3A3F48)
    drawOval(Color(0x33000000), Offset(cx - 1.4f * u, top + 8.55f * u), Size(2.8f * u, 0.35f * u))
    drawCircle(b, 0.55f * u, Offset(cx, top + 0.6f * u))
    drawRect(b, Offset(cx - 0.18f * u, top + 1.1f * u), Size(0.36f * u, 0.3f * u))
    drawRoundRect(b, Offset(cx - 1.1f * u, top + 1.35f * u), Size(2.2f * u, 3.3f * u), CornerRadius(0.45f * u))
    for (sg in listOf(-1f, 1f)) {
        drawRoundRect(b, Offset(cx + sg * 1.375f * u - 0.24f * u, top + 1.45f * u), Size(0.48f * u, 3.0f * u), CornerRadius(0.24f * u))
        drawRoundRect(b, Offset(cx + sg * 0.55f * u - 0.45f * u, top + 4.4f * u), Size(0.9f * u, 4.3f * u), CornerRadius(0.4f * u))
    }
}

/** Fallback figure when no .glb imported. Drag = rotate, pinch = zoom. */
@Composable
fun PlaceholderBody(zones: List<Zone>, modifier: Modifier) {
    var yaw by remember { mutableFloatStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    Canvas(
        modifier.pointerInput(Unit) {
            detectTransformGestures { _, pan, z, _ ->
                yaw += pan.x / 120f
                zoom = (zoom * z).coerceIn(0.6f, 2.5f)
            }
        }
    ) {
        val u = size.height / 9.6f * zoom
        val cx = size.width / 2
        val top = size.height / 2 - 4.35f * u
        val sx = 0.35f + 0.65f * abs(cos(yaw))
        withTransform({ scale(sx, 1f, Offset(cx, size.height / 2)) }) {
            drawFigure(cx, top, u)
            drawZones(zones, cx, top, u, 0.8f)
        }
    }
}

/** Approximate zone markers over an imported model (front view at default camera). */
@Composable
fun ZoneOverlay(zones: List<Zone>, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.height / 9.6f
        drawZones(zones, size.width / 2, size.height / 2 - 4.35f * u, u, 0.35f)
    }
}

private fun File.readDirect(): ByteBuffer {
    val bytes = readBytes()
    return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
}

@Composable
fun GlbViewer(file: File, modifier: Modifier) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val centerNode = rememberNode { Node(engine) }
    val cameraNode = rememberCameraNode(engine).apply { position = Position(0f, 0f, 2.8f) }
    val instance = remember(file) { runCatching { modelLoader.createModelInstance(file.readDirect()) }.getOrNull() }
    if (instance == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Couldn't load model", color = CMuted) }
        return
    }
    val model = rememberNode { ModelNode(modelInstance = instance, scaleToUnits = 1.8f, centerOrigin = Position(0f, 0f, 0f)) }
    Scene(
        modifier = modifier,
        engine = engine,
        modelLoader = modelLoader,
        cameraNode = cameraNode,
        cameraManipulator = rememberCameraManipulator(
            orbitHomePosition = cameraNode.worldPosition,
            targetPosition = centerNode.worldPosition,
        ),
        childNodes = listOf(centerNode, model),
    )
}
