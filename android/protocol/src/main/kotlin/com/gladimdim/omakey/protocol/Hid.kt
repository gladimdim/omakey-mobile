package com.gladimdim.omakey.protocol

/**
 * The phone as a standard Bluetooth HID keyboard and mouse, for computers
 * without omakeyd. Three reports:
 *
 * - 1, keyboard: modifier bits, a reserved byte, six key usages (boot
 *   layout), plus a Caps Lock/Num Lock LED output report.
 * - 2, consumer control: one 16-bit usage, for media and brightness keys.
 * - 3, mouse: three buttons, 16-bit X/Y, wheel and horizontal wheel.
 *
 * Codes on both ends are Linux key codes, as everywhere else in the app.
 */
object Hid {
    const val ID_KEYBOARD = 1
    const val ID_CONSUMER = 2
    const val ID_MOUSE = 3

    /** HID usage "ErrorRollOver": more than six keys held at once. */
    private const val ROLLOVER = 0x01

    val DESCRIPTOR: ByteArray = bytes(
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, // Generic Desktop / Keyboard, Application
        0x85, ID_KEYBOARD,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, // modifiers: Left Ctrl .. Right GUI
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x75, 0x08, 0x95, 0x01, 0x81, 0x01, // reserved byte
        0x05, 0x08, 0x19, 0x01, 0x29, 0x05, // LEDs: Num Lock .. Kana
        0x75, 0x01, 0x95, 0x05, 0x91, 0x02,
        0x75, 0x03, 0x95, 0x01, 0x91, 0x01, // LED padding
        0x05, 0x07, 0x19, 0x00, 0x2A, 0xFF, 0x00, // six key usages, 0..255
        0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x06, 0x81, 0x00,
        0xC0,

        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, // Consumer / Consumer Control, Application
        0x85, ID_CONSUMER,
        0x19, 0x00, 0x2A, 0xFF, 0x03, 0x15, 0x00, 0x26, 0xFF, 0x03,
        0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0,

        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, // Generic Desktop / Mouse, Application
        0x85, ID_MOUSE,
        0x09, 0x01, 0xA1, 0x00, // Pointer, Physical
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, // buttons 1..3
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x03, 0x81, 0x02,
        0x75, 0x05, 0x95, 0x01, 0x81, 0x01, // padding
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, // X, Y: -32767..32767, relative
        0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x06,
        0x09, 0x38, // wheel
        0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
        0x05, 0x0C, 0x0A, 0x38, 0x02, // AC Pan: horizontal wheel
        0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
        0xC0, 0xC0,
    )

    /**
     * Linux's own HID-usage-to-key-code table (`hid_keyboard[]` in
     * drivers/hid/hid-input.c), 0 where it has none. Inverted below, so a
     * key typed here comes out as the same key code on a Linux host.
     */
    private val LINUX_HID_KEYBOARD = intArrayOf(
        0, 0, 0, 0, 30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38,
        50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44, 2, 3,
        4, 5, 6, 7, 8, 9, 10, 11, 28, 1, 14, 15, 57, 12, 13, 26,
        27, 43, 43, 39, 40, 41, 51, 52, 53, 58, 59, 60, 61, 62, 63, 64,
        65, 66, 67, 68, 87, 88, 99, 70, 119, 110, 102, 104, 111, 107, 109, 106,
        105, 108, 103, 69, 98, 55, 74, 78, 96, 79, 80, 81, 75, 76, 77, 71,
        72, 73, 82, 83, 86, 127, 116, 117, 183, 184, 185, 186, 187, 188, 189, 190,
        191, 192, 193, 194, 134, 138, 130, 132, 128, 129, 131, 137, 133, 135, 136, 113,
        115, 114, 0, 0, 0, 121, 0, 89, 93, 124, 92, 94, 95, 0, 0, 0,
        122, 123, 90, 91, 85, 0, 0, 0, 0, 0, 0, 0, 111,
    )

