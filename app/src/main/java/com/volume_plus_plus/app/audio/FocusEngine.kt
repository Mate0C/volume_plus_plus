package com.volume_plus_plus.app.audio

/**
 * The audio-focus rules behind smart mixing: who may keep playing when another app asks for focus.
 * Pure Kotlin with no Android types, so the rules can be unit-tested on the JVM;
 * [com.volume_plus_plus.app.service.FocusPolicyHost] feeds it the system's requests and abandons and
 * carries out the [Action]s it returns.
 *
 * It models the same stack of holders as the platform's focus controller (last = top) and follows
 * stock behaviour — GAIN costs a holder its focus for good, TRANSIENT and EXCLUSIVE take it away until
 * the requester leaves, MAY_DUCK lowers it — with two differences:
 *
 * - Two holders from [mixed] that are both media or game never cost each other anything.
 * - A call always wins. Media, game and unknown-usage requests are refused while one is going on
 *   (DELAYED when the app said it can wait, then granted when the call ends).
 *
 * When the requester abandons, every holder whose blockers are all gone gets AUDIOFOCUS_GAIN back.
 */
class FocusEngine {
    companion object {
        // android.media.AudioManager values, copied so this class stays JVM-only.
        const val GAIN = 1
        const val GAIN_TRANSIENT = 2
        const val GAIN_TRANSIENT_MAY_DUCK = 3
        const val GAIN_TRANSIENT_EXCLUSIVE = 4
        const val LOSS = -1
        const val LOSS_TRANSIENT = -2
        const val LOSS_TRANSIENT_CAN_DUCK = -3
        const val REGAINED = 1

        const val RESULT_FAILED = 0
        const val RESULT_GRANTED = 1
        const val RESULT_DELAYED = 2

        const val FLAG_DELAY_OK = 0x1
        const val FLAG_PAUSES_ON_DUCKABLE_LOSS = 0x2

        // android.media.AudioAttributes usages.
        private const val USAGE_UNKNOWN = 0
        private const val USAGE_MEDIA = 1
        private const val USAGE_VOICE_COMMUNICATION = 2
        private const val USAGE_VOICE_COMMUNICATION_SIGNALLING = 3
        private const val USAGE_NOTIFICATION_RINGTONE = 6
        private const val USAGE_GAME = 14
    }

    class Entry(
        val key: String,
        val pkg: String,
        val usage: Int,
        val gain: Int,
        val flags: Int,
        val handle: Any? = null,
    ) {
        /** Last loss sent to this holder (0 = none). */
        var loss = 0
        /** Being ducked by us (Duck sent, Unduck not yet): the app was not told about the loss. */
        var ducked = false
        override fun toString() = "$pkg(usage=$usage gain=$gain)"
    }

    sealed interface Action {
        /** Answer to the pending request. */
        data class Reply(val entry: Entry, val result: Int) : Action
        /** A focus change to push to a holder: a loss, or REGAINED. */
        data class Dispatch(val entry: Entry, val change: Int) : Action
        /** Lower the holder's players ourselves (stock Android's ducking); the app is not notified. */
        data class Duck(val entry: Entry) : Action
        /** Ramp the holder's players back up. */
        data class Unduck(val entry: Entry) : Action
    }

    /** Packages allowed to play together (media/game usage only). */
    var mixed: Set<String> = emptySet()

    /**
     * Under an external focus policy the system no longer ducks players itself. When true, a duckable loss becomes
     * Duck/Unduck actions (like stock: the app is never told) instead of a LOSS_TRANSIENT_CAN_DUCK dispatch, which
     * only helps apps that lower their own volume.
     */
    var duckByPolicy = false

    /** Turn ducks into pauses (LOSS_TRANSIENT). */
    var pauseInsteadOfDuck = false

    /** The phone is in a call / ringing / in a VoIP call (from AudioManager.getMode). */
    private var modeInCall = false

    private val stack = ArrayList<Entry>()
    private val pending = ArrayList<Entry>()

    val holders: List<Entry> get() = stack.toList()
    val inCall: Boolean get() = modeInCall || stack.any { isCall(it) }

    fun reset() { stack.clear(); pending.clear(); modeInCall = false }

    /** Forgets everything; returns the Unducks to send for players still ducked. */
    fun releaseAll(): List<Action> {
        val out = stack.filter { it.ducked }.map { Action.Unduck(it) }
        reset()
        return out
    }

