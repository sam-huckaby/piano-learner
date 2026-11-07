package com.notesprint.app

import kotlin.math.abs

data class ChordHead(val note: MusicNote, val offsetX: Float)
data class ChordLayout(val stemUp: Boolean, val heads: List<ChordHead>, val accidentalColumns: Map<MusicNote, Int>)

/** Seconds alternate sides of a common stem; nearby accidentals use separate columns. */
fun layoutChord(notes: List<MusicNote>, accidentals: Map<MusicNote, Accidental>, stemUp: Boolean? = null): ChordLayout {
    require(notes.isNotEmpty())
    val middleLine = if (notes.first().clefTreble ?: notes.first().treble) 4 * 7 + 6 else 3 * 7 + 1
    val up = stemUp ?: (notes.map { it.diatonic }.average() < middleLine)
    val ordered = if (up) notes.sortedBy { it.diatonic } else notes.sortedByDescending { it.diatonic }
    val heads = mutableListOf<ChordHead>()
    for (note in ordered) {
        val previous = heads.lastOrNull()
        val shifted = previous != null && abs(previous.note.diatonic - note.diatonic) <= 1 && previous.offsetX == 0f
        heads += ChordHead(note, if (shifted) { if (up) 13f else -13f } else 0f)
    }
    val columns = mutableListOf<MutableList<Int>>()
    val positions = mutableMapOf<MusicNote, Int>()
    for (note in notes.filter { it in accidentals }.sortedByDescending { it.diatonic }) {
        var column = columns.indexOfFirst { occupied -> occupied.all { abs(it - note.diatonic) >= 4 } }
        if (column == -1) { column = columns.size; columns += mutableListOf<Int>() }
        columns[column] += note.diatonic
        positions[note] = column
    }
    return ChordLayout(up, heads, positions)
}