    /** Modifier key codes in HID bit order: LCtrl LShift LAlt LGUI RCtrl RShift RAlt RGUI. */
    private val MODIFIERS = intArrayOf(29, 42, 56, 125, 97, 54, 100, 126)

    /** Media keys go on the consumer page: hosts other than Linux ignore the keyboard page's volume keys. */
    private val CONSUMER = mapOf(
        113 to 0xE2, // MUTE
        114 to 0xEA, // VOLUMEDOWN
        115 to 0xE9, // VOLUMEUP
        140 to 0x192, // CALC
        155 to 0x18A, // MAIL
        158 to 0x224, // BACK
        159 to 0x225, // FORWARD
        161 to 0xB8, // EJECTCD
        163 to 0xB5, // NEXTSONG
        164 to 0xCD, // PLAYPAUSE
        165 to 0xB6, // PREVIOUSSONG
        166 to 0xB7, // STOPCD
        168 to 0xB4, // REWIND
        172 to 0x223, // HOMEPAGE
        200 to 0xB0, // PLAYCD
        201 to 0xB1, // PAUSECD
        208 to 0xB3, // FASTFORWARD
        217 to 0x221, // SEARCH
        224 to 0x70, // BRIGHTNESSDOWN
        225 to 0x6F, // BRIGHTNESSUP
    )

    private val KEYBOARD = IntArray(KeyState.MAX_CODE + 1).also { usage ->
        // The first usage for a code wins (0x31 over 0x32 for backslash, …).
        for (u in LINUX_HID_KEYBOARD.indices.reversed()) {
            val code = LINUX_HID_KEYBOARD[u]
            if (code != 0 && code !in CONSUMER) usage[code] = u
        }
    }

    /** Bit in the modifier byte, or -1 when [code] isn't a modifier. */
    fun modifierBit(code: Int): Int = MODIFIERS.indexOf(code)

    /** Keyboard-page usage for a Linux key code; 0 when there is none. */
    fun keyboardUsage(code: Int): Int = if (code in KEYBOARD.indices) KEYBOARD[code] else 0

    /** Consumer-page usage for a Linux key code; 0 when there is none. */
    fun consumerUsage(code: Int): Int = CONSUMER[code] ?: 0

    /** Key codes that have no HID equivalent here (KEY_MICMUTE, KEY_FN, …) are dropped. */
    fun isSendable(code: Int): Boolean =
        modifierBit(code) >= 0 || keyboardUsage(code) != 0 || consumerUsage(code) != 0 ||
            code in Wire.BTN_LEFT..Wire.BTN_MIDDLE

    /**
     * Builds the reports for the held set and touchpad motion, and hands
     * over the ones that differ from what the host last received. [send]
     * returns false when a report didn't go out; it is then sent again on
     * the next [build] or [repeat], so a lost release can't leave a key
     * stuck on the host (which, unlike omakeyd, has no stuck-key timeout).
     * Not thread-safe.
     */
    class Reports {
        // What the host should see, and what it was last sent successfully.
        private val keyboard = ByteArray(8)
        private val consumer = ByteArray(2)
        private var buttons = 0
        private val sentKeyboard = ByteArray(8)
        private val sentConsumer = ByteArray(2)
        private var sentButtons = 0
        private var fresh = true
        // Scroll not yet sent as a whole notch, in 1/120 units.
        private var wheelRest = 0
        private var hwheelRest = 0

        /** Send everything on the next [build], e.g. after reconnecting. */
        fun reset() {
            fresh = true
            wheelRest = 0
            hwheelRest = 0
        }

        /** The current value of report [id], for a host's GET_REPORT; null for an unknown id. */
        fun current(id: Int): ByteArray? = when (id) {
            ID_KEYBOARD -> keyboard.copyOf()
            ID_CONSUMER -> consumer.copyOf()
            ID_MOUSE -> byteArrayOf(buttons.toByte(), 0, 0, 0, 0, 0, 0)
            else -> null
        }

