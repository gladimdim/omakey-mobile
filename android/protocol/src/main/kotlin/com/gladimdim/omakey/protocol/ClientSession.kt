package com.gladimdim.omakey.protocol

import java.security.SecureRandom

/**
 * The client half of PROTOCOL.md as a socket-free state machine: it builds
 * the datagrams to send and digests the ones received. The transport owns
 * timing and the socket. Not thread-safe; use it from the network thread.
 */
class ClientSession(
    private val host: HostRecord,
    private val phoneName: String,
    val keys: KeyState,
    private val random: SecureRandom = SecureRandom(),
) {
    sealed interface Result {
        /** Handshake finished; the server is called [hostName]. */
        data class Connected(
            val hostName: String,
            val sessionId: Int,
            val features: Int = 0,
            /** Where to reach the computer over Bluetooth, "AA:BB:…"; null without Bluetooth. */
            val btAddress: String? = null,
        ) : Result
        /** [leds]: the computer's lock lights ([Ack.LED_CAPS], …), or null when it doesn't say. */
        data class Acked(val pingMs: Int, val leds: Int? = null) : Result
        data object Rejected : Result
    }

    private val deviceAead = Aead(host.key)
    private var clientRandom = ByteArray(Wire.RANDOM_LEN)

    var connected = false
        private set
    var sessionId = 0
        private set
    private var c2s: Aead? = null
    private var s2c: Aead? = null
    private var sendCounter = 0L
    private var recvCounter = 0L

    init {
        restart()
    }

    /** Drop the session and start a new handshake. */
    fun restart() {
        random.nextBytes(clientRandom)
        connected = false
        sessionId = 0
        c2s = null
        s2c = null
        sendCounter = 0
        recvCounter = 0
    }

    fun helloPacket(): ByteArray {
        val nonce = ByteArray(Wire.NONCE_LEN).also(random::nextBytes)
        return Wire.seal(Wire.HELLO, host.deviceId, nonce, deviceAead, Hello(clientRandom, phoneName).encode())
    }

    fun inputPacket(clientTimeMs: Int): ByteArray? {
        val aead = c2s ?: return null
        val nonce = Wire.counterNonce(sessionId, ++sendCounter)
        return Wire.seal(Wire.INPUT, host.deviceId, nonce, aead, keys.buildInput(clientTimeMs).encode())
    }

    fun byePacket(): ByteArray? {
        val aead = c2s ?: return null
        return Wire.seal(Wire.BYE, host.deviceId, Wire.counterNonce(sessionId, ++sendCounter), aead, ByteArray(0))
    }

    /**
     * Digest one received datagram; null when it's not for us or doesn't
     * verify. A valid new WELCOME is only taken when [claim] agrees (another
     * transport may already be typing for this phone); otherwise nothing
     * changes, the shared [keys] included.
     */
    fun receive(data: ByteArray, length: Int, nowMs: Int, claim: () -> Boolean = { true }): Result? {
        val p = Packet.parse(data, length) ?: return null
        if (!p.deviceId.contentEquals(host.deviceId)) return null
        return when (p.type) {
            Wire.WELCOME -> onWelcome(p, claim)
            Wire.ACK -> onAck(p, nowMs)
            Wire.REJECT -> if (!connected && p.bodyLength >= 1) Result.Rejected else null
            else -> null
        }
    }

    private fun onWelcome(p: Packet, claim: () -> Boolean): Result? {
        val w = Welcome.decode(p.open(deviceAead) ?: return null) ?: return null
        if (!w.clientRandom.contentEquals(clientRandom)) return null
        // A duplicate WELCOME for the session we already have.
        if (connected && w.sessionId == sessionId) return null
        if (!claim()) return null
        val k = SessionKeys.derive(host.key, clientRandom, w.serverRandom)
        c2s = Aead(k.clientToServer)
        s2c = Aead(k.serverToClient)
        sessionId = w.sessionId
        sendCounter = 0
        recvCounter = 0
        connected = true
        keys.resetSession()
        return Result.Connected(w.name, w.sessionId, w.features, w.btAddress?.let(PairingUri::btAddress))
    }

    private fun onAck(p: Packet, nowMs: Int): Result? {
        val aead = s2c ?: return null
        if (p.sessionId != sessionId || p.counter <= recvCounter) return null
        val ack = Ack.decode(p.open(aead) ?: return null) ?: return null
        recvCounter = p.counter
        keys.ack(ack.lastEseq)
        return Result.Acked(nowMs - ack.clientTimeMs, ack.leds)
    }
}
