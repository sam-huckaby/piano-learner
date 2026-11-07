# Offline classical catalogue

The app bundles complete scores for ten works, plus deterministic Easy/Medium practice reductions and a signature excerpt selection for every work/version. Hard/original is the default; full-piece practice is the default. The web app is independent of this Android catalogue.

## Sources and rights

All ten source editions are explicitly marked **Public Domain** by the Mutopia Project. We retain the original LilyPond downloads, credit the typesetters/editions, and include source links and license labels in the app. Petzold is credited for BWV Anh. 114 despite its historical Bach attribution in the source's metadata.

| Work | Edition / typesetter | Source |
| --- | --- | --- |
| Minuet in G, BWV Anh. 114 | Bach-Gesellschaft / Allen Garvin | https://www.mutopiaproject.org/cgibin/piece-info.cgi?id=75 |
| Prelude in C, BWV 846 | Mutopia / Tobias Erbsland | https://www.mutopiaproject.org/cgibin/piece-info.cgi?id=5 |
| Für Elise, WoO 59 | Breitkopf & Härtel, 1888 / Stelios Samelis | https://www.mutopiaproject.org/cgibin/piece-info.cgi?id=931 |
| Arabesque, Op. 100 No. 2 | Collection Litolff / Bas Wassink | https://www.mutopiaproject.org/cgibin/piece-info.cgi?id=203 |
| Sonatina, HWV 585 | Handel's Werke, 1859 / David Maddock | https://www.mutopiaproject.org/cgibin/piece-info.cgi?id=98 |
| Sonatina, Op. 36 No. 1, first movement | Schirmer, 1893 / Brian D. Rude | https://www.mutopiaproject.org/cgibin/piece-info.cgi?id=804 |
| Prelude, Op. 28 No. 4 | Peters, 1879 / Magnus Lewis-Smith | https://www.mutopiaproject.org/cgibin/piece-info.cgi?id=468 |
| Prelude, Op. 28 No. 20 | Edition Peters / Magnus Lewis-Smith | https://www.mutopiaproject.org/cgibin/piece-info.cgi?id=472 |
| Invention No. 8, BWV 779 | Bach-Gesellschaft / Allen Garvin | https://www.mutopiaproject.org/cgibin/piece-info.cgi?id=61 |
| Arabesque No. 1, L. 66 No. 1 | Durand et Fils, 1904 / Keith O'Hara | https://www.mutopiaproject.org/cgibin/piece-info.cgi?id=1777 |

Mutopia's public-domain terms: https://www.mutopiaproject.org/legal.html#publicdomain

## Reproduce the transcription

Tools: **LilyPond 2.26**, its `convert-ly`, Python 3 and `mido` from `requirements.txt`. None of these tools are included in or needed by the Android app.

From this directory:

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python build_catalogue.py
```

Original files are cached in `sources/` and left untouched. Disposable compiler artifacts go in `.work/`. The reproducible outputs are `../app/src/main/assets/catalogue/index.json` and 30 score JSON files. `--reuse` skips compilation and repackages existing `.work/` exports; use a full rebuild after changing the exporter or source. `--only <id>` rebuilds one piece's data; a full build refreshes the catalogue index.

The build:

1. Reads `manifest.json`, downloading a missing original `.ly` source from its recorded Mutopia URL.
2. Converts a disposable copy to current LilyPond syntax. BWV 779's obsolete `old-relative` flag is removed from that copy: its two linear relative voices have no post-chord pitched notes affected by the historical chord-relative rule. The original file and its SHA-256 stay intact.
3. Uses `\unfoldRepeats` for complete practice performances, and selects **only the first score/movement** of the Clementi file. This is why Minuet has 64 performance measures and Clementi has 76, rather than their written 32 and 38.
4. Runs the real notation compiler with `export.ily`. Relative pitches, transposition, independent voices, written durations/dots, tuplets, rests, key/meter/clef changes, pickups, grace notes and ties are resolved by LilyPond rather than guessed from MIDI.
5. Serializes the resolved score at 960 ticks per quarter (sub-tick tuplets are rounded). Retains written spelling/octave separately from MIDI pitch, and the original hand assignment separately from clef/register.
6. Matches every non-grace attack's pitch/onset against the same edition's independently generated MIDI performer output. Grace notes are matched within their anticipated MIDI timing window; identical unisons in multiple engraved voices can collapse to a single physical-key attack. Unresolved ties, inconsistent spellings, invalid ranges and invalid measure coverage fail the build.
7. Produces clearly identified practice reductions across **the same complete measure sequence**: Medium keeps up to three melody/chord notes per onset and a two-note quarter-note bass; Easy keeps a single melody note per onset and one bass note per measure. These are deterministic app-created reductions, not third-party Easy editions or claims of original engraving. Default practice tempos are editorial, with difficulty factors 100%/80%/60% (minimum 36 quarter-note BPM).
8. Writes source SHA-256 hashes, license, credit, URLs and per-version note/measure/onset/tie counts into `index.json`.

The app's practice renderer uses individual flags rather than reproducing every original beam, slur, dynamic or ornamental marking. Literal grace-note events are included; expressive/ornament instructions are available in the linked original edition. MIDI grading follows the transcribed notes, not optional ornament realizations or pedal instructions.

## Data and extension points

- Each score has `tpq`, `measures`, and `notes`.
- A note stores physical part (0 right / 1 left), voice, onset and grace order, sounding duration, written duration log/dots, MIDI pitch, spelled letter/octave/alteration, current clef, measure and tie links. Negative MIDI values represent rests.
- Measures retain start/end, meter, key alterations by C–B, pickup status and performance measure number.
- `manifest.json` records each piece's curated excerpt range, title/composer/work and editorial practice tempo.
- Playback groups simultaneous fresh attacks, excludes tied continuations, and permits previously held notes when their source durations overlap a later onset. Duplicate unisons are one physical target. Guided mode checks releases independently so a long bass does not block shorter melody notes.
- Rendering virtualizes measures and horizontally scrolls dense bars, with automatic follow of the active group. Key-signature cancellations, local accidentals, ties, dots, grace sizing, flags, triplet brackets and original clef changes are displayed.

For another piece, add a complete source and metadata to the manifest, regenerate assets, add/check its curated excerpt and difficulty reductions, and run the Android JVM/device tests. The APK needs no catalogue server or runtime downloads.
