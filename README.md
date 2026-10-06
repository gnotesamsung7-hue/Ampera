# Ampera — the fellowship's navigator: a two-eyed, 8-bit face-tracking companion (Android)

**Ampera** is a standalone app built on Voltnutt 1.1 (EyeBot v4). App ID `com.example.ampera`, so it
installs **next to** Voltnutt and EyeBot. She has **two pink 8-bit eyes with pixel lashes**: the eyes
are drawn into a low-resolution bitmap and scaled up without smoothing, so every blink, lid, heart
and spiral turns into chunky pixels (`VectorFaceView.pixelEyes`, `PIXEL_ROWS`). Her voice is a bright,
natural teen voice (phone TTS pitch 1.25, no robot effect; optional Fish Audio voice). Colour cards can recolour her eyes.

Settings: hold **two fingers** on the face. Talk: **long-press**, or turn on "Hey Ampera".

Note: Ampera keeps her own data, so faces have to be learned again here.
The pan-tilt stand and robot boards use the same firmware as EyeBot (BLE name `EyeBot-PanTilt`).

## Voice & speaking style

Ampera talks like a friendly, expressive 17-year-old on a voice call: short, punchy turns, casual
phrasing ("totally", "kinda", "so true"), little fillers ("Um,", "Oh!", "Wait— hm."), warm and calm.
She pauses about 0.4 s before answering. Phone numbers are read in chunks ("604... 283... 9124"),
websites and short abbreviations are spelled ("a-i dot com", "Q-R") — `SpeechFormat` in `AmperaPersona.kt`.

**Natural voice (optional): Fish Audio.** Settings ▸ Talk & chat ▸ *Natural voice (Fish Audio)*:
paste your API key (fish.audio ▸ API Keys), then *Search voices…* (e.g. "teen girl") or paste a voice
ID, pick a model (`s2.1-pro` default), and *Test voice*. Uses `POST https://api.fish.audio/v1/tts`
(`FishVoice.kt`), pay-as-you-go (about $15 per million characters). Every line is cached on the phone
so repeats are free; with no key, no internet or an error she falls back to the phone's own voice.
The key is stored only on the phone.

## Personality

