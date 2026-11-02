package com.volume_plus_plus.app.audio

import com.volume_plus_plus.app.audio.FocusEngine.Action
import com.volume_plus_plus.app.audio.FocusEngine.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FocusEngineTest {
    private val e = FocusEngine()

    @Before fun setUp() { e.mixed = setOf("deezer", "chrome", "game") }

    private fun req(pkg: String, usage: Int = 1, gain: Int = FocusEngine.GAIN, flags: Int = 0) =
        Entry(pkg, pkg, usage, gain, flags)

    private fun List<Action>.summary() = map {
        when (it) {
            is Action.Reply -> "reply ${it.entry.pkg} ${it.result}"
            is Action.Dispatch -> "dispatch ${it.entry.pkg} ${it.change}"
            is Action.Duck -> "duck ${it.entry.pkg}"
            is Action.Unduck -> "unduck ${it.entry.pkg}"
        }
    }

    private fun nav() = req("nav", usage = 12, gain = FocusEngine.GAIN_TRANSIENT_MAY_DUCK)

    @Test fun duckByPolicyDucksWithoutTellingTheApp() {
        e.duckByPolicy = true
        e.request(req("deezer"))
        assertEquals(listOf("reply nav 1", "duck deezer"), e.request(nav()).summary())
        assertEquals(listOf("unduck deezer"), e.abandon("nav").summary())
    }

    @Test fun duckedHolderThenCallIsUnduckedAndPaused() {
        e.duckByPolicy = true
        e.request(req("deezer"))
        e.request(nav())
        assertEquals(listOf("reply phone 1", "unduck deezer", "dispatch deezer -2", "dispatch nav -2"),
            e.request(req("phone", usage = 2, gain = FocusEngine.GAIN_TRANSIENT)).summary())
        // nav is still on the stack under the call: when the call ends deezer comes back ducked
        assertEquals(listOf("dispatch deezer 1", "duck deezer", "dispatch nav 1"), e.abandon("phone").summary())
        assertEquals(listOf("unduck deezer"), e.abandon("nav").summary())
    }

    @Test fun duckedHolderThatAbandonsIsUnducked() {
        e.duckByPolicy = true
        e.request(req("deezer"))
        e.request(nav())
        assertEquals(listOf("unduck deezer"), e.abandon("deezer").summary())
    }

    @Test fun releaseAllUnducks() {
        e.duckByPolicy = true
        e.request(req("deezer"))
        e.request(nav())
        assertEquals(listOf("unduck deezer"), e.releaseAll().summary())
        assertTrue(e.holders.isEmpty())
    }

    @Test fun pauseInsteadOfDuckWinsOverDuckByPolicy() {
        e.duckByPolicy = true
        e.pauseInsteadOfDuck = true
        e.request(req("deezer"))
        assertEquals(listOf("reply nav 1", "dispatch deezer -2"), e.request(nav()).summary())
    }

    @Test fun mixedMediaPairHasNoLoss() {
        e.request(req("deezer"))
        assertEquals(listOf("reply chrome 1"), e.request(req("chrome")).summary())
        assertEquals(2, e.holders.size)
    }

    @Test fun mixedGameAndMedia() {
        e.request(req("deezer"))
        assertEquals(listOf("reply game 1"), e.request(req("game", usage = 14)).summary())
    }

    @Test fun unmixedAppTakesFocusLikeStock() {
        e.request(req("deezer"))
        assertEquals(listOf("reply other 1", "dispatch deezer -1"), e.request(req("other")).summary())
        assertEquals(listOf("other"), e.holders.map { it.pkg }) // permanent loss drops the holder
    }

    @Test fun mixedAppAgainstUnmixedHolder() {
        e.request(req("other"))
        assertEquals(listOf("reply deezer 1", "dispatch other -1"), e.request(req("deezer")).summary())
    }

    @Test fun transientLossAndRegainOnAbandon() {
        e.request(req("deezer"))
        val r = e.request(req("other", gain = FocusEngine.GAIN_TRANSIENT)).summary()
        assertEquals(listOf("reply other 1", "dispatch deezer -2"), r)
        assertEquals(listOf("dispatch deezer 1"), e.abandon("other").summary())
    }

    @Test fun duckAndPauseOnDuckableFlag() {
        e.request(req("deezer"))
        e.request(req("chrome", flags = FocusEngine.FLAG_PAUSES_ON_DUCKABLE_LOSS))
        val r = e.request(req("nav", usage = 12, gain = FocusEngine.GAIN_TRANSIENT_MAY_DUCK)).summary()
        assertEquals(listOf("reply nav 1", "dispatch deezer -3", "dispatch chrome -2"), r)
        assertEquals(listOf("dispatch deezer 1", "dispatch chrome 1"), e.abandon("nav").summary())
    }

    @Test fun pauseInsteadOfDuckSendsTransientLoss() {
        e.pauseInsteadOfDuck = true
        e.request(req("deezer"))
        assertEquals(listOf("reply nav 1", "dispatch deezer -2"),
            e.request(req("nav", usage = 12, gain = FocusEngine.GAIN_TRANSIENT_MAY_DUCK)).summary())
    }

    @Test fun regainWaitsForEveryBlocker() {
        e.request(req("deezer"))
        e.request(req("a", gain = FocusEngine.GAIN_TRANSIENT))
        e.request(req("b", gain = FocusEngine.GAIN_TRANSIENT_MAY_DUCK))
        assertEquals(listOf("dispatch a 1"), e.abandon("b").summary()) // a was under b; deezer is still blocked by a
        assertEquals(listOf("dispatch deezer 1"), e.abandon("a").summary())
    }

    @Test fun unknownAbandonIsIgnored() {
        assertTrue(e.abandon("nobody").isEmpty())
    }

    @Test fun callBeatsMixedMediaAndMusicResumes() {
        e.request(req("deezer"))
        e.request(req("chrome"))
        val r = e.request(req("phone", usage = 2, gain = FocusEngine.GAIN_TRANSIENT)).summary()
        assertEquals(listOf("reply phone 1", "dispatch deezer -2", "dispatch chrome -2"), r)
        assertEquals(listOf("dispatch deezer 1", "dispatch chrome 1"), e.abandon("phone").summary())
    }

    @Test fun mediaIsRefusedDuringCall() {
        e.request(req("phone", usage = 6, gain = FocusEngine.GAIN_TRANSIENT))
        assertEquals(listOf("reply deezer 0"), e.request(req("deezer")).summary())
        assertEquals(listOf("phone"), e.holders.map { it.pkg })
    }

    @Test fun mediaIsRefusedWhileModeInCall() {
        e.setModeInCall(true)
        assertEquals(listOf("reply deezer 0"), e.request(req("deezer")).summary())
        assertEquals(listOf("reply nav 1"), e.request(req("nav", usage = 12, gain = FocusEngine.GAIN_TRANSIENT_MAY_DUCK)).summary())
    }

    @Test fun delayedRequestIsServedWhenCallEnds() {
        e.request(req("phone", usage = 2))
        assertEquals(listOf("reply chrome 2"), e.request(req("chrome", flags = FocusEngine.FLAG_DELAY_OK)).summary())
        assertEquals(listOf("dispatch chrome 1"), e.abandon("phone").summary())
        assertEquals(listOf("chrome"), e.holders.map { it.pkg })
    }

    @Test fun delayedRequestIsServedWhenModeLeavesCall() {
        e.setModeInCall(true)
        e.request(req("chrome", flags = FocusEngine.FLAG_DELAY_OK))
        assertEquals(listOf("dispatch chrome 1"), e.setModeInCall(false).summary())
    }

    @Test fun abandonedDelayedRequestIsForgotten() {
        e.setModeInCall(true)
        e.request(req("chrome", flags = FocusEngine.FLAG_DELAY_OK))
        e.abandon("chrome")
        assertTrue(e.setModeInCall(false).isEmpty())
    }

    @Test fun reRequestFromSameClientReplacesEntry() {
        e.request(req("deezer"))
        e.request(req("deezer"))
        assertEquals(1, e.holders.size)
    }
}
