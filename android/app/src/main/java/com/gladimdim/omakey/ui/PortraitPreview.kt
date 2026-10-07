package com.gladimdim.omakey.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** A sketch of portrait mode for its card: a phone, the touchpad on top, the phone's keyboard below. */
class PortraitPreview(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val d = density
        val h = height.toFloat()
        val w = h * 0.5f
        val left = (width - w) / 2
        // The phone.
        paint.color = Palette.BG
        r.set(left, 0f, left + w, h)
        canvas.drawRoundRect(r, 12 * d, 12 * d, paint)
        val inset = 5 * d
        val x0 = left + inset
        val x1 = left + w - inset
        var y = inset + 2 * d
        // Toolbar.
        paint.color = Palette.SURFACE
        r.set(x0 + (x1 - x0) * 0.3f, y, x1 - (x1 - x0) * 0.3f, y + 5 * d)
        canvas.drawRoundRect(r, 3 * d, 3 * d, paint)
        y += 9 * d
        // Touchpad: button columns either side of the surface.
        val padBottom = h * 0.5f
        val col = (x1 - x0) * 0.17f
        val gap = 2 * d
        val cells = 4
        val cellH = (padBottom - y - gap * (cells - 1)) / cells
        paint.color = Palette.KEY
        for (i in 0 until cells) {
            val top = y + i * (cellH + gap)
            r.set(x0, top, x0 + col, top + cellH)
            canvas.drawRoundRect(r, 3 * d, 3 * d, paint)
            r.set(x1 - col, top, x1, top + cellH)
            canvas.drawRoundRect(r, 3 * d, 3 * d, paint)
        }
        paint.color = Palette.SURFACE
        r.set(x0 + col + gap, y, x1 - col - gap, padBottom)
        canvas.drawRoundRect(r, 5 * d, 5 * d, paint)
        paint.color = Palette.KEY
        var dy = y + 6 * d
        while (dy < padBottom - 3 * d) {
            var dx = x0 + col + gap + 6 * d
            while (dx < x1 - col - gap - 3 * d) {
                canvas.drawCircle(dx, dy, 0.8f * d, paint)
                dx += 6 * d
            }
            dy += 6 * d
        }
        // The phone's keyboard, in its own tint.
        val kbTop = padBottom + 4 * d
        paint.color = Palette.KEY_MOD
        r.set(x0 - inset + 1, kbTop, x1 + inset - 1, h - 1)
        canvas.drawRoundRect(r, 10 * d, 10 * d, paint)
        val rows = intArrayOf(10, 9, 7, 0)
        val rowH = (h - kbTop - 10 * d) / rows.size
        val keyW = (x1 - x0) / 10
        paint.color = Palette.KEY
        rows.forEachIndexed { i, n ->
            val top = kbTop + 5 * d + i * rowH
            if (n == 0) {
                // The space bar row.
                r.set(x0 + keyW * 2.5f, top + 1.5f * d, x1 - keyW * 2.5f, top + rowH - 1.5f * d)
                canvas.drawRoundRect(r, 2 * d, 2 * d, paint)
                paint.color = Palette.ACCENT
                r.set(x1 - keyW * 2f, top + 1.5f * d, x1 - 1 * d, top + rowH - 1.5f * d)
                canvas.drawRoundRect(r, 2 * d, 2 * d, paint)
                paint.color = Palette.KEY
                return@forEachIndexed
            }
            val start = x0 + (10 - n) * keyW / 2
            for (k in 0 until n) {
                r.set(start + k * keyW + d, top + 1.5f * d, start + (k + 1) * keyW - d, top + rowH - 1.5f * d)
                canvas.drawRoundRect(r, 2 * d, 2 * d, paint)
            }
        }
    }
}
