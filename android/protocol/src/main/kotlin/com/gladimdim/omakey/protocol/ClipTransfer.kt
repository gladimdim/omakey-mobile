package com.gladimdim.omakey.protocol

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * One clipboard transfer with omakeyd (PROTOCOL.md, CLIP): the phone's
 * text to the desktop ([put]), or the desktop's to the phone ([get]).
 * Socket-free, like [ClientSession], which owns the one under way: it asks
 * [body] what to send and hands over each reply. Not thread-safe.
 */
class ClipTransfer private constructor(
    val op: Int,
    private val flags: Int,
    private val text: ByteArray,
    val id: Int,
) {
    sealed interface Outcome {
        /** The desktop has the text, and pasted it when asked to. */
        data object Sent : Outcome

        /** The desktop's text. */
        class Received(val text: String, val sensitive: Boolean) : Outcome

        /** It ended without: a reply's [status] ([Clip.EMPTY] and friends), or [GAVE_UP]. */
        data class Failed(val status: Int) : Outcome
    }

    /** Put: the bytes the desktop has. */
    private var sent = 0
    /** Get: the bytes so far. */
    private val received = ByteArrayOutputStream()

    /** When to send next; 0 is now. */
    var dueAt = 0L
        private set
    private var lastSent = 0L
    /** When something last moved; -1 until the first send. */
    private var lastProgress = -1L
    private var progressed = false

    /** What to send at [nowMs], or null when it isn't time. */
    fun body(nowMs: Long): ByteArray? {
        if (lastProgress < 0 || progressed) lastProgress = nowMs
        progressed = false
        if (nowMs < dueAt) return null
        lastSent = nowMs
        dueAt = nowMs + RESEND_MS
        return when (op) {
            Clip.PUT -> Clip(Clip.PUT, id, offset = sent, flags = flags, total = text.size,
                data = text.copyOfRange(sent, minOf(sent + Clip.CHUNK, text.size))).encode(false)
            else -> Clip(Clip.GET, id, offset = received.size(), flags = flags).encode(false)
        }
    }

    /** Nothing moved for [GIVE_UP_MS]. */
    fun expired(nowMs: Long): Boolean = lastProgress >= 0 && !progressed && nowMs - lastProgress > GIVE_UP_MS

    /** A new session: the desktop forgot the transfer, so start it again. */
    fun restart() {
        sent = 0
        received.reset()
        dueAt = 0
    }

    /** Digest a reply; the outcome once it's over, else null. */
    fun onReply(c: Clip): Outcome? {
        if (c.clipId != id || c.op != op) return null
        if (c.status != Clip.OK && c.status != Clip.WORKING) return Outcome.Failed(c.status)
        // A put's reply says what the desktop has, also while it works.
        if (op == Clip.PUT && c.offset > sent) {
            sent = minOf(c.offset, text.size)
            progressed = true
        }
        if (c.status == Clip.WORKING) {
            // Ask again shortly; a resend would only add to the queue.
            dueAt = lastSent + POLL_MS
            return null
        }
        if (op == Clip.PUT) {
            if (sent >= text.size) return Outcome.Sent
        } else {
            if (c.offset == received.size() && c.data.isNotEmpty()) {
                received.write(c.data)
                progressed = true
            }
            if (received.size() >= c.total) {
                return Outcome.Received(received.toString(Charsets.UTF_8.name()), c.flags and Clip.SENSITIVE != 0)
            }
        }
        // The next piece, now.
        dueAt = 0
        return null
    }

    companion object {
        /** No reply in this long: send again. */
        const val RESEND_MS = 150L
        /** The desktop is working on it: ask again after this long. */
        const val POLL_MS = 50L
        /** Nothing moved for this long: give up. */
        const val GIVE_UP_MS = 5000L
        /** [Outcome.Failed] status when the desktop stopped answering. */
        const val GAVE_UP = -1

        private val random = SecureRandom()

        /**
         * The phone's [text] to the desktop clipboard, pasted there with
         * [paste]. Null when it's empty or over [Clip.MAX_TEXT] bytes.
         */
        fun put(text: String, paste: Boolean, sensitive: Boolean, id: Int = random.nextInt()): ClipTransfer? {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty() || bytes.size > Clip.MAX_TEXT) return null
            val flags = (if (paste) Clip.PASTE else 0) or (if (sensitive) Clip.SENSITIVE else 0)
            return ClipTransfer(Clip.PUT, flags, bytes, id)
        }

        /** The desktop's clipboard text; with [copy], what's selected there, copied first. */
        fun get(copy: Boolean, id: Int = random.nextInt()) =
            ClipTransfer(Clip.GET, if (copy) Clip.COPY else 0, ByteArray(0), id)
    }
}
