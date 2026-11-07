package com.notesprint.app

import kotlin.math.abs
import kotlin.random.Random

data class NoteRange(val lowestOctave: Int = 3, val highestOctave: Int = 5) {
    init { require(lowestOctave in 0..8 && highestOctave in lowestOctave..8) }
    val midi get() = ((lowestOctave + 1) * 12)..((highestOctave + 2) * 12 - 1)
    val label get() = "C$lowestOctave–B$highestOctave"
}

data class SongKey(val name: String, val tonic: Int, val names: List<String>) {
    private val intervals = listOf(0, 2, 4, 5, 7, 9, 11)
    val alterations get() = names.associate { it.first() to when (it.last()) { '#' -> 1; 'b' -> -1; else -> 0 } }
    fun degree(midi: Int) = intervals.indexOf((midi - tonic + 120) % 12)
    fun note(midi: Int) = MusicNote(midi, names[degree(midi)])
}

data class SongBeat(val left: List<MusicNote> = emptyList(), val right: List<MusicNote> = emptyList()) {
    val notes get() = left + right
    val pitches get() = notes.map { it.midi }.toSet()
    init {
        require(left.size <= 6 && right.size <= 6)
        require(left.all { !it.treble } && right.all { it.treble })
        require(notes.isNotEmpty() && notes.map { it.midi }.distinct().size == notes.size)
    }
}

data class Song(val key: SongKey, val beats: List<SongBeat>, val bpm: Int = 72) {
    init { require(beats.size == 16) }
    val beatMillis get() = 60_000L / bpm
    val allNotes get() = beats.flatMap { it.notes }
}

enum class Accidental(val symbol: String) { FLAT("♭"), NATURAL("♮"), SHARP("♯"), DOUBLE_SHARP("𝄪"), DOUBLE_FLAT("𝄫") }

/** Alterations apply to a letter+octave on its staff until the bar line. */
fun accidentalsForBar(key: SongKey, beats: List<SongBeat>): List<Map<MusicNote, Accidental>> {
    val active = mutableMapOf<Pair<Boolean, Int>, Int>()
    return beats.map { beat ->
        buildMap {
            for (note in beat.notes) {
                val location = note.treble to note.diatonic
                val previous = active[location] ?: key.alterations.getValue(note.pitch.first())
                if (previous != note.alteration) {
                    put(note, when (note.alteration) { -2 -> Accidental.DOUBLE_FLAT; -1 -> Accidental.FLAT; 1 -> Accidental.SHARP; 2 -> Accidental.DOUBLE_SHARP; else -> Accidental.NATURAL })
                }
                active[location] = note.alteration
            }
        }
    }
}

/** Four bars, quarter-note beats, bounded per-hand voicings and optional borrowed naturals. */
object SongGenerator {
    val keys = listOf(
        SongKey("C major", 0, listOf("C", "D", "E", "F", "G", "A", "B")),
        SongKey("G major", 7, listOf("G", "A", "B", "C", "D", "E", "F#")),
        SongKey("F major", 5, listOf("F", "G", "A", "Bb", "C", "D", "E")),
        SongKey("D major", 2, listOf("D", "E", "F#", "G", "A", "B", "C#")),
        SongKey("Bb major", 10, listOf("Bb", "C", "D", "Eb", "F", "G", "A")),
    )

