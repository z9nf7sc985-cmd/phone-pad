package com.phonepad.app.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericHidProfileTest {
    @Test
    fun encode_neutral_is_nine_bytes_centred() {
        val r = GenericHidProfile.encode(GamepadState.NEUTRAL)
        assertEquals(9, r.size)
        assertEquals(128, r[0].toInt() and 0xFF)
        assertEquals(128, r[1].toInt() and 0xFF)
        assertEquals(128, r[2].toInt() and 0xFF)
        assertEquals(128, r[3].toInt() and 0xFF)
        assertEquals(0, r[4].toInt() and 0xFF)
        assertEquals(0, r[5].toInt() and 0xFF)
        assertEquals(8, r[6].toInt() and 0x0F) // hat centred
        assertEquals(0, r[7].toInt() and 0xFF)
        assertEquals(0, r[8].toInt() and 0xFF)
    }

    @Test
    fun encode_sets_face_and_trigger_bits() {
        var s = GamepadState.NEUTRAL
            .withButton(GamepadButton.SOUTH, true)
            .withButton(GamepadButton.L1, true)
            .copy(leftTrigger = 200)
        val r = GenericHidProfile.encode(s)
        val lo = r[7].toInt() and 0xFF
        val hi = r[8].toInt() and 0xFF
        val buttons = lo or (hi shl 8)
        assertTrue(buttons and GamepadButton.SOUTH.bit != 0)
        assertTrue(buttons and GamepadButton.L1.bit != 0)
        assertTrue(buttons and GamepadButton.L2.bit != 0) // digital mirror of analog
        assertEquals(200, r[4].toInt() and 0xFF)
    }

    @Test
    fun descriptor_declares_report_id_1_and_gamepad_usage() {
        val d = GenericHidProfile.descriptor
        assertTrue(d.size > 40)
        // Usage Page Generic Desktop + Usage Gamepad near start
        assertEquals(0x05, d[0].toInt() and 0xFF)
        assertEquals(0x01, d[1].toInt() and 0xFF)
        assertEquals(0x09, d[2].toInt() and 0xFF)
        assertEquals(0x05, d[3].toInt() and 0xFF)
        // Report ID 1
        var found = false
        for (i in 0 until d.size - 1) {
            if ((d[i].toInt() and 0xFF) == 0x85 && (d[i + 1].toInt() and 0xFF) == 0x01) {
                found = true
                break
            }
        }
        assertTrue(found)
    }

    @Test
    fun descriptor_is_structurally_valid_and_matches_report_size() {
        assertEquals(null, GenericHidProfile.validateDescriptor())
    }

    @Test
    fun validator_rejects_unbalanced_and_truncated() {
        val d = GenericHidProfile.descriptor
        assertTrue(GenericHidProfile.validateDescriptor(d.copyOf(d.size - 1)) != null)
        assertTrue(GenericHidProfile.validateDescriptor(d.copyOf(d.size - 2) + byteArrayOf(0x26)) != null)
    }
}
