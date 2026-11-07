package com.notesprint.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SongTest {
    @Test fun generationHonorsEveryOctaveRangeAndKeySpelling() {
        for (low in 0..8) for (high in low..8) repeat(20) { seed ->
            val range = NoteRange(low, high)
            val song = SongGenerator.generate(range, true, Random(seed))
            assertEquals(16, song.beats.size)
            song.allNotes.forEach { note ->
                assertTrue("${note.label} escaped ${range.label}", note.midi in range.midi)
                val natural = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11).getValue(note.pitch.first())
                assertEquals((natural + note.alteration + 12) % 12, note.midi % 12)
                if (song.key.degree(note.midi) < 0) {
                    assertEquals(0, note.alteration)
                    assertNotEquals(0, song.key.alterations.getValue(note.pitch.first()))
                }
            }
            assertEquals(0, song.key.degree(song.beats.last().notes.first().midi))
        }
    }

    @Test fun defaultSongsAreNaturalAndUseBothStaves() {
        repeat(50) {
            val song = SongGenerator.generate(NoteRange(), false, Random(it))
            assertEquals("C major", song.key.name)
            assertTrue(song.allNotes.all { !it.sharp && !it.flat })
            assertTrue(song.allNotes.any { it.treble })
            assertTrue(song.allNotes.any { !it.treble })
        }
    }

    @Test fun narrowRangesTakePrecedenceOverBothClefs() {
        val song = SongGenerator.generate(NoteRange(4, 4), false, Random(3))
        assertTrue(song.allNotes.all { it.midi in 60..71 && it.treble })
    }

    @Test fun chromaticModeGeneratesDifferentKeysIncludingFlatsAndSharps() {
        val songs = (0..100).map { SongGenerator.generate(NoteRange(), true, Random(it)) }
        assertEquals(SongGenerator.keys.toSet(), songs.map { it.key }.toSet())
        assertTrue(songs.any { it.allNotes.any(MusicNote::flat) })
        assertTrue(songs.any { it.allNotes.any(MusicNote::sharp) })
        assertTrue(songs.map { it.beats }.distinct().size > 50)
    }

    private fun songEngine(rhythm: Boolean = false): GameEngine = GameEngine(Random(5)).apply {
        setMode(GameMode.SONG)
        setRhythm(rhythm)
        start(0)
        tick(3000)
    }

    @Test fun pitchSongWaitsIndefinitelyAndCompletesExactlyAtLastNote() {
        val game = songEngine()
        game.tick(120_000)
        assertEquals(Phase.PLAYING, game.state.phase)
        val original = game.state.song!!
        repeat(16) { index ->
            assertEquals(original.beats[index].notes.first(), game.state.note)
            val time = 121_000L + index * 200
            game.noteOn("key", game.state.note!!.midi, time)
            game.noteOff("key", time + 20)
            game.tick(time + 85)
            assertEquals(index + 1, game.state.score)
        }
        assertEquals(Phase.FINISHED, game.state.phase)
        game.start(140_000)
        assertNotEquals(original.beats, game.state.song!!.beats)
    }

    @Test fun wrongNotesAndChordsDoNotAdvanceSong() {
        val game = songEngine()
        val target = game.state.note!!.midi
        game.noteOn("wrong", target + 1, 3100)
        game.noteOff("wrong", 3150)
        game.tick(3200)
        assertEquals(0, game.state.score)
        game.noteOn("target", target, 3300)
        game.noteOn("wrong", target + 1, 3310)
        game.noteOff("wrong", 3320)
        game.noteOff("target", 3330)
        game.tick(3400)
        assertEquals(0, game.state.score)
    }

    @Test fun guidedSongAcceptsOnBeatPressesWithQuarterNoteDurations() {
        val game = songEngine(true)
        val beat = game.state.song!!.beatMillis
        repeat(16) { index ->
            val start = 3000 + (index + 1) * beat
            game.noteOn("key", game.state.note!!.midi, start)
            game.tick(start + 85)
            assertEquals(index, game.state.score)
            game.noteOff("key", start + beat)
            assertEquals(index + 1, game.state.score)
        }
        assertEquals(Phase.FINISHED, game.state.phase)
    }

    @Test fun guidedSongRejectsShortNotesAndSchedulesRetryWithoutSkipping() {
        val game = songEngine(true)
        val target = game.state.note!!
        val beat = game.state.song!!.beatMillis
        val start = 3000 + beat
        game.noteOn("key", target.midi, start)
        game.noteOff("key", start + 100)
        assertEquals(0, game.state.score)
        assertEquals(target, game.state.note)
        assertTrue(game.state.feedback.contains("Hold"))
        val retry = start + 100 + beat
        game.noteOn("key", target.midi, retry)
        game.noteOff("key", retry + beat)
        assertEquals(1, game.state.score)
    }

    @Test fun guidedSongRetriesMissesAndRejectsEarlyOrOverlongNotes() {
        val missed = songEngine(true)
        val firstBeat = 3000 + missed.state.song!!.beatMillis
        missed.tick(firstBeat + 300)
        assertEquals(0, missed.state.score)
        assertTrue(missed.state.feedback.contains("Missed"))
        val early = songEngine(true)
        early.tick(firstBeat + 300)
        early.noteOn("key", early.state.note!!.midi, firstBeat + 400)
        assertEquals(0, early.state.score)
        assertTrue(early.state.feedback.contains("early"))
        val long = songEngine(true)
        long.noteOn("key", long.state.note!!.midi, firstBeat)
        long.tick(firstBeat + 1500)
        assertEquals(0, long.state.score)
        assertTrue(long.state.feedback.contains("long"))
    }

    @Test fun rangeAppliesToFlashcardsAndSurvivesBothModesAndReplay() {
        val game = GameEngine(Random(4))
        assertEquals(NoteRange(3, 5), game.state.range)
        game.setRange(NoteRange(4, 4))
        game.start(0); game.tick(3000)
        repeat(30) { index ->
            val note = game.state.note!!
            assertTrue(note.midi in 60..71)
            val now = 3100L + index * 200
            game.noteOn("key", note.midi, now); game.noteOff("key"); game.tick(now + 85)
        }
        game.reset(); game.setMode(GameMode.SONG); game.start(20000); game.tick(23000)
        assertTrue(game.state.song!!.allNotes.all { it.midi in 60..71 })
        game.reset()
        assertEquals(NoteRange(4, 4), game.state.range)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidOctaveRangeIsRejected() { NoteRange(5, 3) }
}
