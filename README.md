# Omakey mobile

Phone apps that turn your phone into a real keyboard for an
[Omarchy](https://omarchy.org/) desktop. Every touch becomes a key press on a
kernel-level virtual keyboard made by `omakeyd`, so Hyprland binds,
`SUPER + SPACE`, F-keys, Esc and the lock screen all work as they do with a
Bluetooth keyboard.

**Download:** the signed APK is on the [releases page](https://github.com/gladimdim/omakey-mobile/releases/latest).
It is free to use and redistribute. Website: <https://gladimdim.github.io/omakey-omarchy-plugin/>.

| Directory  | Status |
|------------|--------|
| `android/` | Working: pairing, discovery, Classic QWERTY, layout import |
| `ios/`     | Planned (M3): SwiftUI + UIView keyboard, Network.framework, CryptoKit |

Two ways to connect:

- **omakeyd** (Omarchy, or a Steam Deck with SteamOS): pair with the QR
  code. Wi-Fi first, with a Bluetooth fallback when Wi-Fi can't reach the
  computer.
- **Bluetooth keyboard** (any computer, tablet or TV): the phone becomes a
  standard Bluetooth HID keyboard and touchpad, paired in the computer's own
  Bluetooth settings. Needs a phone that offers the HID Device profile; some
  makers turn it off.

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
    Hid.kt            Bluetooth keyboard mode: HID descriptor, Linux code → usage, reports
  protocol/src/test/  ProtocolTest, TestVectorsTest, HidTest (+ resources/test-vectors.json)
  app/src/main/java/com/gladimdim/omakey/
    keyboard/KeyboardView.kt   custom View: drawing + raw multi-touch
    keyboard/KeyboardModel.kt  pointer → key/layer logic (unit-tested)
    layout/Layout.kt           layout model, parser, validation, keycode table
    layout/LayoutLink.kt       omakey://layout?d=… (raw DEFLATE, base64url)
    net/Link.kt                what the keyboard types through
    net/KeyboardLink.kt        omakeyd over UDP: timing rules, reconnects, ping
    net/RfcommLink.kt          omakeyd over Bluetooth RFCOMM, the same packets framed
    net/FallbackLink.kt        Wi-Fi first, Bluetooth when Wi-Fi doesn't answer
    net/BluetoothHidLink.kt    the phone as a Bluetooth HID keyboard and mouse
    net/Discovery.kt           NsdManager browse/resolve of _omakey._udp
    store/Stores.kt            paired hosts, Bluetooth keyboard hosts, layouts
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

Published APKs are built with `./gradlew assembleRelease` and signed with the
gladimdim-main key (certificate SHA-256 `8B:E4:25:A4:31:79:FB:FB:08:C2:31:3F:4B:C3:0C:26:F7:55:CA:44:BC:C7:22:EE:96:31:A6:77:45:B7:2D:36`),
so each release installs over the previous one. Check a download with
`apksigner verify --print-certs omakey-*.apk`. Signing settings come from the
gitignored `android/keystore.properties` (see `keystore.properties.example`).

After the layout spec changes in the studio repo, refresh the bundled copies:

```bash
scripts/sync-spec.sh            # defaults to ../omakey-layout-studio
```

### Using it

1. On the desktop, click the keyboard icon in the bar → **Pair phone**, or run
   `omakeyd pair`.
2. In the app, tap **Scan QR code** (or copy the `omakey://pair…` link and tap
   **Paste link**). The keyboard opens.
   Before saving, the app shows the computer's name, addresses and a
   fingerprint (`ABCD-1234`) that must match the one under the QR code. A
   link that would replace an existing pairing with a different key gets a
   loud warning: a pairing link from a web page could otherwise redirect
   your typing.
3. Next time, tap the computer under **Select computer to use**. If its IP changed, the
   app finds it again over mDNS by its host id.

Long-press a paired computer to forget it. **Layout** picks the active layout,
shares one (as an `omakey://layout` link, or the JSON when it's large) or
imports a `.json` file; `omakey://layout?d=…` links, files opened from a file
manager and layouts shared from other apps are imported too, after a preview
that says when it would replace an imported layout with the same id.

**⇧** in the keyboard's top bar turns on sticky keys: tap Shift,
Ctrl, Alt or Super and it stays down for the next key (tap twice to lock it,
once more to let go); tap Fn and the next key uses the Fn layer. Held in a
chord, they work as usual. The keyboard follows the computer's Caps Lock
light where it reports one (omakeyd 0.4+, or Bluetooth keyboard mode).

**Copy** and **Paste** (in portrait mode's top bar, and as keys in layouts
such as Omakey Pro) copy what's selected on the computer and paste there.
With an omakeyd that has the clipboard, the phone's clipboard joins in: a
copy lands on the phone too, and Paste brings the phone's clipboard along
when it has something newer than what the two last swapped. Elsewhere they are the computer's own
copy and paste (Ctrl+Insert, Shift+Insert).

### How it stays fast

- Keys fire on touch-down (`ACTION_DOWN` / `ACTION_POINTER_DOWN`). Each
  pointer holds one key until it lifts, and the code a finger pressed is the
  code its lift releases, even if Fn was let go first.
- Over Wi-Fi the touch thread sends the packet itself (a non-blocking UDP
  send), then wakes the network thread, which resends until ACKed (after
  1.5 × the measured ping + 2 ms, 5–20 ms) and heartbeats every 100 ms.
  Packets are marked DSCP EF so Wi-Fi WMM queues them as voice traffic.
- Over Bluetooth a stream write can block, so the touch thread only wakes
  the link's writer thread, which sends the newest state (a burst of
  touchpad moves becomes one packet).
- The touchpad asks for unbuffered touch dispatch, so moves aren't held
  for the next frame and resampled.
- While the keyboard is visible the app holds a
  `WIFI_MODE_FULL_LOW_LATENCY` Wi-Fi lock; otherwise power save can delay
  packets by 100 ms or more.
- Pausing the app releases every key and leaving sends BYE; the daemon also
  lets go after 500 ms of silence.

### Decisions

- **QR scanning: `zxing-android-embedded`.** It works on phones without
  Google Play services and needs no network model download; the Google code
  scanner would be smaller but ties pairing to Play services.
- **Storage:** paired computers are JSON in app-private SharedPreferences;
  each 256-bit pairing key is encrypted with an AES-256-GCM key that lives
  in the Android Keystore and can't be exported. Pairings saved by older
  versions are encrypted on first read. Backups and device transfer are
  disabled, so keys never leave the phone.
- **No Compose.** The keyboard must be a raw `View` for touch latency, and
  the two connect screens are small enough to build in code, keeping the
  APK and build lean.

## Touchpad

Tap or pull down the **⌄ touchpad** handle at the top centre of the keyboard
screen and a touchpad slides down over the keys (pull it back up, tap the
handle or press Back to return):

- one finger moves the pointer; a tap clicks; tap and hold right-clicks;
  tap, then touch again and move to drag with the button held (two quick
  taps double-click). A tap's click waits 150 ms for that second touch.
  Lifting one finger of a two-finger scroll goes back to moving
- two fingers scroll, content following the fingers; a two-finger tap
  right-clicks, a three-finger tap middle-clicks
- down each side, mirrored: Left click, Right click, Ctrl + Left and
  Shift + Left, held while touched (drag, Ctrl-click multi-select,
  Shift-click range select with the other thumb on the pad)
- a strip along the bottom holds the pointer speed: a slider, and a chip
  that picks a preset for your monitor (laptop, 1080p, 1440p, 3440×1440
  ultrawide, 4K, 5120×1440…). Each computer keeps its own speed. Swipe up
  from the strip to put the touchpad away

It needs omakeyd 0.3 or newer on the computer (the bar widget offers the
update); with an older one the touchpad says so.

## iOS (planned)

Same protocol and layouts: SwiftUI shell, a `UIView` keyboard using
`touchesBegan/Ended`, `NWConnection` UDP, `NWBrowser` for Bonjour,
AVFoundation QR scanning, CryptoKit `AES.GCM` + `HKDF`. The protocol test
vectors apply unchanged.
