// Control surface for the watch bridge that lives inside the Android app.
// The native plugin is registered by MainActivity; in a browser build these
// helpers stay inert and the Settings card is hidden.
import { MOBILE } from './mobile.js'
import { exOr } from './exercises.js'
import { exLine, setsDoneActive, supersetUnits, unitOf } from './history.js'

let lastActiveJson = null
let pushChain = Promise.resolve()

// Plugin proxy — stored in a module variable, never resolved *as* a promise value.
// The Capacitor proxy is thenable on Android, so resolving a promise with it (or
// returning it from a .then callback) makes the runtime call `WatchBridge.then()`,
// which throws "WatchBridge.then() is not implemented on android". The loader
// therefore resolves with a boolean only; callers reach the proxy through this ref.
let _proxy = null
let _proxyPromise = null

function loadPlugin() {
  if (!MOBILE) return Promise.resolve(false)
  if (_proxy) return Promise.resolve(true)
  if (!_proxyPromise) {
    _proxyPromise = import('@capacitor/core')
      .then(({ registerPlugin }) => {
        _proxy = registerPlugin('WatchBridge')
        return true
      })
      .catch(() => false)
  }
  return _proxyPromise
}

// Run `fn(proxy)` once the plugin is loaded. `fn` must invoke a plugin method and
// return that native promise; the proxy itself is never handed back as a result.
function withPlugin(fn) {
  return loadPlugin().then(ok => (ok && _proxy ? fn(_proxy) : null))
}

// A compact, display-only snapshot of the active workout. The phone stays the source of
// truth; the watch renders this and sends back a single "complete" command.
export function activeSnapshot(S) {
  const A = S && S.active
  if (!A || !A.entries || !A.entries.length) return null

  const units = supersetUnits(A.entries)
  const cur = Math.min(Math.max(0, A.cur || 0), A.entries.length - 1)
  const unit = unitOf(units, cur)
  const unitIdx = units.findIndex(u => u === unit)
  const unitEntries = unit.map(i => A.entries[i])
  const setsTotal = A.entries.reduce((n, e) => n + e.sets.length, 0)
  const setsDone = setsDoneActive(A)
  const unitTotal = unitEntries.reduce((n, e) => n + e.sets.length, 0)
  const unitDone = unitEntries.reduce((n, e) => n + e.sets.filter(s => s.done).length, 0)
  const unitComplete = unitTotal > 0 && unitDone === unitTotal
  const lastUnit = unitIdx >= units.length - 1
  const done = A.entries.every(e => e.sets.length && e.sets.every(s => s.done))

  const lineOf = e => {
    const t = { ...(e.target || {}), id: e.id, sets: e.sets.length || (e.target || {}).sets || 1 }
    const first = e.sets[0] || {}
    if (t.reps == null && first.r != null) t.reps = first.r
    if (t.weight == null && first.w != null) t.weight = first.w
    if (t.sec == null && first.sec != null) t.sec = first.sec
    if (t.min == null && first.min != null) t.min = first.min
    if (t.speed == null && first.speed != null) t.speed = first.speed
    return exLine(t, S.unit)
  }

  // canComplete: show "Complete" on the watch?
  // - current unit not done yet: false (nothing to complete)
  // - unit done, more units follow: true ("Next exercise")
  // - unit done, it's the last unit: true ("Finish workout") — phone handles finish
  // - entire workout done (all units): false ("Finish on phone" message shown)
  const canComplete = unitComplete ? !done : false

  return {
    id: A.id,
    name: A.name || 'Workout',
    title: unitEntries.map(e => exOr(e.id).n).join(' + '),
    targetLine: unitEntries.map(lineOf).join('  ·  '),
    position: 'Exercise ' + (unitIdx + 1) + ' / ' + units.length,
    progressText: setsDone + ' / ' + setsTotal,
    unitSetsText: unitDone + ' / ' + unitTotal + ' sets',
    setsDone,
    setsTotal,
    done,
    unitComplete,
    lastUnit,
    canComplete,
    completeLabel: lastUnit && unitComplete ? 'Finish workout' : 'Next exercise',
    restSec: S.restSec || 90,
    startedAt: A.start || null
  }
}

export function watchPushActive(snapshot) {
  if (!MOBILE) return pushChain
  const json = snapshot ? JSON.stringify(snapshot) : 'null'
  if (json === lastActiveJson) return pushChain
  lastActiveJson = json
  pushChain = pushChain
    .then(() => withPlugin(p => p.pushActive({ json })))
    .catch(() => { if (lastActiveJson === json) lastActiveJson = null })
  return pushChain
}

export function watchStatus() {
  return withPlugin(p => p.status()).catch(() => null)
}

export function watchStart() {
  return withPlugin(p => p.start()).catch(() => null)
}

export function watchStop() {
  return withPlugin(p => p.stop()).catch(() => null)
}

export function watchSyncNow() {
  return withPlugin(p => p.syncNow()).catch(() => null)
}

export function watchOpenSettings() {
  return withPlugin(p => p.openSettings()).catch(() => null)
}
