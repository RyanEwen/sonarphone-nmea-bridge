package com.rewen.sonarbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale

/**
 * Classic fish-finder waterfall: newest echo column on the right, scrolling
 * left. Columns land in a ring-buffer bitmap (1 setPixels per column), and
 * onDraw unrolls the ring with two scaled drawBitmap calls — cheap enough to
 * repaint at the ~5–15 Hz the T-Box streams.
 */
class SonarView(context: Context) : android.view.View(context) {

    private companion object {
        const val COLS = 600
        const val SAMPLES = EchoHistory.SAMPLES
    }

    private val bitmap = Bitmap.createBitmap(COLS, SAMPLES, Bitmap.Config.ARGB_8888)
    private val colBuf = IntArray(SAMPLES)
    private var writeCol = 0
    private var filled = 0
    private var lastSeq = 0L
    private var latestDepth = 0.0
    private var windowSamples = SAMPLES / 4 // display 0..~20 m until data says otherwise

    private val bmpPaint = Paint().apply { isFilterBitmap = true }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 44f
        typeface = Typeface.MONOSPACE
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val scalePaint = Paint().apply {
        color = Color.argb(160, 255, 255, 255)
        textSize = 28f
        typeface = Typeface.MONOSPACE
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    // intensity -> color: deep blue -> cyan -> yellow -> red
    private val lut = IntArray(256) { v ->
        fun c(x: Int) = x.coerceIn(0, 255)
        when {
            v < 32 -> Color.rgb(3, 8, 34)
            v < 128 -> Color.rgb(0, c((v - 32) * 2), c(100 + (v - 32)))
            v < 192 -> Color.rgb(c((v - 128) * 4), c(190 + (v - 128)), c(195 - (v - 128) * 3))
            else -> Color.rgb(255, c(255 - (v - 192) * 3), 0)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            pull()
            postDelayed(this, 100)
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

    private fun pull() {
        val fresh = EchoHistory.since(lastSeq)
        if (fresh.isEmpty()) return
        var maxDepth = 0.0
        for (c in fresh) {
            lastSeq = c.seq
            latestDepth = c.depthM
            if (c.depthM > maxDepth) maxDepth = c.depthM
            for (i in 0 until SAMPLES) {
                colBuf[i] = lut[if (i < c.samples.size) c.samples[i].toInt() and 0xFF else 0]
            }
            bitmap.setPixels(colBuf, 0, 1, writeCol, 0, 1, SAMPLES)
            writeCol = (writeCol + 1) % COLS
            if (filled < COLS) filled++
        }
        // auto-window: show a bit past the bottom, expand-fast shrink-slow
        val want = ((latestDepth * 1.35 + 1.0) * EchoHistory.SAMPLES_PER_M).toInt()
            .coerceIn(60, SAMPLES)
        windowSamples = if (want > windowSamples) want
            else (windowSamples * 15 + want) / 16
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(3, 8, 34))
        val w = width.toFloat()
        val h = height.toFloat()
        if (filled > 0) {
            // ring unroll: oldest segment [writeCol..COLS) then newest [0..writeCol)
            val oldW = (COLS - writeCol) * w / COLS
            canvas.drawBitmap(
                bitmap, Rect(writeCol, 0, COLS, windowSamples),
                RectF(0f, 0f, oldW, h), bmpPaint,
            )
            canvas.drawBitmap(
                bitmap, Rect(0, 0, writeCol, windowSamples),
                RectF(oldW, 0f, w, h), bmpPaint,
            )
        }
        // depth scale (0, 1/2, full window)
        val windowM = windowSamples / EchoHistory.SAMPLES_PER_M
        canvas.drawText("0", w - 70f, 30f, scalePaint)
        canvas.drawText(String.format(Locale.US, "%.0f", windowM / 2), w - 70f, h / 2, scalePaint)
        canvas.drawText(String.format(Locale.US, "%.0f m", windowM), w - 90f, h - 12f, scalePaint)
        canvas.drawText(String.format(Locale.US, "%.2f m", latestDepth), 16f, 52f, textPaint)
    }
}
