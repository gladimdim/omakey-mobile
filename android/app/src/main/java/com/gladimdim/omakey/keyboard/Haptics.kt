package com.gladimdim.omakey.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Touch feedback modelled on a MacBook's Force Touch trackpad: a crisp
 * click as a button goes down and a softer one as it comes back up, a
 * deeper double click for a long press (a force click), and faint ticks
 * while scrolling. Keys get the same down and up pair, a little lighter.
 *
 * Built from the vibrator's primitives where it has them (sharp, short
 * pulses on a good linear motor), from the predefined effects otherwise.
 */
class Haptics(context: Context) {
    /** Off in Settings: nothing plays. */
    var enabled = true

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }?.takeIf { it.hasVibrator() }

    private val primitives = Build.VERSION.SDK_INT >= 30 && vibrator?.areAllPrimitivesSupported(
        VibrationEffect.Composition.PRIMITIVE_CLICK,
        VibrationEffect.Composition.PRIMITIVE_TICK,
        VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
    ) == true

    private val buttonDown = effect(VibrationEffect.EFFECT_CLICK) { click(0.85f) }
    private val buttonUp = effect(VibrationEffect.EFFECT_TICK) { click(0.4f) }
    private val tapClick = effect(VibrationEffect.EFFECT_CLICK) { click(0.85f).click(0.4f, delayMs = 45) }
    private val forceClick = effect(VibrationEffect.EFFECT_HEAVY_CLICK) { click(1f).click(0.9f, delayMs = 70) }
    private val scrollNotch = effect(VibrationEffect.EFFECT_TICK) { lowTick(0.6f) }
    private val keyDown = effect(VibrationEffect.EFFECT_CLICK) { click(0.6f) }
    private val keyUp = effect(VibrationEffect.EFFECT_TICK) { tick(0.3f) }

    /** A touchpad button went down / came back up. */
    fun down() = play(buttonDown)
    fun up() = play(buttonUp)

    /** A tap that clicks: the down and up of a click in one. */
    fun tap() = play(tapClick)

    /** A long press: the deeper second click of a force click. */
    fun force() = play(forceClick)

    /** A scroll strip passed a wheel notch. */
    fun notch() = play(scrollNotch)

    fun key(down: Boolean) = play(if (down) keyDown else keyUp)

    private fun play(e: VibrationEffect?) {
        if (!enabled || e == null) return
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(e, TOUCH)
        }
    }

    private fun effect(fallback: Int, compose: Composer.() -> Composer): VibrationEffect? {
        if (vibrator == null) return null
        if (primitives && Build.VERSION.SDK_INT >= 30) {
            return Composer(VibrationEffect.startComposition()).compose().composition.compose()
        }
        return VibrationEffect.createPredefined(fallback)
    }

    private class Composer(val composition: VibrationEffect.Composition) {
        fun click(scale: Float, delayMs: Int = 0) = add(VibrationEffect.Composition.PRIMITIVE_CLICK, scale, delayMs)
        fun tick(scale: Float) = add(VibrationEffect.Composition.PRIMITIVE_TICK, scale, 0)
        fun lowTick(scale: Float) = add(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale, 0)

        private fun add(primitive: Int, scale: Float, delayMs: Int): Composer {
            if (Build.VERSION.SDK_INT >= 30) composition.addPrimitive(primitive, scale, delayMs)
            return this
        }
    }

    private companion object {
        val TOUCH: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
