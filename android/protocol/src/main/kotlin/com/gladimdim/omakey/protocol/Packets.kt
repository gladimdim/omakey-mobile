package com.gladimdim.omakey.protocol

import java.nio.ByteBuffer

/** Constants from PROTOCOL.md. All integers on the wire are big-endian. */
object Wire {
    /** WELCOME feature bit: the server has a virtual mouse for the touchpad. */
    const val FEATURE_POINTER = 1
    /** Length of the INPUT pointer trailer after its length byte. */
    const val POINTER_LEN = 8
    const val BTN_LEFT = 0x110
    const val BTN_RIGHT = 0x111
    const val BTN_MIDDLE = 0x112

    const val MAGIC_0: Byte = 0x4F // 'O'
    const val MAGIC_1: Byte = 0x4B // 'K'
    const val VERSION: Byte = 1
    const val HEADER_LEN = 24
    const val NONCE_LEN = 12
    const val TAG_LEN = 16
    const val ID_LEN = 8
    const val RANDOM_LEN = 16
    const val MAX_DATAGRAM = 1200
    const val DEFAULT_PORT = 47800
    const val SERVICE_TYPE = "_omakey._udp"
    const val MAX_EVENTS = 32
    const val MAX_NAME_BYTES = 64

    const val HELLO: Byte = 1
    const val WELCOME: Byte = 2
    const val INPUT: Byte = 3
    const val ACK: Byte = 4
    const val BYE: Byte = 5
    const val REJECT: Byte = 6

    const val PLATFORM_ANDROID: Byte = 1
    const val PLATFORM_IOS: Byte = 2

    const val REJECT_UNKNOWN_DEVICE: Byte = 1

    /** Nonce for INPUT/ACK/BYE: session id ‖ counter. */
    fun counterNonce(sessionId: Int, counter: Long): ByteArray =
        ByteBuffer.allocate(NONCE_LEN).putInt(sessionId).putLong(counter).array()

    fun header(type: Byte, deviceId: ByteArray, nonce: ByteArray): ByteArray {
        require(deviceId.size == ID_LEN && nonce.size == NONCE_LEN)
        val h = ByteArray(HEADER_LEN)
        h[0] = MAGIC_0
        h[1] = MAGIC_1
        h[2] = VERSION
        h[3] = type
        System.arraycopy(deviceId, 0, h, 4, ID_LEN)
        System.arraycopy(nonce, 0, h, 12, NONCE_LEN)
        return h
    }

    /** Header ‖ AES-GCM(plaintext), with the header as AAD. */
    fun seal(type: Byte, deviceId: ByteArray, nonce: ByteArray, aead: Aead, plaintext: ByteArray): ByteArray {
        val h = header(type, deviceId, nonce)
        return h + aead.seal(nonce, h, plaintext)
    }

    /** `(int16)(a - b) > 0`: is event sequence [a] newer than [b]? */
    fun eseqNewer(a: Int, b: Int): Boolean = ((a - b) and 0xFFFF).toShort() > 0
}

/** A parsed packet header; the body is still encrypted. */
class Packet(
    val type: Byte,
    val deviceId: ByteArray,
    val nonce: ByteArray,
    val aad: ByteArray,
    val data: ByteArray,
    val bodyOffset: Int,
    val bodyLength: Int,
) {
    fun open(aead: Aead): ByteArray? = aead.open(nonce, aad, data, bodyOffset, bodyLength)

    /** Session id and counter of a counter nonce. */
    val sessionId: Int get() = ByteBuffer.wrap(nonce, 0, 4).int
    val counter: Long get() = ByteBuffer.wrap(nonce, 4, 8).long

    companion object {
        fun parse(data: ByteArray, length: Int = data.size): Packet? {
            if (length < Wire.HEADER_LEN + 1 || length > Wire.MAX_DATAGRAM) return null
            if (data[0] != Wire.MAGIC_0 || data[1] != Wire.MAGIC_1 || data[2] != Wire.VERSION) return null
            return Packet(
                type = data[3],
                deviceId = data.copyOfRange(4, 12),
                nonce = data.copyOfRange(12, 24),
                aad = data.copyOfRange(0, 24),
                data = data,
                bodyOffset = Wire.HEADER_LEN,
                bodyLength = length - Wire.HEADER_LEN,
            )
        }
    }
}

class Hello(val clientRandom: ByteArray, val name: String, val platform: Byte = Wire.PLATFORM_ANDROID) {
    fun encode(): ByteArray {
        val nameBytes = truncateUtf8(name, Wire.MAX_NAME_BYTES)
        return ByteBuffer.allocate(Wire.RANDOM_LEN + 1 + nameBytes.size + 1)
            .put(clientRandom).put(nameBytes.size.toByte()).put(nameBytes).put(platform).array()
    }

