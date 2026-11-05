# Releasing PhonePad

1. Bump `versionName` / `versionCode` in `android/app/build.gradle.kts`.
2. `cd android && ./gradlew assembleDebug`
3. Copy APK to `dist/PhonePad-<version>-debug.apk`.
4. With `gh` authenticated as `z9nf7sc985-cmd`:

```bash
gh release create v0.1.0 dist/PhonePad-0.1.0-debug.apk \
  --repo z9nf7sc985-cmd/phone-pad \
  --title "PhonePad 0.1.0" \
  --notes "First debug build: Bluetooth HID gamepad (Xbox-style descriptor). iOS GameController acceptance unverified — see docs/HID_IOS.md."
```

Direct asset URL form:
`https://github.com/z9nf7sc985-cmd/phone-pad/releases/download/v0.1.0/PhonePad-0.1.0-debug.apk`
