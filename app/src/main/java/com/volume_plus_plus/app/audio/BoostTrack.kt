package com.volume_plus_plus.app.audio

import kotlin.math.roundToInt

/**
 * The geometry of an app slider that has room for a boost after its 100% mark.
 *
 * The track is one scale in display percent: 0 up to 100 is the app's volume, and the stretch after
 * it is boost, [BoostPolicy.PERCENT_PER_STEP] points per notch up to [maxSteps]. So a 150% ceiling
 * puts the mark two thirds of the way along. Positions here are fractions of the whole track, 0..1.
 *
 * Pure so it can be tested without a view; [com.volume_plus_plus.app.overlay.VolumeSlider] only
 * draws what this decides.
 */
class BoostTrack(val maxSteps: Int) {

    /** Where the 100% mark sits along the track. 1 when there is no boost room at all. */
    val fullAt: Float = 100f / (100 + maxSteps.coerceAtLeast(0) * BoostPolicy.PERCENT_PER_STEP)

    /** The app's volume (0..1) at [position]; everything past the mark is a full volume. */
    fun volumeAt(position: Float): Float = (position / fullAt).coerceIn(0f, 1f)

    /** The boost notches (0..[maxSteps]) at [position], rounded to the nearest. */
    fun stepsAt(position: Float): Int {
        if (maxSteps <= 0 || position <= fullAt) return 0
        return (((position - fullAt) / (1f - fullAt)) * maxSteps).roundToInt().coerceIn(0, maxSteps)
    }

    /** Where [volume] with [steps] of boost sits; any boost implies a full volume. */
    fun positionOf(volume: Float, steps: Int): Float {
        if (steps <= 0 || maxSteps <= 0) return volume.coerceIn(0f, 1f) * fullAt
        return fullAt + steps.coerceAtMost(maxSteps) / maxSteps.toFloat() * (1f - fullAt)
    }

    /**
     * Where a finger at [position] comes to rest: on a notch once it is past the mark, and held on the
     * mark itself when it is just short of it or just past it, so landing on exactly 100% doesn't
     * take a steady hand.
     */
    fun snap(position: Float): Float {
        val p = position.coerceIn(0f, 1f)
        val steps = stepsAt(p)
        return when {
            steps > 0 -> positionOf(1f, steps)
            p > fullAt - MAGNET -> fullAt
            else -> p
        }
    }

    companion object {
        /** How far short of the mark (as a fraction of the track) the finger is still held on it. */
        const val MAGNET = 0.02f
    }
}
