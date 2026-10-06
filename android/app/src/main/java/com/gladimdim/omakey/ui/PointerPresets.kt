package com.gladimdim.omakey.ui

/**
 * Touchpad speed presets for common developer monitors. The pointer moves in
 * the desktop's logical pixels, so what matters is the logical width: a 4K
 * screen at 1.5x scaling behaves like a 1440p one. The value is roughly
 * proportional to that width, so one swipe across the pad covers a similar
 * share of any screen (the desktop's pointer acceleration does the rest).
 */
object PointerPresets {
    class Preset(val name: String, val detail: String, val sensitivity: Float)

    val all = listOf(
        Preset("Laptop", "13–16″ laptop, about 1920 wide after scaling", 0.8f),
        Preset("1080p", "24″ 1920×1080, or 4K at 2× scaling", 0.85f),
        Preset("1440p", "27″ 2560×1440, or 4K at 1.5× scaling", 1.1f),
        Preset("Ultrawide 1440p", "34″ 3440×1440", 1.45f),
        Preset("4K unscaled", "32″+ 3840×2160 at 100%", 1.6f),
        Preset("Super ultrawide", "49″ 5120×1440, or two 1440p side by side", 2.1f),
        Preset("Triple 1080p", "three 1920×1080 side by side", 2.4f),
    )

    val default: Preset = all.first { it.name == "1440p" }
}
