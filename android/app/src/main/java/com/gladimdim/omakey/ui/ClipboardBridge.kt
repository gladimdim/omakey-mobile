package com.gladimdim.omakey.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import com.gladimdim.omakey.net.Link
import com.gladimdim.omakey.protocol.Clip
import com.gladimdim.omakey.protocol.ClipTransfer
import com.gladimdim.omakey.protocol.Wire
import java.security.MessageDigest

/**
 * Copy and Paste between the phone and the computer (PROTOCOL.md, CLIP).
 *
 * - **Copy** copies what's selected on the computer and puts it on the
 *   phone's clipboard too.
 * - **Paste** pastes on the computer: the phone's clipboard when it has
 *   something new since the two last swapped text, else the computer's own,
 *   so whichever was copied last is what pastes.
 *
 * Without omakeyd's clipboard (a Bluetooth keyboard, an older omakeyd) they
 * are the computer's own copy and paste: Ctrl+Insert and Shift+Insert.
 */
class ClipboardBridge(
    private val context: Context,
    private val link: () -> Link?,
    /** Presses [modifier] + [key] on the computer. */
    private val shortcut: (modifier: Int, key: Int) -> Unit,
    /** The computer's name, for messages. */
    private val hostName: () -> String,
) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val prefs = context.getSharedPreferences("clipboard", Context.MODE_PRIVATE)

    /**
     * SHA-256 of the text the phone and the computer last swapped, so Paste
     * can tell whether the phone's clipboard has something newer. Only the
     * hash is kept, never the text.
     */
    private var lastSwapped: String?
        get() = prefs.getString("last", null)
        set(v) = prefs.edit().putString("last", v).apply()

    /** The text a put is sending, until it's in. */
    private var sending: String? = null

    fun copy() {
        if (link()?.clip(ClipTransfer.get(copy = true)) != true) shortcut(KEY_LEFTCTRL, KEY_INSERT)
    }

    fun paste() {
        val l = link()
        if (l == null || l.features and Wire.FEATURE_CLIPBOARD == 0) return shortcut(KEY_LEFTSHIFT, KEY_INSERT)
        val (text, sensitive) = phoneText() ?: return shortcut(KEY_LEFTSHIFT, KEY_INSERT)
        val hash = sha256(text)
        // Nothing new on the phone: the computer's clipboard is as new or newer.
        if (hash == lastSwapped) return shortcut(KEY_LEFTSHIFT, KEY_INSERT)
        val t = ClipTransfer.put(text, paste = true, sensitive = sensitive)
        if (t == null) {
            toast("The phone's clipboard is too large to send (over 64 KB)")
            return shortcut(KEY_LEFTSHIFT, KEY_INSERT)
        }
        if (!l.clip(t)) return shortcut(KEY_LEFTSHIFT, KEY_INSERT)
        sending = hash
    }

    /** A transfer ended. Main thread. */
    fun onOutcome(outcome: ClipTransfer.Outcome) {
        when (outcome) {
            ClipTransfer.Outcome.Sent -> {
                lastSwapped = sending
                sending = null
            }
            is ClipTransfer.Outcome.Received -> {
                val clip = ClipData.newPlainText("Copied on ${hostName()}", outcome.text)
                if (outcome.sensitive) {
                    clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
                }
                clipboard.setPrimaryClip(clip)
                lastSwapped = sha256(outcome.text)
                // Android 13 and later show what was copied by themselves.
                if (Build.VERSION.SDK_INT < 33) toast("Copied from ${hostName()}")
            }
            is ClipTransfer.Outcome.Failed -> {
                sending = null
                toast(when (outcome.status) {
                    Clip.EMPTY -> "Nothing to copy: ${hostName()}'s clipboard has no text"
                    Clip.TOO_LARGE -> "The copied text is too large for the phone (over 64 KB)"
                    ClipTransfer.GAVE_UP -> "${hostName()} didn't answer"
                    else -> "${hostName()} couldn't reach its clipboard"
                })
            }
        }
    }

    /** The phone's clipboard as text, and whether it's marked sensitive; null when there's none. */
    private fun phoneText(): Pair<String, Boolean>? {
        val clip = try {
            clipboard.primaryClip
        } catch (e: SecurityException) {
            null
        } ?: return null
        val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return null
        return text to (clip.description.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true)
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    companion object {
        /** Layout keys that are Copy and Paste: the app does these itself. */
        const val KEY_COPY = 133
        const val KEY_PASTE = 135
        private const val KEY_LEFTCTRL = 29
        private const val KEY_LEFTSHIFT = 42
        private const val KEY_INSERT = 110
        /** ClipDescription.EXTRA_IS_SENSITIVE, from Android 13; a plain extra before. */
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** The Copy and Paste icons for the top bar, drawn as outlines like the bar's glyphs. */
class ClipIcon(private val paste: Boolean, color: Int, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }
    private val r = RectF()

    override fun getIntrinsicWidth() = (SIZE * density).toInt()
    override fun getIntrinsicHeight() = (SIZE * density).toInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val u = minOf(b.width(), b.height()) / 18f
        val x = b.left + (b.width() - 18 * u) / 2
        val y = b.top + (b.height() - 18 * u) / 2
        val corner = 2.2f * u
        if (paste) {
            // A clipboard: the board, and the clip at its top.
            r.set(x + 3 * u, y + 3 * u, x + 15 * u, y + 17 * u)
            canvas.drawRoundRect(r, corner, corner, paint)
            r.set(x + 6.5f * u, y + 1.5f * u, x + 11.5f * u, y + 4.5f * u)
            canvas.drawRoundRect(r, 1.2f * u, 1.2f * u, paint)
            canvas.drawLine(x + 6.5f * u, y + 9 * u, x + 11.5f * u, y + 9 * u, paint)
            canvas.drawLine(x + 6.5f * u, y + 12.5f * u, x + 10 * u, y + 12.5f * u, paint)
        } else {
            // Two sheets: the copy in front, the original peeking out behind.
            r.set(x + 6 * u, y + 6 * u, x + 16 * u, y + 17 * u)
            canvas.drawRoundRect(r, corner, corner, paint)
            val back = android.graphics.Path().apply {
                moveTo(x + 3 * u, y + 13 * u)
                lineTo(x + 3 * u, y + 3.5f * u)
                quadTo(x + 3 * u, y + 2 * u, x + 4.5f * u, y + 2 * u)
                lineTo(x + 12 * u, y + 2 * u)
            }
            canvas.drawPath(back, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val SIZE = 18f
    }
}
