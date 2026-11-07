package com.notesprint.app

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.random.Random

enum class Phase { WELCOME, COUNTDOWN, PLAYING, FINISHED }
enum class GameMode { FLASHCARDS, SONG, CATALOGUE }

data class MusicNote(val midi: Int, val spelling: String? = null, val staffTreble: Boolean? = null,
    val clefTreble: Boolean? = null, val writtenOctave: Int? = null) {
    val pitch get() = spelling ?: listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[midi % 12]
    val octave get() = writtenOctave ?: (midi / 12 - 1)
    val label get() = "$pitch$octave"
    val treble get() = staffTreble ?: (midi >= 60)
    val sharp get() = pitch.endsWith("#")
    val flat get() = pitch.endsWith("b")
    val alteration get() = pitch.count { it == '#' } - pitch.count { it == 'b' }
    val diatonic get() = octave * 7 + "CDEFGAB".indexOf(pitch.first())
}

data class GameState(
    val phase: Phase = Phase.WELCOME,
    val score: Int = 0,
    val seconds: Int = 60,
    val countdown: Int = 3,
    val note: MusicNote? = null,
    val chromatic: Boolean = false,
    val showNoteName: Boolean = false,
    val mode: GameMode = GameMode.FLASHCARDS,
    val range: NoteRange = NoteRange(),
    val rhythm: Boolean = false,
    val song: Song? = null,
    val elapsedSeconds: Int = 0,
    val feedback: String = "",
    val beat: Int = 1,
    val beatPulse: Boolean = false,
    val maxNotesPerHand: Int = 1,
    val twoHands: Boolean = false,
    val matchedNotes: Set<Int> = emptySet(),
    val catalogueOptions: CatalogueOptions = CatalogueOptions(),
    val cataloguePlan: CataloguePlan? = null,
    val catalogueProgress: CatalogueProgress = CatalogueProgress(),
)

