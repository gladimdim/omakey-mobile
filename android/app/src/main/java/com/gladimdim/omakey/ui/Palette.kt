package com.gladimdim.omakey.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import com.gladimdim.omakey.protocol.DesktopTheme
import com.gladimdim.omakey.store.AppSettings

/** One colour scheme, after an Omarchy theme. */
class Theme(
    val id: String,
    val name: String,
    val light: Boolean,
    val bg: Int,
    val surface: Int,
    val key: Int,
    val keyMod: Int,
    val keyAccent: Int,
    val accent: Int,
    val layer: Int,
    val fg: Int,
    val fgDim: Int,
    val fgOnAccent: Int,
    val ok: Int,
    val warn: Int,
    val error: Int,
)

/**
 * The colours every screen draws with, from the chosen [Theme]. Screens
 * read them while they build and draw, so [load] runs first in each
 * activity's onCreate.
 */
object Palette {
    val THEMES = listOf(
        Theme(
            "tokyo-night", "Tokyo Night", false,
            bg = 0xFF1A1B26.c, surface = 0xFF24283B.c, key = 0xFF2A2F45.c, keyMod = 0xFF1F2335.c,
            keyAccent = 0xFF3D59A1.c, accent = 0xFF7AA2F7.c, layer = 0xFFBB9AF7.c,
            fg = 0xFFC0CAF5.c, fgDim = 0xFF565F89.c, fgOnAccent = 0xFFE0E6FF.c,
            ok = 0xFF9ECE6A.c, warn = 0xFFE0AF68.c, error = 0xFFF7768E.c,
        ),
        Theme(
            "catppuccin", "Catppuccin", false,
            bg = 0xFF1E1E2E.c, surface = 0xFF292C3C.c, key = 0xFF363A4F.c, keyMod = 0xFF24273A.c,
            keyAccent = 0xFF45588A.c, accent = 0xFF89B4FA.c, layer = 0xFFCBA6F7.c,
            fg = 0xFFCDD6F4.c, fgDim = 0xFF6C7086.c, fgOnAccent = 0xFFE6ECFF.c,
            ok = 0xFFA6E3A1.c, warn = 0xFFF9E2AF.c, error = 0xFFF38BA8.c,
        ),
        Theme(
            "gruvbox", "Gruvbox", false,
            bg = 0xFF282828.c, surface = 0xFF32302F.c, key = 0xFF3C3836.c, keyMod = 0xFF2E2C2B.c,
            keyAccent = 0xFF7A5F24.c, accent = 0xFFD8A657.c, layer = 0xFFD3869B.c,
            fg = 0xFFEBDBB2.c, fgDim = 0xFF7C6F64.c, fgOnAccent = 0xFFFBF1C7.c,
            ok = 0xFFB8BB26.c, warn = 0xFFFE8019.c, error = 0xFFFB4934.c,
        ),
        Theme(
            "nord", "Nord", false,
            bg = 0xFF2E3440.c, surface = 0xFF3B4252.c, key = 0xFF434C5E.c, keyMod = 0xFF363D4B.c,
            keyAccent = 0xFF5E81AC.c, accent = 0xFF88C0D0.c, layer = 0xFFB48EAD.c,
            fg = 0xFFECEFF4.c, fgDim = 0xFF7B88A1.c, fgOnAccent = 0xFFF2F6FA.c,
            ok = 0xFFA3BE8C.c, warn = 0xFFEBCB8B.c, error = 0xFFBF616A.c,
        ),
        Theme(
            "everforest", "Everforest", false,
            bg = 0xFF2D353B.c, surface = 0xFF343F44.c, key = 0xFF3D484D.c, keyMod = 0xFF30393E.c,
            keyAccent = 0xFF56704C.c, accent = 0xFFA7C080.c, layer = 0xFFD699B6.c,
            fg = 0xFFD3C6AA.c, fgDim = 0xFF7A8478.c, fgOnAccent = 0xFFF0EEDB.c,
            ok = 0xFFA7C080.c, warn = 0xFFDBBC7F.c, error = 0xFFE67E80.c,
        ),
        Theme(
            "kanagawa", "Kanagawa", false,
            bg = 0xFF1F1F28.c, surface = 0xFF2A2A37.c, key = 0xFF363646.c, keyMod = 0xFF252532.c,
            keyAccent = 0xFF2D4F67.c, accent = 0xFF7E9CD8.c, layer = 0xFF957FB8.c,
            fg = 0xFFDCD7BA.c, fgDim = 0xFF727169.c, fgOnAccent = 0xFFEAE6D0.c,
            ok = 0xFF98BB6C.c, warn = 0xFFE6C384.c, error = 0xFFE46876.c,
        ),
        Theme(
            "rose-pine", "Rosé Pine", false,
            bg = 0xFF191724.c, surface = 0xFF1F1D2E.c, key = 0xFF26233A.c, keyMod = 0xFF1D1B2C.c,
            keyAccent = 0xFF524073.c, accent = 0xFFC4A7E7.c, layer = 0xFFEBBCBA.c,
            fg = 0xFFE0DEF4.c, fgDim = 0xFF6E6A86.c, fgOnAccent = 0xFFF2E9FF.c,
            ok = 0xFF9CCFD8.c, warn = 0xFFF6C177.c, error = 0xFFEB6F92.c,
        ),
        Theme(
            "matte-black", "Matte Black", false,
            bg = 0xFF121212.c, surface = 0xFF1C1C1C.c, key = 0xFF262626.c, keyMod = 0xFF1A1A1A.c,
            keyAccent = 0xFF5A3A10.c, accent = 0xFFE68E0D.c, layer = 0xFFB0A090.c,
            fg = 0xFFEAEAEA.c, fgDim = 0xFF6B6B6B.c, fgOnAccent = 0xFFFFF1DC.c,
            ok = 0xFF8FB573.c, warn = 0xFFE68E0D.c, error = 0xFFD35F5F.c,
        ),
        Theme(
            "catppuccin-latte", "Catppuccin Latte", true,
            bg = 0xFFEFF1F5.c, surface = 0xFFE6E9EF.c, key = 0xFFFFFFFF.c, keyMod = 0xFFDCE0E8.c,
            keyAccent = 0xFF1E66F5.c, accent = 0xFF1E66F5.c, layer = 0xFF8839EF.c,
            fg = 0xFF4C4F69.c, fgDim = 0xFF8C8FA1.c, fgOnAccent = 0xFFFFFFFF.c,
            ok = 0xFF40A02B.c, warn = 0xFFDF8E1D.c, error = 0xFFD20F39.c,
        ),
    )

