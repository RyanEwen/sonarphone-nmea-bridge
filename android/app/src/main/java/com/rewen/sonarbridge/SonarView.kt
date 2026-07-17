package com.rewen.sonarbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import java.util.Locale
import kotlin.math.abs

/**
 * Fish-finder waterfall: newest echo column on the right, scrolling left,
 * with a live A-scope strip on the right edge (standard on modern units,
 * e.g. Deeper). Columns land in a ring-buffer bitmap (one setPixels per
 * column); onDraw unrolls the ring with two scaled drawBitmap calls.
 *
 * Range handling follows real-unit practice: discrete range steps chosen so
 * the bottom sits ~3/4 down the screen, stepping deeper immediately but
 * shallower only after the depth has stayed well inside the smaller range
 * for a few seconds (hysteresis), and the zoom eases rather than jumps.
 */
class SonarView(context: Context) : android.view.View(context) {

    private companion object {
        const val COLS = 600
        const val SAMPLES = EchoHistory.SAMPLES
        val RANGE_STEPS_M = listOf(2.0, 5.0, 10.0, 15.0, 20.0, 30.0, 40.0, 60.0, 80.0)
        const val FIT = 1.25          // range must exceed depth * FIT
        const val STEP_DOWN_MS = 6000L // dwell before zooming back in
    }

    private val dens = resources.displayMetrics.density
    private fun dp(v: Float) = v * dens

    private val bitmap = Bitmap.createBitmap(COLS, SAMPLES, Bitmap.Config.ARGB_8888)
    private val ascopeBmp = Bitmap.createBitmap(1, SAMPLES, Bitmap.Config.ARGB_8888)
    private val colBuf = IntArray(SAMPLES)
    private var writeCol = 0
    private var filled = 0
    private var lastSeq = 0L
    private var latestDepth = 0.0

    // range state
    private var rangeM = 10.0            // current step
    private var windowF = (rangeM * EchoHistory.SAMPLES_PER_M).toFloat()
    private var fitsSmallerSince = 0L

