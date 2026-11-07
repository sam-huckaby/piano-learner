package com.notesprint.app

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

internal fun noteY(note: MusicNote): Float {
    val top = if (note.treble) 55f else 185f
    val bottom = if (note.clefTreble ?: note.treble) 4 * 7 + 2 else 2 * 7 + 4
    return top + 40 - (note.diatonic - bottom) * 5
}

internal fun DrawScope.drawStaves(width: Float, font: Typeface, paint: Paint, upperTreble: Boolean = true, lowerTreble: Boolean = false) {
    for (top in listOf(55f, 185f)) {
        repeat(5) { drawLine(Color.Black, Offset(12f, top + it * 10), Offset(width - 12, top + it * 10), 1f) }
    }
    paint.color = android.graphics.Color.BLACK
    paint.typeface = font
    // Noto Music uses 1000 units/em. Its bass-clef dot centers are 220 units
    // apart: one staff space. Match that space to our 10-unit line spacing.
    // Unicode music glyph baselines aren't staff anchors; use the landmarks
    // in the bundled font's outlines rather than aligning the top ink bounds.
    paint.textSize = 1000f * 10f / 220f
    val bounds = android.graphics.Rect()
    // The treble spiral is centered at font y=250; the bass dots straddle
    // y=644.5 (centers 754.5 and 534.5). Font y increases upward.
    for ((glyph, referenceY, anchorUnits) in listOf(
        Triple(if (upperTreble) "𝄞" else "𝄢", noteY(MusicNote(if (upperTreble) 67 else 53, staffTreble = true, clefTreble = upperTreble)), if (upperTreble) 250f else 644.5f),
        Triple(if (lowerTreble) "𝄞" else "𝄢", noteY(MusicNote(if (lowerTreble) 67 else 53, staffTreble = false, clefTreble = lowerTreble)), if (lowerTreble) 250f else 644.5f),
    )) {
        paint.getTextBounds(glyph, 0, glyph.length, bounds)
        val baseline = referenceY + anchorUnits * paint.textSize / 1000f
        drawContext.canvas.nativeCanvas.drawText(glyph, 18f - bounds.left, baseline, paint)
    }
}

private fun DrawScope.drawLedgers(note: MusicNote, x: Float, color: Color) {
    val top = if (note.treble) 55f else 185f
    val y = noteY(note)
    var ledger = top - 10
    while (ledger >= y) { drawLine(color, Offset(x - 14, ledger), Offset(x + 14, ledger), 1.5f); ledger -= 10 }
    ledger = top + 50
    while (ledger <= y) { drawLine(color, Offset(x - 14, ledger), Offset(x + 14, ledger), 1.5f); ledger += 10 }
}

internal fun DrawScope.drawAccidental(symbol: String, x: Float, y: Float, color: Color, paint: Paint) {
    if (symbol == "𝄪") {
        drawLine(color, Offset(x, y-5), Offset(x+10, y+5), 2.6f)
        drawLine(color, Offset(x, y+5), Offset(x+10, y-5), 2.6f)
        return
    }
    if (symbol == "𝄫") {
        drawAccidental("♭", x, y, color, paint)
        drawAccidental("♭", x+8, y, color, paint)
        return
    }
    paint.color = color.toArgb()
    paint.typeface = Typeface.SERIF
    paint.textSize = 24f
    val bounds = android.graphics.Rect()
    paint.getTextBounds(symbol, 0, symbol.length, bounds)
    val baseline = if (symbol == "♭") y + 5 - bounds.bottom else y - (bounds.top + bounds.bottom) / 2f
    drawContext.canvas.nativeCanvas.drawText(symbol, x - bounds.left, baseline, paint)
}

internal fun DrawScope.drawMusicNote(note: MusicNote, x: Float, color: Color, paint: Paint) {
    val top = if (note.treble) 55f else 185f
    val y = noteY(note)
    drawLedgers(note, x, color)
    if (note.sharp || note.flat) {
        drawAccidental(if (note.flat) "♭" else "♯", x - 28, y, color, paint)
    }
    val up = y > top + 20
    val stemX = x + if (up) 6.5f else -6.5f
    drawLine(color, Offset(stemX, y), Offset(stemX, y + if (up) -34 else 34), 1.5f)
    rotate(-20f, Offset(x, y)) { drawOval(color, Offset(x - 8, y - 5.7f), Size(16f, 11.4f)) }
}

