package com.notesprint.app

import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

enum class Difficulty(val label: String, val description: String, val tempoFactor: Double) {
    HARD("Hard / Original", "Complete source score; written repeats are played in full.", 1.0),
    MEDIUM("Medium", "Practice arrangement: original melody contour, thinner chords and quarter-note bass; slower tempo.", 0.8),
    EASY("Easy", "Practice arrangement: single-note melody and one bass note per bar; slower tempo.", 0.6),
}

data class CatalogueOptions(
    val pieceId: String = "", val difficulty: Difficulty = Difficulty.HARD,
    val excerpt: Boolean = false, val leftHand: Boolean = true, val rightHand: Boolean = true,
) { init { require(leftHand || rightHand) } }

@Keep data class CatalogueEntry(
    val id: String = "", val title: String = "", val composer: String = "", val work: String = "",
    val key: String = "", val bpm: Int = 72, val excerpt: List<Int> = emptyList(),
    val credit: String = "", val license: String = "", val sourceUrl: String = "",
    val sourceSha256: String = "", val midiVerified: Boolean = false,
    val audit: Map<String, CatalogueAudit> = emptyMap(),
)
@Keep data class CatalogueAudit(val measures: Int = 0, val notes: Int = 0, val onsets: Int = 0, val durationTicks: Int = 0, val ties: Int = 0)

@Keep data class CatalogueMeasure(
    val start: Int = 0, val end: Int = 0, val number: Int = 1, val numerator: Int = 4,
    val denominator: Int = 4, val key: List<Int> = List(7) { 0 }, val pickup: Boolean = false,
) {
    fun signature() = SongKey("", 0, "CDEFGAB".mapIndexed { index, letter ->
        "$letter" + when (key[index]) { 1 -> "#"; -1 -> "b"; else -> "" }
    })
}

@Keep data class CatalogueNote(
    val id: Int = 0, val part: Int = 0, val voice: String = "", val time: Int = 0, val grace: Int = 0,
    val duration: Int = 960, val log: Int = 2, val dots: Int = 0, val midi: Int = -1,
    val step: Int = 0, val octave: Int = 4, val alter: Int = 0, val clef: Boolean = true,
    val measure: Int = 0, val tieTo: Int = -1, val tieFrom: Int = -1,
) {
    val rest get() = midi < 0
    fun musicNote() = MusicNote(midi, "${"CDEFGAB"[step]}" + when (alter) {
        2 -> "##"; 1 -> "#"; -1 -> "b"; -2 -> "bb"; else -> ""
    }, staffTreble = part == 0, clefTreble = clef, writtenOctave = octave)
}

@Keep data class CatalogueScore(
    val tpq: Int = 960, val measures: List<CatalogueMeasure> = emptyList(), val notes: List<CatalogueNote> = emptyList(),
) {
    fun validate() {
        require(tpq > 0 && measures.isNotEmpty() && notes.isNotEmpty())
        require(measures.all { it.end > it.start && it.key.size == 7 })
        require(measures.zipWithNext().all { (a, b) -> a.end == b.start })
        val ids = notes.map { it.id }.toSet()
        require(ids.size == notes.size)
        notes.forEach { n ->
            require(n.part in 0..1 && n.measure in measures.indices && n.duration > 0)
            require(n.time >= measures[n.measure].start && n.time < measures[n.measure].end)
            require(n.tieFrom == -1 || n.tieFrom in ids)
            require(n.tieTo == -1 || n.tieTo in ids)
            if (!n.rest) {
                require(n.step in 0..6 && n.midi in 0..127 && n.alter in -2..2)
                require((n.octave + 1) * 12 + listOf(0, 2, 4, 5, 7, 9, 11)[n.step] + n.alter == n.midi)
            }
        }
    }
}

/** Asset reader is injectable so all thirty complete variants can be checked on the JVM. */
class CatalogueRepository(private val readAsset: (String) -> String) {
    private val gson = Gson()
    val entries: List<CatalogueEntry> = gson.fromJson(readAsset("catalogue/index.json"), object : TypeToken<List<CatalogueEntry>>() {}.type)
    fun load(options: CatalogueOptions): CataloguePlan {
        val entry = entries.first { it.id == options.pieceId }
        val score = gson.fromJson(readAsset("catalogue/${entry.id}-${options.difficulty.name.lowercase()}.json"), CatalogueScore::class.java)
        score.validate()
        return CataloguePlan(entry, score, options)
    }
}

data class CatalogueStep(val tick: Int, val grace: Int, val attacks: List<CatalogueNote>, val playTick: Double) {
    val pitches get() = attacks.map { it.midi }.toSet()
}

class CataloguePlan(val entry: CatalogueEntry, val score: CatalogueScore, val options: CatalogueOptions) {
    val measureIndices = score.measures.indices.filter { !options.excerpt || score.measures[it].number in entry.excerpt.first()..entry.excerpt.last() }
    val notes = score.notes.filter { it.measure in measureIndices }
    val selected = notes.filter { !it.rest && enabled(it.part) }
    val byId = selected.associateBy { it.id }
    val end = score.measures[measureIndices.last()].end
    val bpm = (entry.bpm * options.difficulty.tempoFactor).toInt().coerceAtLeast(36)
    val quarterMillis = 60_000.0 / bpm
    val steps: List<CatalogueStep>
    val startTick get() = minOf(score.measures[measureIndices.first()].start.toDouble(), steps.first().playTick)
    init {
        require(measureIndices.isNotEmpty() && selected.isNotEmpty())
        steps = selected.filter { it.tieFrom !in byId }.groupBy { it.time to it.grace }
            .toSortedMap(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second }).map { (position, attacks) ->
                val minGrace = selected.filter { it.time == position.first }.minOf { it.grace }.coerceAtMost(-1)
                val anticipation = if (position.second == 0) 0.0 else position.second.toDouble() / -minGrace * score.tpq / 6.0
                CatalogueStep(position.first, position.second, attacks, position.first + anticipation)
            }
        require(steps.isNotEmpty())
    }
    fun enabled(part: Int) = if (part == 0) options.rightHand else options.leftHand
    fun endFor(note: CatalogueNote): Int {
        var current = note
        val seen = mutableSetOf<Int>()
        while (current.tieTo in byId && seen.add(current.id)) current = byId.getValue(current.tieTo)
        return minOf(end, current.time + current.duration)
    }
    fun millis(ticks: Double) = ticks / score.tpq * quarterMillis
    fun durationFor(index: Int, pitch: Int): Double {
        val step = steps[index]
        val writtenEnd = step.attacks.filter { it.midi == pitch }.maxOf { endFor(it) }
        // Independent voices can repeat the same physical piano key while an
        // earlier voice still sustains it. Re-articulation ends that key hold.
        val nextAttack = steps.drop(index+1).firstOrNull { pitch in it.pitches }
        return millis(minOf((writtenEnd - step.tick).toDouble(), nextAttack?.let { it.playTick-step.playTick } ?: Double.MAX_VALUE))
    }
    fun heldAllowed(index: Int): Set<Int> {
        val step = steps[index]
        return selected.filter { it.time < step.playTick && endFor(it) > step.playTick }.map { it.midi }.toSet()
    }
}
