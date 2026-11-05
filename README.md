# PhonePad

Turn an Android phone (Samsung S9 / Android 9+) into a **Bluetooth HID gamepad** that a host can
pair with. Built with Android’s `BluetoothHidDevice` API (minSdk 28).

> **Honest status:** the HID descriptor is a standards Xbox-style extended gamepad that works on
> Linux/Windows-class hosts. **iOS GameController acceptance is unverified** — see
> [docs/HID_IOS.md](docs/HID_IOS.md). Do not assume an iPhone will treat this as an Xbox/PS pad.

## Features (v0.1.0)

- Landscape PS3-style touch layout (sticks, D-pad, ABXY, L1/R1/L2/R2, Start/Select/Menu, L3/R3)
- Multitouch, large targets, high contrast, color-blind-safe labels, haptics
- Pairing panel: register HID, make discoverable, show host, disconnect / reconnect
- Runtime Bluetooth permissions for Android 12+

## Build

```bash
export PATH="/home/box/tools/jdk-17.0.21+9/bin:$PATH"   # or your JDK 17
cd android
./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ../dist/PhonePad-debug.apk
```

Install over a previous build (same `applicationId` `com.phonepad.app`):

```bash
adb install -r dist/PhonePad-debug.apk
```

## Releases

APKs are published as **GitHub Releases** on `z9nf7sc985-cmd/phone-pad` (version tag + APK asset).

## License

App code: MIT (this repository). HID layout notes document prior art; no Sony/Microsoft trademarks
or logos are used in the UI.
