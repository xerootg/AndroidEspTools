# AndroidEspTools

A native Android flasher for Espressif chips. Plug a dev board into your phone with a USB OTG
cable and do everything `esptool.py` does: detect the chip, flash one or many images, dump and
erase flash, verify, inspect images, poke registers, and watch the serial console.

Built for the [RapidRawCloud ingest firmware](https://github.com/xerootg/RapidRawCloud/releases)
on the Waveshare ESP32-P4-WIFI6-POE-ETH, but it is a general-purpose tool.

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
  manifest), direct URLs, and GitHub release assets (the RapidRawCloud releases are one tap away).
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
- Full protocol log with trace mode for debugging a stubborn board.

## Flashing the RapidRawCloud firmware

1. Connect the board's USB-C console port to the phone (OTG). Hold **BOOT** while tapping
   **RST** if the board does not enter download mode on its own.
2. *Device* tab: tap **Connect** on the port. The P4 is detected and the stub uploaded.
3. *Flash* tab: **GitHub release** → fetch `https://github.com/xerootg/RapidRawCloud/releases/latest`
   → **Get** the `rrcloud-ingest-*.zip` → pick **Merged image** (first install) or **App only**
   (update that keeps NVS and the LittleFS ledger).
4. Tap **Flash**. The chip resets into the new firmware; open the *Monitor* tab to watch it boot.

## Project layout

| Module | What |
| --- | --- |
| `esploader/` | Pure Kotlin/JVM port of the esptool protocol: SLIP, ROM/stub commands, chip definitions, stub upload, compressed writes, MD5 verify, flash reads, image parsing, manifest parsing. No Android dependencies, unit-tested against an in-memory ROM/stub emulator. |
| `app/` | Jetpack Compose UI, USB transport (usb-serial-for-android with a dedicated reader thread), session/operation runner, firmware import. |

## Building

```
./gradlew :esploader:test :app:assembleDebug
```

Requires JDK 17+ and the Android SDK (platform 35). CI builds every push; tagging `vX.Y.Z`
builds a release APK and publishes a GitHub release.

### Release signing

Add these repository secrets (same names as RapidRawCloud's Android build) to get a properly
signed APK: `ANDROID_KEY_BASE64` (base64 of the `.jks`), `ANDROID_KEY_ALIAS`,
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
