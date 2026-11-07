package com.notesprint.app

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class ChordTest {
    private val c = SongGenerator.keys.first()
    private fun event(vararg pitches: Int): SongBeat {
        val notes = pitches.map(::MusicNote)
        return SongBeat(notes.filter { !it.treble }, notes.filter { it.treble })
    }
    private fun engine(beat: SongBeat = event(60, 64, 67), rhythm: Boolean = false): GameEngine {
        val piece = Song(c, List(16) { beat })
        return GameEngine(Random(1)) { _, _ -> piece }.apply {
            setMode(GameMode.SONG); setRhythm(rhythm); start(0); tick(3000)
        }
    }

    @Test fun generatedChordsHonorPerHandLimitsRangesAndOneOctaveSpans() {
        for (low in 0..8) for (high in low..8) for (maximum in 1..6) for (two in listOf(false, true)) {
            val range = NoteRange(low, high)
            val song = SongGenerator.generate(range, true, Random(low * 100 + high * 10 + maximum), maximum, two)
            assertEquals(16, song.beats.size)
            for (beat in song.beats) {
                assertTrue(beat.left.size <= maximum && beat.right.size <= maximum)
                assertTrue(beat.notes.isNotEmpty())
                assertEquals(beat.notes.size, beat.pitches.size)
                assertTrue(beat.notes.all { it.midi in range.midi })
                if (!two) assertTrue(beat.left.isEmpty() || beat.right.isEmpty())
                for (group in listOf(beat.left, beat.right).filter { it.isNotEmpty() }) {
                    assertTrue(group.maxOf { it.midi } - group.minOf { it.midi } <= 12)
                }
            }
        }
    }

    @Test fun maximumIsPerHandAndUnavailableHandsRest() {
        val two = SongGenerator.generate(NoteRange(), false, Random(5), 1, true)
        assertTrue(two.beats.any { it.left.size == 1 && it.right.size == 1 })
        val pieces = (0..20).map { SongGenerator.generate(NoteRange(), false, Random(it), 6, true) }
        assertTrue(pieces.any { song -> song.beats.any { it.notes.size > 6 } })
        assertTrue(pieces.any { song -> song.beats.any { it.left.isEmpty() } })
        assertTrue(pieces.any { song -> song.beats.any { it.right.isEmpty() } })
        val narrow = SongGenerator.generate(NoteRange(4, 4), false, Random(5), 6, true)
        assertTrue(narrow.beats.all { it.left.isEmpty() && it.right.isNotEmpty() })
        val low = SongGenerator.generate(NoteRange(2, 2), false, Random(5), 6, true)
        assertTrue(low.beats.all { it.right.isEmpty() && it.left.isNotEmpty() })
    }

    @Test fun naturalBorrowingOnlyOccursWithChromaticAndRestoresWithinBar() {
        val chromatic = (0..100).map { SongGenerator.generate(NoteRange(), true, Random(it), 4, true) }
        val signs = chromatic.flatMap { song -> song.beats.chunked(4).flatMap { accidentalsForBar(song.key, it) } }
        assertTrue(signs.any { Accidental.NATURAL in it.values })
        assertTrue(signs.any { Accidental.SHARP in it.values || Accidental.FLAT in it.values })
        for (seed in 0..50) {
            val song = SongGenerator.generate(NoteRange(), false, Random(seed), 6, true)
            assertTrue(song.allNotes.all { it.alteration == 0 })
            assertTrue(song.beats.chunked(4).flatMap { accidentalsForBar(song.key, it) }.all { it.isEmpty() })
        }
    }

    @Test fun rolledChordCountsFreshPressesWithoutRequiringHeldOverlap() {
        val game = engine(event(48, 52, 55, 60, 64, 67))
        val targets = game.state.song!!.beats.first().notes
        targets.forEachIndexed { index, note ->
            val time = 3100L + index * 75
            game.noteOn("key", note.midi, time)
            assertTrue(note.midi in game.state.matchedNotes)
            game.noteOff("key", time + 20)
        }
        assertEquals(0, game.state.score)
        game.tick(3560)
        assertEquals(1, game.state.score)
        assertTrue(game.state.matchedNotes.isEmpty())
    }

    @Test fun extraKeyInvalidatesEvenAfterAllRequiredNotesArrive() {
        val game = engine()
        for ((index, note) in listOf(60, 64, 67).withIndex()) {
            game.noteOn("$note", note, 3100L + index * 50)
        }
        game.noteOn("wrong", 61, 3210)
        game.noteOff("wrong", 3220)
        game.tick(3300)
        assertEquals(0, game.state.score)
        assertTrue(game.state.matchedNotes.isEmpty())
    }

    @Test fun incompleteOrTooSlowChordsCannotBeAccumulatedAcrossAttempts() {
        val game = engine(event(60, 64))
        game.noteOn("c", 60, 3100); game.noteOff("c", 3120)
        game.tick(3551)
        assertTrue(game.state.matchedNotes.isEmpty())
        game.noteOn("e", 64, 3600); game.noteOff("e", 3620)
        game.tick(4100)
        assertEquals(0, game.state.score)
        game.noteOn("c", 60, 4200); game.noteOff("c", 4220)
        game.noteOn("e", 64, 4651); game.noteOff("e", 4670)
        game.tick(4800)
        assertEquals(0, game.state.score)
    }

    @Test fun assemblyWindowBoundaryAndAllSixteenChordBeatsWork() {
        val game = engine()
        repeat(16) { index ->
            val start = 3100L + index * 1000
            game.noteOn("c", 60, start); game.noteOff("c", start + 20)
            game.noteOn("e", 64, start + 225); game.noteOff("e", start + 245)
            game.noteOn("g", 67, start + 450); game.noteOff("g", start + 470)
            game.tick(start + 535)
            assertEquals(index + 1, game.state.score)
        }
        assertEquals(Phase.FINISHED, game.state.phase)
    }

    @Test fun previouslyHeldKeysAndDisconnectsCannotSupplyChordNotes() {
        val game = engine()
        game.noteOn("d:old", 59, 3100)
        game.noteOn("d:c", 60, 3120)
        game.noteOn("d:e", 64, 3140)
        game.noteOn("d:g", 67, 3160)
        game.noteOff("d:old", 3170)
        game.tick(3300)
        assertEquals(0, game.state.score)
        game.disconnect("d")
        game.noteOn("d:c", 60, 3400)
        game.noteOn("d:e", 64, 3420)
        game.disconnect("d")
        game.tick(3600)
        assertEquals(0, game.state.score)
    }

    @Test fun guidedRollUsesFirstPressToFinalReleaseNotEachIndividualHold() {
        val game = engine(rhythm = true)
        val first = 3000 + game.state.song!!.beatMillis
        game.noteOn("c", 60, first); game.noteOff("c", first + 20)
        game.noteOn("e", 64, first + 150); game.noteOff("e", first + 180)
        game.noteOn("g", 67, first + 350)
        game.tick(first + 500)
        assertEquals(0, game.state.score)
        game.noteOff("g", first + game.state.song!!.beatMillis)
        assertEquals(1, game.state.score)
    }

    @Test fun guidedRollRejectsShortSpanAndWrongOnset() {
        val game = engine(rhythm = true)
        val first = 3000 + game.state.song!!.beatMillis
        listOf(60, 64, 67).forEachIndexed { index, note ->
            game.noteOn("$note", note, first + index * 50)
            game.noteOff("$note", first + index * 50 + 20)
        }
        game.tick(first + 200)
        assertEquals(0, game.state.score)
        assertTrue(game.state.feedback.contains("Hold"))
        game.noteOn("c", 60, first + 300)
        assertTrue(game.state.feedback.contains("early"))
        assertEquals(0, game.state.score)
    }

    @Test fun chordPreferencesSurviveReplayAndDoNotRelaxFlashcards() {
        val game = GameEngine(Random(1))
        assertEquals(1, game.state.maxNotesPerHand)
        assertFalse(game.state.twoHands)
        game.setMaxNotesPerHand(6); game.setTwoHands(true)
        game.start(0); game.tick(3000)
        val note = game.state.note!!.midi
        game.noteOn("c", note, 3100); game.noteOn("other", note + 1, 3110)
        game.tick(3300)
        assertEquals(0, game.state.score)
        game.reset()
        assertEquals(6, game.state.maxNotesPerHand)
        assertTrue(game.state.twoHands)
    }

    @Test fun accidentalsCarryWithinBarAndResetForEachOctaveStaffAndNewBar() {
        val g = SongGenerator.keys[1]
        val sharp = MusicNote(66, "F#")
        val natural = MusicNote(65, "F")
        val bar = listOf(sharp, natural, natural, sharp).map { SongBeat(right = listOf(it)) }
        val signs = accidentalsForBar(g, bar)
        assertTrue(signs[0].isEmpty())
        assertEquals(Accidental.NATURAL, signs[1][natural])
        assertTrue(signs[2].isEmpty())
        assertEquals(Accidental.SHARP, signs[3][sharp])
        assertTrue(accidentalsForBar(g, listOf(bar.first())).first().isEmpty())
        val higher = MusicNote(77, "F")
        val lower = MusicNote(53, "F")
        val independent = accidentalsForBar(g, listOf(SongBeat(right = listOf(natural)), SongBeat(left = listOf(lower), right = listOf(higher))))
        assertEquals(Accidental.NATURAL, independent[1][higher])
        assertEquals(Accidental.NATURAL, independent[1][lower])
    }

    @Test fun flatSignaturesAndTemporaryRestorationAreCorrect() {
        val f = SongGenerator.keys[2]
        val flat = MusicNote(58, "Bb")
        val natural = MusicNote(59, "B")
        val signs = accidentalsForBar(f, listOf(flat, natural, natural, flat).map { SongBeat(left = listOf(it)) })
        assertTrue(signs[0].isEmpty())
        assertEquals(Accidental.NATURAL, signs[1][natural])
        assertTrue(signs[2].isEmpty())
        assertEquals(Accidental.FLAT, signs[3][flat])
    }

    @Test fun chordHeadsAndAccidentalColumnsAvoidAdjacentCollisions() {
        val notes = listOf(60, 62, 64, 65, 67, 69).map(::MusicNote)
        val layout = layoutChord(notes, notes.associateWith { Accidental.NATURAL })
        for ((a, b) in layout.heads.zipWithNext()) {
            if (abs(a.note.diatonic - b.note.diatonic) == 1) assertEquals(13f, abs(a.offsetX - b.offsetX), 0f)
        }
        for ((a, column) in layout.accidentalColumns) for ((b, other) in layout.accidentalColumns) {
            if (a != b && column == other) assertTrue(abs(a.diatonic - b.diatonic) >= 4)
        }
        assertFalse(layoutChord(listOf(MusicNote(74), MusicNote(76)), emptyMap()).stemUp)
    }
}
