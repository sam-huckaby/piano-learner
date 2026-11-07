#!/usr/bin/env python3
"""Reproducible, build-time transcription from public-domain Mutopia LilyPond.

Requires LilyPond 2.26 (including convert-ly). Android never runs this tooling.
Original downloads remain untouched; converted/unfolded copies are disposable.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from fractions import Fraction
from collections import defaultdict
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT.parent / "app/src/main/assets/catalogue"
TPQ = 960


def tick(value):
    return round(Fraction(value) * 4 * TPQ)


def read_events(entry, path):
    notes, ties, staves = [], set(), {}
    selected = entry.get("score", 0)
    for line in path.read_text().splitlines():
        fields = line.split("\t")
        if int(fields[1]) != selected:
            continue
        if fields[0] == "S":
            staves[int(fields[2])] = fields[3]
        elif fields[0] == "T":
            ties.add((int(fields[2]), fields[3], tick(fields[4]), int(fields[5])))
        elif fields[0] == "N":
            _, _, part, voice, time, grace, bar, pos, length, log, dots, midi, letter, octave, alteration, clef, num, den, key = fields
            assert int(part) in (0, 1), (entry["id"], fields)
            assert clef in ("clefs.G", "clefs.F"), (entry["id"], clef)
            note = dict(part=int(part), voice=voice, time=tick(time), grace=tick(grace),
                        duration=tick(length), log=int(log), dots=int(dots), midi=int(midi),
                        step=int(letter), octave=int(octave)+4, alter=int(Fraction(alteration)*2),
                        clef=clef == "clefs.G", key=[int(Fraction(a)*2) for a in key.split(",")],
                        numerator=int(num), denominator=int(den), position=tick(pos))
            note["measureStart"] = note["time"] - note["position"]
            note["pickup"] = note["position"] < 0
            note["tieTo"] = -1
            note["tieFrom"] = -1
            notes.append(note)
    assert len(staves) == 2, (entry["id"], staves)
    pickup_starts = {}
    for n in notes:
        if n["pickup"]:
            pickup_starts[n["measureStart"]] = min(pickup_starts.get(n["measureStart"], n["time"]), n["time"])
    for n in notes:
        if n["pickup"]:
            n["measureStart"] = pickup_starts[n["measureStart"]]
    notes.sort(key=lambda n: (n["time"], n["grace"], n["part"], n["voice"], n["midi"]))
    starts = sorted(set(n["measureStart"] for n in notes))
    has_pickup = any(n["pickup"] for n in notes)
    measures = []
    for index, start in enumerate(starts):
        local = [n for n in notes if n["measureStart"] == start]
        first = local[0]
        end = starts[index+1] if index+1 < len(starts) else max(n["time"] + n["duration"] for n in local)
        measures.append(dict(start=start, end=end, number=index + (0 if has_pickup else 1),
                             numerator=first["numerator"], denominator=first["denominator"],
                             key=first["key"], pickup=any(n["pickup"] for n in local)))
    by_start = {start: index for index, start in enumerate(starts)}
    for index, note in enumerate(notes):
        note["id"] = index
        note["measure"] = by_start[note.pop("measureStart")]
        note.pop("pickup"); note.pop("position"); note.pop("numerator"); note.pop("denominator"); note.pop("key")
    unresolved = []
    for note in notes:
        if note["midi"] < 0:
            continue
        token = (note["part"], note["voice"], note["time"])
        tied = (*token, note["midi"]) in ties or (*token, -1) in ties
        if tied:
            next_notes = [n for n in notes if n["part"] == note["part"] and n["midi"] == note["midi"] and n["time"] == note["time"] + note["duration"] and n["grace"] == 0]
            match = next((n for n in next_notes if n["voice"] == note["voice"]), next_notes[0] if next_notes else None)
            if match:
                note["tieTo"] = match["id"]
                match["tieFrom"] = note["id"]
            else:
                unresolved.append(token)
    if unresolved:
        raise ValueError(f"Unresolved ties in {entry['id']}: {unresolved}")
    return dict(tpq=TPQ, measures=measures, notes=notes)


def written_duration(ticks):
    for log in range(0, 7):
        for dots in range(3):
            if round(4 * TPQ / 2**log * (2 - 1 / 2**dots)) == ticks:
                return log, dots
    return 2, 0


def arrangement(original, difficulty):
    """Explicit practice reductions; the HARD asset is never changed here."""
    notes = [dict(n) for n in original["notes"] if n["midi"] >= 0]
    kept = []
    for measure_index, measure in enumerate(original["measures"]):
        local = [n for n in notes if n["measure"] == measure_index]
        groups = defaultdict(list)
        for note in local:
            groups[(note["part"], note["time"], note["grace"])].append(note)
        # Keep the full melody contour/onset sequence, thinning simultaneous voices.
        for (part, time, grace), group in groups.items():
            if part == 0:
                kept.extend(sorted(group, key=lambda n: n["midi"], reverse=True)[:1 if difficulty == "easy" else 3])
        bass_groups = sorted((time, group) for (part, time, grace), group in groups.items() if part == 1 and grace == 0)
        if difficulty == "easy" and bass_groups:
            note = dict(min(bass_groups[0][1], key=lambda n: n["midi"]))
            note.update(time=measure["start"], duration=measure["end"]-measure["start"], voice="practice-bass", clef=False,
                        tieTo=-1, tieFrom=-1)
            note["log"], note["dots"] = written_duration(note["duration"])
            kept.append(note)
        elif bass_groups:
            selected = [(time, group) for time, group in bass_groups if time == bass_groups[0][0] or (time-measure["start"]) % TPQ == 0]
            for i, (time, group) in enumerate(selected):
                end = selected[i+1][0] if i+1 < len(selected) else measure["end"]
                for source in sorted(group, key=lambda n: n["midi"])[:2]:
                    note = dict(source, duration=end-time, voice="practice-bass", tieTo=-1, tieFrom=-1)
                    note["log"], note["dots"] = written_duration(note["duration"])
                    kept.append(note)
    ids = {n["id"] for n in kept}
    for n in kept:
        if n["tieFrom"] not in ids: n["tieFrom"] = -1
        if n["tieTo"] not in ids: n["tieTo"] = -1
    # Missing notes are deliberately omitted; rests are filled at runtime per part.
    kept.sort(key=lambda n: (n["time"], n["grace"], n["part"], n["midi"]))
    return dict(tpq=TPQ, measures=original["measures"], notes=kept)


def audit(score):
    measures, notes = score["measures"], score["notes"]
    assert measures and notes
    assert all(m["end"] > m["start"] for m in measures)
    assert all(a["end"] == b["start"] for a, b in zip(measures, measures[1:]))
    ids = {n["id"]: n for n in notes}
    for n in notes:
        m = measures[n["measure"]]
        assert m["start"] <= n["time"] < m["end"], n
        assert n["duration"] > 0
        if n["midi"] >= 0:
            assert 0 <= n["midi"] <= 127
            assert (n["octave"]+1)*12 + [0,2,4,5,7,9,11][n["step"]] + n["alter"] == n["midi"], n
        for field in ("tieFrom", "tieTo"):
            assert n[field] == -1 or n[field] in ids
    return dict(measures=len(measures), notes=sum(n["midi"] >= 0 for n in notes),
                onsets=len(set((n["time"], n["grace"]) for n in notes if n["midi"] >= 0 and n["tieFrom"] == -1)),
                durationTicks=measures[-1]["end"], ties=sum(n["tieTo"] >= 0 for n in notes))


def verify_midi(entry, score):
    import mido
    midi = mido.MidiFile(ROOT / ".work" / entry["id"] / "score.midi")
    actual = set()
    for track in midi.tracks:
        time = 0
        for message in track:
            time += message.time
            if message.type == "note_on" and message.velocity:
                actual.add((round(time * TPQ / midi.ticks_per_beat), message.note))
    expected = {(n["time"], n["midi"]) for n in score["notes"] if n["midi"] >= 0 and n["tieFrom"] < 0 and n["grace"] == 0}
    assert not expected - actual, (entry["id"], "Missing MIDI attacks", expected - actual)
    # MIDI slightly anticipates grace notes. Identical unisons in multiple
    # engraved voices may be coalesced by the MIDI performer, so compare sets.
    grace = [n for n in score["notes"] if n["midi"] >= 0 and n["grace"] != 0]
    extra = actual - expected
    assert len(extra) == len(grace), (entry["id"], "Grace count", extra, grace)
    for time, pitch in extra:
        match = next((n for n in grace if n["midi"] == pitch and abs(n["time"] - time) <= TPQ), None)
        assert match is not None, (entry["id"], "Unexpected MIDI attack", time, pitch)
        grace.remove(match)
    return True


def prepare(entry):
    sources = ROOT / "sources"
    work = ROOT / ".work" / entry["id"]
    sources.mkdir(exist_ok=True)
    work.mkdir(parents=True, exist_ok=True)
    original = sources / (entry["id"] + ".ly")
    if not original.exists():
        original.write_bytes(urlopen("https://www.mutopiaproject.org/ftp/" + entry["path"], timeout=60).read())
    conversion_input = original
    if entry["id"] == "bach-invention-f":
        # This obsolete option predates 1.9. Its two linear relative voices
        # contain no post-chord notes affected by the old chord-relative rule.
        conversion_input = work / "input.ly"
        conversion_input.write_text(original.read_text().replace("#(ly:set-option 'old-relative)", ""))
    converted = subprocess.run(["convert-ly", str(conversion_input)], capture_output=True, text=True)
    if converted.returncode:
        raise RuntimeError(converted.stderr)
    text = converted.stdout
    # A complete performance takes the source's written repeats. The engraver
    # still reports measure positions, while the performance timeline unfolds.
    text = re.sub(r"(\\score\s*\{)", r"\1 \\unfoldRepeats ", text)
    text = '\\include "' + str(ROOT / "export.ily") + '"\n' + text
    target = work / "score.ly"
    target.write_text(text)
    output = work / "events.tsv"
    env = dict(os.environ, NOTESPRINT_EXPORT=str(output))
    result = subprocess.run(["lilypond", "-dno-print-pages", "-o", str(work / "score"), str(target)],
                            capture_output=True, text=True, env=env)
    (work / "compile.log").write_text(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError(f"{entry['id']}: see {work / 'compile.log'}\n{result.stderr[-2500:]}")
    print(entry["id"], "exported", len(output.read_text().splitlines()), "events", flush=True)
    return output


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--only")
    parser.add_argument("--reuse", action="store_true", help="Reuse compiler exports while iterating on data packaging")
    args = parser.parse_args()
    entries = json.loads((ROOT / "manifest.json").read_text())
    index = []
    ASSETS.mkdir(parents=True, exist_ok=True)
    for entry in entries:
        if not args.only or args.only == entry["id"]:
            output = ROOT / ".work" / entry["id"] / "events.tsv" if args.reuse else prepare(entry)
            original = read_events(entry, output)
            record = dict(entry, license="Public Domain", sourceUrl=f"https://www.mutopiaproject.org/cgibin/piece-info.cgi?id={entry['sourceId']}",
                          sourceSha256=hashlib.sha256((ROOT / "sources" / (entry["id"] + ".ly")).read_bytes()).hexdigest())
            record.pop("path", None); record.pop("score", None)
            record["audit"] = {}
            record["midiVerified"] = verify_midi(entry, original)
            for difficulty, score in (("hard", original), ("medium", arrangement(original, "medium")), ("easy", arrangement(original, "easy"))):
                record["audit"][difficulty] = audit(score)
                (ASSETS / f"{entry['id']}-{difficulty}.json").write_text(json.dumps(score, ensure_ascii=False, separators=(",", ":")))
            print(entry["id"], record["audit"], flush=True)
            index.append(record)
    if not args.only:
        (ASSETS / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
