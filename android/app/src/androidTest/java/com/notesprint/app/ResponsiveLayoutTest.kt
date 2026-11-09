package com.notesprint.app

import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercises the real game screen on API 25 without requiring a physical MIDI piano. */
class ResponsiveLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun keepTestDisplayAwake() {
        for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard")) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            @Suppress("DEPRECATION")
            compose.activity.window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
    }

    private fun gameplay(width: Int, height: Int, midi: Int, screenshot: String, showNoteName: Boolean = true) {
        val landscape = width > height
        compose.activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        }
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width.dp, height.dp)) {
                    SprintScreen(
                        GameState(phase = Phase.PLAYING, note = MusicNote(midi), showNoteName = showNoteName),
                        "Connected: Test piano", true, {}, {}, {}, {},
                    )
                }
            }
        }
        compose.onNodeWithTag("grand-staff").assertIsDisplayed()
        if (showNoteName) compose.onNodeWithTag("note-label").assertIsDisplayed()
        else compose.onNodeWithTag("note-label").assertDoesNotExist()
        compose.onNodeWithText("TIME").assertIsDisplayed()
        compose.onNodeWithText("CORRECT").assertIsDisplayed()
        val staff = compose.onNodeWithTag("grand-staff").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Entire staff must fit horizontally", staff.left >= root.left && staff.right <= root.right)
        assertTrue("Entire staff must fit vertically", staff.top >= root.top && staff.bottom <= root.bottom)
        assertTrue("Notation must retain a usable height", staff.height > 100)
        saveScreenshot(screenshot)
    }

    @Test fun tabletPortraitLowC() = gameplay(560, 820, 36, "tablet-portrait-c2")
    @Test fun shortLandscapeLowC() = gameplay(560, 330, 36, "short-landscape-c2")
    @Test fun smallPhoneHighC() = gameplay(320, 480, 84, "phone-c6")
    @Test fun shortPhoneLowC() = gameplay(320, 330, 36, "short-phone-c2")
    @Test fun portraitWithoutNoteName() = gameplay(560, 820, 36, "portrait-hidden-name", false)
    @Test fun landscapeWithoutNoteName() = gameplay(560, 330, 36, "landscape-hidden-name", false)
    @Test fun highestSelectableNoteFits() = gameplay(320, 480, 119, "high-b8")
    @Test fun lowestSelectableNoteFits() = gameplay(320, 480, 12, "low-c0")

    private fun songLayout(width: Int, height: Int, rhythm: Boolean, maximum: Int = 1, twoHands: Boolean = false) {
        val landscape = width > height
        compose.activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        }
        val generated = SongGenerator.generate(NoteRange(), true, kotlin.random.Random(10), maximum, twoHands)
        // Guarantee the layout fixture exercises the full twelve-note worst case.
        val song = if (maximum == 6 && twoHands) generated.copy(beats = generated.beats.toMutableList().apply {
            this[0] = SongBeat((48..59).filter { generated.key.degree(it) >= 0 }.take(6).map(generated.key::note),
                (60..71).filter { generated.key.degree(it) >= 0 }.take(6).map(generated.key::note))
        }) else generated
        val state = mutableStateOf(GameState(mode = GameMode.SONG, phase = Phase.PLAYING, song = song, note = song.beats.first().notes.first(), rhythm = rhythm, showNoteName = true))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width.dp, height.dp)) {
                    SprintScreen(state.value, "Connected: Test piano", true, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("song-bar-0").assertIsDisplayed()
        compose.onNodeWithText("0/16").assertIsDisplayed()
        val prefix = if (maximum > 1) "chords" else "song"
        saveScreenshot("$prefix-${width}x$height")
        compose.runOnIdle { state.value = state.value.copy(score = 12, note = song.beats[12].notes.first()) }
        compose.onNodeWithTag("song-bar-3").assertIsDisplayed()
        compose.onNodeWithText("12/16").assertIsDisplayed()
        saveScreenshot("$prefix-last-${width}x$height")
    }

    @Test fun songPortraitFollowsProgress() = songLayout(560, 820, false)
    @Test fun songLandscapeWithRhythmFollowsProgress() = songLayout(560, 330, true)
    @Test fun songPhoneFollowsProgress() = songLayout(320, 480, false)
    @Test fun denseChordsPortrait() = songLayout(560, 820, false, 6, true)
    @Test fun denseChordsLandscape() = songLayout(560, 330, true, 6, true)
    @Test fun denseChordsPhone() = songLayout(320, 480, false, 6, true)

    @Test fun flatSignatureNaturalsAndRestorationStayVisible() {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        val key = SongGenerator.keys[4]
        val normal = SongBeat(
            left = listOf(48, 51, 53, 55, 58).map(key::note),
            right = listOf(60, 63, 65, 67, 70).map(key::note),
        )
        val naturals = SongBeat(
            left = normal.left.map { if (it.flat) MusicNote(it.midi + 1, it.pitch.first().toString()) else it },
            right = normal.right.map { if (it.flat) MusicNote(it.midi + 1, it.pitch.first().toString()) else it },
        )
        val song = Song(key, List(16) { if (it % 4 == 1) naturals else normal })
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(560.dp, 820.dp)) {
                    SprintScreen(GameState(mode = GameMode.SONG, phase = Phase.PLAYING, song = song, score = 1,
                        matchedNotes = naturals.left.map { it.midi }.toSet()), "Connected", true, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("song-bar-0").assertIsDisplayed()
        saveScreenshot("flat-naturals-restoration")
    }

    @Test fun chordSliderAndTwoHandCheckboxConfigureIndependently() {
        val state = mutableStateOf(GameState(mode = GameMode.SONG))
        compose.setContent {
            MaterialTheme {
                SprintScreen(state.value, "Connected", true, {}, {}, {}, {},
                    onMaxNotes = { state.value = state.value.copy(maxNotesPerHand = it) },
                    onTwoHands = { state.value = state.value.copy(twoHands = it) })
            }
        }
        compose.onNodeWithText("Settings ⋮").performClick()
        compose.onNodeWithText("Two-handed play").performScrollTo().performClick()
        compose.onNodeWithText("Maximum notes per hand: 1").performScrollTo().performClick()
        compose.onNodeWithTag("chord-limit-slider").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(6f) }
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle {
            assertTrue(state.value.twoHands)
            assertTrue(state.value.maxNotesPerHand == 6)
        }
    }

    @Test fun modeAndOctaveSettingsChangeTheSharedState() {
        val state = mutableStateOf(GameState())
        compose.setContent {
            MaterialTheme {
                SprintScreen(state.value, "Connected: Test piano", true, {}, {}, {}, {},
                    onMode = { state.value = state.value.copy(mode = it) },
                    onRange = { state.value = state.value.copy(range = it) },
                    onRhythm = { state.value = state.value.copy(rhythm = it) })
            }
        }
        compose.onNodeWithTag("song-mode").performScrollTo().performClick()
        compose.onNodeWithText("Settings ⋮").performClick()
        compose.onNodeWithText("Rhythm").performClick()
        compose.onNodeWithText("Octave range: 3–5").performScrollTo().performClick()
        compose.onNodeWithText("Lowest octave: 3").performClick()
        compose.onNodeWithText("Octave 4").performScrollTo().performClick()
        compose.onNodeWithText("Highest octave: 5").performClick()
        compose.onNodeWithText("Octave 4").performScrollTo().performClick()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle {
            assertTrue(state.value.mode == GameMode.SONG && state.value.rhythm)
            assertTrue(state.value.range == NoteRange(4, 4))
        }
    }

    @Test fun sightReadingCheckboxControlsVariantAndRemembersChoiceAcrossModes() {
        val engine = GameEngine()
        val state = mutableStateOf(engine.state)
        compose.setContent {
            MaterialTheme {
                SprintScreen(state.value, "Connected: Test piano", true,
                    { engine.start(0); state.value = engine.state }, {}, {}, {},
                    onMode = { engine.setMode(it); state.value = engine.state })
            }
        }
        compose.onNodeWithText("NOTE SPRINT").assertDoesNotExist()
        compose.onNodeWithText("MIDI SIGHT-READING DRILL").assertDoesNotExist()
        compose.onNodeWithText("Flashcards").assertDoesNotExist()
        compose.onNodeWithTag("song-mode").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Generate song →").performScrollTo().assertIsEnabled()
        compose.onNode(hasText("Catalogue") and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText("Sight Reading") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithTag("song-mode").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("Start the challenge →").performScrollTo().performClick()
        compose.onNodeWithText("Settings ⋮").assertIsNotEnabled()
        compose.runOnIdle {
            engine.tick(3000)
            state.value = engine.state
        }
        compose.onNodeWithText("TIME").assertIsDisplayed()
        compose.onNodeWithTag("midi-status").assertTextEquals("Connected: Test piano")
    }

    @Test fun footerKeepsDeviceAndErrorsVisibleDuringPractice() {
        val state = mutableStateOf(GameState(phase = Phase.PLAYING, note = MusicNote(60)))
        compose.setContent {
            MaterialTheme {
                SprintScreen(state.value, "Connected: Test piano", true, {}, {}, {}, {}, error = "Unable to load score")
            }
        }
        compose.onNodeWithTag("midi-status").assertTextEquals("Connected: Test piano").assertIsDisplayed()
        compose.onNodeWithTag("session-error").assertTextEquals("Unable to load score").assertIsDisplayed()
        val range = compose.onNodeWithTag("range-key-info").fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithTag("midi-status").fetchSemanticsNode().boundsInRoot
        val staff = compose.onNodeWithTag("grand-staff").fetchSemanticsNode().boundsInRoot
        assertTrue(range.left < status.left)
        assertTrue(staff.bottom <= range.top && staff.bottom <= status.top)
    }

    @Test fun compactWelcomeAndResultsScrollWithoutMovingGameplayOffscreen() {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        val state = mutableStateOf(GameState())
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(560.dp, 330.dp)) {
                    SprintScreen(state.value, "Connected: Test piano", true, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("Start the challenge →").performScrollTo().assertIsDisplayed()
        saveScreenshot("compact-welcome")
        compose.runOnIdle { state.value = GameState(phase = Phase.FINISHED, score = 42) }
        compose.onNodeWithText("Play again →").performScrollTo().assertIsDisplayed()
        saveScreenshot("compact-results")
        compose.runOnIdle { state.value = GameState(phase = Phase.PLAYING, note = MusicNote(36)) }
        compose.onNodeWithTag("grand-staff").assertIsDisplayed()
        compose.onNodeWithTag("note-label").assertDoesNotExist()
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = instrumentation.targetContext.getExternalFilesDir(null)!!
        lateinit var bitmap: Bitmap
        // Capture the app's decor view, excluding unrelated system-app crash dialogs.
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
        }
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // Keep screenshots after Gradle removes the test packages and their external files.
        instrumentation.uiAutomation.executeShellCommand("cp ${File(directory, "$name.png").absolutePath} /data/local/tmp/notesprint-$name.png")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
    }
}
