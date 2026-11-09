package com.notesprint.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CatalogueLanding(entries: List<CatalogueEntry>, options: CatalogueOptions, change: (CatalogueOptions) -> Unit,
    canStart: Boolean, loading: Boolean, onStart: () -> Unit, modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null) {
    Box(modifier.fillMaxWidth()) {
        val wide = header != null
        val controls: @Composable () -> Unit = {
            header?.invoke()
            CatalogueControls(entries, options, change, showPicker = !wide)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onStart, enabled = canStart) {
                Text(if (loading) "Loading score…" else "Play selected piece →")
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { controls() }
                CatalogueSongList(entries, options, change, Modifier.weight(1f).fillMaxHeight())
            }
        } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { controls() }
    }
}

@Composable
private fun CatalogueSongList(entries: List<CatalogueEntry>, options: CatalogueOptions, change: (CatalogueOptions) -> Unit,
    modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(entries.firstOrNull { it.id == options.pieceId }?.title ?: "Select song below",
            Modifier.padding(bottom = 12.dp).testTag("catalogue-selection"), fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("catalogue-song-list")) {
            items(entries, key = { it.id }) { piece ->
                CatalogueSongItem(piece, piece.id == options.pieceId) { change(options.copy(pieceId = piece.id)) }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CatalogueSongItem(piece: CatalogueEntry, selected: Boolean, select: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (selected) Color(0xFFE6F2E8) else Color.Transparent)
        .selectable(selected = selected, role = Role.RadioButton, onClick = select)
        .testTag("catalogue-song-${piece.id}").padding(12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(piece.title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            Text(piece.composer, fontSize = 12.sp)
        }
        if (selected) Text("✓", color = Color(0xFF26733D), fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("catalogue-selected-check"))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CatalogueControls(entries: List<CatalogueEntry>, options: CatalogueOptions, change: (CatalogueOptions) -> Unit,
    showPicker: Boolean = true) {
    var choosing by remember { mutableStateOf(false) }
    var showSource by remember { mutableStateOf(false) }
    val entry = entries.firstOrNull { it.id == options.pieceId }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        if (showPicker) Box {
            OutlinedButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth().testTag("catalogue-picker")) {
                Text(entry?.title ?: "Select song below", maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry != null) Text(" ✓", color = Color(0xFF26733D))
            }
            DropdownMenu(choosing, { choosing = false }) {
                entries.forEach { piece ->
                    CatalogueSongItem(piece, piece.id == options.pieceId) {
                        change(options.copy(pieceId = piece.id)); choosing = false
                    }
                }
            }
        }
        entry?.let {
            Text("${it.composer} · ${it.work}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("${it.key} · ${it.audit[options.difficulty.name.lowercase()]?.measures ?: ""} performance measures · repeats written out", fontSize = 12.sp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Difficulty.entries.forEach { difficulty ->
                FilterChip(selected = options.difficulty == difficulty, onClick = { change(options.copy(difficulty = difficulty)) }, label = { Text(difficulty.label) })
            }
        }
        Text(options.difficulty.description, fontSize = 12.sp)
        FlowRow(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(options.leftHand, { change(options.copy(leftHand = it)) }, enabled = !options.leftHand || options.rightHand, modifier = Modifier.testTag("catalogue-left-hand"))
                Text("Left hand", fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(options.rightHand, { change(options.copy(rightHand = it)) }, enabled = !options.rightHand || options.leftHand, modifier = Modifier.testTag("catalogue-right-hand"))
                Text("Right hand", fontSize = 13.sp)
            }
        }
        Text("Select at least one hand. Inactive parts stay visible in gray.", fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(options.excerpt, { change(options.copy(excerpt = it)) }, modifier = Modifier.testTag("catalogue-excerpt"))
            Text("Play signature excerpt", fontSize = 13.sp)
        }
        if (options.excerpt && entry != null) Text("Curated section: measures ${entry.excerpt.first()}–${entry.excerpt.last()}", fontSize = 12.sp)
        TextButton(onClick = { showSource = true }, enabled = entry != null) { Text("Score source & arrangements") }
    }
    if (showSource && entry != null) {
        val uri = LocalUriHandler.current
        AlertDialog(onDismissRequest = { showSource = false }, title = { Text(entry.title) }, text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text("${entry.composer} — ${entry.work}\n\n${entry.credit}\n\nSource edition: ${entry.license}")
                Text("\nHard preserves source pitches, note values, voices and written repeats. Medium and Easy are app-created practice reductions, not the original score. Practice tempos are editorial. Source ornament/dynamic markings remain available in the linked edition.", fontSize = 13.sp)
            }
        }, confirmButton = { TextButton(onClick = { showSource = false }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { runCatching { uri.openUri(entry.sourceUrl) } }) { Text("Open source score") } })
    }
}

@Composable
internal fun CataloguePanel(state: GameState, onReset: () -> Unit, modifier: Modifier) {
    val plan = state.cataloguePlan ?: return
    val progress = state.catalogueProgress
    val total = plan.steps.size
    Column(modifier) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(plan.entry.title, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onReset) { Text("End") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${plan.options.difficulty.label} · ${if (plan.options.excerpt) "Excerpt" else "Full"} · ${plan.bpm} BPM", Modifier.weight(1f), fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${progress.index}/$total", modifier = Modifier.testTag("catalogue-progress"), fontSize = 13.sp)
            }
            LinearProgressIndicator(progress = { progress.index.toFloat()/total }, modifier = Modifier.fillMaxWidth())
            Text(progress.feedback.ifEmpty { if (state.rhythm) "${if (progress.pulse) "●" else "○"} Follow the pulse; hold notes for their written lengths."
                else "Orange: next group · Green: played · Sustain earlier notes if written" }, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            CatalogueScoreView(plan, progress, state.showNoteName, Modifier.fillMaxWidth().weight(1f))
        }
    }
}