    fun generate(range: NoteRange, chromatic: Boolean, random: Random, maxNotesPerHand: Int = 1, twoHands: Boolean = false): Song {
        require(maxNotesPerHand in 1..6)
        val key = if (chromatic) keys.random(random) else keys.first()
        val pool = range.midi.filter { key.degree(it) >= 0 }
        val rightPool = pool.filter { it >= 60 }
        val leftPool = pool.filter { it < 60 }
        val melody = rightPool.ifEmpty { leftPool }
        val progression = listOf(0, listOf(3, 5).random(random), 4, 0)
        val motif = listOf(listOf(0, 1, -1), listOf(0, -1, 1), listOf(0, 2, 1)).random(random)
        val result = mutableListOf<SongBeat>()
        var previous = melody.filter { key.degree(it) == 0 }.minBy { abs(it - 67) }
        var previousBass = leftPool.minByOrNull { abs(it - 48) } ?: previous

        fun voice(handPool: List<Int>, anchor: Int, root: Int, strong: Boolean): List<MusicNote> {
            if (handPool.isEmpty()) return emptyList()
            // Favor dyads/triads rather than filling every allowed slot on every beat.
            val sizes = if (strong) listOf(1, 2, 2, 3, 3, 3, 4, 4, 5, 6) else listOf(1, 1, 1, 2, 2, 3, 4)
            val count = sizes.filter { it <= maxNotesPerHand }.random(random)
            // Core triad, then seventh/ninth/sixth colors. Keep a hand within one octave.
            val priorities = listOf(0, 2, 4, 6, 1, 5, 3).map { (root + it) % 7 }
            val octave = anchor / 12
            val candidates = handPool.filter { it / 12 == octave && it != anchor }
                .sortedWith(compareBy<Int> { priorities.indexOf(key.degree(it)) }.thenBy { abs(it - anchor) })
            return (listOf(anchor) + candidates.take(count - 1)).sorted().map(key::note)
        }

        repeat(4) { bar ->
            val root = progression[bar]
            val chord = setOf(root, (root + 2) % 7, (root + 4) % 7)
            repeat(4) { beat ->
                val ending = bar == 3 && beat == 3
                val options = melody.filter { (beat != 0 && beat != 2) || key.degree(it) in chord }
                val desired = previous + motif[(beat + 2) % 3] * 2
                val nearby = options.sortedBy { abs(it - desired) }.take(3)
                val melodyPitch = if (ending) melody.filter { key.degree(it) == 0 }.minBy { abs(it - previous) }
                    else if (random.nextInt(4) == 0) nearby.random(random) else nearby.first()
                val bassPitch = leftPool.filter { key.degree(it) == root }.minByOrNull { abs(it - previousBass) }
                val strong = beat == 0 || beat == 2
                var useLeft = leftPool.isNotEmpty() && (rightPool.isEmpty() || if (twoHands) strong || random.nextInt(3) == 0 else beat == 0)
                var useRight = rightPool.isNotEmpty() && (if (twoHands) strong || random.nextInt(5) != 0 else !useLeft)
                // Either hand may rest, but the beat must always have something to play.
                if (!useLeft && !useRight) {
                    if (rightPool.isNotEmpty()) useRight = true else useLeft = true
                }
                val left = if (useLeft) {
                    val anchor = if (rightPool.isEmpty() && beat != 0) melodyPitch else bassPitch!!
                    previousBass = anchor
                    voice(leftPool, anchor, root, strong)
                } else emptyList()
                val right = if (useRight) voice(rightPool, melodyPitch, root, strong) else emptyList()
                if (useRight || rightPool.isEmpty()) previous = melodyPitch
                result += SongBeat(left, right)
            }
        }
        // A borrowed natural on a weak beat, followed by its diatonic restoration,
        // teaches cancellations without disrupting the opening or tonic cadence.
        if (chromatic && key.alterations.values.any { it != 0 } && random.nextInt(3) != 0) {
            val index = random.nextInt(1, 3) * 4 + 1
            val useRight = result[index].right.isNotEmpty()
            val handPool = if (useRight) rightPool else leftPool
            val original = if (useRight) result[index].right else result[index].left
            val altered = handPool.map(key::note).filter { it.alteration != 0 }
                .filter { it.midi - it.alteration in range.midi && (it.midi - it.alteration >= 60) == useRight }
                .minByOrNull { abs(it.midi - original.first().midi) }
            if (altered != null) {
                val natural = MusicNote(altered.midi - altered.alteration, altered.pitch.first().toString())
                fun replace(at: Int, note: MusicNote) {
                    val old = result[at]
                    val group = if (useRight) old.right else old.left
                    val voiced = (listOf(note) + group.filter { it.diatonic != note.diatonic && abs(it.midi - note.midi) <= 12 })
                        .distinctBy { it.midi }.take(maxNotesPerHand).sortedBy { it.midi }
                    result[at] = if (useRight) old.copy(right = voiced) else old.copy(left = voiced)
                }
                replace(index, natural)
                replace(index + 1, altered)
            }
        }
        return Song(key, result)
    }
}
