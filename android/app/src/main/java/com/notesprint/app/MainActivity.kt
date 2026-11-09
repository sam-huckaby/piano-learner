package com.notesprint.app

import android.os.Bundle
import android.app.Application
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val Ink = Color(0xFF1D2C27)
private val Accent = Color(0xFFE75437)
private val Paper = Color(0xFFFBFAF5)

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = GameEngine()
    private val catalogue = CatalogueRepository { name -> application.assets.open(name).bufferedReader().use { it.readText() } }
    val catalogueEntries = catalogue.entries
    var loading by mutableStateOf(false)
        private set
    var state by mutableStateOf(engine.state)
        private set
    var status by mutableStateOf("Connect a USB MIDI piano.")
    var connected by mutableStateOf(false)
    var loadError by mutableStateOf<String?>(null)
        private set

    fun tick() { engine.tick(SystemClock.elapsedRealtime()); state = engine.state }
    fun start() {
        if (!connected || loading) return
        loadError = null
        if (state.mode == GameMode.CATALOGUE) {
            if (catalogueEntries.none { it.id == state.catalogueOptions.pieceId }) return
            viewModelScope.launch {
                loading = true
                try {
                    val options = state.catalogueOptions
                    val plan = withContext(Dispatchers.IO) { catalogue.load(options) }
                    if (state.mode == GameMode.CATALOGUE && state.catalogueOptions == options && connected) {
                        engine.startCatalogue(plan, SystemClock.elapsedRealtime())
                        state = engine.state
                    }
                } catch (_: Exception) { loadError = "Unable to load this score. Please choose another piece and retry." }
                finally { loading = false }
            }
        } else { engine.start(SystemClock.elapsedRealtime()); state = engine.state }
    }
    fun reset() { engine.reset(); state = engine.state; loadError = null }
    fun chromatic(value: Boolean) { engine.setChromatic(value); state = engine.state }
    fun showNoteName(value: Boolean) { engine.setShowNoteName(value); state = engine.state }
    fun mode(value: GameMode) { engine.setMode(value); state = engine.state; loadError = null }
    fun range(value: NoteRange) { engine.setRange(value); state = engine.state }
    fun rhythm(value: Boolean) { engine.setRhythm(value); state = engine.state }
    fun maxNotes(value: Int) { engine.setMaxNotesPerHand(value); state = engine.state }
    fun twoHands(value: Boolean) { engine.setTwoHands(value); state = engine.state }
    fun catalogueOptions(value: CatalogueOptions) { engine.setCatalogueOptions(value); state = engine.state; loadError = null }
    fun disconnect(source: String) { engine.disconnect(source); state = engine.state }
    fun midi(source: String, status: Int, first: Int, second: Int) {
        val key = "$source:${status and 15}:$first"
        val now = SystemClock.elapsedRealtime()
        when (status and 0xf0) {
            0x90 -> if (second == 0) engine.noteOff(key, now) else engine.noteOn(key, first, now)
            0x80 -> engine.noteOff(key, now)
            0xb0 -> if (first == 120 || first == 123) engine.disconnect("$source:${status and 15}")
        }
        state = engine.state
    }
}