        /** Whether a key or button state is waiting to be (re)sent. */
        val behind: Boolean
            get() = !keyboard.contentEquals(sentKeyboard) || !consumer.contentEquals(sentConsumer) || buttons != sentButtons

        /**
         * Returns true when the key or button state changed, so the caller
         * can [repeat] it a little later in case the host missed it.
         */
        fun build(held: IntArray, pointer: Pointer?, send: (id: Int, data: ByteArray) -> Boolean): Boolean {
            var mods = 0
            val keys = ByteArray(6)
            var n = 0
            var media = 0
            var btn = 0
            for (code in held) {
                val bit = modifierBit(code)
                val usage = keyboardUsage(code)
                when {
                    bit >= 0 -> mods = mods or (1 shl bit)
                    code in Wire.BTN_LEFT..Wire.BTN_MIDDLE -> btn = btn or (1 shl (code - Wire.BTN_LEFT))
                    consumerUsage(code) != 0 -> if (media == 0) media = consumerUsage(code)
                    usage != 0 -> { if (n < 6) keys[n] = usage.toByte(); n++ }
                }
            }
            if (n > 6) keys.fill(ROLLOVER.toByte())

            val kb = ByteArray(8).also {
                it[0] = mods.toByte()
                System.arraycopy(keys, 0, it, 2, 6)
            }
            val cc = byteArrayOf(media.toByte(), (media shr 8).toByte())
            val changed = !kb.contentEquals(keyboard) || !cc.contentEquals(consumer) || btn != buttons
            kb.copyInto(keyboard)
            cc.copyInto(consumer)
            buttons = btn

            if (fresh || !keyboard.contentEquals(sentKeyboard)) sendKeyboard(send)
            if (fresh || !consumer.contentEquals(sentConsumer)) sendConsumer(send)

            val dx = pointer?.dx ?: 0
            val dy = pointer?.dy ?: 0
            wheelRest += pointer?.wheel ?: 0
            hwheelRest += pointer?.hwheel ?: 0
            val wheel = (wheelRest / 120).coerceIn(-127, 127)
            val hwheel = (hwheelRest / 120).coerceIn(-127, 127)
            wheelRest -= wheel * 120
            hwheelRest -= hwheel * 120
            if (fresh || buttons != sentButtons || dx != 0 || dy != 0 || wheel != 0 || hwheel != 0) {
                sendMouse(send, dx, dy, wheel, hwheel)
            }
            fresh = false
            return changed
        }

        /** Send the key and button state again, whether or not it changed. */
        fun repeat(send: (id: Int, data: ByteArray) -> Boolean) {
            sendKeyboard(send)
            sendConsumer(send)
            if (buttons != sentButtons) sendMouse(send, 0, 0, 0, 0)
        }

        private fun sendKeyboard(send: (Int, ByteArray) -> Boolean) {
            if (send(ID_KEYBOARD, keyboard.copyOf())) keyboard.copyInto(sentKeyboard)
        }

        private fun sendConsumer(send: (Int, ByteArray) -> Boolean) {
            if (send(ID_CONSUMER, consumer.copyOf())) consumer.copyInto(sentConsumer)
        }

        private fun sendMouse(send: (Int, ByteArray) -> Boolean, dx: Int, dy: Int, wheel: Int, hwheel: Int) {
            val x = dx.coerceIn(-32767, 32767)
            val y = dy.coerceIn(-32767, 32767)
            val b = buttons
            // HID is little-endian.
            val ok = send(ID_MOUSE, byteArrayOf(
                b.toByte(), x.toByte(), (x shr 8).toByte(), y.toByte(), (y shr 8).toByte(),
                wheel.toByte(), hwheel.toByte(),
            ))
            if (ok) sentButtons = b
        }
    }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
}
