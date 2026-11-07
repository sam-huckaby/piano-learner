# Note Sprint — Android

Standalone native Kotlin / Jetpack Compose app. Android 7.1+ (API 25), phones and tablets, USB MIDI keyboards. No WebView, server, account, or saved scores. A bundled music font keeps notation available offline.

## Device compatibility

The minimum SDK is API 25, the lowest version across the currently identified devices. Font loading uses AndroidX ResourcesCompat rather than the API 26-only framework method.

| Device | Android / API | Status |
| --- | --- | --- |
| Samsung Galaxy Tab E SM-T377T | 7.1.1 / 25 | Debug APK installed and welcome screen verified on hardware. No launch crash. |
| Converted Meta Portal | 9 / 28 (recorded in `~/echo-show-dev/`) | Meets minimum Android version; installation and USB MIDI are not yet verified. Recorded wireless ADB address was unreachable during testing. |
| Fire tablet / Echo Show | 9 / 28 and 11 / 30 (device records) | Meets minimum Android version; not tested with this app. |

USB MIDI gameplay still requires a physical piano test on each device; OS compatibility alone does not establish USB host/MIDI functionality on customized firmware.

## Run in Android Studio

1. Open this `android/` directory in Android Studio (Meerkat or newer).
2. Install Android SDK Platform 35 and use JDK 17 (Android Studio's bundled compatible JDK also works).
3. Allow Gradle sync, connect an Android device with USB debugging enabled, and run the `app` configuration.
4. Connect a class-compliant USB MIDI piano using a host/OTG adapter matching the device's USB port (micro-USB for the Galaxy Tab E, USB-C on newer devices). A powered USB hub may be needed for keyboards that draw more power than the device supplies.

Start becomes available when a MIDI output port from a USB keyboard is open. Android calls the piano-to-app direction an **output** port. No Bluetooth pairing or microphone permission is involved.

## Command line

Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to your SDK directory (or put `sdk.dir=/absolute/path/to/sdk` in an untracked `local.properties`). From this directory:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew installDebug
```

Windows: use `gradlew.bat`. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Gradle and Maven downloads require internet on the first build. The installed app works offline.

## Modes

Select **Flashcards**, **Song Mode**, or **Catalogue** on the start screen. All have a three-second countdown. Flashcards require solo key presses; songs can include rolled chords and simultaneous left/right hand parts.

### Flashcards

Play as many prompted notes as possible in a full 60-second round. Correct answers generate another note; wrong notes do not score. Quick solo taps count.

### Song Mode

Generate a new 16-beat piece in four bars of 4/4. Each beat is a quarter note or chord. Both treble and bass staves are displayed, with rests for inactive hands. A major scale, a I–IV/vi–V–I chord progression, nearby melodic motion, a recurring interval pattern, and a final tonic keep pieces musically related. Chords favor core triads with occasional seventh/ninth/sixth colors, bounded within an octave per hand. Smaller voicings are favored rather than filling every available slot.

The next beat is orange with a highlighted column; individual notes turn green as they are played, then the whole beat completes. All four bars are available by scrolling, and the score follows the active bar automatically. Adjacent chord noteheads alternate sides of their shared stem; nearby accidentals occupy separate columns. Completing all 16 beats shows a completion screen; **New song** generates another piece.

For a chord, every required pitch needs a fresh press within **450 ms of the first press**. Keys can be rolled and released separately; they do not have to be held together. Missing notes, an extra pitch, or an expired assembly window invalidates that attempt without advancing. An 85 ms guard after the last required press catches extra keys. Release all keys before the next beat. A one-note song beat retains the quick-tap behavior.

**Rhythm off (default):** play the pitches at your own pace, without a time limit.

**Rhythm on:** follow the visual 72 BPM pulse. The first pulse is one beat after the score appears. The first press must be within ±250 ms of the pulse; all required pitches must still be assembled within 450 ms. The span from the first press to the **final key release** must equal one beat with ±35% tolerance. Individual rolled keys can be released sooner. Early/late, incomplete, extra-note, or overheld attempts show retry feedback and schedule another pulse for the same beat. Completed beats stay completed. There is no audible metronome or backing track.

### Catalogue

Ten complete, offline piano works are bundled, each with three complete versions (30 score assets):

1. Petzold — Minuet in G, BWV Anh. 114 (formerly attributed to Bach)
2. Bach — Prelude in C major, BWV 846
3. Beethoven — Für Elise, WoO 59
4. Burgmüller — Arabesque, Op. 100 No. 2
5. Handel — Sonatina in B-flat major, HWV 585
6. Clementi — Sonatina in C, Op. 36 No. 1, **complete first movement**
7. Chopin — Prelude in E minor, Op. 28 No. 4
8. Chopin — Prelude in C minor, Op. 28 No. 20
9. Bach — Invention No. 8 in F major, BWV 779
10. Debussy — Arabesque No. 1, L. 66 No. 1

Choose a piece and version before starting:

- **Hard / Original (default):** pitches, written values, independent voices, ties, rests, pickups, grace notes, meter/key/clef changes from the source edition. Written repeats are expanded into the full practice sequence (so performance-measure counts can exceed the original printed measure count).
- **Medium:** a clearly labelled practice reduction preserving the melody contour and onset sequence, thinning right-hand chords to three notes and left-hand chords to two, with quarter-note bass accompaniment and an 80% practice tempo.
- **Easy:** a clearly labelled practice reduction with one melody note per onset, one bass note per measure, and a 60% practice tempo. Both reductions span every measure of the complete work; they are not random generations or excerpts labelled as full pieces.
- **Play signature excerpt:** off by default. Selects a curated recognizable section of the chosen version. The selected measure range is shown.
- **Left hand / Right hand:** independently enable parts, with at least one enabled. Inactive parts stay visible in gray. Catalogue follows the original written hand assignments, including a left-hand part above middle C or a treble clef on the lower staff.

The notation retains original pitches/registers and does not apply the random-song range, chromatic, chord-limit, or two-handed generation settings. **Show note name** and **Rhythm** remain available. Practice tempos are editorial and displayed in BPM. The score is a practice rendering with individually flagged short notes and tuplet brackets; consult **Score source & arrangements → Open source score** for the original edition's full engraving and expressive/ornament markings.

Each simultaneous attack group highlights orange and turns green as played. Tied continuations do not require another press. A sustained bass can overlap later melody attacks. Onsets/grace notes/chords count as groups, so the progress total varies by piece, version, excerpt and enabled hands rather than always being 16.

With rhythm off, play at your own pace; sustained notes may be released early. With rhythm on, the quarter-note pulse guides the original rhythm, and each note's held length is checked separately, including ties. Timing tolerance adapts to the next onset (70–250 ms); release tolerance is ±35% or 100 ms, whichever is larger. Re-articulating a key ends its previous hold when voices overlap on the same pitch. Duration mistakes return to that note's attack group instead of blocking later notes behind a sustained bass. Leading and trailing rests remain in the timed performance. There is no backing audio.

Measures are vertically virtualized for full works and scroll automatically to the active measure. Dense measures can scroll horizontally without squeezing the staff to illegible sizes; the active group is brought into view. Source credits, license, original source links and arrangement descriptions are available within Catalogue. See [`catalogue/README.md`](catalogue/README.md) for provenance and regeneration.

## Settings

Changes apply between rounds and survive replay and rotation, but are not saved across process restarts.

- **Octave range:** lowest/highest full octave, inclusive, applies to Flashcards and generated Song Mode. Default **3–5 (C3–B5)**. Octaves 0–8 are selectable; invalid reversed ranges cannot be selected. A range takes priority over forcing both hands: octave 4 alone produces only C4–B4, so the bass part rests. Catalogue preserves its written registers. Choose endpoints your keyboard can actually play.
- **Include chromatic notes:** off by default. In flashcards, enables all semitones within the range. In songs, allows C, G, F, D and B-flat major, with standard key signatures on both staves. Occasional borrowed naturals on weak beats cancel key-signature notes, followed by a diatonic restoration. Temporary accidentals apply to the same letter and octave on the same staff for the remainder of a measure, and reset at the next bar. Repeated accidentals are suppressed while effective; restoring a sharp/flat in the same bar is explicitly marked. Chromatic off generates white-key C-major music without these alterations.
- **Show note name:** off by default; reveals the label below a flashcard or beneath each song note.
- **Rhythm:** available in Song Mode and Catalogue, off by default. Enables their respective guided timing rules described above.
- **Maximum notes per hand:** Song Mode slider from **1–6**, default **1**. Each hand has its own ceiling, not a combined total or a required number. A hand may have fewer notes or rest on a beat.
- **Two-handed play:** Song Mode checkbox, off by default. On permits both hands on the same beat; max 1 can therefore mean one note per hand. Notes below C4 form the bass/left part and C4 upward form the treble/right part. If the range does not support a part, it rests. With this off, only one hand is active on any given beat.

## MIDI handling

- Flashcards retain their solo-note rule: an 85 ms grace window rejects extra keys, including a second note quickly released. Song beats instead grade their complete expected pitch set as described above.
- Flashcards and generated songs require releasing keys between prompts. Catalogue allows earlier notes to remain held when the original score sustains them across later attacks. Note-on velocity zero is treated as note-off; sustain-pedal messages do not change physical key tracking.
- MIDI streams are tracked separately per device, port and channel; running status and split messages are supported.
- Rotation preserves the round. Background time still counts using a monotonic clock. Input ports close in the background and reopen on return. Disconnecting invalidates any pending attempt; reconnect to continue. Guided rhythm schedules retries rather than skipping notes while disconnected.

## Verification on hardware

Run a full round; confirm flashcards reject extra keys, songs accept all required chord pitches (including rolls) and reject extras, low C2/high C6 remain visible, reconnecting works, and phone/tablet portrait/landscape layouts remain readable. Emulators generally cannot validate a real USB MIDI connection; use a physical Android device.

The active game fits its staff proportionally inside the available window height, with a side-by-side scoreboard on short or wide screens. Welcome and results screens scroll independently; entering a round resets their scroll position. Status-bar and display-cutout insets are respected.

The 47 JVM tests cover the existing modes plus validation of all 30 catalogue assets, complete pitch-only playback across all versions/hand combinations, guided playback of all ten originals, source theme/register checks, independent sustained notes/ties, excerpts, and settings. The 24 on-device Compose tests cover existing layouts and Catalogue selection, hand/excerpt/difficulty controls, original notation, full-work progress, source credits and compact portrait/landscape scrolling on the Galaxy Tab E (API 25). Run with a connected device:

```sh
./gradlew connectedDebugAndroidTest
```

## Font license

`app/src/main/res/font/noto_music.ttf` is Noto Music, distributed under SIL Open Font License 1.1. See `licenses/OFL-NotoMusic.txt`.
