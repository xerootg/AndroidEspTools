# Changelog

Notable, user-visible changes. The **Unreleased** section becomes the notes of the next release
(see "Versioning and releases" in the README), so add to it with your changes.

## Unreleased

### Highlights
- **Flash any Espressif chip from a phone**: ESP8266, ESP32, S2, S3, C2, C3, C5, C6, C61, H2, H21,
  H4, P4 and S31, auto-detected. Works over USB-UART bridges (CP210x, CH340/CH343/CH9102, FTDI,
  PL2303) and the chips' native USB-Serial/JTAG and USB-OTG ports.
- **esptool-equivalent flashing**: flasher stub upload, compressed transfers, MD5 verification,
  bootloader header patching (flash mode/size/frequency), chip and revision checks, multi-image
  writes, erase-all and force options, ROM-only fallback.
- **Firmware import**: `.bin` files, ESP-IDF build zips (`flasher_args.json` offsets and settings),
  ESP Web Tools manifests, direct URLs and GitHub release assets, with merged / components /
  app-only choices.
- **Tools**: flash dump to file, chip and region erase, verify-against-file, image info
  (segments, app and bootloader descriptors), flash status registers, memory read/write/dump,
  load-to-RAM, security info, hard / watchdog / soft reset.
- **Serial terminal**: a full VT100/xterm emulator (Termux terminal-view) with extra keys
  (Ctrl, Esc, Tab, arrows, Home/End, ^C/^D/^L), pinch zoom, selection and log export, so
  interactive consoles behave as in `idf.py monitor`.
- **Diagnostics**: protocol log with trace mode, crash reports captured to a file and offered
  for copy/share/save on the next launch, and a one-tap diagnostics export.

### Notes
- Releases are signed with a debug key until the signing secrets are configured; updates over a
  differently signed install require uninstalling first.
