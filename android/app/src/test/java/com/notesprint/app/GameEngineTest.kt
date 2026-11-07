package com.notesprint.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class GameEngineTest {
    @Test fun noteNameDefaultsOffAndSelectionSurvivesRoundsAndReset() {
        val game = GameEngine()
        assertFalse(game.state.showNoteName)
        game.setShowNoteName(true)
        game.start(0)
        game.tick(3000)
        assertTrue(game.state.showNoteName)
        game.tick(63000)
        game.start(64000)
        assertTrue(game.state.showNoteName)
        game.reset()
        assertTrue(game.state.showNoteName)
        game.setShowNoteName(false)
        assertFalse(game.state.showNoteName)
    }

    private fun playing(): GameEngine = GameEngine(Random(1)).apply { start(0); tick(3000) }

    @Test fun countdownDoesNotConsumePlayingTime() {
        val game = GameEngine()
        game.start(0)
        game.tick(2999)
        assertEquals(Phase.COUNTDOWN, game.state.phase)
        game.tick(3000)
        assertEquals(60, game.state.seconds)
        game.tick(62999)
        assertEquals(Phase.PLAYING, game.state.phase)
        game.tick(63000)
        assertEquals(Phase.FINISHED, game.state.phase)
    }

    @Test fun soloNoteScoresAfterChordWindowAndDoesNotRepeat() {
        val game = playing()
        val note = game.state.note!!.midi
        game.noteOn("1:0:0:$note", note, 3100)
        game.tick(3184)
        assertEquals(0, game.state.score)
        game.tick(3185)
        assertEquals(1, game.state.score)
        assertNotEquals(note, game.state.note!!.midi)
    }

    @Test fun chordCannotScoreEvenIfExtraKeyIsReleased() {
        val game = playing()
        val note = game.state.note!!.midi
        game.noteOn("target", note, 3100)
        game.noteOn("other", note + 1, 3110)
        game.noteOff("other")
        game.tick(3185)
        assertEquals(0, game.state.score)
    }

    @Test fun incorrectHeldNotePreventsScore() {
        val game = playing()
        val note = game.state.note!!.midi
        game.noteOn("other", note + 1, 3100)
        game.noteOn("target", note, 3110)
        game.noteOff("other")
        game.tick(3300)
        assertEquals(0, game.state.score)
    }

    @Test fun quickSoloTapCountsWithoutHavingToHoldFor85ms() {
        val game = playing()
        game.noteOn("target", game.state.note!!.midi, 3100)
        game.noteOff("target")
        game.tick(3185)
        assertEquals(1, game.state.score)
    }

    @Test fun unconfirmedNoteCannotScoreAfterDeadline() {
        val game = playing()
        game.noteOn("target", game.state.note!!.midi, 62990)
        game.tick(63075)
        assertEquals(0, game.state.score)
        assertEquals(Phase.FINISHED, game.state.phase)
    }

    @Test fun countdownHeldKeysMustBeReleasedBeforeScoring() {
        val game = GameEngine(Random(1))
        game.start(0)
        game.noteOn("held", 60, 1000)
        game.tick(3000)
        val note = game.state.note!!.midi
        game.noteOn("target", note, 3100)
        game.tick(3200)
        assertEquals(0, game.state.score)
    }

    @Test fun naturalNotePoolAndNotationExtremes() {
        val game = playing()
        repeat(100) { index ->
            val note = game.state.note!!
            assertTrue(note.midi in 36..84)
            assertFalse(note.sharp)
            val now = 3100L + index * 200
            game.noteOn("tap", note.midi, now)
            game.noteOff("tap")
            game.tick(now + 85)
        }
        assertEquals(100, game.state.score)
        assertEquals("C2", MusicNote(36).label)
        assertEquals("C6", MusicNote(84).label)
    }
}