Ampera is a knee-high navigator robot from Project Aegis and the map-reader of the fellowship
(**Voltnutt, Sparky, Unit 7, Whirr**). She charts the route through factory after factory to the
**Origin Press**, where they hope to print **Thorne** (who lives inside Voltnutt) a new body.
Bright, clever, warm, a little bossy; loves maps, stars and counting things (she keeps a chart of
Voltnutt's knee squeaks).

- All her lines live in `AmperaPersona.kt`; conversation in `ChatBrain.kt`.
- Tics like *[Compass ping]*, *[Map rustle]*, *[Recalculating...]* show in the bubble but are never spoken.
- About 15 % of the time a dreamy *[Star chart]* line slips in.
- Safety lines (drowsiness, back seat, heat, critical battery) stay short and plain.

## Talk-back (Ampera 1.0)

Long-press the face (or press **T** on a keyboard) and speak. Optional: Settings ▸ Talk & chat ▸
*Listen for "Hey Ampera"* keeps the mic listening in the background (uses more battery).
Uses Android's built-in speech recogniser (offline once the language pack is installed); nothing
is sent anywhere by the app itself.

- `ChatBrain.kt` (pure Kotlin, unit-tested): jokes, riddles (he waits for your answer), knock-knock
  jokes both ways, rock-paper-scissors, guess-my-number, a 10-part factory story that remembers where
  you got to, facts, songs, simple maths, time/day/battery, lore Q&A (Thorne, Sparky, Unit 7, Whirr),
  feelings, a few Tagalog phrases (kumusta, salamat, mahal kita), and commands (party, sleep, food).
- Banter: every few minutes she starts something himself (joke offer, story teaser, question about
  your day or favourite food, time-of-day remark, squeak counter). Questions reopen the mic.
  Settings ▸ Talk & chat ▸ Chattiness: Quiet / Normal / Chatty.
- Replies are a quick chirp followed by clear words (so riddles are understandable), plus the bubble.
- `VoiceListener.kt` wraps SpeechRecognizer; she pauses listening while she talks.

## Build (GitHub Actions)

1. Create a new **private** repo called `Ampera`.
2. Upload everything in this folder (including the hidden `.github` folder: on the upload page,
   drag the whole unzipped folder contents in).
3. Actions ▸ latest run ▸ Artifacts ▸ **ampera-debug** → unzip → install the APK.

## What's new in v4

| Feature | What it does | Where |
|---|---|---|
| **Beep voice** | Talks in R2-style chirps: one beep per syllable, the same word always has a similar tune, "?" rises, "!" bounces. Mood sets pitch & speed. Modes: beeps + speech bubble (default), beeps only, beeps then words, words | `BeepSpeech.kt`, `VoiceBox.kt` |
| **Household faces** | Learns up to 15 people (MobileFaceNet, on-device). Each gets a signature melody, an optional favourite eye colour and birthday song. Guests auto-forget after 7 days. Keeps learning as kids grow | `FaceRecognizer.kt`, `FaceRegistry.kt`, `PeopleUi.kt` |
| **Animals** | Dog, cat, bird, cow, sheep, horse, bear, teddy bear (+ elephant/zebra/giraffe) get a pixel-art icon, a sound and a reaction; the eyes and the stand follow pets | `AnimalDetector.kt`, `PixelIcons.kt` |
| **Lasting mood** | Happiness / energy / social drift over the day. Petting, food, parties and familiar faces cheer it up; ignore it and it greets you with a grumpy "hmph" | `Mood.kt` |
| **Car Mode** | Detects driving by GPS speed. While moving: calm face, no games / touch / QR / bubbles, eyes mostly on the road (looks away if you stare >2 s), dim at night, drowsiness watch (microsleeps, heavy blinking, yawns), 2-hour break reminder, bump/brake reactions, "we're here", back-seat reminder if passengers or pets rode along | `CarLogic.kt`, `CarModeController.kt` |
| **Power antics** | Plug in a charger → golden power-up aura, charge-up hum and burst. ≤15 % → tummy rumbles and a "Charge me pls" card. Hot phone → sweating; very hot → camera rests until it cools | `CarLogic.kt` (`PowerWatch`), `VectorFaceView.kt` |
| **Wi-Fi robot link** | UDP link to ESP8266/ESP32 boards (auto-discovery on Wi-Fi or your hotspot) — the LAFVIN spider uses an ESP8266, which has no Bluetooth | `ServoLink.kt` (`WifiServoLink`), `firmware/esp8266_link` |
| **Eye size** | Small (50 %, default) / Medium / Large (v3 size) | Settings ▸ Eye size |

### Settings (two-finger hold on the face)

People EyeBot knows · Voice · Eye size · Car mode (Automatic / Always on / Off) · Features
(sounds, voice, robot voice effect, face recognition, animals, colour cards, QR cards, servos,
invert pan/tilt) · Robot link (Wi-Fi / BLE / USB).

### Learning a face

Settings ▸ People ▸ **Learn a new face** → the person looks at the camera; the speech bubble
says "look at me… turn a little left… now right" while it collects 8 samples (~3–5 s) →
type their name (tick *Guest* for visitors). From then on it greets them with their melody and
name, at most once every 10 minutes. Tap a person to play / re-roll their melody, rename,
set a favourite eye colour or birthday, re-learn, or forget them.

Privacy: only face fingerprints (192 numbers per sample) are stored, in the app's private
storage on this phone. No photos. "Forget" deletes them.

### Car Mode safety notes

* **Automatic** turns on above ~10 km/h for 5 s and off after 60 s stopped (red lights don't
  count). GPS only runs while the phone is charging or a trip is in progress, so it doesn't
  drain the battery on your desk. Needs the location permission.
* While driving, safety messages (drowsiness, break, back seat, heat) are always spoken in
  clear words, whatever the voice mode.
* Mount the phone low, where it doesn't block the windshield, and follow your local rules on
  phone mounts. EyeBot's drowsiness alerts are a helper, not a safety system — if you feel
  sleepy, stop and rest.
* Dashboards get very hot in the sun. EyeBot sweats at 42 °C battery temperature and rests its
  camera at 46 °C (or when Android reports severe heat). A vent mount or shade helps a lot.

### Models

* `app/src/main/assets/mobilefacenet.tflite` — MobileFaceNet face embeddings (MIT; from the
  `capacitor-face-recognition` npm package), bundled.
* `efficientdet_lite0.tflite` — MediaPipe EfficientDet-Lite0 (Apache-2.0). **Downloaded
  automatically at build time** by the `downloadAnimalModel` Gradle task. If the download
  fails the app still builds and animal spotting is simply off (the debug readout shows
  `animals=-`).

New dependencies: `org.tensorflow:tensorflow-lite:2.16.1`, `com.google.mediapipe:tasks-vision:0.10.14`.

---

*Everything from v3 still works:* QR cards and mini-games, colour-card eyes, touch & buttons,
idle curiosity and screensaver, SoundPool chirps, pan-tilt tracking. The v3 reference follows.

## Project layout

```
EyeBot/
├── .github/workflows/android.yml   CI: unit tests + debug & release APKs on every push
├── app/build.gradle.kts            CameraX 1.4.1, ML Kit face + barcode, usb-serial-for-android
├── app/src/main/
│   ├── AndroidManifest.xml         camera, BLE, USB host (optional), TTS query
│   ├── res/xml/usb_device_filter.xml   auto-open when an ESP32 is plugged in over OTG
│   └── java/com/example/eyebot/
│       ├── MainActivity.kt         camera, permissions, wiring, keys, settings dialog
│       ├── FaceTrackingAnalyzer.kt ★ faces + QR + motion + colour in one analyzer
│       ├── VisionTypes.kt          FaceObservation, VisionFrame
│       ├── ColorDetector.kt        ★ YUV plane sampling of the target region
│       ├── ColorClassifier.kt      ★ HSV buckets + debouncing (pure Kotlin)
│       ├── QrCommand.kt            ★ QR / serial command parser (pure Kotlin)
│       ├── CompanionBrain.kt       rules: observations + cards + touch + time -> emotion / mode
│       ├── VectorFaceView.kt       custom View: eyes, colour, party, dim, animations
│       ├── Emotion.kt              Emotion enum + per-emotion eye poses
│       ├── MotionDetector.kt       frame-difference wave / circle gestures (v2)
│       ├── TouchInterpreter.kt     gestures -> pet / boop / poke / fling; Settings
│       ├── ToneSynth.kt            sound design, WAV encode/decode, robot voice FX (pure Kotlin)
│       ├── AudioPersonality.kt     SoundBank (SoundPool) + emotion -> sound mapping
│       ├── RobotVoice.kt           Text-to-Speech wrapper
│       ├── PanTiltController.kt    face offset -> servo angles (pure Kotlin)
│       ├── ServoLink.kt            BLE (Nordic UART), USB-serial and Wi-Fi (v4) links
│       ├── BeepSpeech.kt / VoiceBox.kt         v4 robot-language voice
│       ├── FaceRecognizer.kt / FaceRegistry.kt / PeopleUi.kt   v4 household faces
│       ├── AnimalDetector.kt / PixelIcons.kt   v4 animals + icons
│       ├── Mood.kt                 v4 lasting mood
│       └── CarLogic.kt / CarModeController.kt  v4 Car Mode, power & heat
├── app/src/test/…                  JUnit tests for the pure-Kotlin parts
├── firmware/esp32_pantilt/         Arduino sketch for the pan-tilt stand (BLE / USB)
├── firmware/esp8266_link/          v4: Wi-Fi (UDP) sketch for ESP8266 stands / the spider robot
└── qr-cards/                       printable cards + generator
```

## Build

**GitHub Actions:** push to `main`. The workflow runs the unit tests, then builds debug and
release APKs (download from the run's **Artifacts**: `eyebot-v4-debug`, or `eyebot-debug` if your repo still has the older workflow file). Tag `v4.0` to attach
them to a GitHub Release. Signing secrets are the same as v2 (`KEYSTORE_BASE64`, …).

**Locally:** open the folder in Android Studio (Ladybug or newer) and press Run, or
`./gradlew assembleDebug`.

New dependencies: `com.google.mlkit:barcode-scanning:17.3.0` (bundled, offline) and
`com.github.mik3y:usb-serial-for-android:3.8.1` from **JitPack** (repository already added in
`settings.gradle.kts`).

## Using it

### Touch

| Gesture | Reaction |
|---|---|
| Stroke / rub the face (top = its head) | **Pet** → CONTENT: squinting smile, purring, repeats while you stroke |
| Quick tap on the top 30 % (head) | **Boop** → HAPPY chirp |
| Quick tap on the eyes / lower face | **Poke** → SURPRISED gasp, looks at your finger, then CURIOUS |
| 3 taps within 1.2 s | CONFUSED, "Hey! That tickles!" |
| Fast fling | EXCITED "whee", eyes follow the flick |
| Double tap | camera preview + debug readout (emotion, mode, QR, colour, servo angles) |
| Long press | **talk to Ampera** (mic opens for one sentence). Demo mode moved to Settings ▸ Talk & chat |
| Two-finger tap | mute / unmute sounds and voice |
| **Two-finger hold** | **Settings**: sounds, voice, robot effect, colour eyes, QR cards, servos, invert pan / tilt, auto-connect, **Connect BLE / Connect USB** |

### Physical buttons (Bluetooth or USB keyboard, remote, game pad, selfie shutter)

| Key | Reaction |
|---|---|
| Space, Enter, D-pad centre, A, play/pause, headset button | Pet |
| X, B (key), media previous | Boop |
| B (pad), P, media next, camera | Poke |
| Y (game pad), F | Fling / excited |
| Arrow keys | Look left / right / up / down |
| 1…9, 0 | Show emotion #1–10 for 4 s |
| S / W / Y / H / E | Sleep / Wake / partY / Hungry (feed-me) / Eat |

### QR cards (any QR generator works – the text is the command)

| Payload | Effect |
|---|---|
| `HAPPY`, `CURIOUS`, `SLEEPY`, `EXCITED`, `SAD`, `LOVESTRUCK`, … (any emotion; `EMOTION:X` also works) | Instantly shows that emotion; held for 4 s after the card leaves view |
| `FEED_ME` | HUNGRY (pleading eyes, tummy rumbles) until you show a food card; SAD after 45 s |
| `FOOD`, `FOOD:PIZZA`, `APPLE`, `COOKIE`, `MANGO`, … | While hungry: chomps (EATING) then HAPPY, "Yum, pizza!" Otherwise "I'm full" |
| `SLEEP` / `WAKE` | Dark low-brightness sleep (ignores faces, only WAKE or a tap wakes it) / wake |
| `PARTY` | 20 s of rainbow eyes, bouncing, party jingles, servo dance (show again to extend) |
| `COLOR:RED`, `COLOR:#00FFAA`, `COLOR:RESET` | Sets the base eye colour |
| `SAY:any text` | Speaks the text |
| anything else | CURIOUS, "Ooh, a mystery code!" |

An optional `EYEBOT:` prefix is ignored, so `EYEBOT:HAPPY` works too.

### Colour cards

Hold a solid, saturated card so it fills the **middle of the camera view** (the face is
masked out). After ~4 frames (~0.3 s) the eyes fade to the card's hue; remove it and they
fade back to teal (or the `COLOR:` card colour). Skin tones and orange are deliberately
ignored. Tune in `ColorClassifier.kt` (`ColorBucket` saturation/value minimums,
`minCoverage`) and `ColorDetector.target`.

### Behaviour timeline when nobody is around (`CompanionBrain` constants)

| Time alone | Behaviour |
|---|---|
| 3 s | SEARCHING (eyes sweep, servo scans), CONFUSED blips |
| every 5–10 s | random idle act: look around, peek, heavy blink, sneeze, yawn |
| 15 s | BORED (sighs, eye rolls) |
| 35 s | SLEEPY (Z z z, snoring, yawns) |
| 60 s | **Screensaver**: dims the screen and redraws at 10 fps; camera analysis drops to every 3rd frame. Any face, wave, tap or card wakes it with a greeting |

## Pan-tilt stand (optional)

Parts: ESP32 dev board, 2 × SG90 / MG90S servos (or a pan-tilt bracket kit), a separate 5 V
supply for the servos (shared ground), optional coin / copper-tape touch pad.

1. Arduino IDE → install the **esp32** board package and the **ESP32Servo** library.
2. Open `firmware/esp32_pantilt/esp32_pantilt.ino`, check the pins at the top (pan 18,
   tilt 19, touch 4), upload.
3. In EyeBot: two-finger hold → **Connect BLE** (allow Bluetooth permission). Or plug the
   board in with a USB-OTG cable → EyeBot opens and asks for USB access → allow.
4. If the stand turns *away* from you, tick **Invert pan** (and/or **Invert tilt**).

Protocol (115200 baud or BLE Nordic UART service `6E400001-…`):

* phone → board: `S<pan>,<tilt>\n` in degrees, e.g. `S97,84` (max 25 per second, only on change)
* board → phone: `TOUCH:HEAD` (pet), `BTN:2` (boop), `BTN:3` (poke), `TOUCH:POKE`, or any
  QR-style command (`PARTY`, `SAY:Hi`)

Tuning: `PanTiltController.Config` (gain 70 °/s at full offset, dead zone 8 %, limits
pan 15–165°, tilt 55–125°) and `MAX_DEG_PER_SEC` in the sketch.

> Android 11 and older need **Location** turned on to scan for BLE devices. ESP32-C3 boards
> have no capacitive touch pins – use a push button instead.

## Tests

`./gradlew testDebugUnitTest` runs the JVM tests for the QR parser, colour classifier,
servo controller and sound synthesiser (also run by CI before building).

## Signed release APK

Same as v2: without secrets the release APK is unsigned (won't install). Add
`KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`
repository secrets. The debug APK is always installable for testing.
