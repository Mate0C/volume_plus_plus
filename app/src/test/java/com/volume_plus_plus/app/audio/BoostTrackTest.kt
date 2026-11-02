package com.volume_plus_plus.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class BoostTrackTest {

    private val track = BoostTrack(maxSteps = 10) // a 150% ceiling

    @Test
    fun theMarkSitsWhereOneHundredPercentFallsOnTheScale() {
        assertEquals(100f / 150f, track.fullAt, 0.0001f)
        assertEquals(100f / 120f, BoostTrack(4).fullAt, 0.0001f)
    }

    @Test
    fun noBoostRoomLeavesTheMarkAtTheEnd() {
        assertEquals(1f, BoostTrack(0).fullAt, 0f)
        assertEquals(0, BoostTrack(0).stepsAt(1f))
    }

    @Test
    fun volumeFillsTheTrackUpToTheMark() {
        assertEquals(0f, track.volumeAt(0f), 0f)
        assertEquals(0.5f, track.volumeAt(track.fullAt / 2f), 0.0001f)
        assertEquals(1f, track.volumeAt(track.fullAt), 0.0001f)
        assertEquals(1f, track.volumeAt(1f), 0f)
    }

    @Test
    fun noBoostUntilPastTheMark() {
        assertEquals(0, track.stepsAt(0.3f))
        assertEquals(0, track.stepsAt(track.fullAt))
    }

    @Test
    fun theEndOfTheTrackIsTheCeiling() {
        assertEquals(10, track.stepsAt(1f))
        assertEquals(4, BoostTrack(4).stepsAt(1f))
    }

    @Test
    fun stepsRoundToTheNearestNotch() {
        val halfwayToFirst = track.fullAt + (1f - track.fullAt) / 10f * 0.4f
        val pastHalfway = track.fullAt + (1f - track.fullAt) / 10f * 0.6f
        assertEquals(0, track.stepsAt(halfwayToFirst))
        assertEquals(1, track.stepsAt(pastHalfway))
    }

    @Test
    fun positionAndStepsRoundTrip() {
        for (steps in 1..10) {
            assertEquals(steps, track.stepsAt(track.positionOf(1f, steps)))
        }
        assertEquals(track.fullAt, track.positionOf(1f, 0), 0.0001f)
        assertEquals(track.fullAt / 2f, track.positionOf(0.5f, 0), 0.0001f)
    }

    @Test
    fun aStepCountAboveTheCeilingIsHeldAtIt() {
        assertEquals(1f, track.positionOf(1f, 99), 0.0001f)
    }

    @Test
    fun theFingerSticksToTheMarkOnEitherSide() {
        assertEquals(track.fullAt, track.snap(track.fullAt - 0.01f), 0f)
        assertEquals(track.fullAt, track.snap(track.fullAt + 0.001f), 0f)
    }

    @Test
    fun farBelowTheMarkTheFingerIsLeftAlone() {
        assertEquals(0.4f, track.snap(0.4f), 0f)
    }

    @Test
    fun pastTheMarkTheFingerLandsOnANotch() {
        val notch = track.positionOf(1f, 3)
        assertEquals(notch, track.snap(notch + 0.003f), 0.0001f)
    }
}
