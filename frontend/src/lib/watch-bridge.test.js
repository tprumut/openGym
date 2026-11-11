import { describe, it, expect } from 'vitest'
import { activeSnapshot } from './watch-bridge.js'
import { EXDB } from './exercises.js'

const LIFT = EXDB.find(e => e.bp !== 'cardio').id
const LIFT2 = EXDB.filter(e => e.bp !== 'cardio')[1].id

const state = (entries, cur = 0) => ({
  unit: 'kg',
  restSec: 75,
  active: { id: 'w1', name: 'Push', start: 1, cur, entries }
})

const entry = (id, sets, extra = {}) => ({
  id,
  sg: extra.sg,
  target: { sets: sets.length, reps: 10, weight: 60, ...extra },
  sets
})

describe('activeSnapshot', () => {
  it('is null when no workout is active', () => {
    expect(activeSnapshot({ active: null })).toBeNull()
    expect(activeSnapshot({ active: { entries: [] } })).toBeNull()
  })

  it('describes the current exercise without sending the whole workout', () => {
    const snap = activeSnapshot(state([
      entry(LIFT, [{ w: 60, r: 10, done: false }, { w: 60, r: 10, done: false }])
    ]))
    expect(snap.title).toBeTruthy()
    expect(snap.position).toBe('Exercise 1 / 1')
    expect(snap.progressText).toBe('0 / 2')
    expect(snap.unitSetsText).toBe('0 / 2 sets')
    expect(snap.unitComplete).toBe(false)
    expect(snap.canComplete).toBe(false)
    expect(snap.completeLabel).toBe('Next exercise')
    expect(snap.restSec).toBe(75)
  })

  it('groups a superset into one completion unit', () => {
    const snap = activeSnapshot(state([
      entry(LIFT, [{ w: 60, r: 10, done: true }], { sg: 's1' }),
      entry(LIFT2, [{ w: 20, r: 12, done: true }], { sg: 's1' }),
      entry(LIFT, [{ w: 60, r: 10, done: false }])
    ]))
    expect(snap.title).toContain(' + ')
    expect(snap.unitSetsText).toBe('2 / 2 sets')
    expect(snap.canComplete).toBe(true)
    expect(snap.completeLabel).toBe('Next exercise')
  })

  it('stops asking for completion once the final unit is already done', () => {
    const snap = activeSnapshot(state([
      entry(LIFT, [{ w: 60, r: 10, done: true }])
    ]))
    expect(snap.unitComplete).toBe(true)
    expect(snap.done).toBe(true)
    expect(snap.canComplete).toBe(false)
  })
})
