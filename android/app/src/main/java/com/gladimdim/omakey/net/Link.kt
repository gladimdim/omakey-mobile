package com.gladimdim.omakey.net

import com.gladimdim.omakey.protocol.DesktopTheme

/**
 * A live connection the keyboard types through: omakeyd over Wi-Fi or
 * Bluetooth, or the phone as a Bluetooth keyboard. The touch thread
 * changes the shared [com.gladimdim.omakey.protocol.KeyState] and calls
 * [send]; the link gets the change to the computer.
 */
interface Link {
    enum class State {
        CONNECTING,
        CONNECTED,
        /** omakeyd doesn't know this phone. */
        REJECTED,
        /** Bluetooth keyboard: waiting for a computer to pair with the phone. */
        WAITING,
        /** Bluetooth keyboard: this phone can't act as one. */
        UNSUPPORTED,
    }

    /** Called on a background thread. */
    interface Listener {
        fun onState(state: State, hostName: String?)
        fun onPing(ms: Int)

        /** The computer's lock lights: [com.gladimdim.omakey.protocol.Ack.LED_CAPS] and friends. */
        fun onLeds(leds: Int) {}

        /** The computer's Omarchy theme, when its omakeyd sends one. */
        fun onTheme(theme: DesktopTheme) {}
    }

    fun start()

    /** Lets go of the computer (it releases every key). May wait briefly for threads to end. */
    fun stop()

    /** A key changed: get it to the computer now. Safe from any thread. */
    fun send()

    /** WELCOME feature bits of the connection, e.g. [com.gladimdim.omakey.protocol.Wire.FEATURE_POINTER]. */
    val features: Int

    /** How it's connected, for the status pill: "Wi-Fi", "Bluetooth". */
    val transport: String
}
