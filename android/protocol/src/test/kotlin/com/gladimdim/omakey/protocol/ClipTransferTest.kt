package com.gladimdim.omakey.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipTransferTest {
    private fun sent(body: ByteArray?) = Clip.decode(body!!, reply = false)!!

    @Test
    fun clipRoundTripsWithStatusOnlyInReplies() {
        val c = Clip(Clip.PUT, 0x01020304, offset = 1024, flags = Clip.PASTE, total = 1030, data = "hello!".toByteArray())
        val enc = c.encode(false)
        assertEquals(14 + 6, enc.size)
        val d = Clip.decode(enc, false)!!
        assertEquals(listOf(c.op, c.clipId, c.offset, c.flags, c.total), listOf(d.op, d.clipId, d.offset, d.flags, d.total))
        assertArrayEquals(c.data, d.data)
        val r = Clip(Clip.GET, 7, status = Clip.WORKING)
        assertEquals(15, r.encode(true).size)
        assertEquals(Clip.WORKING, Clip.decode(r.encode(true), true)!!.status)
        assertNull(Clip.decode(r.encode(true).copyOf(14), true))
    }

    @Test
    fun putSendsPiecesInOrderAndEndsWhenAllAreIn() {
        val text = "ї".repeat(1000) // 2000 bytes: two pieces
        val t = ClipTransfer.put(text, paste = true, sensitive = true, id = 5)!!
        val first = sent(t.body(0))
        assertEquals(listOf(0, 2000, Clip.CHUNK), listOf(first.offset, first.total, first.data.size))
        assertEquals(Clip.PASTE or Clip.SENSITIVE, first.flags)
        // Not due until the resend time, then the same piece again.
        assertNull(t.body(100))
        assertEquals(0, sent(t.body(ClipTransfer.RESEND_MS)).offset)
        // The desktop has the first piece: the second goes at once.
        assertNull(t.onReply(Clip(Clip.PUT, 5, offset = Clip.CHUNK, total = 2000)))
        val second = sent(t.body(160))
        assertEquals(listOf(Clip.CHUNK, 2000 - Clip.CHUNK), listOf(second.offset, second.data.size))
        // All in, but the desktop is still setting it: ask again shortly.
        assertNull(t.onReply(Clip(Clip.PUT, 5, status = Clip.WORKING, offset = 2000, total = 2000)))
        assertNull(t.body(170))
        val poll = sent(t.body(160 + ClipTransfer.POLL_MS))
        assertEquals(listOf(2000, 0), listOf(poll.offset, poll.data.size))
        assertEquals(ClipTransfer.Outcome.Sent, t.onReply(Clip(Clip.PUT, 5, offset = 2000, total = 2000)))
    }

    @Test
    fun getGathersPiecesAndIgnoresOthersReplies() {
        val text = "selected ✓ ".repeat(150)
        val bytes = text.toByteArray()
        val t = ClipTransfer.get(copy = true, id = 9)
        assertEquals(Clip.COPY, sent(t.body(0)).flags)
        assertNull(t.onReply(Clip(Clip.GET, 9, status = Clip.WORKING)))
        // A reply for another transfer changes nothing.
        assertNull(t.onReply(Clip(Clip.GET, 8, total = 3, data = "old".toByteArray())))
        var now = 100L
        var outcome: ClipTransfer.Outcome? = null
        while (outcome == null) {
            val ask = sent(t.body(now) ?: t.body(now + ClipTransfer.RESEND_MS))
            val end = minOf(ask.offset + Clip.CHUNK, bytes.size)
            outcome = t.onReply(Clip(Clip.GET, 9, offset = ask.offset, flags = Clip.SENSITIVE, total = bytes.size,
                data = bytes.copyOfRange(ask.offset, end)))
            now += 10
        }
        val got = outcome as ClipTransfer.Outcome.Received
        assertEquals(text, got.text)
        assertTrue(got.sensitive)
    }

    @Test
    fun failuresEndItAndSilenceGivesUp() {
        val t = ClipTransfer.get(copy = false, id = 1)
        t.body(0)
        assertEquals(ClipTransfer.Outcome.Failed(Clip.EMPTY), t.onReply(Clip(Clip.GET, 1, status = Clip.EMPTY)))

        val quiet = ClipTransfer.get(copy = false, id = 2)
        quiet.body(1000)
        assertTrue(!quiet.expired(1000 + ClipTransfer.GIVE_UP_MS))
        assertTrue(quiet.expired(1001 + ClipTransfer.GIVE_UP_MS))
    }

    @Test
    fun emptyAndOversizedTextIsNotSent() {
        assertNull(ClipTransfer.put("", paste = true, sensitive = false))
        assertNull(ClipTransfer.put("x".repeat(Clip.MAX_TEXT + 1), paste = true, sensitive = false))
        assertTrue(ClipTransfer.put("x".repeat(Clip.MAX_TEXT), paste = true, sensitive = false) != null)
    }
}
