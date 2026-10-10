# usb-serial-for-android locates drivers reflectively (probe(UsbDevice) + constructor(UsbDevice)).
-keep class com.hoho.android.usbserial.driver.** { *; }
# Keep the loader library's public API readable in crash logs.
-keepnames class io.github.xerootg.esploader.** { *; }
-dontwarn org.slf4j.**
