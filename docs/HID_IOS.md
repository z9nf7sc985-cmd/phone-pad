# HID descriptor choice and iOS uncertainty

## What we ship

PhonePad registers via Android `BluetoothHidDevice` (API 28+) as a **Bluetooth Classic HID gamepad**
with a standards-based Xbox-style / generic extended report:

| Field | Encoding |
|-------|----------|
| Sticks | Generic Desktop `X/Y/Z/Rz`, 8-bit, centre 128 |
| Triggers | Simulation Controls `Brake` / `Accelerator`, 8-bit (DualShock/Xbox BT convention) |
| D-pad | Hat switch with **null state** (value 8 = centred) |
| Buttons | 16 bits, Linux `hid-input` gamepad order (SOUTH/EAST/…/THUMBR) |
| SDP subclass | `0x08` (gamepad nibble shifted) — not `SUBCLASS2_GAMEPAD` (`0x02`) |
| Report | ID 1, 9 bytes |

This matches the layout proven on **Linux and Windows** by similar Android apps (notably Blugaemand).
It is the best generic descriptor we can publish without spoofing a branded controller.

## What iOS actually accepts

Apple’s `GameController` framework officially supports:

- MFi controllers
- Sony DualShock 4 / DualSense (from iOS 13 / later)
- Microsoft Xbox Wireless (One S / Series, from iOS 13 / 14.5)

Those devices are recognised by **specific vendor/product identities and protocols**, not by “any
valid HID gamepad descriptor.” A phone advertising a generic Classic HID gamepad **may appear in
Settings → Bluetooth** after pairing, yet **never surface as a `GCController` / extended gamepad**
inside games.

Android also cannot register HID-over-GATT (`0x1812`) from an app, and `BluetoothHidDevice` does
**not** let us set USB/BT VID/PID. Spoofing Xbox/Sony identities is neither available nor appropriate.

**iPhone compatibility is therefore unverified and may fail.** This box cannot test against an iPhone.

## How to test on iPhone

1. Install the debug APK on the Samsung S9 (Android 9+).
2. Open PhonePad, grant Nearby devices / Bluetooth permissions, wait for **Ready to pair**.
3. Tap **Make discoverable** (accept the system dialog).
4. On the iPhone: **Settings → Bluetooth** → select the S9 / “PhonePad Gamepad” entry.
5. Confirm the pairing code on both devices.
6. Check whether games that support controllers see an extended gamepad, or use a GameController
   tester app. Pairing success ≠ GameController recognition.

If the S9 was previously paired as a phone, **Forget This Device** on the iPhone first, then pair
again **while PhonePad already shows Ready** (hosts cache SDP at pair time).

## Fallback ideas if iOS rejects the descriptor

1. **PC / Android / Linux host** — this descriptor should work there; validate the pad first.
2. **Try DualShock-4-shaped report layout** (different axis/button packing) — still may fail without
   Sony identity, but worth an A/B if iOS pairs but ignores input.
3. **Companion iOS app over BLE GATT** (non-HID) that maps touches into on-screen controls — works for
   your own apps only, not system GameController.
4. **Hardware bridge** (ESP32 BLE presenting DualSense-style HID) — outside this APK; some open
   projects take that route specifically for iOS.
5. Do **not** rely on faking Xbox/Sony VID/PID from `BluetoothHidDevice`; the API does not expose it.
