package com.phonepad.app.hid

enum class Hat(val value: Int) {
    NORTH(0), NORTH_EAST(1), EAST(2), SOUTH_EAST(3),
    SOUTH(4), SOUTH_WEST(5), WEST(6), NORTH_WEST(7), CENTER(8);

    companion object {
        fun of(up: Boolean, down: Boolean, left: Boolean, right: Boolean): Hat {
            val v = (if (up) -1 else 0) + (if (down) 1 else 0)
            val h = (if (left) -1 else 0) + (if (right) 1 else 0)
            return when {
                v < 0 && h == 0 -> NORTH
                v < 0 && h > 0 -> NORTH_EAST
                v == 0 && h > 0 -> EAST
                v > 0 && h > 0 -> SOUTH_EAST
                v > 0 && h == 0 -> SOUTH
                v > 0 && h < 0 -> SOUTH_WEST
                v == 0 && h < 0 -> WEST
                v < 0 && h < 0 -> NORTH_WEST
                else -> CENTER
            }
        }
    }
}

/**
 * HID button numbers follow Linux hid-input gamepad mapping
 * (BTN_SOUTH, BTN_EAST, skip C, BTN_NORTH, BTN_WEST, …) so hosts label correctly.
 * Face buttons are positional: SOUTH=A, EAST=B, NORTH=Y, WEST=X (Xbox lettering).
 */
enum class GamepadButton(val hidButtonNumber: Int) {
    SOUTH(1), EAST(2), NORTH(4), WEST(5),
    L1(7), R1(8), L2(9), R2(10),
    BACK(11), START(12), GUIDE(13), L3(14), R3(15);

    val bit: Int get() = 1 shl (hidButtonNumber - 1)
}

data class GamepadState(
    val leftStickX: Int = AXIS_CENTER,
    val leftStickY: Int = AXIS_CENTER,
    val rightStickX: Int = AXIS_CENTER,
    val rightStickY: Int = AXIS_CENTER,
    val leftTrigger: Int = AXIS_MIN,
    val rightTrigger: Int = AXIS_MIN,
    val hat: Hat = Hat.CENTER,
    val buttons: Int = 0,
) {
    fun withButton(button: GamepadButton, pressed: Boolean): GamepadState = copy(
        buttons = if (pressed) buttons or button.bit else buttons and button.bit.inv(),
    )

    companion object {
        const val AXIS_MIN = 0
        const val AXIS_CENTER = 128
        const val AXIS_MAX = 255
        val NEUTRAL = GamepadState()

        fun clampAxis(value: Int): Int = value.coerceIn(AXIS_MIN, AXIS_MAX)

        /** -1..1 → 0..255, centre 128. Scale by 128 so both rails saturate. */
        fun axisFromUnit(unit: Float): Int =
            clampAxis(Math.round(AXIS_CENTER + unit.coerceIn(-1f, 1f) * 128f))
    }
}
