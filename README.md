# AndroidEspTools

A native Android flasher for Espressif chips. Plug a dev board into your phone with a USB OTG
cable and do everything `esptool.py` does: detect the chip, flash one or many images, dump and
erase flash, verify, inspect images, poke registers, and watch the serial console.

It targets the whole ESP family, including the newer RISC-V parts and the ESP32-P4, and works
with both external USB-UART bridges and the chips' built-in USB interfaces.

## Features

- **Chips**: ESP8266, ESP32, ESP32-S2, S3, C2, C3, C5, C6, C61, H2, H21, H4, P4, S31
  (auto-detected via chip id / magic register, exactly like esptool).
- **Bridges**: Espressif USB-Serial/JTAG and USB-OTG, CP210x, CH340/CH343/CH9102, FTDI,
  PL2303 and any CDC-ACM device. Auto-reset over DTR/RTS (classic and USB-JTAG sequences) or
  manual BOOT/RESET.
- **Flasher stub**: uploads Espressif's stub for fast, compressed transfers, MD5 verification,
  whole-chip erase and fast flash reads. ROM-only mode is available when the stub cannot be used
  (secure download mode, ESP32-C2 rev 0, your choice).
- **Multi-image flashing** with offsets, bootloader header patching (flash mode/size/frequency with
  SHA-256 re-computation), chip and revision compatibility checks, erase-all, force.
- **Import** `.bin` files, ESP-IDF build zips (`flasher_args.json` is honoured for offsets and flash
  settings), ESP Web Tools `manifest.json` (local zip or URL, parts downloaded relative to the
  manifest), direct URLs, and assets of any GitHub release (paste the release page URL).
  Choose "merged image", "components" or "app only" for ESP-IDF bundles.
- **Dump** any flash region to a file (streamed, MD5 logged), **erase** chip or region,
  **verify** a file against flash with the chip-side MD5.
- **Advanced**: image info (header, segments, `esp_app_desc` / bootloader descriptor), read/write
  flash status registers, read/write/dump memory, load an image to RAM and run it, security info
  (secure boot, flash encryption, JTAG, key purposes), hard / watchdog / soft reset.
- **Serial terminal**: a real VT100/xterm emulator (Termux's terminal-view) so interactive
  consoles (`esp_console`, linenoise editing and history, colours) work like `idf.py monitor`.
  Configurable baud, reset / enter-bootloader buttons, Ctrl / Esc / Tab / arrow extra keys,
  pinch to zoom, text selection and copy, plain-text log export.
- Full protocol log with trace mode for debugging a stubborn board. Crashes are captured into a
  report (stack trace, device, USB devices, log tail) that the app offers to copy, share or save
  on the next launch; Settings → Diagnostics exports the same without a crash.

## Typical workflow

1. Connect the board with a USB OTG cable. Boards with an auto-reset circuit (or a native USB
   port) enter download mode on their own; otherwise hold **BOOT** while tapping **RESET**.
2. *Device* tab: tap **Connect** on the port. The chip is detected, the stub uploaded and the
   flash identified.
3. *Flash* tab: add `.bin` files, an ESP-IDF build zip, a manifest URL or a GitHub release asset.
   Bundles with a `flasher_args.json` fill in the offsets and flash settings; choose
   **Merged image** for a first install or **App only** to keep NVS and data partitions.
4. Tap **Flash**. The chip resets into the new firmware; open the *Monitor* tab to watch it boot
   or to use its console.

## Project layout

| Module | What |
| --- | --- |
| `esploader/` | Pure Kotlin/JVM port of the esptool protocol: SLIP, ROM/stub commands, chip definitions, stub upload, compressed writes, MD5 verify, flash reads, image parsing, manifest parsing. No Android dependencies, unit-tested against an in-memory ROM/stub emulator. |
| `app/` | Jetpack Compose UI, USB transport (usb-serial-for-android with a dedicated reader thread), session/operation runner, firmware import. |

## Building

```
./gradlew :esploader:test :app:assembleDebug
```

Requires JDK 17+ and the Android SDK (platform 35). CI runs the tests, a debug build and lint on
every push.

### Versioning and releases

Every push to `main` is released automatically. The workflow looks at the commits since the last
`v*` tag and bumps the version by their messages (conventional-commit style, semver-ish):

| Commit subject | Bump |
| --- | --- |
| `feat!: …`, `fix!: …` or a `BREAKING CHANGE` footer | major (minor while still on 0.x) |
| `feat: …` / `feat(scope): …` | minor |
| anything else (`fix:`, `docs:`, `ci:`, plain messages) | patch |

The commit is tagged `vX.Y.Z`, a release APK (`versionCode = X*10000 + Y*100 + Z`) is built and a
GitHub release is published with `SHA256SUMS` and the R8 mapping file. The release notes are the
**Unreleased** section of `CHANGELOG.md` followed by GitHub's generated commit list, so describe
user-visible changes there as you go. A head of
`main` that is already tagged is left alone. The workflow can also be run by hand with a forced
bump level or an explicit version (that is how the first release was cut).

### Release signing

Add these repository secrets to get a properly signed APK: `ANDROID_KEY_BASE64` (base64 of the `.jks`), `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`. Without them the release is signed with a throwaway debug key and the
asset is suffixed `-debugsigned`.

Locally, create `keystore.properties` in the repo root:

```
storeFile=/path/to/keystore.jks
keyAlias=upload
password=secret
```

## License

GPL-3.0-or-later. The loader is a port of esptool (GPL-2.0-or-later); the bundled flasher stubs are
Apache-2.0/MIT. See `THIRD_PARTY_NOTICES.md`.