    private val bmpPaint = Paint().apply { isFilterBitmap = true }
    private val gridPaint = Paint().apply {
        color = Color.argb(36, 255, 255, 255)
        strokeWidth = dp(1f)
    }
    private val tickPaint = Paint().apply {
        color = Color.argb(140, 255, 255, 255)
        strokeWidth = dp(1.5f)
    }
    private val labelPaint = Paint().apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = dp(12f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.RIGHT
        isAntiAlias = true
    }
    private val chipBgPaint = Paint().apply {
        color = Color.argb(140, 0, 0, 0)
        isAntiAlias = true
    }
    private val depthPaint = Paint().apply {
        color = Color.WHITE
        textSize = dp(34f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isAntiAlias = true
    }
    private val depthUnitPaint = Paint(depthPaint).apply {
        textSize = dp(16f)
        color = Color.argb(190, 255, 255, 255)
    }
    private val tempPaint = Paint(depthUnitPaint).apply { textSize = dp(14f) }
    private val demoPaint = Paint().apply {
        color = Color.rgb(255, 179, 64)
        textSize = dp(13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isAntiAlias = true
        letterSpacing = 0.12f
    }
    private val demoStroke = Paint(demoPaint).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val markerPaint = Paint().apply {
        color = Color.rgb(255, 179, 64)
        isAntiAlias = true
    }

    private val bgColor = Color.rgb(3, 8, 34)

    // intensity -> color: deep blue -> cyan -> yellow -> red
    private val lut = IntArray(256) { v ->
        fun c(x: Int) = x.coerceIn(0, 255)
        when {
            v < 32 -> bgColor
            v < 128 -> Color.rgb(0, c((v - 32) * 2), c(100 + (v - 32)))
            v < 192 -> Color.rgb(c((v - 128) * 4), c(190 + (v - 128)), c(195 - (v - 128) * 3))
            else -> Color.rgb(255, c(255 - (v - 192) * 3), 0)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            step()
            postDelayed(this, 60)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    /** Crisp interface line at the water/bottom boundary. */
    private val bottomLineColor = Color.rgb(255, 232, 176)

    /** Sediment fill below the detected bottom, textured by echo intensity. */
    private fun earth(v: Int, depthBelow: Int): Int {
        val t = 0.70f + 0.30f * (v / 255f)
        val fade = (1f - depthBelow / 2200f).coerceAtLeast(0.78f)
        val s = t * fade
        return Color.rgb((150 * s).toInt(), (100 * s).toInt(), (56 * s).toInt())
    }

    private fun step() {
        var changed = false
        val fresh = EchoHistory.since(lastSeq)
        for (c in fresh) {
            lastSeq = c.seq
            latestDepth = c.depthM
            // water column above the detected bottom, solid earth below it
            val bottomIdx = if (c.depthM > 0.3) {
                (c.depthM * EchoHistory.SAMPLES_PER_M).toInt()
            } else {
                Int.MAX_VALUE // no bottom lock: all water
            }
            for (i in 0 until SAMPLES) {
                val v = if (i < c.samples.size) c.samples[i].toInt() and 0xFF else 0
                colBuf[i] = when {
                    i < bottomIdx -> lut[v]
                    i < bottomIdx + 3 -> bottomLineColor
                    else -> earth(v, i - bottomIdx)
                }
            }
            bitmap.setPixels(colBuf, 0, 1, writeCol, 0, 1, SAMPLES)
            ascopeBmp.setPixels(colBuf, 0, 1, 0, 0, 1, SAMPLES)
            writeCol = (writeCol + 1) % COLS
            if (filled < COLS) filled++
            changed = true
        }

        // ---- stepped auto-range with hysteresis (see class doc)
        if (fresh.isNotEmpty()) {
            val need = latestDepth * FIT
            val fitStep = RANGE_STEPS_M.firstOrNull { it >= need } ?: RANGE_STEPS_M.last()
            val now = SystemClock.elapsedRealtime()
            if (fitStep > rangeM) {
                rangeM = fitStep // deepening: react immediately, bottom must stay visible
                fitsSmallerSince = 0L
            } else if (fitStep < rangeM) {
                if (fitsSmallerSince == 0L) fitsSmallerSince = now
                if (now - fitsSmallerSince > STEP_DOWN_MS) {
                    rangeM = fitStep
                    fitsSmallerSince = 0L
                }
            } else {
                fitsSmallerSince = 0L
            }
        }

        // ---- eased zoom toward the selected range
        val target = (rangeM * EchoHistory.SAMPLES_PER_M).toFloat().coerceAtMost(SAMPLES.toFloat())
        if (abs(target - windowF) > 0.5f) {
            windowF += (target - windowF) * 0.10f
            changed = true
        }

        if (changed) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        val w = width.toFloat()
        val h = height.toFloat()
        val ascopeW = dp(14f)
        val plotW = w - ascopeW - dp(4f)
        val window = windowF.coerceIn(30f, SAMPLES.toFloat())

        // ---- waterfall (ring unroll: oldest [writeCol..COLS) then [0..writeCol))
        if (filled > 0) {
            val oldW = (COLS - writeCol) * plotW / COLS
            canvas.drawBitmap(
                bitmap, Rect(writeCol, 0, COLS, window.toInt()),
                RectF(0f, 0f, oldW, h), bmpPaint,
            )
            canvas.drawBitmap(
                bitmap, Rect(0, 0, writeCol, window.toInt()),
                RectF(oldW, 0f, plotW, h), bmpPaint,
            )
            // live A-scope strip
            canvas.drawBitmap(
                ascopeBmp, Rect(0, 0, 1, window.toInt()),
                RectF(plotW + dp(4f), 0f, w, h), bmpPaint,
            )
        }

        // ---- depth grid at nice intervals (display units)
        val windowDisp = window / EchoHistory.SAMPLES_PER_M * if (Units.feet) 3.28084 else 1.0
        val unit = if (Units.feet) "ft" else "m"
        val interval = niceInterval(windowDisp / 4.5)
        var d = interval
        while (d < windowDisp) {
            val y = (d / windowDisp * h).toFloat()
            canvas.drawLine(0f, y, plotW, y, gridPaint)
            canvas.drawLine(plotW - dp(6f), y, plotW, y, tickPaint)
            canvas.drawText(
                String.format(Locale.US, "%.0f", d),
                plotW - dp(10f), y + dp(4f), labelPaint,
            )
            d += interval
        }
        canvas.drawText(unit, plotW - dp(10f), h - dp(8f), labelPaint)

        // ---- depth marker on the A-scope
        if (latestDepth > 0) {
            val y = (latestDepth * EchoHistory.SAMPLES_PER_M / window * h).toFloat()
            if (y < h) {
                canvas.drawCircle(plotW + dp(2f), y, dp(3.5f), markerPaint)
            }
        }

        // ---- readout chip (top-left)
        val inset = dp(12f)
        val depthTxt = if (Units.feet) {
            String.format(Locale.US, "%.1f", latestDepth * 3.28084)
        } else {
            String.format(Locale.US, "%.2f", latestDepth)
        }
        val tempC = BridgeState.flow.value.tempC
        val tempTxt = tempC?.let { Units.temp(it) }
        val numW = depthPaint.measureText(depthTxt)
        val unitW = depthUnitPaint.measureText(" $unit")
        val chipW = numW + unitW + dp(28f)
        val chipH = if (tempTxt != null) dp(74f) else dp(54f)
        val chip = RectF(inset, inset, inset + chipW, inset + chipH)
        canvas.drawRoundRect(chip, dp(14f), dp(14f), chipBgPaint)
        canvas.drawText(depthTxt, chip.left + dp(14f), chip.top + dp(38f), depthPaint)
        canvas.drawText(" $unit", chip.left + dp(14f) + numW, chip.top + dp(38f), depthUnitPaint)
        if (tempTxt != null) {
            canvas.drawText(tempTxt, chip.left + dp(14f), chip.top + dp(62f), tempPaint)
        }

        // ---- DEMO pill
        if (BridgeState.flow.value.phase == "DEMO") {
            val txt = "DEMO"
            val tw = demoPaint.measureText(txt)
            val pill = RectF(
                plotW - tw - dp(26f), inset,
                plotW - dp(6f), inset + dp(24f),
            )
            canvas.drawRoundRect(pill, dp(12f), dp(12f), chipBgPaint)
            canvas.drawRoundRect(pill, dp(12f), dp(12f), demoStroke)
            canvas.drawText(txt, pill.left + dp(10f), pill.bottom - dp(7f), demoPaint)
        }
    }

    /** 1/2/5×10^k interval that yields ~4–6 gridlines. */
    private fun niceInterval(raw: Double): Double {
        var mag = 1.0
        while (raw / mag >= 10) mag *= 10
        while (raw / mag < 1) mag /= 10
        val n = raw / mag
        return when {
            n < 1.5 -> 1.0
            n < 3.5 -> 2.0
            n < 7.5 -> 5.0
            else -> 10.0
        } * mag
    }
}
