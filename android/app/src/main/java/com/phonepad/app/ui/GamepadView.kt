package com.phonepad.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.phonepad.app.R
import com.phonepad.app.hid.GamepadButton
import com.phonepad.app.hid.GamepadState
import com.phonepad.app.hid.Hat
import kotlin.math.hypot
import kotlin.math.min

/**
 * Landscape PS3-style pad: L/R sticks, D-pad, ABXY, shoulders, triggers, Start/Select/Menu.
 * Multitouch: each pointer binds to the control it hit until lift.
 * High-contrast, color-blind safe (letters + distinct hues), haptics on digital press.
 */
class GamepadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    fun interface Listener {
        fun onStateChanged(state: GamepadState)
    }

    var listener: Listener? = null

    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = ContextCompat.getColor(context, R.color.text)
    }

    private val colorBg = ContextCompat.getColor(context, R.color.bg)
    private val colorRest = ContextCompat.getColor(context, R.color.resting)
    private val colorPressed = ContextCompat.getColor(context, R.color.pressed)
    private val colorA = ContextCompat.getColor(context, R.color.accent_a)
    private val colorB = ContextCompat.getColor(context, R.color.accent_b)
    private val colorX = ContextCompat.getColor(context, R.color.accent_x)
    private val colorY = ContextCompat.getColor(context, R.color.accent_y)
    private val colorWell = ContextCompat.getColor(context, R.color.stick_well)
    private val colorKnob = ContextCompat.getColor(context, R.color.stick_knob)
    private val colorOutline = ContextCompat.getColor(context, R.color.outline)
    private val colorDim = ContextCompat.getColor(context, R.color.text_dim)

    private enum class Control {
        L_STICK, R_STICK,
        DPAD, // handled via sectors
        A, B, X, Y,
        L1, R1, L2, R2,
        SELECT, START, MENU,
        L3, R3,
    }

    private data class Circle(var cx: Float = 0f, var cy: Float = 0f, var r: Float = 0f) {
        fun contains(x: Float, y: Float) = hypot(x - cx, y - cy) <= r
    }

    private data class Box(var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f) {
        fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
        fun toRectF() = RectF(left, top, right, bottom)
        val cx get() = (left + right) / 2f
        val cy get() = (top + bottom) / 2f
    }

    private val lStick = Circle()
    private val rStick = Circle()
    private val dpad = Circle()
    private val btnA = Circle()
    private val btnB = Circle()
    private val btnX = Circle()
    private val btnY = Circle()
    private val btnL1 = Box()
    private val btnR1 = Box()
    private val btnL2 = Box()
    private val btnR2 = Box()
    private val btnSelect = Box()
    private val btnStart = Box()
    private val btnMenu = Box()
    private val btnL3 = Circle()
    private val btnR3 = Circle()

    // Pointer bindings
    private val stickPointers = HashMap<Int, Control>() // L_STICK or R_STICK
    private val digitalPointers = HashMap<Int, MutableSet<Control>>()
    private val dpadPointers = HashSet<Int>()

    private var lStickNx = 0f
    private var lStickNy = 0f
    private var rStickNx = 0f
    private var rStickNy = 0f
    private var lTrigger = 0
    private var rTrigger = 0
    private var buttons = 0
    private var hat = Hat.CENTER
    private var dpadUp = false
    private var dpadDown = false
    private var dpadLeft = false
    private var dpadRight = false

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutControls(w.toFloat(), h.toFloat())
    }

    private fun layoutControls(w: Float, h: Float) {
        val unit = min(h, w * 9f / 16f)
        val faceR = unit * 0.075f
        val stickR = unit * 0.13f
        val dpadR = unit * 0.14f
        val shoulderH = unit * 0.08f
        val shoulderW = unit * 0.16f

        // Left stick lower-left; D-pad upper-left (PS3-ish: stick below dpad-ish but classic PS puts
        // dpad left / stick lower — we put dpad mid-left, stick lower-left)
        lStick.cx = w * 0.22f
        lStick.cy = h * 0.62f
        lStick.r = stickR

        dpad.cx = w * 0.18f
        dpad.cy = h * 0.32f
        dpad.r = dpadR

        // Right stick lower-right; face diamond mid-right
        rStick.cx = w * 0.72f
        rStick.cy = h * 0.62f
        rStick.r = stickR

        val faceCx = w * 0.82f
        val faceCy = h * 0.34f
        val faceGap = faceR * 1.85f
        btnA.cx = faceCx; btnA.cy = faceCy + faceGap; btnA.r = faceR
        btnB.cx = faceCx + faceGap; btnB.cy = faceCy; btnB.r = faceR
        btnX.cx = faceCx - faceGap; btnX.cy = faceCy; btnX.r = faceR
        btnY.cx = faceCx; btnY.cy = faceCy - faceGap; btnY.r = faceR

        // Shoulders / triggers along top
        fun boxAt(cx: Float, cy: Float, bw: Float, bh: Float) = Box(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
        btnL2.left = w * 0.06f; btnL2.top = h * 0.04f; btnL2.right = btnL2.left + shoulderW; btnL2.bottom = btnL2.top + shoulderH
        btnL1.left = w * 0.06f; btnL1.top = h * 0.14f; btnL1.right = btnL1.left + shoulderW; btnL1.bottom = btnL1.top + shoulderH
        btnR2.right = w * 0.94f; btnR2.top = h * 0.04f; btnR2.left = btnR2.right - shoulderW; btnR2.bottom = btnR2.top + shoulderH
        btnR1.right = w * 0.94f; btnR1.top = h * 0.14f; btnR1.left = btnR1.right - shoulderW; btnR1.bottom = btnR1.top + shoulderH

        // Center cluster
        val cW = unit * 0.09f
        val cH = unit * 0.055f
        val cy = h * 0.12f
        btnSelect.left = w * 0.42f - cW; btnSelect.top = cy - cH / 2; btnSelect.right = w * 0.42f + cW / 4; btnSelect.bottom = cy + cH / 2
        // fix select width
        val midY = h * 0.12f
        with(btnSelect) { left = w * 0.38f; right = w * 0.46f; top = midY - cH / 2; bottom = midY + cH / 2 }
        with(btnMenu) { left = w * 0.47f; right = w * 0.53f; top = midY - cH / 2; bottom = midY + cH / 2 }
        with(btnStart) { left = w * 0.54f; right = w * 0.62f; top = midY - cH / 2; bottom = midY + cH / 2 }

        // Stick clicks as small buttons under sticks
        btnL3.cx = lStick.cx; btnL3.cy = lStick.cy + stickR + unit * 0.08f; btnL3.r = unit * 0.045f
        btnR3.cx = rStick.cx; btnR3.cy = rStick.cy + stickR + unit * 0.08f; btnR3.r = unit * 0.045f

        paintText.textSize = faceR * 0.9f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colorBg)

        drawShoulder(canvas, btnL2, "L2", lTrigger > 0)
        drawShoulder(canvas, btnL1, "L1", buttons and GamepadButton.L1.bit != 0)
        drawShoulder(canvas, btnR2, "R2", rTrigger > 0)
        drawShoulder(canvas, btnR1, "R1", buttons and GamepadButton.R1.bit != 0)

        drawPill(canvas, btnSelect, "SELECT", buttons and GamepadButton.BACK.bit != 0)
        drawPill(canvas, btnMenu, "☰", buttons and GamepadButton.GUIDE.bit != 0)
        drawPill(canvas, btnStart, "START", buttons and GamepadButton.START.bit != 0)

        drawStick(canvas, lStick, lStickNx, lStickNy)
        drawStick(canvas, rStick, rStickNx, rStickNy)
        drawDpad(canvas)
        drawFace(canvas, btnA, "A", colorA, buttons and GamepadButton.SOUTH.bit != 0)
        drawFace(canvas, btnB, "B", colorB, buttons and GamepadButton.EAST.bit != 0)
        drawFace(canvas, btnX, "X", colorX, buttons and GamepadButton.WEST.bit != 0)
        drawFace(canvas, btnY, "Y", colorY, buttons and GamepadButton.NORTH.bit != 0)

        drawFace(canvas, btnL3, "L3", colorRest, buttons and GamepadButton.L3.bit != 0)
        drawFace(canvas, btnR3, "R3", colorRest, buttons and GamepadButton.R3.bit != 0)
    }

    private fun drawStick(canvas: Canvas, well: Circle, nx: Float, ny: Float) {
        paintFill.color = colorWell
        canvas.drawCircle(well.cx, well.cy, well.r, paintFill)
        paintStroke.color = colorOutline
        canvas.drawCircle(well.cx, well.cy, well.r, paintStroke)
        val knobR = well.r * 0.42f
        val kx = well.cx + nx * (well.r - knobR)
        val ky = well.cy + ny * (well.r - knobR)
        paintFill.color = colorKnob
        canvas.drawCircle(kx, ky, knobR, paintFill)
    }

    private fun drawDpad(canvas: Canvas) {
        val arm = dpad.r * 0.42f
        val thick = dpad.r * 0.38f
        // cross
        fun armRect(dx: Float, dy: Float): RectF {
            val cx = dpad.cx + dx * arm
            val cy = dpad.cy + dy * arm
            return if (dx == 0f) RectF(cx - thick / 2, cy - arm, cx + thick / 2, cy + arm)
            else RectF(cx - arm, cy - thick / 2, cx + arm, cy + thick / 2)
        }
        // draw four arms lit by hat
        data class Arm(val dx: Float, val dy: Float, val lit: Boolean)
        val arms = listOf(
            Arm(0f, -1f, dpadUp),
            Arm(0f, 1f, dpadDown),
            Arm(-1f, 0f, dpadLeft),
            Arm(1f, 0f, dpadRight),
        )
        // base cross
        paintFill.color = colorRest
        canvas.drawRoundRect(armRect(0f, 0f).also {
            // full vertical
            it.set(dpad.cx - thick / 2, dpad.cy - dpad.r * 0.85f, dpad.cx + thick / 2, dpad.cy + dpad.r * 0.85f)
        }, 12f, 12f, paintFill)
        canvas.drawRoundRect(RectF(dpad.cx - dpad.r * 0.85f, dpad.cy - thick / 2, dpad.cx + dpad.r * 0.85f, dpad.cy + thick / 2), 12f, 12f, paintFill)

        for (a in arms) {
            if (!a.lit) continue
            paintFill.color = colorPressed
            val r = when {
                a.dy < 0 -> RectF(dpad.cx - thick / 2, dpad.cy - dpad.r * 0.85f, dpad.cx + thick / 2, dpad.cy)
                a.dy > 0 -> RectF(dpad.cx - thick / 2, dpad.cy, dpad.cx + thick / 2, dpad.cy + dpad.r * 0.85f)
                a.dx < 0 -> RectF(dpad.cx - dpad.r * 0.85f, dpad.cy - thick / 2, dpad.cx, dpad.cy + thick / 2)
                else -> RectF(dpad.cx, dpad.cy - thick / 2, dpad.cx + dpad.r * 0.85f, dpad.cy + thick / 2)
            }
            canvas.drawRoundRect(r, 12f, 12f, paintFill)
        }
        paintStroke.color = colorOutline
        canvas.drawCircle(dpad.cx, dpad.cy, dpad.r, paintStroke)
    }

    private fun drawFace(canvas: Canvas, c: Circle, label: String, accent: Int, pressed: Boolean) {
        paintFill.color = if (pressed) accent else colorRest
        canvas.drawCircle(c.cx, c.cy, c.r, paintFill)
        paintStroke.color = if (pressed) accent else colorOutline
        canvas.drawCircle(c.cx, c.cy, c.r, paintStroke)
        // Always draw letter for color-blind safety
        paintText.color = ContextCompat.getColor(context, R.color.text)
        paintText.textSize = c.r * 0.95f
        val ty = c.cy - (paintText.descent() + paintText.ascent()) / 2
        canvas.drawText(label, c.cx, ty, paintText)
        // Accent ring when resting so colors remain identifiable
        if (!pressed) {
            paintStroke.color = accent
            paintStroke.strokeWidth = 5f
            canvas.drawCircle(c.cx, c.cy, c.r - 4f, paintStroke)
            paintStroke.strokeWidth = 3f
        }
    }

    private fun drawShoulder(canvas: Canvas, b: Box, label: String, pressed: Boolean) {
        paintFill.color = if (pressed) colorPressed else colorRest
        canvas.drawRoundRect(b.toRectF(), 16f, 16f, paintFill)
        paintStroke.color = colorOutline
        canvas.drawRoundRect(b.toRectF(), 16f, 16f, paintStroke)
        paintText.color = ContextCompat.getColor(context, R.color.text)
        paintText.textSize = (b.bottom - b.top) * 0.55f
        val ty = b.cy - (paintText.descent() + paintText.ascent()) / 2
        canvas.drawText(label, b.cx, ty, paintText)
    }

    private fun drawPill(canvas: Canvas, b: Box, label: String, pressed: Boolean) {
        paintFill.color = if (pressed) colorPressed else colorRest
        canvas.drawRoundRect(b.toRectF(), 20f, 20f, paintFill)
        paintText.color = if (pressed) ContextCompat.getColor(context, R.color.text) else colorDim
        paintText.textSize = (b.bottom - b.top) * 0.5f
        val ty = b.cy - (paintText.descent() + paintText.ascent()) / 2
        canvas.drawText(label, b.cx, ty, paintText)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                bindPointer(event.getPointerId(idx), event.getX(idx), event.getY(idx))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    updatePointer(id, x, y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = if (event.actionMasked == MotionEvent.ACTION_CANCEL) -1 else event.actionIndex
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    clearAll()
                } else {
                    unbindPointer(event.getPointerId(idx))
                }
            }
        }
        emit()
        invalidate()
        return true
    }

    private fun bindPointer(id: Int, x: Float, y: Float) {
        // Priority: face/shoulder/center over stick wells when overlapping
        hitDigital(x, y)?.let { ctrl ->
            digitalPointers.getOrPut(id) { mutableSetOf() }.add(ctrl)
            setDigital(ctrl, true)
            haptic()
            return
        }
        if (dpad.contains(x, y)) {
            dpadPointers.add(id)
            updateDpad(x, y)
            haptic()
            return
        }
        if (lStick.contains(x, y)) {
            stickPointers[id] = Control.L_STICK
            updateStick(Control.L_STICK, x, y)
            return
        }
        if (rStick.contains(x, y)) {
            stickPointers[id] = Control.R_STICK
            updateStick(Control.R_STICK, x, y)
            return
        }
    }

    private fun updatePointer(id: Int, x: Float, y: Float) {
        stickPointers[id]?.let { updateStick(it, x, y); return }
        if (id in dpadPointers) {
            updateDpad(x, y)
            return
        }
        // Digitals stay pressed while bound (no drag-off) — real pads behave that way
        digitalPointers[id]?.let { return }
    }

    private fun unbindPointer(id: Int) {
        stickPointers.remove(id)?.let { ctrl ->
            if (ctrl == Control.L_STICK) { lStickNx = 0f; lStickNy = 0f }
            if (ctrl == Control.R_STICK) { rStickNx = 0f; rStickNy = 0f }
        }
        if (dpadPointers.remove(id)) {
            // recompute hat from remaining dpad pointers — if none, center
            if (dpadPointers.isEmpty()) {
                dpadUp = false; dpadDown = false; dpadLeft = false; dpadRight = false
                hat = Hat.CENTER
            }
        }
        digitalPointers.remove(id)?.forEach { setDigital(it, false) }
    }

    private fun clearAll() {
        stickPointers.clear()
        digitalPointers.clear()
        dpadPointers.clear()
        lStickNx = 0f; lStickNy = 0f; rStickNx = 0f; rStickNy = 0f
        lTrigger = 0; rTrigger = 0
        buttons = 0
        dpadUp = false; dpadDown = false; dpadLeft = false; dpadRight = false
        hat = Hat.CENTER
    }

    private fun hitDigital(x: Float, y: Float): Control? {
        if (btnA.contains(x, y)) return Control.A
        if (btnB.contains(x, y)) return Control.B
        if (btnX.contains(x, y)) return Control.X
        if (btnY.contains(x, y)) return Control.Y
        if (btnL1.contains(x, y)) return Control.L1
        if (btnR1.contains(x, y)) return Control.R1
        if (btnL2.contains(x, y)) return Control.L2
        if (btnR2.contains(x, y)) return Control.R2
        if (btnSelect.contains(x, y)) return Control.SELECT
        if (btnStart.contains(x, y)) return Control.START
        if (btnMenu.contains(x, y)) return Control.MENU
        if (btnL3.contains(x, y)) return Control.L3
        if (btnR3.contains(x, y)) return Control.R3
        return null
    }

    private fun setDigital(ctrl: Control, pressed: Boolean) {
        fun bit(b: GamepadButton) {
            buttons = if (pressed) buttons or b.bit else buttons and b.bit.inv()
        }
        when (ctrl) {
            Control.A -> bit(GamepadButton.SOUTH)
            Control.B -> bit(GamepadButton.EAST)
            Control.X -> bit(GamepadButton.WEST)
            Control.Y -> bit(GamepadButton.NORTH)
            Control.L1 -> bit(GamepadButton.L1)
            Control.R1 -> bit(GamepadButton.R1)
            Control.L2 -> { lTrigger = if (pressed) 255 else 0; bit(GamepadButton.L2) }
            Control.R2 -> { rTrigger = if (pressed) 255 else 0; bit(GamepadButton.R2) }
            Control.SELECT -> bit(GamepadButton.BACK)
            Control.START -> bit(GamepadButton.START)
            Control.MENU -> bit(GamepadButton.GUIDE)
            Control.L3 -> bit(GamepadButton.L3)
            Control.R3 -> bit(GamepadButton.R3)
            else -> {}
        }
    }

    private fun updateStick(ctrl: Control, x: Float, y: Float) {
        val well = if (ctrl == Control.L_STICK) lStick else rStick
        var dx = (x - well.cx) / well.r
        var dy = (y - well.cy) / well.r
        val len = hypot(dx, dy)
        if (len > 1f) { dx /= len; dy /= len }
        if (ctrl == Control.L_STICK) { lStickNx = dx; lStickNy = dy } else { rStickNx = dx; rStickNy = dy }
    }

    private fun updateDpad(x: Float, y: Float) {
        val dx = x - dpad.cx
        val dy = y - dpad.cy
        val dead = dpad.r * 0.22f
        dpadUp = dy < -dead
        dpadDown = dy > dead
        dpadLeft = dx < -dead
        dpadRight = dx > dead
        hat = Hat.of(dpadUp, dpadDown, dpadLeft, dpadRight)
    }

    private fun emit() {
        val state = GamepadState(
            leftStickX = GamepadState.axisFromUnit(lStickNx),
            leftStickY = GamepadState.axisFromUnit(lStickNy),
            rightStickX = GamepadState.axisFromUnit(rStickNx),
            rightStickY = GamepadState.axisFromUnit(rStickNy),
            leftTrigger = lTrigger,
            rightTrigger = rTrigger,
            hat = hat,
            buttons = buttons,
        )
        listener?.onStateChanged(state)
    }

    fun resetToNeutral() {
        clearAll()
        emit()
        invalidate()
    }

    private fun haptic() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator?.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(18)
            }
        } catch (_: Throwable) { /* ignore */ }
    }
}