    /** The theme id that follows the computer's Omarchy theme. */
    const val FROM_COMPUTER = "computer"

    @Volatile var theme: Theme = THEMES[0]
        private set

    fun byId(id: String): Theme = THEMES.firstOrNull { it.id == id } ?: THEMES[0]

    /** Read the chosen theme and paint [activity]'s window with it. */
    fun load(activity: Activity) {
        val settings = AppSettings(activity)
        theme = if (settings.themeId == FROM_COMPUTER) fromComputer(settings.desktopTheme) else byId(settings.themeId)
        activity.window.setBackgroundDrawable(ColorDrawable(BG))
    }

    /**
     * The computer's theme, or the default until one arrives. Omarchy gives
     * a background, a foreground and named colours; keys and surfaces are
     * mixed from those, a step towards the foreground (or white, on light).
     */
    fun fromComputer(d: DesktopTheme?): Theme {
        val t = d ?: return THEMES[0].let { Theme(FROM_COMPUTER, "From computer", it.light, it.bg, it.surface, it.key,
            it.keyMod, it.keyAccent, it.accent, it.layer, it.fg, it.fgDim, it.fgOnAccent, it.ok, it.warn, it.error) }
        fun c(key: String) = 0xFF000000.toInt() or t.colors[DesktopTheme.KEYS.indexOf(key)]
        val bg = c("background")
        val fg = c("foreground")
        val accent = c("accent")
        return Theme(
            FROM_COMPUTER, prettyName(t.name), t.light,
            bg = bg,
            surface = mix(bg, fg, if (t.light) 0.05f else 0.07f),
            key = if (t.light) mix(bg, WHITE, 0.75f) else mix(bg, fg, 0.12f),
            keyMod = mix(bg, fg, if (t.light) 0.09f else 0.05f),
            keyAccent = if (t.light) accent else mix(bg, accent, 0.45f),
            accent = accent,
            layer = c("magenta"),
            fg = fg,
            fgDim = c("muted"),
            fgOnAccent = if (t.light) WHITE else mix(WHITE, accent, 0.12f),
            ok = c("green"),
            warn = c("yellow"),
            error = c("red"),
        )
    }

    /** "tokyo-night" as Omarchy shows it: "Tokyo Night". */
    fun prettyName(id: String) = id.split('-', '_', ' ').filter { it.isNotEmpty() }
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

    private const val WHITE = 0xFFFFFFFF.toInt()

    /** [a] moved [t] of the way to [b], per channel. */
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    val BG get() = theme.bg
    val SURFACE get() = theme.surface
    val KEY get() = theme.key
    val KEY_MOD get() = theme.keyMod
    val KEY_FKEY get() = theme.keyMod
    val KEY_ACCENT get() = theme.keyAccent
    val ACCENT get() = theme.accent
    val LAYER get() = theme.layer
    val FG get() = theme.fg
    val FG_DIM get() = theme.fgDim
    val FG_ON_ACCENT get() = theme.fgOnAccent
    val OK get() = theme.ok
    val WARN get() = theme.warn
    val ERROR get() = theme.error

    /** The system dialog style that matches: light or dark. */
    val dialogTheme get() =
        if (theme.light) android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert

    private val Long.c get() = toInt()
}
