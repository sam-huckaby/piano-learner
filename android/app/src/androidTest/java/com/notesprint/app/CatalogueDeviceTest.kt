package com.notesprint.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.WindowManager
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class CatalogueDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val repository get() = CatalogueRepository { name ->
        InstrumentationRegistry.getInstrumentation().targetContext.assets.open(name).bufferedReader().use { it.readText() }
    }

    @Before fun awake() {
        for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard")) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            @Suppress("DEPRECATION")
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
    }

    private fun orient(landscape: Boolean) {
        compose.activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        compose.waitUntil(5000) { compose.activity.resources.configuration.orientation == if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
    }

    @Test fun catalogueDefaultsAndIndependentOptionsAreReachableOnPhone() {
        orient(false)
        val repo = repository
        val state = mutableStateOf(GameState(mode = GameMode.CATALOGUE))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(320.dp, 480.dp)) {
                    SprintScreen(state.value, "Connected", true, {}, {}, {}, {}, catalogueEntries = repo.entries,
                        onCatalogueOptions = { state.value = state.value.copy(catalogueOptions = it) })
                }
            }
        }
        compose.onNodeWithText("Select song below").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Play selected piece →").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Hard / Original").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Medium").performScrollTo().performClick()
        compose.onNodeWithTag("catalogue-right-hand").performScrollTo().performClick()
        compose.onNodeWithTag("catalogue-left-hand").assertIsNotEnabled()
        compose.onNodeWithTag("catalogue-excerpt").performScrollTo().performClick()
        compose.onNodeWithTag("catalogue-picker").performScrollTo().performClick()
        compose.onNodeWithText("Arabesque No. 1").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("debussy-arabesque", state.value.catalogueOptions.pieceId)
            assertEquals(Difficulty.MEDIUM, state.value.catalogueOptions.difficulty)
            assertTrue(state.value.catalogueOptions.leftHand && !state.value.catalogueOptions.rightHand && state.value.catalogueOptions.excerpt)
        }
        compose.onNodeWithText("Play selected piece →").performScrollTo().assertIsEnabled()
        screenshot("catalogue-phone-controls")
    }

    @Test fun wideCatalogueListShowsSelectionAndKeepsOptionsIndependent() {
        orient(true)
        val repo = repository
        val state = mutableStateOf(GameState(mode = GameMode.CATALOGUE))
        val connected = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(820.dp, 560.dp)) {
                    SprintScreen(state.value, "Connected: Test piano", connected.value, {}, {}, {}, {},
                        catalogueEntries = repo.entries,
                        onCatalogueOptions = { state.value = state.value.copy(catalogueOptions = it) })
                }
            }
        }
        compose.onNodeWithTag("catalogue-picker").assertDoesNotExist()
        compose.onNodeWithTag("catalogue-selection").assertTextEquals("Select song below")
        compose.onNodeWithText("Play selected piece →").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("catalogue-song-list").performScrollToNode(hasText("Arabesque No. 1"))
        compose.onNodeWithTag("catalogue-song-debussy-arabesque").performClick().assertIsSelected()
        compose.onNodeWithTag("catalogue-selected-check", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("catalogue-selection").assertTextEquals("Arabesque No. 1")
        compose.onNodeWithText("Medium").performScrollTo().performClick()
        compose.onNodeWithText("Play selected piece →").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            assertEquals("debussy-arabesque", state.value.catalogueOptions.pieceId)
            connected.value = false
        }
        compose.onNodeWithText("Play selected piece →").assertIsNotEnabled()
        compose.onNodeWithTag("catalogue-song-list").performScrollToNode(hasText("Minuet in G"))
        compose.onNodeWithTag("catalogue-song-petzold-minuet-g").performClick().assertIsSelected()
        compose.onNodeWithTag("catalogue-selection").assertTextEquals(repo.entries.first { it.id == "petzold-minuet-g" }.title)
        compose.onAllNodesWithTag("catalogue-selected-check", useUnmergedTree = true).assertCountEquals(1)
        screenshot("catalogue-wide-selection")
    }

    private fun score(id: String, width: Int, height: Int, options: CatalogueOptions = CatalogueOptions(id), focus: Int? = null) {
        orient(width > height)
        val plan = repository.load(options)
        val state = mutableStateOf(GameState(mode = GameMode.CATALOGUE, phase = Phase.PLAYING, catalogueOptions = options,
            cataloguePlan = plan, catalogueProgress = CatalogueProgress(index = focus ?: 0), showNoteName = true))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width.dp, height.dp)) { SprintScreen(state.value, "Connected", true, {}, {}, {}, {}) }
            }
        }
        compose.onNodeWithTag("catalogue-score").assertIsDisplayed()
        compose.onNodeWithTag("catalogue-progress").assertTextEquals("${focus ?: 0}/${plan.steps.size}")
        screenshot("catalogue-$id-${width}x$height")
        compose.runOnIdle { state.value = state.value.copy(catalogueProgress = CatalogueProgress(index = plan.steps.lastIndex)) }
        val lastMeasure = plan.steps.last().attacks.first().measure
        compose.onNodeWithTag("catalogue-measure-$lastMeasure").assertIsDisplayed()
        screenshot("catalogue-$id-last-${width}x$height")
    }

    @Test fun fullMinuetPortraitScrollsBeyondSixteenEvents() = score("petzold-minuet-g", 560, 820)
    @Test fun denseOriginalLandscapeScrollsHorizontallyToActiveNote() = score("bach-prelude-c", 560, 330, focus = 12)
    @Test fun fullDebussyPhoneHandlesTupletsAndClefChanges() = score("debussy-arabesque", 320, 480)
    @Test fun leftHandOnlyExcerptDisplaysBothPartsButGradesOnlyLeft() = score("chopin-prelude-cm", 560, 820,
        CatalogueOptions("chopin-prelude-cm", Difficulty.EASY, excerpt = true, leftHand = true, rightHand = false))

    @Test fun sourceCreditsRemainReachableInCompactLandscape() {
        orient(true)
        val repo = repository
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(560.dp, 330.dp)) {
                    SprintScreen(GameState(mode = GameMode.CATALOGUE, catalogueOptions = CatalogueOptions("petzold-minuet-g")), "Connected", true, {}, {}, {}, {}, catalogueEntries = repo.entries)
                }
            }
        }
        compose.onNodeWithText("Score source & arrangements").performScrollTo().performClick()
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        compose.onNodeWithText("Play selected piece →").performScrollTo().assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
        }
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        instrumentation.uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/$name.png").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
        }
    }
}
