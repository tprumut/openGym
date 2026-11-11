# Huawei watch sync (Wear Engine P2P)

openGym can drive a Huawei watch from inside the phone app: the watch shows the current
exercise and logs the workout, the phone runs openGym's own progression code and writes the
result through the normal store, so the workout reaches the backup and the server exactly as
one logged on the phone would.

There is no second app to install on the phone. The bridge is a Capacitor plugin
(`WatchBridgePlugin`) plus a foreground service (`P2pService`) in the same APK, and the
Settings card that turns it on lives under **Settings → Data**.

## What you need

- A Huawei phone or tablet with HMS (Wear Engine talks to Huawei Health, not Google Play
  services).
- Huawei Health signed in, with a watch paired to it. The bridge was developed against a
  Watch Fit 5 Pro.
- The openGym watch app (Lite Wearable, `.hpk`) installed on the watch. Its source and the
  AGC application material live in the
  [opengym-companion](https://github.com/tprumut/opengym-companion) repository.
- The watch app's bundle name and signing fingerprint, and the HMS app id AGC issued for the
  phone package, in `frontend/android/local.properties` (see below).

## How it works

```
Watch (Lite Wearable JS/HML)
   ↕ Wear Engine P2P, JSON messages
P2pService (foreground service, connectedDevice)
   ↕ WatchState → the live WebView, or opengym-state.json
openGym store (the same code the phone UI uses)
```

- **`WatchState`** reads and writes the state openGym already keeps. It asks the live WebView
  first (`window.__openGymWatch`, installed by `src/store/useStore.js`) and falls back to
  `opengym-state.json` in the app's private directory when the WebView is gone. A write goes
  to the file first and then into the running UI, so React, the file mirror and the server
  push stay in step.
- **`PlannerEngine`** evaluates `assets/planner.iife.js` in a headless WebView. That bundle is
  openGym's `history.js` + `progression.js`, so the targets the watch shows are the ones the
  phone would show — progression is never reimplemented in Kotlin.
- **`WorkoutMerger`** mirrors `doFinishWorkout()`: it appends once (idempotent on the workout
  id), then computes volume, PR ids and `exWeights` against the freshly read state.
- **`P2pService`** registers the P2P receiver, answers `hello`/`sync`/`ping`/`plan`/`finish`/
  `complete`/`revoke`, and retries the link with a backoff. Its status is published on
  `BridgeBus`, which the Settings card polls through the plugin.
- **`src/lib/watch-bridge.js`** is the page side: it builds a compact snapshot of the active
  workout (`activeSnapshot`), pushes it on every store change, and exposes the plugin calls
  the card uses.

The watch card is Android-only and starts switched off: turning it on asks for the Bluetooth
(nearby devices) and notification permissions the service needs.

## Build inputs

The bridge build reads `frontend/android/local.properties`, which is git-ignored. Without it
the app builds exactly as upstream — the bridge code compiles, the watch simply never
connects.

```properties
# applicationId AGC registered (lets the bridge build sit next to the store app)
app.id=tprumut.com.opengym.bridge.apk
# the watch app's bundle name and signing certificate fingerprint
watch.package=tprumut.com.opengym.watch.hpk
watch.fingerprint=AA:BB:...
# the HMS app id from AppGallery Connect, as `appid=123456789`
hms.app.id=appid=123456789
```

`app.id` only changes the `applicationId`; the namespace, URL scheme and Capacitor `appId`
stay `ch.duartesantos.opengym`.

## `assets/planner.iife.js`

The planner bundle is generated, not hand-written. In the companion repository:

```bash
cd planner
npm ci
npm run build          # writes dist/planner.iife.js
cp dist/planner.iife.js ../android-bridge/app/src/main/assets/
```

Copy that file to `frontend/android/app/src/main/assets/planner.iife.js` here whenever
`history.js` or `progression.js` changes; the phone app loads it as-is.

## Tests

`frontend/src/lib/watch-bridge.test.js` covers the snapshot the watch receives (title,
progress, superset units, when Complete/Finish may show). The Android side has no JVM tests;
the Gradle build is the check that the plugin, the service and the manifest still compile.