/** Repeat the signature on each grand-staff system, in conventional sharp/flat order. */
internal fun DrawScope.drawKeySignature(key: SongKey, paint: Paint, upperTreble: Boolean = true, lowerTreble: Boolean = false,
    startX: Float = 58f, cancel: Boolean = false): Float {
    val sharp = key.alterations.values.any { it == 1 }
    val order = if (sharp) "FCGDAEB" else "BEADGCF"
    val treble = if (sharp) listOf(77, 72, 79, 74, 69, 76, 71) else listOf(71, 76, 69, 74, 67, 72, 65)
    val bass = if (sharp) listOf(53, 48, 55, 50, 45, 52, 47) else listOf(47, 52, 45, 50, 43, 48, 41)
    var count = 0
    order.forEachIndexed { index, letter ->
        if (key.alterations.getValue(letter) != 0) {
            val x = startX + count++ * 16
            val symbol = if (cancel) "♮" else if (sharp) "♯" else "♭"
            drawAccidental(symbol, x, noteY(MusicNote((if (upperTreble) treble else bass)[index], staffTreble = true, clefTreble = upperTreble)), Color.Black, paint)
            drawAccidental(symbol, x, noteY(MusicNote((if (lowerTreble) treble else bass)[index], staffTreble = false, clefTreble = lowerTreble)), Color.Black, paint)
        }
    }
    return startX + count * 16 + 14
}

internal fun DrawScope.drawChord(
    notes: List<MusicNote>, x: Float, baseColor: Color, matched: Set<Int>,
    accidentals: Map<MusicNote, Accidental>, paint: Paint,
    noteLog: Int = 2, dots: Int = 0, stemUp: Boolean? = null,
) {
    if (notes.isEmpty()) return
    val layout = layoutChord(notes, accidentals, stemUp)
    val minX = layout.heads.minOf { it.offsetX }
    for ((note, offset) in layout.heads) {
        val color = if (note.midi in matched) Color(0xFF2B8552) else baseColor
        val headX = x + offset
        val y = noteY(note)
        drawLedgers(note, headX, color)
        layout.accidentalColumns[note]?.let { column ->
            drawAccidental(accidentals.getValue(note).symbol, x + minX - 28 - column * 18, y, color, paint)
        }
        rotate(-20f, Offset(headX, y)) {
            val position = Offset(headX - 8, y - 5.7f)
            if (noteLog <= 1) {
                drawOval(Color(0xFFFBFAF5), position, Size(16f, 11.4f))
                drawOval(color, position, Size(16f, 11.4f), style = Stroke(1.8f))
            } else drawOval(color, position, Size(16f, 11.4f))
        }
        repeat(dots) { dot -> drawCircle(color, 1.7f, Offset(x + 23 + dot * 6, y - if (note.diatonic % 2 == 0) 5 else 0)) }
    }
    val high = notes.minOf { noteY(it) }
    val low = notes.maxOf { noteY(it) }
    val stemX = x + if (layout.stemUp) 6.5f else -6.5f
    if (noteLog > 0) {
        val end = if (layout.stemUp) high - 34 else low + 34
        drawLine(baseColor, Offset(stemX, if (layout.stemUp) low else high), Offset(stemX, end), 1.5f)
        repeat((noteLog - 2).coerceIn(0, 4)) { flag ->
            val direction = if (layout.stemUp) 1 else -1
            val y = end + direction * flag * 7
            val path = Path().apply {
                moveTo(stemX, y)
                cubicTo(stemX + 20, y + direction * 6, stemX + 20, y + direction * 20, stemX + 11, y + direction * 27)
            }
            drawPath(path, baseColor, style = Stroke(2.2f))
        }
    }
}

internal fun DrawScope.drawQuarterRest(x: Float, staffTop: Float, font: Typeface, paint: Paint) {
    val glyph = "𝄽"
    paint.typeface = font
    paint.color = android.graphics.Color.DKGRAY
    paint.textSize = 45f
    val bounds = android.graphics.Rect()
    paint.getTextBounds(glyph, 0, glyph.length, bounds)
    if (bounds.height() == 0) return
    paint.textSize *= 30f / bounds.height()
    paint.getTextBounds(glyph, 0, glyph.length, bounds)
    drawContext.canvas.nativeCanvas.drawText(glyph, x - (bounds.left + bounds.right) / 2f,
        staffTop + 20 - (bounds.top + bounds.bottom) / 2f, paint)
}
