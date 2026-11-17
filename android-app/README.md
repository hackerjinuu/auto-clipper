# Auto Clipper — native Android app

A fully native Kotlin + Jetpack Compose port of the Auto Clipper web editor (`index.html`).
No WebView: decoding, transcription, rendering and export all run natively on the phone.

## Open & run
1. Android Studio (Ladybug 2024.2 or newer) → **File › Open** → this folder.
2. Let Gradle sync (it downloads Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21). If Studio offers to upgrade AGP, accept.
3. Run on a real phone (Android 10 / API 29 or newer, arm64 recommended).

## Flow
Login (same backend as the site) → **one-time model download** → editor.

## The transcription model (downloaded once, then always local)
* Same files your website loads from Cloudflare R2:
  `encoder-model.int8.onnx`, `decoder_joint-model.int8.onnx`, `vocab.txt`
  (NVIDIA Parakeet TDT 0.6B v2, INT8, ≈670 MB).
* Downloaded by a WorkManager foreground job: survives leaving the app, resumes after a dropped
  connection (HTTP Range), verifies sizes, then writes a `ready.json` marker.
* Stored in the app's private storage (`files/models/parakeet-tdt-0.6b-v2-int8/`). It is never
  downloaded again unless you delete it (Settings › Manage Parakeet model) or clear app data.
* Runs with ONNX Runtime Android (CPU, 4 threads). The session is created once and kept in memory.
  On first load ONNX Runtime also saves an optimised copy (`encoder.optimized.onnx`) so later app
  starts load faster. (This uses roughly another 650 MB of storage; delete it any time, it is rebuilt.)
* The NeMo log-mel front end and TDT greedy decoding are ported from `onnx-asr` (the same model
  format parakeet.js uses); the mel features were checked against the reference implementation.
* Long audio is split at the quietest point every 20–30 s, so memory stays low on phones.

## Feature map (web → app)
| Web editor | Android |
|---|---|
| Source video, start, max length | `EditorViewModel.pickSource`, Settings |
| Auto-cut silence (strength, min gap) | `Core.makeRanges` on a 20 ms loudness envelope |
| ✨ Find hook lines (first 3 min, top 3, tap any line) | `findHooks`, `Core.hookScore`, `applyStart` |
| Frame ratio / hook text / handle / position sliders | `Renderer.layout`, `frameText` |
| Transcript (SRT, VTT, `[0:05]`, plain text) | `Core.parseTr` |
| Auto-transcribe (Parakeet) | `asr/ParakeetEngine` |
| Blur list + bleep | `Core.blCompile/markBlur`, blur in `Renderer`, bleep in `AudioMixer` |
| Captions: kinetic / karaoke / box / minimal / red keyword | `Renderer.drawCaptions` |
| Auto B-roll from captions (filename keywords, aliases, rules) | `BrMatch.index/events/plan` |
| Manual B-roll + auto-place | `addManualBroll`, `autoPlace` |
| Zoom in / out | `Core.zoomK` |
| Auto BGM (scored, ducked, faded) / Auto Hook SFX / transition SFX | `Library.scoreBgm`, `placeSfx`, `placeTrans`, `AudioMixer` |
| Timeline + edit report | `ui/Timeline.kt`, `report()` |
| Export 1080p / 4K, 30 / 60 fps, MP4 H.264 + AAC | `media/Exporter.kt` → saved to *Movies/AutoClipper* |

## Library (bgm, best hook, transitionsfx, broll)
Settings › Library source:
* **GitHub** (default `hackerjinuu/auto-clipper`, branch `main`): reads the folders straight from the
  repo. Add a token if the repo is private.
* **Website**: your deployed site address; reads `library.json` + `broll.json` (from `make-manifest.js`).
* **Phone**: pick a folder that contains the four folders.

Remote files are downloaded once into the app and reused; analysis (energy envelopes) is cached too.

## If the app ever closes by itself
On the next launch it shows a report (crash trace, or Android's reason such as out of memory, plus the
last actions). Tap **Copy** and send it. Code: `CrashLog.kt`.

## Frames (video shapes)
Frames tool: pick a shape and the video shows inside its white part (soft edges, preview and export).
Built in: Squircle, Rounded, Retro TV (4:5), Wide 16:9. Add your own from the phone (white shape on a
black picture), or put pictures in a `frames/` folder in R2 and run `node make-r2-manifest.js` again.
Code: `editor/Frames.kt`, masking shader in `gl/GlCanvas.kt`.

## SFX tool
Listen to every hook SFX and transition sound, then **Add** it at the playhead (its loudest moment lands
exactly there). Auto hook SFX / Auto transitions still work; sounds you add or edit are kept when autos re-run.
Tap a sound on the timeline to move it, change its volume or delete it.

## GPU engine (preview + export)
* `gl/` — one OpenGL ES 3 context (`EglCore`), a tiny shader canvas (`GlCanvas`) and hardware video
  decoding straight into GPU textures (`VideoTexture`, no CPU copies).
* `editor/Compositor` builds every reel frame on the GPU; text (hook, handle, captions, blurred words)
  is painted once into textures and only animated per frame.
* Preview draws to the screen with audio as the master clock; sliders update live while playing.
* Export draws the same frames into the hardware H.264 encoder with exact timestamps
  (`eglPresentationTimeANDROID`), as fast as the phone allows.

## R2 asset pack (music, SFX, B-roll)
1. In your website folder run `node make-r2-manifest.js` → writes `assets.json`.
2. Upload `assets.json` plus the folders `bgm/`, `best hook/`, `transitionsfx/`, `broll/` to the ROOT
   of the same R2 bucket as the model.
3. The app downloads model + pack together (Settings › Model & assets), checks SHA-256, analyses the
   audio once, and afterwards Auto BGM / Hook SFX / B-roll run locally and instantly.
   "Check R2 for new music / B-roll" downloads only changed files.

## Code layout
```
asr/      NemoMel (log-mel), ParakeetEngine (ONNX Runtime + TDT decoding)
model/    ModelManager + resumable ModelDownloadWorker
gl/       EglCore, GlCanvas (shaders), VideoTexture (decoder → GPU texture)
media/    AudioDecoder (MediaCodec + resampler), Exporter (AAC + H.264 encoder/muxer)
editor/   Core (pure ported logic), BRoll, Library, Renderer, AudioMixer, PreviewEngine, EditorViewModel
ui/       Compose screens: login, model, editor, timeline, dialogs
```

## Notes
* Preview plays the exact export mix (speech, bleeps, music ducking, SFX) and draws with the same
  renderer as the export, so what you see is what you export.
* Video frames are decoded by the hardware decoder straight into GPU buffers. If a phone does not
  support that, the app automatically falls back to a CPU path.
* Fonts: Android has no Impact / Arial Black / Georgia, so the caption fonts use the closest system
  fonts (condensed black, black, serif). Drop a .ttf into `res/font` and change `Renderer.captionFont`
  to use your exact font.
