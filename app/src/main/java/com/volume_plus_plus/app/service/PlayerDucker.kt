package com.volume_plus_plus.app.service

import android.media.AudioManager
import java.lang.reflect.InvocationTargetException

/**
 * Ducks an app's players the way the platform does for stock audio focus: one volume shaper per
 * player, ramping it down to [DUCK_VOLUME] and back. While an external focus policy is registered the
 * platform skips that step, leaving a duckable focus loss as a notification the app may well ignore.
 *
 * Runs in the privileged process. A player's handle ([PlayerProxy][android.media.PlayerProxy], hidden)
 * comes with its [android.media.AudioPlaybackConfiguration] only for a caller holding
 * MODIFY_AUDIO_ROUTING, which is exactly what the shell and root UIDs are. Everything on those
 * classes is reflective for the same reason [UserService]'s per-player volume is: none of it is public.
 */
internal class PlayerDucker(private val audioManager: AudioManager) {

    /** Ducks every live player of [uid]. Returns how many were reached. */
    fun duck(uid: Int): Int {
        val config = duckConfiguration()
        val operation = operationBuilder(OPERATION_PLAY).also { it.call("createIfNeeded") }.call("build")
            ?: return 0
        return players(uid) { it in PLAYER_STATE_IDLE..PLAYER_STATE_PAUSED }
            .count { it.applyShaper(config, operation) }
    }

    /**
     * Ramps the duck back up on every player of [uid]. A player that was never ducked ignores it, so
     * this is safe to call for any uid.
     */
    fun unduck(uid: Int): Int {
        val config = shaperConfigurationClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(DUCK_ID)
        val operation = shaperOperationClass.getField(OPERATION_REVERSE).get(null) ?: return 0
        return players(uid) { true }.count { it.applyShaper(config, operation) }
    }

    /** The shaper the platform itself uses (same id, level and shape), so the two never stack. */
    private fun duckConfiguration(): Any {
        val builder = shaperConfigurationClass.classes.first { it.simpleName == "Builder" }
            .getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()
        builder.call("setId", DUCK_ID)
        builder.call("setCurve", floatArrayOf(0f, 1f), floatArrayOf(1f, DUCK_VOLUME))
        builder.call("setOptionFlags", OPTION_FLAG_CLOCK_TIME)
        builder.call("setInterpolatorType", INTERPOLATOR_LINEAR)
        builder.call("setDuration", RAMP_MS)
        return builder.call("build")!!
    }

    private fun operationBuilder(base: String): Any {
        val builder = shaperOperationClass.classes.first { it.simpleName == "Builder" }
        return builder.declaredConstructors.first { it.parameterTypes.size == 1 }
            .apply { isAccessible = true }
            .newInstance(shaperOperationClass.getField(base).get(null))
    }

    /** The player handle of each of [uid]'s players whose state passes [accept]. */
    private fun players(uid: Int, accept: (Int) -> Boolean): List<Any> =
        audioManager.activePlaybackConfigurations
            .filter { it.call("getClientUid") == uid && accept(it.call("getPlayerState") as Int) }
            .mapNotNull { it.call("getPlayerProxy") }

    private fun Any.applyShaper(config: Any, operation: Any): Boolean =
        try {
            call("applyVolumeShaper", config, operation)
            true
        } catch (e: Throwable) {
            false
        }

    private fun Any.call(name: String, vararg args: Any?): Any? {
        val method = (javaClass.declaredMethods + javaClass.methods)
            .first { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }
        return try {
            method.invoke(this, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private companion object {
        /** VOLUME_SHAPER_SYSTEM_DUCK_ID in the platform's PlaybackActivityMonitor. */
        const val DUCK_ID = 2

        /** The level the platform ducks to, about -14 dB. */
        const val DUCK_VOLUME = 0.2f
        const val RAMP_MS = 300L

        // VolumeShaper.Configuration flags and VolumeShaper.Operation constants.
        const val OPTION_FLAG_CLOCK_TIME = 2
        const val INTERPOLATOR_LINEAR = 1
        const val OPERATION_PLAY = "PLAY"
        const val OPERATION_REVERSE = "REVERSE"

        // AudioPlaybackConfiguration player states.
        const val PLAYER_STATE_IDLE = 1
        const val PLAYER_STATE_PAUSED = 3

        val shaperConfigurationClass: Class<*> by lazy { Class.forName("android.media.VolumeShaper\$Configuration") }
        val shaperOperationClass: Class<*> by lazy { Class.forName("android.media.VolumeShaper\$Operation") }
    }
}
