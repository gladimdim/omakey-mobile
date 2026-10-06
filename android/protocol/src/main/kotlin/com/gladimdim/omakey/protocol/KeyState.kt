package com.gladimdim.omakey.protocol

/**
 * What the phone holds right now, and the press/release events the server
 * hasn't acknowledged yet. The touch thread calls [press]/[release]; the
 * network thread calls [buildInput]/[ack]. Every method is synchronized.
 *
 * Two keys in a layout may share a code, so held codes are reference
 * counted: the server sees one press when the first finger lands and one
 * release when the last lifts.
 */
class KeyState {
    private val counts = IntArray(MAX_CODE + 1)
    /** Held codes in ascending order, as PROTOCOL.md sends them. */
    private val heldOrder = IntArray(MAX_HELD)
    private var heldCount = 0

    private val pending = ArrayDeque<KeyEvent>()
    private var nextEseq = 1

    // Touchpad motion and scroll waiting for the next INPUT. Fractions are
    // kept, so slow finger movement still adds up to whole counts.
    private var moveX = 0f
    private var moveY = 0f
    private var wheel = 0f
    private var hwheel = 0f

    /** Bumped on every change, so the sender knows to transmit now. */
    @Volatile
    var version = 0L
        private set

    @Synchronized
    fun press(code: Int): Boolean {
        if (code !in 1..MAX_CODE) return false
        if (counts[code]++ > 0) return false
        if (heldCount < MAX_HELD) insertHeld(code)
        addEvent(code, 1)
        return true
    }

    @Synchronized
    fun release(code: Int): Boolean {
        if (code !in 1..MAX_CODE || counts[code] == 0) return false
        if (--counts[code] > 0) return false
        removeHeld(code)
        addEvent(code, 0)
        return true
    }

    /** Lets go of everything, sending a release for each held key. */
    @Synchronized
    fun releaseAll() {
        while (heldCount > 0) {
            val code = heldOrder[heldCount - 1]
            counts[code] = 1
            release(code)
        }
        counts.fill(0)
    }

    /** A new session starts: sequence numbers restart, old events are moot. */
    @Synchronized
    fun resetSession() {
        pending.clear()
        nextEseq = 1
        version++
    }

    @Synchronized
    fun ack(lastEseq: Int) {
        while (pending.isNotEmpty() && !Wire.eseqNewer(pending.first().eseq, lastEseq)) pending.removeFirst()
    }

    @get:Synchronized
    val hasUnacked: Boolean get() = pending.isNotEmpty()

    @get:Synchronized
    val isHolding: Boolean get() = heldCount > 0

    @Synchronized
    fun held(): IntArray = heldOrder.copyOf(heldCount)

    /** Touchpad motion in mouse counts; sent with the next INPUT, once. */
    @Synchronized
    fun addMotion(dx: Float, dy: Float) {
        moveX += dx
        moveY += dy
        version++
    }

    /** Scroll in 1/120 of a notch: positive [v] scrolls up, positive [h] right. */
    @Synchronized
    fun addScroll(v: Float, h: Float) {
        wheel += v
        hwheel += h
        version++
    }

    @Synchronized
    fun buildInput(clientTimeMs: Int): Input =
        Input(clientTimeMs, 0, heldOrder.copyOf(heldCount), pending.toList(), takePointer())

    private fun takePointer(): Pointer? {
        fun take(v: Float): Int = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val p = Pointer(take(moveX), take(moveY), take(wheel), take(hwheel))
        if (p.dx == 0 && p.dy == 0 && p.wheel == 0 && p.hwheel == 0) return null
        moveX -= p.dx; moveY -= p.dy; wheel -= p.wheel; hwheel -= p.hwheel
        return p
    }

    private fun addEvent(code: Int, value: Int) {
        pending.addLast(KeyEvent(nextEseq, code, value))
        nextEseq = (nextEseq + 1) and 0xFFFF
        // Over the cap, drop the oldest; the held set still repairs the state.
        while (pending.size > Wire.MAX_EVENTS) pending.removeFirst()
        version++
    }

    private fun insertHeld(code: Int) {
        var i = heldCount
        while (i > 0 && heldOrder[i - 1] > code) {
            heldOrder[i] = heldOrder[i - 1]
            i--
        }
        heldOrder[i] = code
        heldCount++
    }

    private fun removeHeld(code: Int) {
        for (i in 0 until heldCount) {
            if (heldOrder[i] == code) {
                System.arraycopy(heldOrder, i + 1, heldOrder, i, heldCount - i - 1)
                heldCount--
                return
            }
        }
    }

    companion object {
        const val MAX_CODE = 0x2FF
        const val MAX_HELD = 64
    }
}
