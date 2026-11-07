package com.notesprint.app

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs

private data class WrittenGroup(val part: Int, val time: Int, val grace: Int, val voice: String, val log: Int, val dots: Int, val clef: Boolean)

@Composable
internal fun CatalogueScoreView(plan: CataloguePlan, progress: CatalogueProgress, showNames: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val font = remember(context) { requireNotNull(ResourcesCompat.getFont(context, R.font.noto_music)) }
    val list = rememberLazyListState()
    val current = plan.steps.getOrNull(progress.index)
    val measure = current?.attacks?.first()?.measure ?: plan.measureIndices.last()
    val activeRow = plan.measureIndices.indexOf(measure)
    val rootStep = remember(plan) {
        val map = mutableMapOf<Int, Int>()
        plan.steps.forEachIndexed { index, step -> step.attacks.forEach { map[it.id] = index } }
        plan.selected.forEach { n ->
            var ancestor = n
            while (ancestor.tieFrom in plan.byId) ancestor = plan.byId.getValue(ancestor.tieFrom)
            map[ancestor.id]?.let { map[n.id] = it }
        }
        map
    }
    LaunchedEffect(plan, activeRow) { if (activeRow >= 0) list.animateScrollToItem(activeRow) }
    BoxWithConstraints(modifier.testTag("catalogue-score")) {
        val rowHeight = minOf(310.dp, maxOf(80.dp, maxHeight))
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(plan.measureIndices, key = { _, index -> index }) { _, index ->
                val bar = plan.score.measures[index]
                val local = remember(plan, index) { fillRests(plan.notes.filter { it.measure == index }, bar, index, plan.score.tpq) }
                Column(Modifier.fillMaxWidth().height(rowHeight).testTag("catalogue-measure-$index")) {
                    Text(if (bar.pickup) "PICKUP" else "MEASURE ${bar.number}", fontSize = 11.sp, color = Color(0xFF65736D))
                    CatalogueBar(plan, local, bar, current, progress, rootStep, showNames, font, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

/** Fill silent parts in reduced arrangements without masking independent original voices. */
internal fun fillRests(notes: List<CatalogueNote>, bar: CatalogueMeasure, measure: Int, tpq: Int): List<CatalogueNote> {
    val result = notes.toMutableList()
    for (part in 0..1) {
        val local = notes.filter { it.part == part && it.grace == 0 }.sortedBy { it.time }
        var cursor = bar.start
        val gaps = mutableListOf<Pair<Int, Int>>()
        for (n in local) {
            if (n.time > cursor) gaps += cursor to n.time
            cursor = maxOf(cursor, n.time + n.duration)
        }
        if (cursor < bar.end) gaps += cursor to bar.end
        for ((start, end) in gaps) {
            var time = start
            while (time < end) {
                val choice = (0..6).flatMap { log -> (0..2).map { dots -> Triple((4.0 * tpq / (1 shl log) * (2 - 1.0 / (1 shl dots))).toInt(), log, dots) } }
                    .filter { it.first <= end-time }.maxByOrNull { it.first } ?: Triple(end-time, 6, 0)
                result += CatalogueNote(id = -1-result.size, part = part, time = time, duration = choice.first,
                    log = choice.second, dots = choice.third, measure = measure, clef = local.firstOrNull()?.clef ?: (part == 0))
                time += choice.first
            }
        }
    }
    return result
}

@Composable
private fun CatalogueBar(
    plan: CataloguePlan, notes: List<CatalogueNote>, bar: CatalogueMeasure, current: CatalogueStep?,
    progress: CatalogueProgress, steps: Map<Int, Int>, showNames: Boolean, font: Typeface, modifier: Modifier,
) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val horizontal = rememberScrollState()
    val slots = remember(notes) { notes.map { it.time to it.grace }.distinct().sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second }) }
    val groups = remember(notes) { notes.groupBy { WrittenGroup(it.part, it.time, it.grace, it.voice, it.log, it.dots, it.clef) } }
    val pitched = notes.filter { !it.rest }.map { it.musicNote() }
    val labels = remember(notes) {
        slots.associateWith { slot ->
            (0..1).flatMap { part ->
                notes.filter { !it.rest && it.part == part && (it.time to it.grace) == slot }.distinctBy { it.midi }
                    .chunked(3).mapIndexed { line, group ->
                        (if (line == 0) { if (part == 0) "R: " else "L: " } else "") + group.joinToString(" ") { it.musicNote().label }
                    }
            }
        }
    }
    val top = minOf(0f, pitched.minOfOrNull(::noteY)?.minus(55) ?: 0f)
    val musicBottom = maxOf(270f, pitched.maxOfOrNull(::noteY)?.plus(55) ?: 270f)
    val bottom = musicBottom + if (showNames) maxOf(40, labels.values.maxOf { it.size } * 16 + 8) else 0
    val logicalHeight = bottom-top
    val measureIndex = notes.first().measure
    val previousKey = if (measureIndex > plan.measureIndices.first()) plan.score.measures[measureIndex-1].key else bar.key
    val cancellations = bar.key.indices.map { if (previousKey[it] != bar.key[it]) previousKey[it] else 0 }
    val signCount = cancellations.count { it != 0 } + bar.key.count { it != 0 }
    val contentStart = maxOf(190f, 130f + signCount*16)
    val logicalWidth = maxOf(700f, contentStart + 15f + slots.size * 62f)
    val xAt: (Int, Int) -> Float = { time, grace -> contentStart + slots.indexOf(time to grace).coerceAtLeast(0) * (logicalWidth-contentStart-40)/slots.size.coerceAtLeast(1) }
    val signs = remember(plan, notes) {
        val visibleIds = plan.notes.map { it.id }.toSet()
        catalogueAccidentals(bar, notes.map { if (it.tieFrom >= 0 && it.tieFrom !in visibleIds) it.copy(tieFrom = -1) else it })
    }
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val heightPixels = with(density) { maxHeight.toPx() }
        val viewportPixels = with(density) { maxWidth.toPx() }
        val scale = heightPixels/logicalHeight
        val widthPixels = maxOf(viewportPixels, logicalWidth*scale)
        val widthDp = with(density) { widthPixels.toDp() }
        val targetX = if (current != null && current.attacks.any { it.measure == notes.firstOrNull()?.measure }) xAt(current.tick, current.grace) else 0f
        LaunchedEffect(targetX, scale) {
            if (targetX > 0) {
                val x = targetX * scale
                val margin = minOf(80f, viewportPixels * 0.2f)
                if (x < horizontal.value + margin || x > horizontal.value + viewportPixels - margin) {
                    horizontal.animateScrollTo((x - viewportPixels * 0.45f).toInt().coerceAtLeast(0))
                }
            }
        }
        Box(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
            Canvas(Modifier.width(widthDp).fillMaxHeight().semantics { contentDescription = "Measure ${bar.number}, ${bar.numerator}/${bar.denominator}" }) {
                translate((size.width-logicalWidth*scale)/2, -top*scale) {
                    scale(scale, scale, pivot = Offset.Zero) {
                        val upper = notes.firstOrNull { it.part == 0 }?.clef ?: true
                        val lower = notes.firstOrNull { it.part == 1 }?.clef ?: false
                        if (targetX > 0) drawRect(Color(0xFFFFEDCE), Offset(targetX-23, top), Size(48f, logicalHeight))
                        drawStaves(logicalWidth, font, paint, upper, lower)
                        val cancellationEnd = if (cancellations.any { it != 0 }) {
                            drawKeySignature(bar.copy(key = cancellations).signature(), paint, upper, lower, cancel = true)
                        } else 58f
                        val timeX = drawKeySignature(bar.signature(), paint, upper, lower, startX = cancellationEnd)
                        paint.typeface = Typeface.DEFAULT; paint.textSize = 16f; paint.color = android.graphics.Color.DKGRAY
                        for (staffTop in listOf(55f, 185f)) {
                            drawContext.canvas.nativeCanvas.drawText(bar.numerator.toString(), timeX, staffTop+16, paint)
                            drawContext.canvas.nativeCanvas.drawText(bar.denominator.toString(), timeX, staffTop+37, paint)
                        }
                        drawLine(Color.Black, Offset(logicalWidth-12, 55f), Offset(logicalWidth-12, 225f), 1.5f)
                        val clefs = mutableMapOf(0 to upper, 1 to lower)
                        for ((group, members) in groups.entries.sortedWith(compareBy({ it.key.time }, { it.key.grace }, { it.key.part }))) {
                            val x = xAt(group.time, group.grace)
                            if (clefs[group.part] != group.clef) {
                                drawSmallClef(x-24, if (group.part == 0) 55f else 185f, group.clef, font, paint)
                                clefs[group.part] = group.clef
                            }
                            val real = members.filter { !it.rest }
                            val active = plan.enabled(group.part)
                            val color = when {
                                !active -> Color(0xFFB6BAB5)
                                real.isNotEmpty() && real.all { (steps[it.id] ?: Int.MAX_VALUE) < progress.index } -> Color(0xFF2B8552)
                                real.any { it.id in (current?.attacks?.map { n -> n.id } ?: emptyList()) } -> Color(0xFFE75437)
                                else -> Color(0xFF293B33)
                            }
                            if (real.isEmpty()) {
                                drawWrittenRest(x, if (group.part == 0) 55f else 185f, group.log, group.dots, font, paint)
                            } else {
                                val music = real.map { it.musicNote() }
                                val accidentalMap = real.mapNotNull { note -> signs[note.id]?.let { note.musicNote() to it } }.toMap()
                                val seen = if (color == Color(0xFFE75437)) progress.matched else emptySet()
                                val voiceCount = groups.keys.filter { it.part == group.part && it.time == group.time }.map { it.voice }.distinct().size
                                val stem = if (voiceCount > 1) group.voice == groups.keys.first { it.part == group.part && it.time == group.time }.voice else null
                                if (group.grace != 0) {
                                    scale(0.7f, 0.7f, pivot = Offset(x, noteY(music.first()))) { drawChord(music, x, color, seen, accidentalMap, paint, group.log, group.dots, stem) }
                                } else drawChord(music, x, color, seen, accidentalMap, paint, group.log, group.dots, stem)
                                for (note in real) {
                                    val y = noteY(note.musicNote()) + 9
                                    if (note.tieTo >= 0) {
                                        val next = notes.firstOrNull { it.id == note.tieTo }
                                        val end = if (next != null) xAt(next.time, next.grace)-8 else logicalWidth-15
                                        drawTie(x+8, end, y, color)
                                    }
                                    if (note.tieFrom >= 0 && notes.none { it.id == note.tieFrom }) drawTie(x-30, x-8, y, color)
                                }
                            }
                        }
                        if (showNames) {
                            paint.typeface = Typeface.DEFAULT; paint.textSize = 12f; paint.color = android.graphics.Color.DKGRAY
                            for ((slot, names) in labels) names.forEachIndexed { line, label ->
                                val x = xAt(slot.first, slot.second)
                                drawContext.canvas.nativeCanvas.drawText(label, x-paint.measureText(label)/2, musicBottom+14+line*16, paint)
                            }
                        }
                        drawTupletBrackets(plan, groups, xAt, paint)
                    }
                }
            }
        }
    }
}

internal fun catalogueAccidentals(bar: CatalogueMeasure, notes: List<CatalogueNote>): Map<Int, Accidental> {
    val active = mutableMapOf<Pair<Int, Int>, Int>()
    return buildMap {
        for (n in notes.filter { !it.rest }.sortedWith(compareBy({ it.time }, { it.grace }, { it.part }))) {
            val place = n.part to (n.octave*7+n.step)
            val old = active[place] ?: bar.key[n.step]
            // Tied continuations do not restate an accidental across a bar line.
            if (n.alter != old && n.tieFrom < 0) put(n.id, when (n.alter) {
                -2 -> Accidental.DOUBLE_FLAT; -1 -> Accidental.FLAT; 1 -> Accidental.SHARP; 2 -> Accidental.DOUBLE_SHARP; else -> Accidental.NATURAL
            })
            active[place] = n.alter
        }
    }
}

private fun DrawScope.drawTie(start: Float, end: Float, y: Float, color: Color) {
    val path = Path().apply { moveTo(start, y); cubicTo(start+(end-start)/3, y+10, end-(end-start)/3, y+10, end, y) }
    drawPath(path, color, style = Stroke(1.2f))
}

private fun DrawScope.drawSmallClef(x: Float, top: Float, treble: Boolean, font: Typeface, paint: Paint) {
    paint.typeface = font; paint.textSize = 1000f*10f/220f; paint.color = android.graphics.Color.BLACK
    val reference = if (treble) top+30 else top+10
    drawContext.canvas.nativeCanvas.drawText(if (treble) "𝄞" else "𝄢", x, reference + (if (treble) 250f else 644.5f)*paint.textSize/1000, paint)
}

private fun DrawScope.drawWrittenRest(x: Float, top: Float, log: Int, dots: Int, font: Typeface, paint: Paint) {
    if (log <= 1) {
        drawRect(Color.DarkGray, Offset(x-7, top + if (log == 0) 10 else 15), Size(14f, 5f))
    } else {
        val glyph = when (log) { 2 -> "𝄽"; 3 -> "𝄾"; 4 -> "𝄿"; 5 -> "𝅀"; else -> "𝅁" }
        paint.typeface = font; paint.textSize = 45f; paint.color = android.graphics.Color.DKGRAY
        val rect = android.graphics.Rect(); paint.getTextBounds(glyph, 0, glyph.length, rect)
        drawContext.canvas.nativeCanvas.drawText(glyph, x-(rect.left+rect.right)/2f, top+20-(rect.top+rect.bottom)/2f, paint)
    }
    repeat(dots) { drawCircle(Color.DarkGray, 1.7f, Offset(x+15+it*6, top+15)) }
}

private fun DrawScope.drawTupletBrackets(plan: CataloguePlan, groups: Map<WrittenGroup, List<CatalogueNote>>, xAt: (Int, Int) -> Float, paint: Paint) {
    val tuplets = groups.filter { (g, n) -> g.grace == 0 && abs(n.first().duration - 4.0*plan.score.tpq/(1 shl g.log)*(2-1.0/(1 shl g.dots))*2/3) < 2 }
    for (voice in tuplets.keys.map { it.part to it.voice }.distinct()) {
        val members = tuplets.keys.filter { it.part == voice.first && it.voice == voice.second }.sortedBy { it.time }
        for (chunk in members.chunked(3).filter { it.size == 3 }) {
            val start = xAt(chunk.first().time, 0)-8
            val end = xAt(chunk.last().time, 0)+10
            val real = chunk.flatMap { groups.getValue(it) }.filter { !it.rest }
            val y = minOf(if (voice.first == 0) 40f else 170f, real.minOfOrNull { noteY(it.musicNote())-45 } ?: 40f)
            drawLine(Color.DarkGray, Offset(start, y+4), Offset(start, y), 1f)
            drawLine(Color.DarkGray, Offset(start, y), Offset(end, y), 1f)
            drawLine(Color.DarkGray, Offset(end, y), Offset(end, y+4), 1f)
            paint.typeface = Typeface.DEFAULT; paint.textSize = 12f; paint.color = android.graphics.Color.DKGRAY
            drawContext.canvas.nativeCanvas.drawText("3", (start+end)/2, y-3, paint)
        }
    }
}
