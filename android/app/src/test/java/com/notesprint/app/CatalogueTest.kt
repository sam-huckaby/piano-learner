package com.notesprint.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CatalogueTest {
    private val repository = CatalogueRepository { name -> File("src/main/assets/$name").readText() }

    @Test fun allThirtyCompleteVersionsAreValidAndHaveExpectedSourceCoverage() {
        assertEquals(10, repository.entries.size)
        assertEquals(10, repository.entries.map { it.id }.distinct().size)
        for (entry in repository.entries) {
            assertTrue(entry.midiVerified)
            assertEquals("Public Domain", entry.license)
            assertEquals(64, entry.sourceSha256.length)
            val hard = repository.load(CatalogueOptions(entry.id))
            assertEquals(entry.audit.getValue("hard").measures, hard.score.measures.size)
            assertEquals(entry.audit.getValue("hard").notes, hard.score.notes.count { !it.rest })
            for (difficulty in Difficulty.entries) {
                val plan = repository.load(CatalogueOptions(entry.id, difficulty))
                plan.score.validate()
                assertEquals(hard.score.measures, plan.score.measures)
                assertTrue(plan.steps.isNotEmpty())
                assertTrue(plan.score.notes.count { !it.rest } <= hard.score.notes.count { !it.rest })
            }
        }
        assertEquals(107, repository.load(CatalogueOptions("debussy-arabesque")).score.measures.size)
        assertEquals(35, repository.load(CatalogueOptions("bach-prelude-c")).score.measures.size)
        assertEquals(76, repository.load(CatalogueOptions("clementi-sonatina-c")).score.measures.size)
    }

    @Test fun originalThemesAndWrittenRegistersArePreserved() {
        val elise = repository.load(CatalogueOptions("beethoven-fur-elise"))
        assertEquals(listOf(76, 75, 76, 75, 76, 71, 74, 72, 69), elise.steps.take(9).map { it.attacks.first { n -> n.part == 0 }.midi })
        val prelude = repository.load(CatalogueOptions("bach-prelude-c"))
        assertEquals(listOf(60, 64, 67, 72, 76), prelude.steps.take(5).map { it.pitches.single() })
        val bassE = prelude.selected.first { it.midi == 64 && it.part == 1 }
        assertFalse(bassE.musicNote().treble)
        assertEquals(1920, prelude.endFor(bassE))
        assertFalse(prelude.steps.any { it.attacks.any { n -> n.id == bassE.tieTo } })
        val debussy = repository.load(CatalogueOptions("debussy-arabesque"))
        assertTrue(debussy.selected.any { it.part == 1 && it.clef })
        assertTrue(debussy.selected.any { it.alter == 2 })
        assertTrue(debussy.score.measures.any { it.numerator == 2 && it.denominator == 4 })
    }

    @Test fun handSelectionAndCuratedExcerptsDoNotAlterTheUnderlyingScore() {
        for (entry in repository.entries) for (difficulty in Difficulty.entries) {
            val full = repository.load(CatalogueOptions(entry.id, difficulty))
            val excerpt = repository.load(CatalogueOptions(entry.id, difficulty, excerpt = true))
            assertTrue(excerpt.steps.size < full.steps.size)
            assertEquals(full.score, excerpt.score)
            for (left in listOf(false, true)) {
                val one = repository.load(CatalogueOptions(entry.id, difficulty, leftHand = left, rightHand = !left))
                assertTrue(one.selected.all { it.part == if (left) 1 else 0 })
                assertTrue(one.steps.isNotEmpty())
                assertTrue(one.score.notes.any { it.part == if (left) 0 else 1 }) // Disabled part remains available to display.
            }
        }
    }

    @Test fun everyPieceDifficultyAndHandSelectionCanFinishPitchOnly() {
        for (entry in repository.entries) for (difficulty in Difficulty.entries) for (hands in listOf(0, 1, 2)) {
            val plan = repository.load(CatalogueOptions(entry.id, difficulty, leftHand = hands != 0, rightHand = hands != 1))
            val player = CataloguePlayer(plan, false)
            player.start(0)
            plan.steps.forEachIndexed { index, step ->
                val time = 1000L + index * 1000L
                step.pitches.forEachIndexed { i, pitch ->
                    player.noteOn("$pitch", pitch, time + i * 15)
                    player.noteOff("$pitch", time + i * 15 + 5)
                }
                player.tick(time + 500)
                assertEquals("${entry.id} $difficulty hands=$hands index=$index ${player.progress.feedback}", index+1, player.progress.index)
            }
            assertTrue(player.progress.finished)
        }
    }

    @Test fun sustainedBassDoesNotBlockLaterMelodyNotes() {
        val plan = repository.load(CatalogueOptions("bach-prelude-c"))
        val player = CataloguePlayer(plan, false)
        player.start(0)
        player.noteOn("bass", 60, 1000); player.tick(1100)
        player.noteOn("e", 64, 1200); player.tick(1300)
        player.noteOn("g", 67, 1400); player.noteOff("g", 1450); player.tick(1500)
        assertEquals(3, player.progress.index)
        player.noteOn("wrong", 66, 1600)
        assertEquals(3, player.progress.index)
        assertTrue(player.progress.feedback.contains("Extra"))
        player.disconnect("bass", 1700)
        assertFalse(player.progress.finished)
    }

    @Test fun guidedTimingGradesIndividualOverlappingDurationsAndRejectsShortHolds() {
        val plan = repository.load(CatalogueOptions("bach-prelude-c", excerpt = true))
        val player = CataloguePlayer(plan, true)
        player.start(0)
        val first = plan.quarterMillis.toLong()
        player.noteOn("c", 60, first)
        player.tick(first+90)
        assertEquals(1, player.progress.index)
        val second = first + plan.millis(plan.steps[1].playTick-plan.steps[0].playTick).toLong()
        player.noteOn("e", 64, second); player.tick(second+90)
        assertEquals(2, player.progress.index)
        player.noteOff("e", second+100)
        assertEquals(1, player.progress.index)
        assertTrue(player.progress.feedback.contains("length"))
    }

    @Test fun allOriginalPiecesCanFinishWithWrittenOnsetsAndIndependentDurations() {
        data class Action(val time: Long, val pitch: Int, val on: Boolean)
        for (entry in repository.entries) {
            val plan = repository.load(CatalogueOptions(entry.id))
            val player = CataloguePlayer(plan, true)
            player.start(0)
            val actions = mutableListOf<Action>()
            plan.steps.forEachIndexed { index, step ->
                val start = plan.quarterMillis.toLong() + plan.millis(step.playTick-plan.startTick).toLong()
                for (pitch in step.pitches) {
                    actions += Action(start, pitch, true)
                    val grace = step.attacks.filter { it.midi == pitch }.all { it.grace != 0 }
                    val duration = if (grace) 30L else plan.durationFor(index, pitch).toLong()
                    actions += Action(start + duration, pitch, false)
                }
            }
            var clock = 0L
            for (action in actions.sortedWith(compareBy<Action> { it.time }.thenBy { it.on })) {
                while (clock + 10 < action.time) { clock += 10; player.tick(clock) }
                clock = action.time
                if (action.on) player.noteOn("${action.pitch}", action.pitch, clock) else player.noteOff("${action.pitch}", clock)
            }
            player.tick(maxOf(clock + 200, plan.quarterMillis.toLong() + plan.millis(plan.end-plan.startTick).toLong()))
            assertTrue("${entry.id}: ${player.progress.index}/${plan.steps.size} ${player.progress.feedback}", player.progress.finished)
        }
    }

    @Test fun catalogueDefaultsAndEngineCountdownReplayStayIndependentOfSongSettings() {
        val engine = GameEngine()
        assertEquals(Difficulty.HARD, engine.state.catalogueOptions.difficulty)
        assertFalse(engine.state.catalogueOptions.excerpt)
        engine.setRange(NoteRange(4, 4)); engine.setMaxNotesPerHand(1); engine.setTwoHands(false)
        val options = CatalogueOptions("chopin-prelude-cm", Difficulty.HARD, true, leftHand = true, rightHand = false)
        engine.setCatalogueOptions(options)
        val plan = repository.load(options)
        engine.startCatalogue(plan, 100)
        engine.tick(3099); assertEquals(Phase.COUNTDOWN, engine.state.phase)
        engine.tick(3100); assertEquals(Phase.PLAYING, engine.state.phase)
        assertTrue(plan.selected.any { it.midi !in 60..71 })
        engine.reset()
        assertEquals(options, engine.state.catalogueOptions)
        assertEquals(NoteRange(4, 4), engine.state.range)
    }

    @Test fun displayedNotesHonorClefAndEnharmonicOctave() {
        val bSharp = CatalogueNote(midi = 60, step = 6, octave = 3, alter = 1, part = 1, clef = true).musicNote()
        assertEquals("B#3", bSharp.label)
        assertFalse(bSharp.treble)
        assertEquals(true, bSharp.clefTreble)
        assertEquals(2, CatalogueNote(midi = 67, step = 3, octave = 4, alter = 2).musicNote().alteration)
    }

    @Test fun keysHeldBeforeCatalogueCountdownMustBeReleased() {
        val engine = GameEngine()
        engine.setMode(GameMode.CATALOGUE)
        engine.noteOn("usb:held", 30, 0)
        val plan = repository.load(CatalogueOptions("bach-prelude-c"))
        engine.startCatalogue(plan, 100)
        engine.tick(3100)
        engine.noteOn("usb:c", 60, 3200); engine.noteOff("usb:c", 3250); engine.tick(3300)
        assertEquals(0, engine.state.score)
        engine.noteOff("usb:held", 3350)
        engine.noteOn("usb:c", 60, 3400); engine.noteOff("usb:c", 3450); engine.tick(3500)
        assertEquals(1, engine.state.score)
    }

    @Test(expected = IllegalArgumentException::class)
    fun bothHandsCannotBeDisabled() { CatalogueOptions(leftHand = false, rightHand = false) }
}