    fun request(e: Entry): List<Action> {
        val out = ArrayList<Action>()
        remove(e.key, out)
        if (!isCall(e) && inCall && isMixable(e)) {
            if (e.flags and FLAG_DELAY_OK != 0) {
                pending += e
                out += Action.Reply(e, RESULT_DELAYED)
            } else {
                out += Action.Reply(e, RESULT_FAILED)
            }
            return out
        }
        out += Action.Reply(e, RESULT_GRANTED)
        take(e, out)
        return out
    }

    fun abandon(key: String): List<Action> {
        val out = ArrayList<Action>()
        if (remove(key, out)) afterRelease(out)
        return out
    }

    /** Drops a holder or delayed request; a holder being ducked is unducked. True if it was a holder. */
    private fun remove(key: String, out: MutableList<Action>): Boolean {
        pending.removeAll { it.key == key }
        val gone = stack.filter { it.key == key }
        gone.forEach { unduck(it, out) }
        return stack.removeAll(gone.toSet())
    }

    private fun unduck(h: Entry, out: MutableList<Action>) {
        if (!h.ducked) return
        h.ducked = false
        out += Action.Unduck(h)
    }

    fun setModeInCall(value: Boolean): List<Action> {
        if (modeInCall == value) return emptyList()
        modeInCall = value
        val out = ArrayList<Action>()
        if (!value) afterRelease(out)
        return out
    }

    // ---------------------------------------------------------------- internals

    /** Puts e on top, sending losses to the holders it conflicts with. */
    private fun take(e: Entry, out: MutableList<Action>) {
        for (h in stack.toList()) {
            if (!conflicts(e, h)) continue
            val loss = lossFor(e.gain, h)
            if (loss == LOSS) {
                unduck(h, out)
                out += Action.Dispatch(h, LOSS)
                stack.remove(h)
            } else if (severity(loss) > severity(h.loss)) {
                unduck(h, out)
                h.loss = loss
                if (loss == LOSS_TRANSIENT_CAN_DUCK && duckByPolicy) {
                    h.ducked = true
                    out += Action.Duck(h)
                } else {
                    out += Action.Dispatch(h, loss)
                }
            }
        }
        stack += e
    }

    /** Something left (abandon or call ended): regain holders that are no longer blocked, then serve delayed requests. */
    private fun afterRelease(out: MutableList<Action>) {
        for ((i, h) in stack.withIndex()) {
            if (h.loss == 0) continue
            val wanted = stack.subList(i + 1, stack.size)
                .filter { conflicts(it, h) }
                .map { lossFor(it.gain, h) }
                .maxByOrNull { severity(it) } ?: 0
            if (severity(wanted) < severity(h.loss)) {
                h.loss = wanted
                if (h.ducked) {
                    unduck(h, out) // ducked means CAN_DUCK, the lightest loss, so the app never knew: nothing to regain
                } else if (wanted == LOSS_TRANSIENT_CAN_DUCK && duckByPolicy) {
                    h.ducked = true
                    out += Action.Dispatch(h, REGAINED)
                    out += Action.Duck(h)
                } else {
                    out += Action.Dispatch(h, if (wanted == 0) REGAINED else wanted)
                }
            }
        }
        if (!inCall) {
            for (e in pending.toList()) {
                pending.remove(e)
                take(e, out)
                out += Action.Dispatch(e, REGAINED)
            }
        }
    }

    private fun isCall(e: Entry) =
        e.usage == USAGE_VOICE_COMMUNICATION || e.usage == USAGE_VOICE_COMMUNICATION_SIGNALLING || e.usage == USAGE_NOTIFICATION_RINGTONE

    private fun isMixable(e: Entry) = e.usage == USAGE_MEDIA || e.usage == USAGE_GAME || e.usage == USAGE_UNKNOWN

    /** Does `upper` taking focus take it away from `lower`? */
    private fun conflicts(upper: Entry, lower: Entry): Boolean {
        if (isCall(upper) && !isCall(lower)) return true
        return !(upper.pkg in mixed && lower.pkg in mixed && isMixable(upper) && isMixable(lower))
    }

    private fun lossFor(gain: Int, holder: Entry) = when (gain) {
        GAIN -> LOSS
        GAIN_TRANSIENT_MAY_DUCK ->
            if (pauseInsteadOfDuck || holder.flags and FLAG_PAUSES_ON_DUCKABLE_LOSS != 0) LOSS_TRANSIENT else LOSS_TRANSIENT_CAN_DUCK
        else -> LOSS_TRANSIENT // TRANSIENT, EXCLUSIVE
    }

    private fun severity(loss: Int) = when (loss) {
        LOSS -> 3
        LOSS_TRANSIENT -> 2
        LOSS_TRANSIENT_CAN_DUCK -> 1
        else -> 0
    }
}
