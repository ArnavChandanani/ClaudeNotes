# Boox Notes — v0 (pen + vector capture test)

Minimal Android app to confirm the Onyx Pen SDK delivers stylus strokes on the
Boox Go 10.3, AND that strokes can be captured as vector data. Full-screen drawing
surface, Save (writes a PNG preview + a JSON of the strokes), Clear.

## Build & sideload
1. Open this folder in Android Studio, let Gradle sync (downloads Gradle 8.6.1 and
   the Onyx SDK from repo.boox.com).
2. Enable Developer Options + USB debugging on the Go 10.3, connect via USB.
3. Run `app` on the device. (Or: `./gradlew :app:assembleDebug` then
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`.)

## What to check on device
- Ink appears under the pen with little/no lag -> hardware display path works.
- Draw a few strokes, hit Save. Toast reports "N strokes, M points".
    * N > 0 and note_*.json contains points -> vector capture works. Green light.
    * N = 0 / empty JSON -> hardware draws but callbacks aren't firing (known Go 10.3
      issue). We fix the SDK before building the notebook layer.

Files land in: Android/data/com.example.booxnotes/files/  (pull via file manager or
`adb pull`). Open note_*.json to see the exact stroke format we'll store notes in.

## Stored format (schemaVersion 1)
{ "schemaVersion":1, "page":{ "width":.., "height":.., "strokes":[
    { "tool":"fountain","color":"#000000","width":3.0,
      "points":[ {"x":..,"y":..,"p":pressure,"t":timestamp}, ... ] } ] } }

## Import paths
Onyx class packages shift between SDK versions. If TouchPoint / TouchPointList /
RawInputCallback / TouchHelper won't resolve, let Android Studio pick the import.