/** All calls are confined to the main thread. Times use a monotonic clock. */
class GameEngine(
    private val random: Random = Random.Default,
    private val generateSong: (GameState, Random) -> Song = { settings, rng ->
        SongGenerator.generate(settings.range, settings.chromatic, rng, settings.maxNotesPerHand, settings.twoHands)
    },
) {
    var state = GameState()
        private set
    private val held = mutableMapOf<String, Int>()
    private var countdownEnds = 0L
    private var gameStarts = 0L
    private var gameEnds = 0L
    private var rhythmDue = 0L
    private var attemptAt: Long? = null
    private var lastNoteAt = 0L
    private val seenNotes = mutableSetOf<Int>()
    private var releasedAt: Long? = null
    private var invalid = false
    private var ready = true
    private var cataloguePlayer: CataloguePlayer? = null
    private var catalogueNow = 0L
    private val guided get() = state.mode == GameMode.SONG && state.rhythm
    private val beatMillis get() = state.song?.beatMillis ?: 833L
    private val onsetTolerance = 250L
    private val durationTolerance get() = beatMillis * 35 / 100
    private val expected get() = if (state.mode == GameMode.SONG) state.song!!.beats[state.score].pitches else setOf(state.note!!.midi)

    private fun configure(update: GameState.() -> GameState) {
        if (state.phase == Phase.WELCOME || state.phase == Phase.FINISHED) state = state.update()
    }
    fun setChromatic(value: Boolean) = configure { copy(chromatic = value) }
    fun setShowNoteName(value: Boolean) = configure { copy(showNoteName = value) }
    fun setMode(value: GameMode) = configure { copy(mode = value) }
    fun setRange(value: NoteRange) = configure { copy(range = value) }
    fun setRhythm(value: Boolean) = configure { copy(rhythm = value) }
    fun setMaxNotesPerHand(value: Int) { require(value in 1..6); configure { copy(maxNotesPerHand = value) } }
    fun setTwoHands(value: Boolean) = configure { copy(twoHands = value) }
    fun setCatalogueOptions(value: CatalogueOptions) = configure { copy(catalogueOptions = value) }

    private fun fresh(phase: Phase = Phase.WELCOME) = GameState(
        phase = phase, chromatic = state.chromatic, showNoteName = state.showNoteName,
        mode = state.mode, range = state.range, rhythm = state.rhythm,
        maxNotesPerHand = state.maxNotesPerHand, twoHands = state.twoHands,
        catalogueOptions = state.catalogueOptions,
    )

    fun start(now: Long) {
        require(state.mode != GameMode.CATALOGUE) { "Load a catalogue plan before starting" }
        cataloguePlayer = null
        state = fresh(Phase.COUNTDOWN)
        if (state.mode == GameMode.SONG) state = state.copy(song = generateSong(state, random))
        countdownEnds = now + 3_000
        clearAttempt()
        ready = held.isEmpty()
    }

    fun startCatalogue(plan: CataloguePlan, now: Long) {
        state = fresh(Phase.COUNTDOWN).copy(mode = GameMode.CATALOGUE, cataloguePlan = plan, catalogueOptions = plan.options)
        cataloguePlayer = CataloguePlayer(plan, state.rhythm)
        held.forEach { (key, pitch) -> cataloguePlayer?.noteOn(key, pitch, now) }
        countdownEnds = now + 3000
        clearAttempt()
    }

    fun reset() { state = fresh(); cataloguePlayer = null; clearAttempt() }

    fun tick(now: Long) {
        catalogueNow = now
        if (state.phase == Phase.COUNTDOWN) {
            if (now < countdownEnds) {
                state = state.copy(countdown = ceil((countdownEnds - now) / 1000.0).toInt())
                return
            }
            gameStarts = now
            gameEnds = now + 60_000
            // Give the player one beat to see the first note before its pulse.
            rhythmDue = now + if (guided) beatMillis else 0
            state = state.copy(phase = Phase.PLAYING, note = if (state.mode == GameMode.CATALOGUE) null else state.song?.beats?.first()?.notes?.first() ?: nextNote())
            cataloguePlayer?.start(now)
            ready = held.isEmpty()
        }
        if (state.phase != Phase.PLAYING) return
        if (state.mode == GameMode.CATALOGUE) {
            cataloguePlayer?.tick(now)
            publishCatalogue()
            return
        }
        if (state.mode == GameMode.FLASHCARDS) {
            if (now >= gameEnds) {
                state = state.copy(phase = Phase.FINISHED, seconds = 0)
                clearAttempt()
                return
            }
            state = state.copy(seconds = ceil((gameEnds - now) / 1000.0).toInt())
        } else {
            state = state.copy(elapsedSeconds = ((now - gameStarts) / 1000).toInt())
            if (guided) {
                val position = Math.floorMod(now - rhythmDue, beatMillis)
                state = state.copy(beat = state.score % 4 + 1, beatPulse = now >= rhythmDue && position < 150)
                if (attemptAt == null && now > rhythmDue + onsetTolerance) {
                    retryRhythm(now, "Missed the beat. Try this note on the next pulse.")
                }
            }
        }
        val started = attemptAt ?: return
        if (now - started < 85) return
        if (invalid) {
            clearAttempt()
            ready = held.isEmpty()
            if (guided) retryRhythm(now, "Extra note. Release all keys and retry on the next pulse.")
            else state = state.copy(feedback = "Release all keys and retry the highlighted beat.")
            return
        }
        val complete = seenNotes.containsAll(expected)
        if (!complete) {
            if (now - started > CHORD_ASSEMBLY_MS) failAssembly(now)
            return
        }
        // Retain the grace period after the final rolled note to catch extra keys.
        if (now - lastNoteAt < 85) return
        if (guided) {
            val released = releasedAt
            if (released == null) {
                if (now - started > beatMillis + durationTolerance) retryRhythm(now, "Held too long. Release and retry on the next pulse.")
                return
            }
            if (abs(released - started - beatMillis) > durationTolerance) {
                retryRhythm(now, "Hold for one beat, then release. Retry on the next pulse.")
                return
            }
        }
        advance()
    }

    private fun advance() {
        clearAttempt()
        val score = state.score + 1
        val song = state.song
        state = if (state.mode == GameMode.SONG && song != null) {
            if (guided) rhythmDue += beatMillis
            if (score == song.beats.size) state.copy(score = score, phase = Phase.FINISHED, feedback = "Piece complete!")
            else state.copy(score = score, note = song.beats[score].notes.first(), feedback = "")
        } else state.copy(score = score, note = nextNote(), feedback = "")
        ready = held.isEmpty()
    }

    private fun clearAttempt() {
        attemptAt = null; releasedAt = null; invalid = false; seenNotes.clear()
        state = state.copy(matchedNotes = emptySet())
    }

    private fun failAssembly(now: Long) {
        val message = "Roll every highlighted note within 450 ms. Release and retry."
        if (guided) retryRhythm(now, message)
        else { clearAttempt(); ready = held.isEmpty(); state = state.copy(feedback = message) }
    }

    private fun retryRhythm(now: Long, message: String) {
        clearAttempt()
        rhythmDue = now + beatMillis
        ready = held.isEmpty()
        state = state.copy(feedback = message, beatPulse = false)
    }

    fun noteOn(key: String, note: Int, now: Long) {
        if (state.mode == GameMode.CATALOGUE) {
            catalogueNow = now; held[key] = note; cataloguePlayer?.noteOn(key, note, now); publishCatalogue(); return
        }
        if (held.containsKey(key)) return
        held[key] = note
        if (state.phase != Phase.PLAYING || (state.mode == GameMode.FLASHCARDS && now >= gameEnds)) { tick(now); return }
        val targets = expected
        val started = attemptAt
        if (started != null) {
            if (note !in targets || state.mode == GameMode.FLASHCARDS) {
                invalid = true
                state = state.copy(feedback = "Extra note. Release all keys and retry.")
            } else if (now - started > CHORD_ASSEMBLY_MS && !seenNotes.containsAll(targets)) {
                invalid = true
                state = state.copy(feedback = "Chord assembled too slowly. Release and retry.")
            } else {
                seenNotes += note
                lastNoteAt = now
                releasedAt = null
                state = state.copy(matchedNotes = seenNotes.toSet())
            }
            return
        }
        if (note !in targets) {
            state = state.copy(feedback = "Try only the highlighted notes.")
            return
        }
        if (ready && held.size == 1) {
            if (guided && abs(now - rhythmDue) > onsetTolerance) {
                retryRhythm(now, "A little early or late. Release and try on the next pulse.")
                return
            }
            attemptAt = now
            lastNoteAt = now
            seenNotes += note
            releasedAt = null
            invalid = false
            state = state.copy(matchedNotes = seenNotes.toSet(), feedback = if (guided) "Roll the notes; finish releasing at the next beat." else "")
        }
    }

    fun noteOff(key: String, now: Long? = null) {
        if (state.mode == GameMode.CATALOGUE) { held.remove(key); cataloguePlayer?.noteOff(key, now ?: catalogueNow); publishCatalogue(); return }
        held.remove(key)
        if (attemptAt != null && held.isEmpty() && now != null) releasedAt = now
        if (held.isEmpty() && attemptAt == null) ready = true
        if (guided && now != null) tick(now)
    }

    fun disconnect(source: String) {
        if (state.mode == GameMode.CATALOGUE) {
            held.keys.removeAll { it.startsWith("$source:") }
            cataloguePlayer?.disconnect(source, catalogueNow)
            publishCatalogue()
            return
        }
        held.keys.removeAll { it.startsWith("$source:") }
        if (attemptAt != null) invalid = true
        if (held.isEmpty()) ready = true
    }

    private fun nextNote(): MusicNote {
        val pool = state.range.midi.map(::MusicNote).filter { (state.chromatic || !it.sharp) && it.midi != state.note?.midi }
        return pool.random(random)
    }

    private fun publishCatalogue() {
        cataloguePlayer?.let { player ->
            state = state.copy(catalogueProgress = player.progress, score = player.progress.index,
                elapsedSeconds = player.progress.elapsedSeconds, feedback = player.progress.feedback,
                phase = if (player.progress.finished) Phase.FINISHED else state.phase)
        }
    }

    companion object { const val CHORD_ASSEMBLY_MS = 450L }
}