class MainActivity : ComponentActivity() {
    private val game: GameViewModel by viewModels()
    private lateinit var midi: UsbMidi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        midi = UsbMidi(this, game::midi, game::disconnect) { text, connected ->
            game.status = text
            game.connected = connected
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = Color(0xFFF3F0E8), surface = Paper, onSurface = Ink)) {
                LaunchedEffect(Unit) { while (true) { game.tick(); delay(10) } }
                val playing = game.state.phase == Phase.PLAYING || game.state.phase == Phase.COUNTDOWN
                DisposableEffect(playing) {
                    if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                SprintScreen(game.state, game.status, game.connected, game::start, game::reset,
                    game::chromatic, game::showNoteName, game::mode, game::range, game::rhythm, game::maxNotes, game::twoHands,
                     game.catalogueEntries, game::catalogueOptions, game.loading, game.loadError)
            }
        }
    }

    override fun onStart() { super.onStart(); midi.start(); game.tick() }
    override fun onStop() { midi.close(); super.onStop() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SprintScreen(
    state: GameState,
    status: String,
    connected: Boolean,
    onStart: () -> Unit,
    onReset: () -> Unit,
    onChromatic: (Boolean) -> Unit,
    onShowNoteName: (Boolean) -> Unit,
    onMode: (GameMode) -> Unit = {},
    onRange: (NoteRange) -> Unit = {},
    onRhythm: (Boolean) -> Unit = {},
    onMaxNotes: (Int) -> Unit = {},
    onTwoHands: (Boolean) -> Unit = {},
    catalogueEntries: List<CatalogueEntry> = emptyList(),
    onCatalogueOptions: (CatalogueOptions) -> Unit = {},
    loading: Boolean = false,
    error: String? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var rangeOpen by remember { mutableStateOf(false) }
    var chordLimitOpen by remember { mutableStateOf(false) }
    val canConfigure = state.phase == Phase.WELCOME || state.phase == Phase.FINISHED
    val songMode = state.mode == GameMode.SONG
    val catalogueMode = state.mode == GameMode.CATALOGUE
    if (rangeOpen && canConfigure) RangeDialog(state.range, { rangeOpen = false }) { onRange(it); rangeOpen = false }
    if (chordLimitOpen && canConfigure) ChordLimitDialog(state.maxNotesPerHand, { chordLimitOpen = false }) { onMaxNotes(it); chordLimitOpen = false }
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF3F0E8)) {
        BoxWithConstraints(Modifier.safeDrawingPadding().fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val compact = maxHeight < 600.dp || maxWidth < 400.dp
            val wideCatalogue = maxWidth >= 680.dp
            val scroll = rememberScrollState()
            LaunchedEffect(state.phase) { scroll.scrollTo(0) }
            Column(Modifier.padding(if (compact) 8.dp else 20.dp).widthIn(max = 900.dp).fillMaxSize()
                .border(1.dp, Color(0xFFD5D6CA)).background(Paper)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                    Box {
                        TextButton(onClick = { menuOpen = true }, enabled = canConfigure) { Text("Settings ⋮") }
                        DropdownMenu(expanded = menuOpen && canConfigure, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Include chromatic notes") }, enabled = !catalogueMode, onClick = { onChromatic(!state.chromatic) },
                                trailingIcon = { Checkbox(state.chromatic, onChromatic, enabled = !catalogueMode) })
                            DropdownMenuItem(text = { Text("Show note name") }, onClick = { onShowNoteName(!state.showNoteName) },
                                trailingIcon = { Checkbox(state.showNoteName, onShowNoteName) })
                            DropdownMenuItem(text = { Text("Rhythm") }, enabled = songMode || catalogueMode, onClick = { onRhythm(!state.rhythm) },
                                trailingIcon = { Checkbox(state.rhythm, onRhythm, enabled = songMode || catalogueMode) })
                            DropdownMenuItem(text = { Text("Two-handed play") }, enabled = songMode, onClick = { onTwoHands(!state.twoHands) },
                                trailingIcon = { Checkbox(state.twoHands, onTwoHands, enabled = songMode) })
                            DropdownMenuItem(text = { Text("Maximum notes per hand: ${state.maxNotesPerHand}") }, enabled = songMode,
                                onClick = { menuOpen = false; chordLimitOpen = true })
                            DropdownMenuItem(text = { Text("Octave range: ${state.range.lowestOctave}–${state.range.highestOctave}") }, enabled = !catalogueMode,
                                onClick = { menuOpen = false; rangeOpen = true })
                        }
                    }
                }
                if (state.phase == Phase.PLAYING) {
                    val panel = Modifier.fillMaxWidth().weight(1f)
                    if (catalogueMode) CataloguePanel(state, onReset, panel)
                    else if (songMode) SongPanel(state, onReset, panel)
                    else PlayingPanel(state, onReset, panel)
                } else if (state.phase == Phase.WELCOME && catalogueMode && wideCatalogue) {
                    CatalogueLanding(catalogueEntries, state.catalogueOptions, onCatalogueOptions,
                        connected && !loading && catalogueEntries.any { it.id == state.catalogueOptions.pieceId }, loading, onStart,
                        Modifier.weight(1f).fillMaxWidth().padding(if (compact) 16.dp else 24.dp), header = {
                            Heading("Catalogue")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = false, onClick = { onMode(if (state.sightReadingSong) GameMode.SONG else GameMode.FLASHCARDS) }, label = { Text("Sight Reading") })
                                FilterChip(selected = true, onClick = { onMode(GameMode.CATALOGUE) }, label = { Text("Catalogue") })
                            }
                        })
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(Modifier.fillMaxWidth()
                            .padding(if (compact) 16.dp else 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            when (state.phase) {
                                Phase.WELCOME -> {
                                    Spacer(Modifier.height(if (compact) 8.dp else 24.dp))
                                    Heading(if (catalogueMode) "Catalogue" else "Sight Reading")
                                    FlowRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(selected = !catalogueMode, onClick = { onMode(if (state.sightReadingSong) GameMode.SONG else GameMode.FLASHCARDS) }, label = { Text("Sight Reading") })
                                        FilterChip(selected = catalogueMode, onClick = { onMode(GameMode.CATALOGUE) }, label = { Text("Catalogue") })
                                    }
                                    if (catalogueMode) CatalogueLanding(catalogueEntries, state.catalogueOptions, onCatalogueOptions,
                                        connected && !loading && catalogueEntries.any { it.id == state.catalogueOptions.pieceId }, loading, onStart)
                                    else {
                                        Row(Modifier.testTag("song-mode").toggleable(songMode, role = Role.Checkbox,
                                            onValueChange = { onMode(if (it) GameMode.SONG else GameMode.FLASHCARDS) }), verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(songMode, null)
                                            Text("Song mode")
                                        }
                                        Text(if (songMode) "A new four-bar piece every time.\nFollow the highlighted notes to the end."
                                            else "See the note. Play it cleanly.\nHow many can you get in one minute?",
                                            modifier = Modifier.padding(vertical = 20.dp), textAlign = TextAlign.Center, color = Ink)
                                        Text(if (songMode) "16 beats · ${if (state.rhythm) "Guided rhythm · 72 BPM" else "At your own pace"}\nUp to ${state.maxNotesPerHand} notes per hand · ${if (state.twoHands) "Two hands" else "One hand at a time"}" else "One note at a time · 60 seconds",
                                            fontSize = 13.sp, color = Ink, textAlign = TextAlign.Center)
                                        Spacer(Modifier.height(24.dp))
                                        Button(onClick = onStart, enabled = connected && !loading) { Text(if (songMode) "Generate song →" else "Start the challenge →") }
                                    }
                                    Spacer(Modifier.height(24.dp))
                                }
                                Phase.COUNTDOWN -> {
                                    Eyebrow("GET YOUR HANDS READY")
                                    Text(state.countdown.toString(), fontSize = if (compact) 90.sp else 130.sp, fontFamily = FontFamily.Serif, color = Accent)
                                    Text(if (catalogueMode) "${state.cataloguePlan?.entry?.title}\n${if (state.rhythm) "Begin on the pulse" else "Play at your own pace"}"
                                        else if (songMode) "${state.song?.key?.name} · 4/4 · ${if (state.rhythm) "72 BPM — begin on the first pulse" else "Play at your own pace"}"
                                        else "Your full minute starts after the countdown.", textAlign = TextAlign.Center)
                                    Spacer(Modifier.height(24.dp))
                                }
                                Phase.FINISHED -> {
                                    Eyebrow(if (songMode || catalogueMode) "PIECE COMPLETE" else "CHALLENGE COMPLETE")
                                    Heading("Well played!")
                                    Text(if (catalogueMode) "${state.score}/${state.cataloguePlan?.steps?.size}" else if (songMode) "${state.score}/16" else state.score.toString(), fontSize = if (compact) 70.sp else 100.sp,
                                        fontFamily = FontFamily.Serif, color = Accent)
                                    Text(if (catalogueMode) "${state.cataloguePlan?.entry?.title}\n${state.catalogueOptions.difficulty.label} · ${state.elapsedSeconds}s"
                                        else if (songMode) "All beats played · ${state.elapsedSeconds}s · ${state.song?.key?.name}"
                                        else "notes correctly in one minute", textAlign = TextAlign.Center)
                                    Spacer(Modifier.height(24.dp))
                                    Button(onClick = onStart, enabled = connected && !loading && (!catalogueMode || catalogueEntries.any { it.id == state.catalogueOptions.pieceId })) { Text(if (loading) "Loading score…" else if (songMode) "New song →" else "Play again →") }
                                    TextButton(onClick = onReset) { Text("Back to start") }
                                }
                                Phase.PLAYING -> Unit
                            }
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFFDCE1D4))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("card-footer"),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                    val keyInfo = if (catalogueMode) {
                        val entry = catalogueEntries.firstOrNull { it.id == state.catalogueOptions.pieceId } ?: state.cataloguePlan?.entry
                        "Original registers${entry?.let { " · ${it.key}" } ?: ""}"
                    } else "${state.range.label} · ${state.song?.key?.name ?: if (state.chromatic) "Chromatic" else "White keys"}"
                    Text(keyInfo, Modifier.weight(1f).testTag("range-key-info"), fontSize = 11.sp, color = Ink)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(status, Modifier.testTag("midi-status"), textAlign = TextAlign.End,
                            fontSize = 11.sp, color = if (connected) Ink else Accent)
                        error?.let { Text(it, Modifier.testTag("session-error"), textAlign = TextAlign.End, fontSize = 11.sp, color = Accent) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChordLimitDialog(maximum: Int, dismiss: () -> Unit, confirm: (Int) -> Unit) {
    var value by remember { mutableIntStateOf(maximum) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Maximum notes per hand") }, text = {
        Column {
            Text("Up to $value ${if (value == 1) "note" else "notes"} per hand on each beat.")
            Slider(value = value.toFloat(), onValueChange = { value = it.roundToInt() }, valueRange = 1f..6f, steps = 4,
                modifier = Modifier.testTag("chord-limit-slider"))
            Text("With two-handed play, each hand gets its own limit. A hand can rest. Rolled chords are accepted.", fontSize = 13.sp)
        }
    }, confirmButton = { TextButton(onClick = { confirm(value) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun RangeDialog(range: NoteRange, dismiss: () -> Unit, confirm: (NoteRange) -> Unit) {
    var low by remember { mutableIntStateOf(range.lowestOctave) }
    var high by remember { mutableIntStateOf(range.highestOctave) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Octave range") }, text = {
        Column {
            Text("Applies to flashcards and songs. All keys within each octave are included.")
            OctavePicker("Lowest octave", low, 0..high) { low = it }
            OctavePicker("Highest octave", high, low..8) { high = it }
            Text("C$low–B$high", fontWeight = FontWeight.Bold)
        }
    }, confirmButton = { TextButton(onClick = { confirm(NoteRange(low, high)) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun OctavePicker(label: String, selected: Int, allowed: IntRange, change: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("$label: $selected") }
        DropdownMenu(expanded, { expanded = false }) {
            for (octave in 0..8) DropdownMenuItem(text = { Text("Octave $octave") }, enabled = octave in allowed,
                onClick = { change(octave); expanded = false })
        }
    }
}

@Composable
private fun SongPanel(state: GameState, onReset: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${state.song?.key?.name} · 4/4", color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("${state.score}/16", color = Accent, modifier = Modifier.testTag("song-progress"))
            TextButton(onClick = onReset) { Text("End") }
        }
        LinearProgressIndicator(progress = { state.score / 16f }, modifier = Modifier.fillMaxWidth())
        if (state.rhythm) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
                Text("72 BPM  ", fontSize = 12.sp)
                repeat(4) { beat ->
                    Text(if (state.beat == beat + 1 && state.beatPulse) "● " else "○ ",
                        color = if (state.beat == beat + 1) Accent else Ink, fontSize = 16.sp)
                }
            }
        }
        Text(state.feedback.ifEmpty { if (state.rhythm) "Start on the pulse; finish releasing at the next beat." else "Orange: next beat · Green: played · Rolled chords accepted" },
            fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Ink)
        state.song?.let { SongScore(it, state.score, state.showNoteName, Modifier.weight(1f).fillMaxWidth(), state.matchedNotes) }
    }
}

@Composable
private fun PlayingPanel(state: GameState, onReset: () -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        val short = maxHeight < 400.dp
        val showSideHint = maxHeight >= 220.dp
        if (maxWidth >= 480.dp && (short || maxWidth > maxHeight * 1.4f)) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(130.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    Stat("TIME", "${state.seconds}s", compact = short)
                    Stat("CORRECT", state.score.toString(), compact = short)
                    TextButton(onClick = onReset) { Text("End challenge") }
                }
                NotePanel(state, Modifier.weight(1f).fillMaxHeight(), showHint = showSideHint)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Stat("TIME", "${state.seconds}s", compact = short)
                    Stat("CORRECT", state.score.toString(), compact = short)
                    TextButton(onClick = onReset) { Text("End") }
                }
                NotePanel(state, Modifier.weight(1f).fillMaxWidth(), showHint = !short)
            }
        }
    }
}

@Composable
private fun NotePanel(state: GameState, modifier: Modifier, showHint: Boolean) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (showHint) Text("PLAY THIS NOTE", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Accent)
        state.note?.let { note ->
            GrandStaff(note, Modifier.weight(1f).fillMaxWidth().testTag("grand-staff"))
            if (state.showNoteName) Text(note.label, fontFamily = FontFamily.Serif, fontSize = 24.sp, color = Ink, modifier = Modifier.testTag("note-label"))
        }
        if (showHint) Text("Play the displayed key by itself", fontSize = 12.sp, color = Ink, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Heading(text: String) { Text(text, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 48.sp, textAlign = TextAlign.Center, color = Ink) }

@Composable
private fun Eyebrow(text: String) { Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Accent, modifier = Modifier.padding(bottom = 12.dp)) }

@Composable
private fun Stat(label: String, value: String, compact: Boolean = false) {
    Column { Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Ink); Text(value, fontFamily = FontFamily.Serif, fontSize = if (compact) 26.sp else 36.sp, color = Ink) }
}
