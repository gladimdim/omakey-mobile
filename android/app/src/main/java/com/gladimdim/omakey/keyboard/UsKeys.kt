package com.gladimdim.omakey.keyboard

/**
 * What each Linux key code types on a US layout, and back: the phone's
 * keyboard sends characters, the computer wants keys. Covers printable
 * ASCII plus Enter and Tab.
 */
object UsKeys {
    const val KEY_ESC = 1
    const val KEY_BACKSPACE = 14
    const val KEY_TAB = 15
    const val KEY_ENTER = 28
    const val KEY_LEFTSHIFT = 42
    const val KEY_SPACE = 57
    const val KEY_UP = 103
    const val KEY_LEFT = 105
    const val KEY_RIGHT = 106
    const val KEY_DOWN = 108
    const val KEY_DELETE = 111

    /** Ctrl, Shift, Alt and Super, left and right. */
    val MODIFIERS = setOf(29, 97, 42, 54, 56, 100, 125, 126)

    /** Code to (unshifted, shifted) character. */
    val PRINTABLE: Map<Int, Pair<Char, Char>> = buildMap {
        "1234567890".forEachIndexed { i, c -> put(2 + i, c to "!@#\$%^&*()"[i]) }
        put(12, '-' to '_'); put(13, '=' to '+')
        "qwertyuiop".forEachIndexed { i, c -> put(16 + i, c to c.uppercaseChar()) }
        put(26, '[' to '{'); put(27, ']' to '}')
        "asdfghjkl".forEachIndexed { i, c -> put(30 + i, c to c.uppercaseChar()) }
        put(39, ';' to ':'); put(40, '\'' to '"'); put(41, '`' to '~'); put(43, '\\' to '|')
        "zxcvbnm".forEachIndexed { i, c -> put(44 + i, c to c.uppercaseChar()) }
        put(51, ',' to '<'); put(52, '.' to '>'); put(53, '/' to '?')
        put(KEY_SPACE, ' ' to ' ')
    }

    internal val BY_CHAR: Map<Char, Pair<Int, Boolean>> = buildMap {
        for ((code, chars) in PRINTABLE) {
            putIfAbsent(chars.second, code to true)
            put(chars.first, code to false)
        }
        put('\n', KEY_ENTER to false)
        put('\t', KEY_TAB to false)
    }

    /** The key and whether it needs Shift; null for a character a US keyboard can't type. */
    fun forChar(c: Char): Pair<Int, Boolean>? = BY_CHAR[c]
}
