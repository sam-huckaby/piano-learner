import { useEffect, useEffectEvent, useRef, useState } from 'react'
import { GrandStaff } from './GrandStaff'
import { getRandomNote, isSoloTarget, type MusicNote } from './music'
import './App.css'

type Screen = 'welcome' | 'countdown' | 'playing' | 'finished'

const GAME_LENGTH_MS = 60_000
const CHORD_WINDOW_MS = 85

function App() {
  const [screen, setScreen] = useState<Screen>('welcome')
  const [countdown, setCountdown] = useState(3)
  const [score, setScore] = useState(0)
  const [timeLeft, setTimeLeft] = useState(60)
  const [target, setTarget] = useState<MusicNote | null>(null)
  const [chromatic, setChromatic] = useState(false)
  const [midiMessage, setMidiMessage] = useState('Connect your MIDI keyboard to begin.')
  const [flash, setFlash] = useState(false)

  const midiAccess = useRef<MIDIAccess | null>(null)
  const pressedNotes = useRef(new Set<number>())
  const targetRef = useRef<MusicNote | null>(null)
  const playingRef = useRef(false)
  const candidate = useRef<{ id: number; note: number; invalid: boolean } | null>(null)
  const candidateId = useRef(0)
  const gameEndsAt = useRef(0)

  useEffect(() => {
    targetRef.current = target
  }, [target])

  useEffect(() => {
    return () => {
      midiAccess.current?.inputs.forEach((input) => {
        input.onmidimessage = null
      })
    }
  }, [])

  function chooseNextNote(previous?: MusicNote | null) {
    const next = getRandomNote(chromatic, previous?.midi)
    setTarget(next)
  }

  function beginGame() {
    pressedNotes.current.clear()
    candidate.current = null
    setScore(0)
    setTimeLeft(60)
    chooseNextNote()
    gameEndsAt.current = Date.now() + GAME_LENGTH_MS
    playingRef.current = true
    setScreen('playing')
  }

  function finishGame() {
    playingRef.current = false
    candidate.current = null
    setTimeLeft(0)
    setScreen('finished')
  }

  function handleMidiMessage(event: MIDIMessageEvent) {
    const data = event.data
    if (!data) return
    const [status, note, velocity = 0] = data
    if (status === undefined || note === undefined) return

    const command = status & 0xf0
    const isNoteOn = command === 0x90 && velocity > 0
    const isNoteOff = command === 0x80 || (command === 0x90 && velocity === 0)

    if (isNoteOff) {
      pressedNotes.current.delete(note)
      return
    }
    if (!isNoteOn) return

    pressedNotes.current.add(note)
    const activeCandidate = candidate.current
    if (activeCandidate && note !== activeCandidate.note) activeCandidate.invalid = true

    const currentTarget = targetRef.current
    if (!playingRef.current || !currentTarget || note !== currentTarget.midi || pressedNotes.current.size !== 1) return

    const id = ++candidateId.current
    candidate.current = { id, note, invalid: false }
    window.setTimeout(() => {
      const attempt = candidate.current
      if (
        !attempt ||
        attempt.id !== id ||
        !playingRef.current ||
        !targetRef.current ||
        !isSoloTarget(pressedNotes.current, targetRef.current.midi, attempt.invalid)
      ) {
        return
      }

      candidate.current = null
      setScore((value) => value + 1)
      setFlash(true)
      window.setTimeout(() => setFlash(false), 180)
      chooseNextNote(targetRef.current)
    }, CHORD_WINDOW_MS)
  }

  async function startSession() {
    if (!navigator.requestMIDIAccess) {
      setMidiMessage('Web MIDI is unavailable. Use Chrome or Edge on a desktop computer.')
      return
    }

    try {
      const access = await navigator.requestMIDIAccess()
      midiAccess.current = access
      access.inputs.forEach((input) => {
        input.onmidimessage = handleMidiMessage
      })
      access.onstatechange = () => {
        access.inputs.forEach((input) => {
          input.onmidimessage = handleMidiMessage
        })
        setMidiMessage(access.inputs.size ? 'MIDI keyboard connected.' : 'No MIDI keyboard detected. You can still reconnect it during the game.')
      }
      setMidiMessage(access.inputs.size ? 'MIDI keyboard connected.' : 'No MIDI keyboard detected. You can still reconnect it during the game.')
      setCountdown(3)
      setScreen('countdown')
    } catch {
      setMidiMessage('MIDI access was not granted. Allow access and try again.')
    }
  }

  function playAgain() {
    pressedNotes.current.clear()
    candidate.current = null
    setTarget(null)
    setScreen('welcome')
  }

  const startAfterCountdown = useEffectEvent(() => beginGame())
  const updateTimer = useEffectEvent(() => {
    const remaining = Math.max(0, gameEndsAt.current - Date.now())
    setTimeLeft(Math.ceil(remaining / 1000))
    if (remaining === 0) finishGame()
  })

  useEffect(() => {
    if (screen !== 'countdown') return
    const timer = window.setTimeout(() => {
      if (countdown === 1) startAfterCountdown()
      else setCountdown((value) => value - 1)
    }, 1000)
    return () => window.clearTimeout(timer)
  }, [screen, countdown])

  useEffect(() => {
    if (screen !== 'playing') return
    const timer = window.setInterval(updateTimer, 100)
    return () => window.clearInterval(timer)
  }, [screen])

  const title = screen === 'welcome' ? 'Note Sprint' : screen === 'finished' ? 'Time!' : 'Note Sprint'

  return (
    <main className="app-shell">
      <header className="topbar">
        <p className="wordmark">NOTE SPRINT</p>
        <label className="chromatic-toggle">
          <input type="checkbox" checked={chromatic} onChange={(event) => setChromatic(event.target.checked)} disabled={screen === 'playing' || screen === 'countdown'} />
          <span>Include sharps & flats</span>
        </label>
      </header>

      <section className="game-card" aria-live="polite">
        {screen === 'welcome' && (
          <div className="intro-panel">
            <p className="eyebrow">MIDI SIGHT-READING DRILL</p>
            <h1>{title}</h1>
            <p className="intro-copy">See the note. Play it cleanly. How many can you get in one minute?</p>
            <div className="rules">
              <span>01&nbsp; One note at a time</span>
              <span>02&nbsp; 60 seconds</span>
              <span>03&nbsp; MIDI piano required</span>
            </div>
            <button className="primary-button" type="button" onClick={startSession}>Start the challenge <span aria-hidden="true">→</span></button>
            <p className="midi-status">{midiMessage}</p>
          </div>
        )}

        {screen === 'countdown' && (
          <div className="countdown-panel">
            <p className="eyebrow">GET YOUR HANDS READY</p>
            <strong>{countdown || 'Go'}</strong>
            <p>{midiMessage}</p>
          </div>
        )}

        {screen === 'playing' && target && (
          <div className="play-panel">
            <div className="scoreboard">
              <div><span>TIME</span><strong>{String(timeLeft).padStart(2, '0')}<small>s</small></strong></div>
              <div><span>CORRECT</span><strong>{score}</strong></div>
            </div>
            <p className="prompt">Play this note</p>
            <div className={flash ? 'notation-wrap success' : 'notation-wrap'}><GrandStaff note={target} /></div>
            <p className="note-name">{target.label}</p>
            <p className="hint">Play the displayed key by itself</p>
          </div>
        )}

        {screen === 'finished' && (
          <div className="finish-panel">
            <p className="eyebrow">CHALLENGE COMPLETE</p>
            <h1>{title}</h1>
            <p>You played</p>
            <strong className="final-score">{score}</strong>
            <p className="score-label">notes correctly</p>
            <button className="primary-button" type="button" onClick={playAgain}>Play again <span aria-hidden="true">→</span></button>
          </div>
        )}
      </section>
    </main>
  )
}

export default App
