package com.rewen.sonarbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import java.util.Locale
import kotlin.math.abs

/**
 * Fish-finder waterfall: newest echo column on the right, scrolling left,
 * with a live A-scope strip on the right edge.
 *
 * Echo columns are upsampled 4x in intensity space (Catmull-Rom + a mild
 * contrast curve) before hitting the ring bitmap, so the residual on-screen
 * upscale is small and marks stay defined instead of smearing. The bottom is
 * vector geometry from each column's reported depth: crisp hardness line +
 * gradient earth fill at any zoom.
 *
 * Range: AUTO follows real-unit practice (discrete steps, deepen instantly,
 * shallow only after a dwell, eased zoom). The -/AUTO/+ chips switch to
 * manual stepping through the same table.
 */
class SonarView(context: Context) : android.view.View(context) {

    private companion object {
        const val COLS = 600
        const val SAMPLES = EchoHistory.SAMPLES
        const val UP = 4 // vertical data-space upsample factor
        const val HI = SAMPLES * UP
        val RANGE_STEPS_M = listOf(2.0, 5.0, 10.0, 15.0, 20.0, 30.0, 40.0, 60.0, 80.0)
        const val FIT = 1.25
        const val STEP_DOWN_MS = 6000L
    }

    private val dens = resources.displayMetrics.density
    private fun dp(v: Float) = v * dens

    private val bitmap = Bitmap.createBitmap(COLS, HI, Bitmap.Config.ARGB_8888)
    private val ascopeBmp = Bitmap.createBitmap(1, HI, Bitmap.Config.ARGB_8888)
    private val colBuf = IntArray(HI)
    private val smooth = FloatArray(HI)
    private val depthRing = FloatArray(COLS) { -1f }
    private var writeCol = 0
    private var filled = 0
    private var lastSeq = 0L
    private var latestDepth = 0.0

    // range state
    private var autoRange = true
    private var rangeIdx = 2 // 10 m
    private var rangeM = RANGE_STEPS_M[rangeIdx]
    private var windowF = (rangeM * EchoHistory.SAMPLES_PER_M).toFloat()
    private var fitsSmallerSince = 0L

    // chip hit areas (filled during onDraw)
    private val minusRect = RectF()
    private val autoRect = RectF()
    private val plusRect = RectF()

