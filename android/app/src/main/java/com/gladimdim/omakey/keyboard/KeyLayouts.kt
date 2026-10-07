package com.gladimdim.omakey.keyboard

/**
 * A computer keyboard layout the phone types with: its xkb name, and which
 * key (and Shift) gives each character. The computer reads Omakey's keys
 * with the layout the phone asks for (INPUT `layout`), so what arrives is
 * what was typed on the phone, whatever layout the computer itself is on.
 */
class KeyLayout(val xkb: String, private val chars: Map<Char, Pair<Int, Boolean>>) {
    fun forChar(c: Char): Pair<Int, Boolean>? = chars[c]
}

object KeyLayouts {
    val US = KeyLayout("us", UsKeys.BY_CHAR)

    /** Ukrainian, xkb `ua` (its default variant): ЙЦУКЕН. */
    val UA = KeyLayout("ua", buildMap {
        fun row(first: Int, letters: String) = letters.forEachIndexed { i, c ->
            put(c, first + i to false)
            put(c.uppercaseChar(), first + i to true)
        }
        row(16, "йцукенгшщзхї")
        row(30, "фівапролджє")
        row(44, "ячсмитьбю")
        put('ґ', 43 to false); put('Ґ', 43 to true)
        put('\'', 41 to false); put('ʼ', 41 to true)
        // Digits and the punctuation ua has, so they don't switch layouts mid-word.
        "1234567890".forEachIndexed { i, c -> put(c, 2 + i to false) }
        "!\"№;%:?*()".forEachIndexed { i, c -> put(c, 2 + i to true) }
        put('-', 12 to false); put('_', 12 to true); put('=', 13 to false); put('+', 13 to true)
        put('.', 53 to false); put(',', 53 to true)
        put(' ', UsKeys.KEY_SPACE to false)
        put('\n', UsKeys.KEY_ENTER to false); put('\t', UsKeys.KEY_TAB to false)
    })

    /** The phone keyboard's language, first choice first: Ukrainian puts ua before us. */
    fun preferred(languageTag: String?): List<KeyLayout> =
        if (languageTag?.lowercase()?.startsWith("uk") == true) listOf(UA, US) else listOf(US, UA)

    /** The layout and key for [c]: [current] when it has it (no switching), else the first of [preferred] that does. */
    fun pick(c: Char, current: String?, preferred: List<KeyLayout>): Pair<KeyLayout, Pair<Int, Boolean>>? {
        preferred.firstOrNull { it.xkb == current }?.forChar(c)?.let { return preferred.first { l -> l.xkb == current } to it }
        for (l in preferred) l.forChar(c)?.let { return l to it }
        return null
    }
}
