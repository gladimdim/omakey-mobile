# Omakey mobile

Phone apps that turn your phone into a real keyboard for an
[Omarchy](https://omarchy.org/) desktop. Every touch becomes a key press on a
kernel-level virtual keyboard made by `omakeyd`, so Hyprland binds,
`SUPER + SPACE`, F-keys, Esc and the lock screen all work as they do with a
Bluetooth keyboard.

| Directory  | Status |
|------------|--------|
| `android/` | Working: pairing, discovery, Classic QWERTY, layout import |
| `ios/`     | Planned (M3): SwiftUI + UIView keyboard, Network.framework, CryptoKit |

The desktop side lives in `omakey-omarchy-plugin` and the layout editor in
`omakey-layout-studio`. The wire protocol is specified in
`omakey-omarchy-plugin/docs/PROTOCOL.md`, the layout format in
`omakey-layout-studio/spec/LAYOUT.md`.

## Android

Kotlin, minSdk 29, target 35. Two Gradle modules:

- `protocol/` is plain Kotlin/JVM with no Android APIs: packets, AES-256-GCM,
  HKDF-SHA256, the handshake state machine (`ClientSession`), the held-key set
  and resend queue (`KeyState`), and pairing-link parsing. It is tested
  against the daemon's `docs/test-vectors.json` byte for byte.
- `app/` is the Android app.

```
android/
  protocol/src/main/kotlin/com/gladimdim/omakey/protocol/
    Packets.kt        wire format: header, HELLO/WELCOME/INPUT/ACK/BYE/REJECT
    Crypto.kt         HKDF-SHA256, AES-256-GCM, session key derivation
    ClientSession.kt  socket-free client state machine
    KeyState.kt       held set (ascending, ref-counted) + un-acked event queue
    Pairing.kt        omakey://pair link parser, HostRecord, hex
  protocol/src/test/  ProtocolTest, TestVectorsTest (+ resources/test-vectors.json)
  app/src/main/java/com/gladimdim/omakey/
    keyboard/KeyboardView.kt   custom View: drawing + raw multi-touch
    keyboard/KeyboardModel.kt  pointer → key/layer logic (unit-tested)
    layout/Layout.kt           layout model, parser, validation, keycode table
    layout/LayoutLink.kt       omakey://layout?d=… (raw DEFLATE, base64url)
    net/KeyboardLink.kt        UDP thread: timing rules, reconnects, ping
    net/Discovery.kt           NsdManager browse/resolve of _omakey._udp
    store/Stores.kt            paired hosts, built-in + imported layouts
    ui/MainActivity.kt         connect screen, pairing, imports, deep links
    ui/KeyboardActivity.kt     fullscreen keyboard, Wi-Fi lock, status pill
  app/src/main/assets/         keycodes.json, layouts/*.json (synced from the studio)
```

### Build

```bash
cd android
./gradlew assembleDebug testDebugUnitTest :protocol:test
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17+ and the Android SDK (`android/local.properties` with
`sdk.dir=…`, not committed).

After the layout spec changes in the studio repo, refresh the bundled copies:

```bash
scripts/sync-spec.sh            # defaults to ../omakey-layout-studio
```

### Using it

1. On the desktop, click the keyboard icon in the bar → **Pair phone**, or run
   `omakeyd pair`.
2. In the app, tap **Scan QR code** (or copy the `omakey://pair…` link and tap
   **Paste link**). The keyboard opens.
3. Next time, tap the computer in **Paired computers**. If its IP changed, the
   app finds it again over mDNS by its host id.

Long-press a paired computer to forget it. **Layout** picks the active layout
or imports a `.json` file; `omakey://layout?d=…` links and layouts shared from
other apps are imported too.

### How it stays fast

- Keys fire on touch-down (`ACTION_DOWN` / `ACTION_POINTER_DOWN`). Each
  pointer holds one key until it lifts, and the code a finger pressed is the
  code its lift releases, even if Fn was let go first.
- The touch thread only updates `KeyState` and wakes the network thread's
  `Selector`. That thread sends at once, resends every 20 ms until ACKed,
  and heartbeats every 100 ms. Packets are marked DSCP EF so Wi-Fi WMM
  queues them as voice traffic.
- While the keyboard is visible the app holds a
  `WIFI_MODE_FULL_LOW_LATENCY` Wi-Fi lock; otherwise power save can delay
  packets by 100 ms or more.
- Pausing the app releases every key and leaving sends BYE; the daemon also
  lets go after 500 ms of silence.

### Decisions

- **QR scanning: `zxing-android-embedded`.** It works on phones without
  Google Play services and needs no network model download; the Google code
  scanner would be smaller but ties pairing to Play services.
- **Storage:** paired computers (including their 256-bit keys) are JSON in
  app-private SharedPreferences. Backups and device transfer are disabled
  so keys never leave the phone. A future step is wrapping them with an
  Android Keystore key.
- **No Compose.** The keyboard must be a raw `View` for touch latency, and
  the two connect screens are small enough to build in code, keeping the
  APK and build lean.

## iOS (planned)

Same protocol and layouts: SwiftUI shell, a `UIView` keyboard using
`touchesBegan/Ended`, `NWConnection` UDP, `NWBrowser` for Bonjour,
AVFoundation QR scanning, CryptoKit `AES.GCM` + `HKDF`. The protocol test
vectors apply unchanged.