    private val bmpPaint = Paint().apply { isFilterBitmap = true }
    // subtle hairline keeping the interface crisp; Deeper relies on contrast
    // alone, our 9.5 samples/m needs a little help
    private val bottomLinePaint = Paint().apply {
        color = Color.argb(215, 255, 214, 140)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        isAntiAlias = true
        pathEffect = CornerPathEffect(dp(6f))
    }
    private val tickPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = dp(1.8f)
    }
    private val tickHalo = Paint().apply {
        color = Color.argb(170, 0, 0, 0)
        strokeWidth = dp(4.5f)
    }
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = dp(12f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.RIGHT
        isAntiAlias = true
        setShadowLayer(dp(2.5f), 0f, 0f, Color.BLACK)
    }
    private val labelScrim = Paint().apply {
        color = Color.argb(120, 0, 0, 0)
        isAntiAlias = true
    }
    private val chipBgPaint = Paint().apply {
        color = Color.argb(140, 0, 0, 0)
        isAntiAlias = true
    }
    private val chipStrokePaint = Paint().apply {
        color = Color.argb(90, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        isAntiAlias = true
    }
    private val chipTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = dp(15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val chipTextActive = Paint(chipTextPaint).apply {
        color = Color.rgb(255, 179, 64)
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
    // near-black water, Deeper-style high contrast
    private val bgColor = Color.rgb(1, 3, 10)
    private val bottomPath = Path()

    // water-column echoes: deep blue -> cyan -> yellow -> red
    private val lut = IntArray(256) { v ->
        fun c(x: Int) = x.coerceIn(0, 255)
        when {
            v < 32 -> bgColor
            v < 128 -> Color.rgb(0, c((v - 32) * 2), c(100 + (v - 32)))
            v < 192 -> Color.rgb(c((v - 128) * 4), c(190 + (v - 128)), c(195 - (v - 128) * 3))
            else -> Color.rgb(255, c(255 - (v - 192) * 3), 0)
        }
    }

    /**
     * Below the detected bottom the echo renders in orange monochrome:
     * intensity = brightness, so hard bottom reads as a thick blazing mass
     * and soft as dim grain. Weak/no return falls to dark earth brown, not
     * black — the ground isn't hollow.
     */
    private val bottomLut = IntArray(256) { v ->
        val t = if (v < 26) 0f else (v - 26) / 229f
        Color.rgb(
            (78 + 177 * t).toInt(),
            (52 + 92 * t).toInt(),
            (28 + 2 * t).toInt(),
        )
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

    /** Catmull-Rom upsample + mild contrast curve, then LUT into colBuf —
     *  water palette above the reported bottom, orange monochrome below. */
    private fun expandColumn(samples: ByteArray, depthM: Double) {
        val n = SAMPLES
        fun s(i: Int) = (samples.getOrElse(i.coerceIn(0, n - 1)) { 0 }.toInt() and 0xFF).toFloat()
        for (j in 0 until HI) {
            val x = j.toFloat() / UP
            val i = x.toInt()
            val t = x - i
            val p0 = s(i - 1); val p1 = s(i); val p2 = s(i + 1); val p3 = s(i + 2)
            val v = 0.5f * (
                (2f * p1) +
                    (-p0 + p2) * t +
                    (2f * p0 - 5f * p1 + 4f * p2 - p3) * t * t +
                    (-p0 + 3f * p1 - 3f * p2 + p3) * t * t * t
                )
            smooth[j] = v
        }
        val bottomHi = if (depthM > 0.3) {
            (depthM * EchoHistory.SAMPLES_PER_M * UP).toInt()
        } else {
            Int.MAX_VALUE // no bottom lock: all water
        }
        // user gain (sensitivity) on top of the noise-floor cut; the noise
        // filter raises that floor (off/low/med/high -> 18/32/46/60)
        val g = 1.18f * Units.gain
        val floor = 18f + Units.noiseFilter * 14f
        // surface clarity: fade the top clutter band (low/med/high metres)
        val clarityM = when (Units.surfaceClarity) {
            1 -> 0.6; 2 -> 1.0; 3 -> 1.5; else -> 0.0
        }
        val clarityRows = (clarityM * EchoHistory.SAMPLES_PER_M * UP).toInt()
        for (j in 0 until HI) {
            var v = ((smooth[j] - floor) * g).coerceIn(0f, 255f)
            if (j < clarityRows) {
                // strongest suppression at the surface, easing to none
                v *= 0.15f + 0.85f * (j.toFloat() / clarityRows)
            }
            colBuf[j] = if (j < bottomHi) lut[v.toInt()] else bottomLut[v.toInt()]
        }
    }

    private fun step() {
        var changed = false
        val fresh = EchoHistory.since(lastSeq)
        for (c in fresh) {
            lastSeq = c.seq
            latestDepth = c.depthM
            expandColumn(c.samples, c.depthM)
            bitmap.setPixels(colBuf, 0, 1, writeCol, 0, 1, HI)
            ascopeBmp.setPixels(colBuf, 0, 1, 0, 0, 1, HI)
            depthRing[writeCol] = if (c.depthM > 0.3) c.depthM.toFloat() else -1f
            writeCol = (writeCol + 1) % COLS
            if (filled < COLS) filled++
            changed = true
        }

        // AUTO: stepped range with hysteresis; manual: rangeM pinned by chips
        if (autoRange && fresh.isNotEmpty()) {
            val need = latestDepth * FIT
            val fitStep = RANGE_STEPS_M.firstOrNull { it >= need } ?: RANGE_STEPS_M.last()
            val now = SystemClock.elapsedRealtime()
            if (fitStep > rangeM) {
                rangeM = fitStep
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
            rangeIdx = RANGE_STEPS_M.indexOf(rangeM).coerceAtLeast(0)
        }

        // eased zoom toward the selected range
        val target = (rangeM * EchoHistory.SAMPLES_PER_M).toFloat().coerceAtMost(SAMPLES.toFloat())
        if (abs(target - windowF) > 0.5f) {
            windowF += (target - windowF) * 0.10f
            changed = true
        }

        if (changed) invalidate()
    }

    // ---------------------------------------------------------------- input

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val x = event.x
                val y = event.y
                when {
                    minusRect.contains(x, y) -> setManualRange(rangeIdx - 1)
                    plusRect.contains(x, y) -> setManualRange(rangeIdx + 1)
                    autoRect.contains(x, y) -> {
                        autoRange = true
                        fitsSmallerSince = 0L
                    }
                    else -> return performClick().let { true }
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun setManualRange(idx: Int) {
        autoRange = false
        rangeIdx = idx.coerceIn(0, RANGE_STEPS_M.size - 1)
        rangeM = RANGE_STEPS_M[rangeIdx]
    }

    // ---------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        // boat-legible text: generous bases × user scale (settings > text size)
        val sc = Units.sonarScale
        labelPaint.textSize = dp(15f) * sc
        chipTextPaint.textSize = dp(18f) * sc
        chipTextActive.textSize = dp(18f) * sc
        depthPaint.textSize = dp(40f) * sc
        depthUnitPaint.textSize = dp(19f) * sc
        tempPaint.textSize = dp(17f) * sc
        demoPaint.textSize = dp(15f) * sc
        val w = width.toFloat()
        val h = height.toFloat()
        val ascopeW = dp(14f)
        val plotW = w - ascopeW - dp(4f)
        val window = windowF.coerceIn(30f, SAMPLES.toFloat())
        val windowHi = (window * UP).toInt().coerceAtMost(HI)

        val colW = dp(3f)
        val visible = minOf(filled, (plotW / colW).toInt(), COLS)

        if (visible > 0) {
            val x0 = plotW - visible * colW
            val newest = (writeCol - 1 + COLS) % COLS
            val oldest = (newest - visible + 1 + COLS) % COLS

            // ---- echo bitmap (one or two ring segments)
            if (oldest <= newest) {
                canvas.drawBitmap(
                    bitmap, Rect(oldest, 0, newest + 1, windowHi),
                    RectF(x0, 0f, plotW, h), bmpPaint,
                )
            } else {
                val seg1W = (COLS - oldest) * colW
                canvas.drawBitmap(
                    bitmap, Rect(oldest, 0, COLS, windowHi),
                    RectF(x0, 0f, x0 + seg1W, h), bmpPaint,
                )
                canvas.drawBitmap(
                    bitmap, Rect(0, 0, newest + 1, windowHi),
                    RectF(x0 + seg1W, 0f, plotW, h), bmpPaint,
                )
            }

            // ---- bottom: the orange-monochrome echo texture (already in the
            // bitmap, Deeper-style — density IS hardness) plus a thin vector
            // hairline to keep the interface crisp.
            bottomPath.rewind()
            var lastYv = h + dp(8f)
            for (k in 0 until visible) {
                val idx = (oldest + k) % COLS
                val d = depthRing[idx]
                val y = if (d > 0f) {
                    (d * EchoHistory.SAMPLES_PER_M / window * h).toFloat()
                } else {
                    h + dp(8f)
                }
                val x = x0 + k * colW + colW / 2f
                if (k == 0) {
                    bottomPath.moveTo(x0, y)
                }
                bottomPath.lineTo(x, y)
                lastYv = y
            }
            bottomPath.lineTo(plotW, lastYv)
            canvas.drawPath(bottomPath, bottomLinePaint)

            // ---- live A-scope strip
            canvas.drawBitmap(
                ascopeBmp, Rect(0, 0, 1, windowHi),
                RectF(plotW + dp(4f), 0f, w, h), bmpPaint,
            )
        }

        // ---- depth scale: ticks + labels, guaranteed contrast on any
        // background (dark halo under ticks, scrim chip under labels)
        val windowDisp = window / EchoHistory.SAMPLES_PER_M * if (Units.feet) 3.28084 else 1.0
        val unit = if (Units.feet) "ft" else "m"
        val interval = niceInterval(windowDisp / 4.5)
        fun scaleText(txt: String, y: Float) {
            val xR = plotW - dp(14f)
            val tw = labelPaint.measureText(txt)
            canvas.drawRoundRect(
                RectF(
                    xR - tw - dp(6f), y - labelPaint.textSize,
                    xR + dp(5f), y + dp(5f),
                ),
                dp(6f), dp(6f), labelScrim,
            )
            canvas.drawText(txt, xR, y, labelPaint)
        }
        var d = interval
        while (d < windowDisp) {
            val y = (d / windowDisp * h).toFloat()
            canvas.drawLine(plotW - dp(10f), y, plotW, y, tickHalo)
            canvas.drawLine(plotW - dp(10f), y, plotW, y, tickPaint)
            scaleText(String.format(Locale.US, "%.0f", d), y + dp(5f))
            d += interval
        }
        scaleText(unit, h - dp(8f))

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
        val chipW = numW + unitW + dp(28f) * sc
        val chipH = (if (tempTxt != null) dp(84f) else dp(62f)) * sc
        val chip = RectF(inset, inset, inset + chipW, inset + chipH)
        canvas.drawRoundRect(chip, dp(14f), dp(14f), chipBgPaint)
        canvas.drawText(depthTxt, chip.left + dp(14f) * sc, chip.top + dp(44f) * sc, depthPaint)
        canvas.drawText(" $unit", chip.left + dp(14f) * sc + numW, chip.top + dp(44f) * sc, depthUnitPaint)
        if (tempTxt != null) {
            canvas.drawText(tempTxt, chip.left + dp(14f) * sc, chip.top + dp(70f) * sc, tempPaint)
        }

        // ---- DEMO pill
        if (BridgeState.flow.value.phase == "DEMO") {
            val txt = "DEMO"
            val tw = demoPaint.measureText(txt)
            val pillH = dp(28f) * sc
            val pill = RectF(
                plotW - tw - dp(26f) * sc, inset,
                plotW - dp(6f), inset + pillH,
            )
            canvas.drawRoundRect(pill, pillH / 2f, pillH / 2f, chipBgPaint)
            canvas.drawRoundRect(pill, pillH / 2f, pillH / 2f, demoStroke)
            canvas.drawText(txt, pill.left + dp(10f) * sc, pill.bottom - dp(9f) * sc, demoPaint)
        }

        // ---- range chips: [ − ] [ AUTO | range ] [ + ]  (bottom-left)
        val chipH2 = dp(46f) * sc
        val chipY = h - chipH2 - dp(14f)
        val bw = dp(56f) * sc
        minusRect.set(inset, chipY, inset + bw, chipY + chipH2)
        val midLabel = if (autoRange) {
            "AUTO"
        } else {
            val r = rangeM * if (Units.feet) 3.28084 else 1.0
            String.format(Locale.US, "%.0f %s", r, unit)
        }
        val midW = maxOf(chipTextPaint.measureText(midLabel) + dp(28f) * sc, dp(76f) * sc)
        autoRect.set(minusRect.right + dp(8f), chipY, minusRect.right + dp(8f) + midW, chipY + chipH2)
        plusRect.set(autoRect.right + dp(8f), chipY, autoRect.right + dp(8f) + bw, chipY + chipH2)

        val chipR = chipH2 / 2f
        for ((rect, label) in listOf(minusRect to "−", autoRect to midLabel, plusRect to "+")) {
            canvas.drawRoundRect(rect, chipR, chipR, chipBgPaint)
            canvas.drawRoundRect(rect, chipR, chipR, chipStrokePaint)
            val p = if (rect === autoRect && autoRange) chipTextActive else chipTextPaint
            canvas.drawText(label, rect.centerX(), rect.centerY() + dp(6f) * sc, p)
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
