# Third-party notices

## esptool (protocol reference)
The `esploader` module is a Kotlin port of the serial protocol implementation, chip
definitions and flashing logic of [esptool](https://github.com/espressif/esptool)
(Fredrik Ahlberg, Angus Gratton, Espressif Systems and contributors), licensed under
GPL-2.0-or-later. This project is therefore distributed under GPL-3.0-or-later (see `LICENSE`).

## esp-flasher-stub
`esploader/src/main/resources/esploader/stubs/*.json` are the flasher stub binaries released by
Espressif at https://github.com/espressif/esp-flasher-stub (v1.4.0), redistributed unchanged
from esptool 5.5.0. They are dual licensed under the Apache License 2.0 or the MIT license; both
texts ship next to the files.

## Termux terminal-emulator / terminal-view
https://github.com/termux/termux-app (`terminal-emulator`, `terminal-view`, 0.118.0) — GPLv3.
The serial monitor drives Termux's emulator from the USB stream through a small bridge class in
`app/src/main/kotlin/com/termux/terminal/`.

## usb-serial-for-android
https://github.com/mik3y/usb-serial-for-android — MIT License. Copyright (c) 2011-2013 Google
Inc., 2013 Mike Wakerly and contributors.

## AndroidX, Jetpack Compose, Kotlin, kotlinx
Apache License 2.0.
