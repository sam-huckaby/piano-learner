export type MusicNote = {
  midi: number
  key: string
  label: string
  accidental?: '#' | 'b'
  clef: 'treble' | 'bass'
}

const SHARP_NAMES = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B']

export function createNote(midi: number): MusicNote {
  const pitch = SHARP_NAMES[midi % 12]
  const octave = Math.floor(midi / 12) - 1
  const hasSharp = pitch.includes('#')
  const letter = pitch[0].toLowerCase()
  return {
    midi,
    key: `${letter}${hasSharp ? '#' : ''}/${octave}`,
    label: `${pitch}${octave}`,
    accidental: hasSharp ? '#' : undefined,
    clef: midi >= 60 ? 'treble' : 'bass',
  }
}

export function getRandomNote(includeChromatic: boolean, excludeMidi?: number): MusicNote {
  const available = Array.from({ length: 49 }, (_, index) => index + 36)
    .filter((midi) => includeChromatic || !SHARP_NAMES[midi % 12].includes('#'))
    .filter((midi) => midi !== excludeMidi)
  return createNote(available[Math.floor(Math.random() * available.length)])
}

export function isSoloTarget(pressedNotes: ReadonlySet<number>, targetMidi: number, invalid: boolean) {
  return !invalid && pressedNotes.size === 1 && pressedNotes.has(targetMidi)
}