    companion object {
        fun decode(b: ByteArray): Hello? = try {
            val buf = ByteBuffer.wrap(b)
            val cr = ByteArray(Wire.RANDOM_LEN).also { buf.get(it) }
            val n = ByteArray(buf.get().toInt() and 0xFF).also { buf.get(it) }
            Hello(cr, String(n, Charsets.UTF_8), buf.get())
        } catch (e: RuntimeException) {
            null
        }
    }
}

class Welcome(
    val clientRandom: ByteArray,
    val serverRandom: ByteArray,
    val sessionId: Int,
    val name: String,
    /** [Wire.FEATURE_POINTER] and friends; 0 from servers that predate them. */
    val features: Int = 0,
) {
    fun encode(): ByteArray {
        val nameBytes = truncateUtf8(name, 255)
        return ByteBuffer.allocate(Wire.RANDOM_LEN * 2 + 4 + 1 + nameBytes.size + 1)
            .put(clientRandom).put(serverRandom).putInt(sessionId)
            .put(nameBytes.size.toByte()).put(nameBytes).put(features.toByte()).array()
    }

    companion object {
        fun decode(b: ByteArray): Welcome? = try {
            val buf = ByteBuffer.wrap(b)
            val cr = ByteArray(Wire.RANDOM_LEN).also { buf.get(it) }
            val sr = ByteArray(Wire.RANDOM_LEN).also { buf.get(it) }
            val sid = buf.int
            val n = ByteArray(buf.get().toInt() and 0xFF).also { buf.get(it) }
            val features = if (buf.hasRemaining()) buf.get().toInt() and 0xFF else 0
            Welcome(cr, sr, sid, String(n, Charsets.UTF_8), features)
        } catch (e: RuntimeException) {
            null
        }
    }
}

/** One key press (value 1) or release (value 0). */
data class KeyEvent(val eseq: Int, val code: Int, val value: Int)

/** Touchpad motion and scroll since the previous INPUT; scroll in 1/120 of a notch. */
data class Pointer(val dx: Int, val dy: Int, val wheel: Int, val hwheel: Int)

class Input(
    val clientTimeMs: Int,
    val flags: Int,
    val held: IntArray,
    val events: List<KeyEvent>,
    val pointer: Pointer? = null,
) {
    fun encode(): ByteArray {
        require(held.size <= 255 && events.size <= Wire.MAX_EVENTS)
        val trailer = if (pointer != null) 1 + Wire.POINTER_LEN else 0
        val buf = ByteBuffer.allocate(4 + 1 + 1 + held.size * 2 + 1 + events.size * 5 + trailer)
        buf.putInt(clientTimeMs).put(flags.toByte()).put(held.size.toByte())
        for (c in held) buf.putShort(c.toShort())
        buf.put(events.size.toByte())
        for (e in events) buf.putShort(e.eseq.toShort()).putShort(e.code.toShort()).put(e.value.toByte())
        if (pointer != null) {
            buf.put(Wire.POINTER_LEN.toByte())
            buf.putShort(pointer.dx.toShort()).putShort(pointer.dy.toShort())
                .putShort(pointer.wheel.toShort()).putShort(pointer.hwheel.toShort())
        }
        return buf.array()
    }

    companion object {
        fun decode(b: ByteArray): Input? = try {
            val buf = ByteBuffer.wrap(b)
            val t = buf.int
            val flags = buf.get().toInt() and 0xFF
            val held = IntArray(buf.get().toInt() and 0xFF) { buf.short.toInt() and 0xFFFF }
            val events = List(buf.get().toInt() and 0xFF) {
                KeyEvent(buf.short.toInt() and 0xFFFF, buf.short.toInt() and 0xFFFF, buf.get().toInt() and 0xFF)
            }
            var pointer: Pointer? = null
            if (buf.hasRemaining()) {
                val len = buf.get().toInt() and 0xFF
                if (len >= Wire.POINTER_LEN) {
                    pointer = Pointer(buf.short.toInt(), buf.short.toInt(), buf.short.toInt(), buf.short.toInt())
                }
            }
            Input(t, flags, held, events, pointer)
        } catch (e: RuntimeException) {
            null
        }
    }
}

class Ack(val clientTimeMs: Int, val lastEseq: Int) {
    fun encode(): ByteArray = ByteBuffer.allocate(6).putInt(clientTimeMs).putShort(lastEseq.toShort()).array()

    companion object {
        fun decode(b: ByteArray): Ack? =
            if (b.size < 6) null
            else ByteBuffer.wrap(b).let { Ack(it.int, it.short.toInt() and 0xFFFF) }
    }
}

internal fun truncateUtf8(s: String, maxBytes: Int): ByteArray {
    var bytes = s.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return bytes
    // Cut at a character boundary.
    var end = s.length
    while (end > 0 && bytes.size > maxBytes) {
        end--
        if (end > 0 && Character.isLowSurrogate(s[end])) end--
        bytes = s.substring(0, end).toByteArray(Charsets.UTF_8)
    }
    return bytes
}
