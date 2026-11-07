import type { MusicNote } from './music'

type Props = { note: MusicNote }

const LETTERS = ['c', 'd', 'e', 'f', 'g', 'a', 'b']
const LINE_GAP = 10
const STAFF_WIDTH = 760
const TREBLE_Y = 57
const BASS_Y = 187

function staffPosition(key: string, clef: MusicNote['clef']) {
  const match = /^([a-g])#?\/(\d)$/.exec(key)
  if (!match) return 0
  const letter = LETTERS.indexOf(match[1])
  const octave = Number(match[2])
  const diatonic = octave * 7 + letter
  const bottomLine = clef === 'treble' ? 4 * 7 + LETTERS.indexOf('e') : 2 * 7 + LETTERS.indexOf('g')
  return diatonic - bottomLine
}

function ledgerLines(y: number, staffY: number) {
  const top = staffY
  const bottom = staffY + LINE_GAP * 4
  const lines: number[] = []
  for (let line = top - LINE_GAP; line >= y; line -= LINE_GAP) lines.push(line)
  for (let line = bottom + LINE_GAP; line <= y; line += LINE_GAP) lines.push(line)
  return lines
}

function Clef({ type, y }: { type: MusicNote['clef']; y: number }) {
  return (
    <text className="clef" x="23" y={y + 58} aria-hidden="true">
      {type === 'treble' ? '𝄞' : '𝄢'}
    </text>
  )
}

function Staff({ y }: { y: number }) {
  return <>{Array.from({ length: 5 }, (_, index) => <line key={index} className="staff-line" x1="18" x2={STAFF_WIDTH - 18} y1={y + index * LINE_GAP} y2={y + index * LINE_GAP} />)}</>
}

export function GrandStaff({ note }: Props) {
  const staffY = note.clef === 'treble' ? TREBLE_Y : BASS_Y
  const position = staffPosition(note.key, note.clef)
  const noteY = staffY + LINE_GAP * 4 - position * (LINE_GAP / 2)
  const noteX = STAFF_WIDTH / 2
  const stemUp = noteY > staffY + LINE_GAP * 2
  const stemEnd = stemUp ? noteY - 34 : noteY + 34

  return (
    <svg className="notation" viewBox={`0 0 ${STAFF_WIDTH} 310`} aria-label={`${note.label} on the ${note.clef} staff`} role="img">
      <Staff y={TREBLE_Y} />
      <Staff y={BASS_Y} />
      <Clef type="treble" y={TREBLE_Y} />
      <Clef type="bass" y={BASS_Y} />
      {ledgerLines(noteY, staffY).map((y) => <line key={y} className="ledger-line" x1={noteX - 14} x2={noteX + 14} y1={y} y2={y} />)}
      {note.accidental && <text className="accidental" x={noteX - 25} y={noteY + 7}>#</text>}
      <line className="note-stem" x1={stemUp ? noteX + 6 : noteX - 6} x2={stemUp ? noteX + 6 : noteX - 6} y1={noteY} y2={stemEnd} />
      <ellipse className="note-head" cx={noteX} cy={noteY} rx="8" ry="5.7" transform={`rotate(-20 ${noteX} ${noteY})`} />
    </svg>
  )
}
