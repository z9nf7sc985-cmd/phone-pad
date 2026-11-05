package com.phonepad.app.hid

/**
 * Xbox-style / generic extended gamepad HID report descriptor for BluetoothHidDevice.
 *
 * Choice rationale (and uncertainty — see docs/HID_IOS.md):
 * - Layout mirrors DualShock / Xbox Bluetooth convention: sticks on Generic Desktop
 *   X/Y/Z/Rz, triggers as Simulation Controls Brake/Accelerator, D-pad as hat with
 *   null-state, 16 buttons in Linux hid-input order.
 * - Proven on Linux/Windows by similar Android BluetoothHidDevice apps (e.g. Blugaemand).
 * - iOS GameController officially supports MFi, DualShock 4/DualSense, and Xbox Wireless
 *   controllers — NOT arbitrary Classic HID gamepads. This descriptor is the best
 *   standards-compliant attempt; iPhone acceptance is UNVERIFIED and may fail.
 * - We deliberately do NOT spoof Xbox/Sony VID/PID (Android does not expose SDP VID/PID
 *   control for BluetoothHidDevice anyway, and spoofing would be trademark-problematic).
 *
 * Report ID 1, 9 bytes: LX LY RX RY LT RT hat+pad buttons_lo buttons_hi
 */
object GenericHidProfile {
    const val REPORT_ID = 1
    const val REPORT_SIZE = 9
    const val SDP_NAME = "PhonePad Gamepad"
    const val SDP_DESCRIPTION = "Virtual Bluetooth Gamepad"
    const val SDP_PROVIDER = "PhonePad"

    /**
     * Low byte of CoD / HID subclass: gamepad nibble shifted (0x08).
     * Do NOT use BluetoothHidDevice.SUBCLASS2_GAMEPAD (0x02) — unshifted, reads as unspecified.
     */
    const val SUBCLASS: Byte = 0x08

    private const val TRIGGER_DIGITAL_THRESHOLD = 32

    val descriptor: ByteArray = byteArrayOf(
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x05,             // Usage (Gamepad)
        0xA1.toByte(), 0x01,    // Collection (Application)
        0x85.toByte(), 0x01,    // Report ID (1)
        0xA1.toByte(), 0x00,    // Collection (Physical)

        // Four stick axes
        0x09, 0x30,             // Usage (X)
        0x09, 0x31,             // Usage (Y)
        0x09, 0x32,             // Usage (Z)
        0x09, 0x35,             // Usage (Rz)
        0x15, 0x00,             // Logical Min 0
        0x26, 0xFF.toByte(), 0x00, // Logical Max 255
        0x75, 0x08,             // Report Size 8
        0x95.toByte(), 0x04,    // Report Count 4
        0x81.toByte(), 0x02,    // Input (Data,Var,Abs)

        // Triggers as Brake / Accelerator (DS/Xbox convention)
        0x05, 0x02,             // Usage Page (Simulation)
        0x09, 0xC5.toByte(),    // Usage (Brake) LT
        0x09, 0xC4.toByte(),    // Usage (Accelerator) RT
        0x15, 0x00,
        0x26, 0xFF.toByte(), 0x00,
        0x75, 0x08,
        0x95.toByte(), 0x02,
        0x81.toByte(), 0x02,

        // Hat switch with null state (8 = centred)
        0x05, 0x01,
        0x09, 0x39,             // Hat switch
        0x15, 0x00,
        0x25, 0x07,
        0x35, 0x00,
        0x46, 0x3B, 0x01,       // Physical Max 315°
        0x65, 0x14,             // Unit Degrees
        0x75, 0x04,
        0x95.toByte(), 0x01,
        0x81.toByte(), 0x42,    // Input Null State
        0x65, 0x00,
        0x75, 0x04,
        0x95.toByte(), 0x01,
        0x81.toByte(), 0x03,    // Constant pad

        // 16 buttons
        0x05, 0x09,
        0x19, 0x01,
        0x29, 0x10,
        0x15, 0x00,
        0x25, 0x01,
        0x75, 0x01,
        0x95.toByte(), 0x10,
        0x81.toByte(), 0x02,

        0xC0.toByte(),          // End Physical
        0xC0.toByte(),          // End Application
    )

    fun encode(state: GamepadState): ByteArray {
        var buttons = state.buttons
        if (state.leftTrigger >= TRIGGER_DIGITAL_THRESHOLD) buttons = buttons or GamepadButton.L2.bit
        if (state.rightTrigger >= TRIGGER_DIGITAL_THRESHOLD) buttons = buttons or GamepadButton.R2.bit
        return byteArrayOf(
            GamepadState.clampAxis(state.leftStickX).toByte(),
            GamepadState.clampAxis(state.leftStickY).toByte(),
            GamepadState.clampAxis(state.rightStickX).toByte(),
            GamepadState.clampAxis(state.rightStickY).toByte(),
            GamepadState.clampAxis(state.leftTrigger).toByte(),
            GamepadState.clampAxis(state.rightTrigger).toByte(),
            (state.hat.value and 0x0F).toByte(),
            (buttons and 0xFF).toByte(),
            ((buttons shr 8) and 0xFF).toByte(),
        )
    }
}
