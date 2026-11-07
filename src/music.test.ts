import { describe, expect, it } from 'vitest'
import { createNote, isSoloTarget } from './music'

describe('music notes', () => {
  it('maps middle C to treble staff notation', () => {
    expect(createNote(60)).toMatchObject({ key: 'c/4', label: 'C4', clef: 'treble' })
  })

  it('does not accept a target played with another held key', () => {
    expect(isSoloTarget(new Set([60, 62]), 60, false)).toBe(false)
  })

  it('rejects a target when another note arrived during the chord window', () => {
    expect(isSoloTarget(new Set([60]), 60, true)).toBe(false)
  })
})
