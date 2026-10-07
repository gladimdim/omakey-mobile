package com.gladimdim.omakey.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

class ProtocolTest {
    private fun hex(s: String) = Hex.decode(s.replace(" ", ""))

    @Test
    fun hkdfRfc5869Case1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val prk = Hkdf.extract(salt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", Hex.encode(prk))
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hex.encode(Hkdf.expand(prk, info, 42)),
        )
    }

    @Test
    fun counterNonceIsSessionIdThenCounter() {
        assertEquals("deadbeef0000000000000102", Hex.encode(Wire.counterNonce(0xdeadbeef.toInt(), 258)))
    }

    @Test
    fun sealedPacketRoundTripsAndRejectsTampering() {
        val key = ByteArray(32) { it.toByte() }
        val dev = hex("0102030405060708")
        val nonce = Wire.counterNonce(7, 1)
        val pkt = Wire.seal(Wire.INPUT, dev, nonce, Aead(key), byteArrayOf(1, 2, 3))
        assertEquals(Wire.HEADER_LEN + 3 + Wire.TAG_LEN, pkt.size)
        assertEquals("4f4b0103", Hex.encode(pkt.copyOfRange(0, 4)))

        val p = Packet.parse(pkt)!!
        assertEquals(Wire.INPUT, p.type)
        assertEquals(7, p.sessionId)
        assertEquals(1L, p.counter)
        assertArrayEquals(byteArrayOf(1, 2, 3), p.open(Aead(key)))

        // The header is AAD: flipping the type breaks authentication.
        val tampered = pkt.copyOf().also { it[3] = Wire.BYE }
        assertNull(Packet.parse(tampered)!!.open(Aead(key)))
        assertNull(Packet.parse(pkt)!!.open(Aead(ByteArray(32))))
        assertNull(Packet.parse(byteArrayOf(0x4f, 0x4b, 2, 3)))
    }

    @Test
    fun inputEncodingMatchesSpec() {
        val input = Input(0x01020304, 0, intArrayOf(125, 57), listOf(KeyEvent(9, 125, 1), KeyEvent(10, 57, 1)))
        val b = input.encode()
        assertEquals(
            "01020304" + "00" + "02" + "007d" + "0039" + "02" + "0009007d01" + "000a003901",
            Hex.encode(b),
        )
        val back = Input.decode(b)!!
        assertArrayEquals(intArrayOf(125, 57), back.held)
        assertEquals(input.events, back.events)
    }

    @Test
    fun helloWelcomeAckEncoding() {
        val cr = ByteArray(16) { 1 }
        val hello = Hello(cr, "Pixel", Wire.PLATFORM_ANDROID).encode()
        assertEquals(Hex.encode(cr) + "05" + Hex.encode("Pixel".toByteArray()) + "01", Hex.encode(hello))
        assertEquals("Pixel", Hello.decode(hello)!!.name)

        val w = Welcome(cr, ByteArray(16) { 2 }, 42, "desk").encode()
        val wd = Welcome.decode(w)!!
        assertEquals(42, wd.sessionId)
        assertEquals("desk", wd.name)

        assertEquals("0000000a0005", Hex.encode(Ack(10, 5).encode()))
    }

    @Test
    fun longNamesAreCutAtCharacterBoundaries() {
        val name = "ж".repeat(40) // 80 bytes
        val b = truncateUtf8(name, 64)
        assertEquals(64, b.size)
        assertEquals("ж".repeat(32), String(b, Charsets.UTF_8))
    }

    @Test
    fun eseqComparisonWraps() {
        assertTrue(Wire.eseqNewer(1, 0))
        assertTrue(Wire.eseqNewer(0, 65535))
        assertTrue(Wire.eseqNewer(5, 65530))
        assertFalse(Wire.eseqNewer(65530, 5))
        assertFalse(Wire.eseqNewer(7, 7))
    }

    @Test
    fun keyStateRefCountsAndAcks() {
        val k = KeyState()
        assertTrue(k.press(42))
        assertFalse(k.press(42)) // second finger on a key with the same code
        assertTrue(k.press(30))
        assertArrayEquals(intArrayOf(30, 42), k.held())
        assertFalse(k.release(42))
        assertTrue(k.release(42))
        assertArrayEquals(intArrayOf(30), k.held())

        val input = k.buildInput(0)
        assertEquals(listOf(KeyEvent(1, 42, 1), KeyEvent(2, 30, 1), KeyEvent(3, 42, 0)), input.events)
        k.ack(2)
        assertEquals(listOf(KeyEvent(3, 42, 0)), k.buildInput(0).events)
        k.ack(3)
        assertFalse(k.hasUnacked)

        k.releaseAll()
        assertFalse(k.isHolding)
        assertEquals(listOf(KeyEvent(4, 30, 0)), k.buildInput(0).events)
    }

    @Test
    fun keyStateKeepsAtMost32Events() {
        val k = KeyState()
        repeat(20) { k.press(30); k.release(30) }
        val events = k.buildInput(0).events
        assertEquals(32, events.size)
        assertEquals(9, events.first().eseq)
        assertEquals(40, events.last().eseq)
    }

    @Test
    fun keyStateSequenceWraps() {
        val k = KeyState()
        repeat(65535) { k.press(30); k.release(30); k.ack(k.buildInput(0).events.last().eseq) }
        k.press(30) // eseq 65535 * 2 + 1 wraps
        assertEquals((65535 * 2 + 1) and 0xFFFF, k.buildInput(0).events.single().eseq)
    }

    private val goodKey = ByteArray(32) { (it * 7).toByte() }
    private val goodLink = "omakey://pair?v=1&h=0011223344556677&n=gladimdim%20b9&a=192.168.50.219,100.67.193.30" +
        "&p=47800&d=8899aabbccddeeff&k=" + Base64.getUrlEncoder().withoutPadding().encodeToString(goodKey)

    @Test
    fun parsesPairingLink() {
        val h = PairingUri.parse(goodLink)
        assertEquals("0011223344556677", h.hostId)
        assertEquals("gladimdim b9", h.name)
        assertEquals(listOf("192.168.50.219", "100.67.193.30"), h.addresses)
        assertEquals(47800, h.port)
        assertEquals("8899aabbccddeeff", h.deviceIdHex)
        assertArrayEquals(goodKey, h.key)
    }

    @Test
    fun rejectsBadPairingLinks() {
        for (bad in listOf(
            "https://example.com",
            goodLink.replace("v=1", "v=2"),
            goodLink.replace("h=0011223344556677", "h=0011"),
            goodLink.replace("p=47800", "p=0"),
            goodLink.replace("a=192.168.50.219,100.67.193.30", "a=not-an-ip"),
            goodLink.substringBefore("&k=") + "&k=AAAA",
        )) {
            try {
                PairingUri.parse(bad)
                fail("accepted $bad")
            } catch (e: PairingException) {
                // expected
            }
        }
    }

    /** A minimal server, written from PROTOCOL.md, drives a full session. */
    @Test
    fun clientSessionHandshakeInputAndAck() {
        val host = PairingUri.parse(goodLink)
        val keys = KeyState()
        val client = ClientSession(host, "Phone", keys)

        // HELLO decrypts with the device key.
        val hp = Packet.parse(client.helloPacket())!!
        assertEquals(Wire.HELLO, hp.type)
        val hello = Hello.decode(hp.open(Aead(host.key))!!)!!
        assertEquals("Phone", hello.name)

        // WELCOME.
        val sr = ByteArray(16).also(SecureRandom()::nextBytes)
        val welcome = Wire.seal(
            Wire.WELCOME, host.deviceId, ByteArray(12).also(SecureRandom()::nextBytes), Aead(host.key),
            Welcome(hello.clientRandom, sr, 99, "desk").encode(),
        )
        assertEquals(ClientSession.Result.Connected("desk", 99), client.receive(welcome, welcome.size, 0))
        assertTrue(client.connected)

        // INPUT under Kcs with nonce session ‖ 1.
        val sk = SessionKeys.derive(host.key, hello.clientRandom, sr)
        keys.press(125)
        keys.press(57)
        val ip = Packet.parse(client.inputPacket(1000)!!)!!
        assertEquals(99, ip.sessionId)
        assertEquals(1L, ip.counter)
        val input = Input.decode(ip.open(Aead(sk.clientToServer))!!)!!
        assertArrayEquals(intArrayOf(57, 125), input.held)
        assertEquals(2, input.events.size)

        // ACK under Ksc.
        val ack = Wire.seal(Wire.ACK, host.deviceId, Wire.counterNonce(99, 1), Aead(sk.serverToClient), Ack(1000, 2).encode())
        assertEquals(ClientSession.Result.Acked(7), client.receive(ack, ack.size, 1007))
        assertFalse(keys.hasUnacked)
        // A replayed ACK is ignored.
        assertNull(client.receive(ack, ack.size, 1010))

        // A WELCOME for some other HELLO is ignored.
        val stray = Wire.seal(
            Wire.WELCOME, host.deviceId, ByteArray(12), Aead(host.key), Welcome(ByteArray(16), sr, 5, "x").encode(),
        )
        assertNull(client.receive(stray, stray.size, 0))

        assertNotNull(client.byePacket())
        assertEquals(Wire.BYE, client.byePacket()!![3])
    }

    @Test
    fun rejectIsReportedOnlyBeforeConnecting() {
        val host = PairingUri.parse(goodLink)
        val client = ClientSession(host, "Phone", KeyState())
        val reject = Wire.header(Wire.REJECT, host.deviceId, ByteArray(12)) + byteArrayOf(Wire.REJECT_UNKNOWN_DEVICE)
        assertEquals(ClientSession.Result.Rejected, client.receive(reject, reject.size, 0))
    }

    @Test
    fun pointerTrailerIsOptionalAndMotionIsSentOnce() {
        val keys = KeyState()
        assertEquals(null, keys.buildInput(1).pointer)
        keys.addMotion(2.6f, -1.4f)
        keys.addScroll(-50f, 0f)
        val first = keys.buildInput(2)
        assertEquals(Pointer(2, -1, -50, 0), first.pointer)
        // Fractions carry over; whole counts are not resent.
        keys.addMotion(0.5f, 0f)
        assertEquals(Pointer(1, 0, 0, 0), keys.buildInput(3).pointer)
        assertEquals(null, keys.buildInput(4).pointer)
        // Round trip, and an old-style packet without a trailer still decodes.
        val decoded = Input.decode(first.encode())!!
        assertEquals(first.pointer, decoded.pointer)
        assertEquals(null, Input.decode(Input(1, 0, IntArray(0), emptyList()).encode())!!.pointer)
    }

    @Test
    fun welcomeFeaturesDefaultToZeroFromOldServers() {
        val w = Welcome(ByteArray(16), ByteArray(16), 5, "desk", Wire.FEATURE_POINTER)
        val enc = w.encode()
        assertEquals(Wire.FEATURE_POINTER, Welcome.decode(enc)!!.features)
        assertEquals(0, Welcome.decode(enc.copyOf(enc.size - 1))!!.features)
    }

    @Test
    fun bluetoothAddressComesFromThePairingLinkAndWelcome() {
        val link = "omakey://pair?v=1&h=0102030405060708&n=desk&a=192.168.1.5&p=47800" +
            "&d=0909090909090909&k=" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)) +
            "&b=1418c368871e"
        assertEquals("14:18:C3:68:87:1E", PairingUri.parse(link).btAddress)
        assertNull(PairingUri.parse(link.substringBefore("&b=")).btAddress)

        val bt = hex("1418c368871e")
        val w = Welcome(ByteArray(16), ByteArray(16), 5, "desk", Wire.FEATURE_POINTER, bt)
        assertArrayEquals(bt, Welcome.decode(w.encode())!!.btAddress)
        assertNull(Welcome.decode(Welcome(ByteArray(16), ByteArray(16), 5, "desk", 1).encode())!!.btAddress)
    }

    @Test
    fun ackCarriesLockLightsWhenTheServerSendsThem() {
        assertNull(Ack.decode(Ack(5, 7).encode())!!.leds)
        val a = Ack.decode(Ack(5, 7, Ack.LED_CAPS).encode())!!
        assertEquals(7, a.lastEseq)
        assertEquals(Ack.LED_CAPS, a.leds)
    }

    @Test
    fun aWelcomeThatIsntClaimedChangesNothing() {
        val host = HostRecord("0102030405060708", "desk", listOf("10.0.0.2"), 47800, ByteArray(8) { 9 }, ByteArray(32) { 3 })
        val keys = KeyState()
        val client = ClientSession(host, "phone", keys)
        keys.press(30) // an event waiting to be acknowledged
        val hello = Hello.decode(Packet.parse(client.helloPacket())!!.open(Aead(host.key))!!)!!
        val welcome = Wire.seal(Wire.WELCOME, host.deviceId, ByteArray(12), Aead(host.key),
            Welcome(hello.clientRandom, ByteArray(16) { 2 }, 99, "desk").encode())
        assertNull(client.receive(welcome, welcome.size, 0) { false })
        assertFalse(client.connected)
        assertTrue(keys.hasUnacked) // not reset by a WELCOME we didn't take
        assertNotNull(client.receive(welcome, welcome.size, 0) { true })
        assertTrue(client.connected)
    }

    @Test
    fun fingerprintIsTheStartOfTheKeysSha256() {
        val h = HostRecord("0102030405060708", "desk", listOf("10.0.0.2"), 47800, ByteArray(8), ByteArray(32))
        // SHA-256 of 32 zero bytes starts 66687aad.
        assertEquals("6668-7AAD", h.fingerprint)
    }
}
