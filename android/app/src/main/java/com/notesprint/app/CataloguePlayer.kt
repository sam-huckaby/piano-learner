package com.notesprint.app

import kotlin.math.abs

data class CatalogueProgress(
    val index: Int = 0, val matched: Set<Int> = emptySet(), val feedback: String = "",
    val finished: Boolean = false, val elapsedSeconds: Int = 0, val pulse: Boolean = false,
)

/** Separate voices can overlap: a sustained bass does not block later melody attacks. */
class CataloguePlayer(val plan: CataloguePlan, private val rhythm: Boolean) {
    var progress = CatalogueProgress()
        private set
    private val held = mutableMapOf<String, Int>()
    private data class Press(val key: String, val pitch: Int, val at: Long, var released: Long? = null)
    private data class ReleaseCheck(val index: Int, val press: Press, val expected: Double)
    private val checks = mutableMapOf<String, ReleaseCheck>()
    private val presses = mutableMapOf<Int, Press>()
    private var attemptAt: Long? = null
    private var lastPressAt = 0L
    private var clock = 0L
    private var startedAt = 0L
    private var blocked = false
    private var playing = false
    private val step get() = plan.steps.getOrNull(progress.index)
    private fun due(index: Int) = clock + plan.millis(plan.steps[index].playTick - plan.startTick).toLong()
    private fun finishReady(now: Long) = checks.isEmpty() && (!rhythm || now >= clock + plan.millis(plan.end-plan.startTick).toLong())
    private fun tolerance(index: Int): Long {
        val gap = plan.steps.getOrNull(index + 1)?.let { plan.millis(it.playTick - plan.steps[index].playTick) } ?: plan.quarterMillis
        return (gap * 0.45).toLong().coerceIn(70, 250)
    }

    fun start(now: Long) {
        playing = true
        startedAt = now
        clock = now + if (rhythm) plan.quarterMillis.toLong() else 0
        blocked = held.isNotEmpty()
    }

    fun tick(now: Long) {
        if (!playing || progress.finished) return
        progress = progress.copy(elapsedSeconds = ((now - startedAt) / 1000).toInt(),
            pulse = rhythm && now >= clock && (now - clock) % plan.quarterMillis.toLong() < 120)
        if (rhythm) {
            val overdue = checks.values.firstOrNull { now - it.press.at > it.expected + maxOf(100.0, it.expected * 0.35) }
            if (overdue != null) { retry(overdue.index, now, "Held too long. Release and retry from this note."); return }
        }
        val current = step
        if (current == null) {
            if (finishReady(now)) progress = progress.copy(finished = true, feedback = "Piece complete!")
            return
        }
        if (attemptAt == null) {
            if (rhythm && now > due(progress.index) + tolerance(progress.index)) retry(progress.index, now, "Missed onset. Release and try on the next pulse.")
            return
        }
        val complete = presses.keys.containsAll(current.pitches)
        if (!complete) {
            if (now - attemptAt!! > 450) retry(progress.index, now, "Missing notes. Release and retry the highlighted group.")
            return
        }
        val nextGap = plan.steps.getOrNull(progress.index + 1)?.let { plan.millis(it.playTick - current.playTick) } ?: 250.0
        val guard = minOf(85L, (nextGap / 3).toLong().coerceAtLeast(20))
        if (now - lastPressAt < guard) return
        if (rhythm) {
            for (press in presses.values) {
                val originals = current.attacks.filter { it.midi == press.pitch }
                if (originals.all { it.grace != 0 }) continue
                val duration = plan.durationFor(progress.index, press.pitch)
                val check = ReleaseCheck(progress.index, press, duration)
                if (press.released != null && !validRelease(check, press.released!!)) {
                    retry(progress.index, now, "Note length: retry this group."); return
                }
                if (press.released == null) checks[press.key] = check
            }
        }
        presses.clear(); attemptAt = null
        progress = progress.copy(index = progress.index + 1, matched = emptySet(), feedback = "")
        if (progress.index == plan.steps.size && finishReady(now)) progress = progress.copy(finished = true, feedback = "Piece complete!")
    }

    fun noteOn(key: String, pitch: Int, now: Long) {
        if (held.containsKey(key)) return
        // Finish a previous short note before interpreting the next onset.
        tick(now)
        val previousHeld = held.values.toSet()
        held[key] = pitch
        if (!playing || progress.finished || blocked) return
        val current = step
        if (current == null) {
            retry(plan.steps.lastIndex, now, "Rest at the end. Release and retry the ending.")
            return
        }
        if (pitch !in current.pitches || previousHeld.any { it !in plan.heldAllowed(progress.index) && it !in current.pitches }) {
            retry(progress.index, now, "Extra note. Release and retry this group."); return
        }
        if (attemptAt == null) {
            if (rhythm && abs(now - due(progress.index)) > tolerance(progress.index)) {
                retry(progress.index, now, "Early or late. Release and retry on the pulse."); return
            }
            attemptAt = now
        }
        if (now - attemptAt!! > 450 && !presses.keys.containsAll(current.pitches)) {
            retry(progress.index, now, "Roll the notes within 450 ms."); return
        }
        presses[pitch] = Press(key, pitch, now)
        lastPressAt = now
        progress = progress.copy(matched = presses.keys.toSet(), feedback = "")
    }

    fun noteOff(key: String, now: Long) {
        held.remove(key)
        presses.values.filter { it.key == key }.forEach { it.released = now }
        val check = checks.remove(key)
        if (check != null && !validRelease(check, now)) retry(check.index, now, "Note length: retry from this group.")
        if (held.isEmpty()) blocked = false
        tick(now)
    }

    private fun validRelease(check: ReleaseCheck, now: Long) = abs(now - check.press.at - check.expected) <= maxOf(100.0, check.expected * 0.35)

    private fun retry(index: Int, now: Long, text: String) {
        presses.clear(); checks.clear(); attemptAt = null
        blocked = held.isNotEmpty()
        clock = now + plan.quarterMillis.toLong() - plan.millis(plan.steps[index].playTick - plan.startTick).toLong()
        progress = progress.copy(index = index, matched = emptySet(), feedback = text, pulse = false)
    }

    fun disconnect(source: String, now: Long) {
        held.keys.removeAll { it.startsWith("$source:") }
        if (playing && !progress.finished) retry(minOf(progress.index, plan.steps.lastIndex), now, "Keyboard disconnected. Reconnect and retry.")
    }
}
