package com.notesprint.app

import android.graphics.Paint
import androidx.core.content.res.ResourcesCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One complete bar per row keeps accidentals and both staves legible on phones. */
@Composable
internal fun SongScore(song: Song, completed: Int, showNames: Boolean, modifier: Modifier = Modifier, matched: Set<Int> = emptySet()) {
    val context = LocalContext.current
    val font = remember(context) { requireNotNull(ResourcesCompat.getFont(context, R.font.noto_music)) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier.testTag("song-score")) {
        val rowHeight = minOf(280.dp, maxOf(100.dp, maxHeight))
        val rowPixels = with(androidx.compose.ui.platform.LocalDensity.current) { rowHeight.toPx() }
        LaunchedEffect(completed / 4, rowPixels) {
            scroll.animateScrollTo((completed.coerceAtMost(15) / 4 * rowPixels).toInt())
        }
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            repeat(4) { bar ->
                val beats = song.beats.subList(bar * 4, bar * 4 + 4)
                val notes = beats.flatMap { it.notes }
                val accidentals = accidentalsForBar(song.key, beats)
                val description = "Bar ${bar + 1}, ${beats.joinToString { beat -> beat.notes.joinToString(" + ") { it.label } }}"
                Column(Modifier.height(rowHeight).fillMaxWidth().testTag("song-bar-$bar")) {
                    Text("BAR ${bar + 1}", fontSize = 11.sp, color = Color(0xFF65736D))
                    Canvas(Modifier.fillMaxWidth().weight(1f).semantics { contentDescription = description }) {
                        val width = 700f
                        val top = minOf(0f, notes.minOf { noteY(it) } - 45)
                        val musicBottom = maxOf(265f, notes.maxOf { noteY(it) } + 45)
                        val bottom = musicBottom + if (showNames) 80 else 0
                        val height = bottom - top
                        val fit = minOf(size.width / width, size.height / height)
                        translate((size.width - width * fit) / 2, (size.height - height * fit) / 2 - top * fit) {
                            scale(fit, fit, pivot = Offset.Zero) {
                                if (completed / 4 == bar && completed < 16) {
                                    val x = 180f + (completed % 4) * 130
                                    drawRect(Color(0xFFFFEDCE), Offset(x - 63, top + 4), androidx.compose.ui.geometry.Size(124f, height - 8))
                                }
                                drawStaves(width, font, paint)
                                val timeX = drawKeySignature(song.key, paint)
                                // Bar lines join the grand staff. Quarter-note spacing gives four beats per bar.
                                drawLine(Color.Black, Offset(width - 12, 55f), Offset(width - 12, 225f), 1.5f)
                                beats.forEachIndexed { beat, event ->
                                    val index = bar * 4 + beat
                                    val x = 180f + beat * 130
                                    val current = index == completed
                                    val color = when { index < completed -> Color(0xFF2B8552); current -> Color(0xFFE75437); else -> Color(0xFF293B33) }
                                    val seen = if (current) matched else emptySet()
                                    drawChord(event.left, x, color, seen, accidentals[beat], paint)
                                    drawChord(event.right, x, color, seen, accidentals[beat], paint)
                                    if (event.left.isEmpty()) drawQuarterRest(x, 185f, font, paint)
                                    if (event.right.isEmpty()) drawQuarterRest(x, 55f, font, paint)
                                    if (showNames) {
                                        paint.color = android.graphics.Color.DKGRAY
                                        paint.typeface = android.graphics.Typeface.DEFAULT
                                        paint.textSize = 14f
                                        val lines = event.left.chunked(3).mapIndexed { i, group -> (if (i == 0) "L: " else "") + group.joinToString(" ") { it.label } } +
                                            event.right.chunked(3).mapIndexed { i, group -> (if (i == 0) "R: " else "") + group.joinToString(" ") { it.label } }
                                        lines.forEachIndexed { line, label ->
                                            drawContext.canvas.nativeCanvas.drawText(label, x - paint.measureText(label) / 2, musicBottom + 12 + line * 17, paint)
                                        }
                                    }
                                }
                                paint.color = android.graphics.Color.DKGRAY
                                paint.typeface = android.graphics.Typeface.DEFAULT
                                paint.textSize = 16f
                                drawContext.canvas.nativeCanvas.drawText("4", timeX, 71f, paint)
                                drawContext.canvas.nativeCanvas.drawText("4", timeX, 92f, paint)
                                drawContext.canvas.nativeCanvas.drawText("4", timeX, 201f, paint)
                                drawContext.canvas.nativeCanvas.drawText("4", timeX, 222f, paint)
                            }
                        }
                    }
                }
            }
        }
    }
}
