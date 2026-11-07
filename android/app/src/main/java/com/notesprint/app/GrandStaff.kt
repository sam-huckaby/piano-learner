package com.notesprint.app

import android.graphics.Paint
import androidx.core.content.res.ResourcesCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun GrandStaff(note: MusicNote, modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(1.8f)) {
    val context = LocalContext.current
    val musicFont = remember(context) { requireNotNull(ResourcesCompat.getFont(context, R.font.noto_music)) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    Canvas(modifier.semantics { contentDescription = "${note.label} on the ${if (note.treble) "treble" else "bass"} staff" }) {
        val width = 540f
        // Adapt to extreme octave ranges, including ledger lines and stems.
        val top = minOf(0f, noteY(note) - 45)
        val bottom = maxOf(300f, noteY(note) + 45)
        val height = bottom - top
        val fit = minOf(size.width / width, size.height / height)
        translate((size.width - width * fit) / 2, (size.height - height * fit) / 2 - top * fit) {
            scale(fit, fit, pivot = Offset.Zero) {
                drawStaves(width, musicFont, paint)
                drawMusicNote(note, width / 2, Color.Black, paint)
            }
        }
    }
}
