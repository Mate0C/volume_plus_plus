package com.volume_plus_plus.app.data

import android.content.Context

/**
 * Remembers which packages the user has enabled mixing for, so the switches reflect the
 * last applied state instantly without an appops round-trip per app. The actual system
 * state is still owned by appops; this is a fast local mirror kept in sync on every toggle.
 */
class MixPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("mix_audio", Context.MODE_PRIVATE)

    fun enabledPackages(): Set<String> =
        prefs.getStringSet(KEY_ENABLED, emptySet())?.toSet() ?: emptySet()

    fun setEnabled(packageName: String, enabled: Boolean) {
        val updated = enabledPackages().toMutableSet().apply {
            if (enabled) add(packageName) else remove(packageName)
        }
        prefs.edit().putStringSet(KEY_ENABLED, updated).apply()
    }

    /**
     * Replaces the remembered set wholesale. Used when the real appops state is re-read, so
     * the mirror is corrected rather than accumulated — a package dropped from the system's
     * ignore set must drop out of here too.
     */
    fun setEnabledPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_ENABLED, packages).apply()
    }

    /** Whether the user has dismissed the "may be unstable" warning banner. */
    fun isWarningDismissed(): Boolean = prefs.getBoolean(KEY_WARNING_DISMISSED, false)

    fun setWarningDismissed(dismissed: Boolean) {
        prefs.edit().putBoolean(KEY_WARNING_DISMISSED, dismissed).apply()
    }

    /**
     * Whether the user chose root over Shizuku for the privileged service. Read on every process
     * start, so the choice is restored before anything measures the Shizuku side and the user never
     * sees a checklist they've already opted out of.
     */
    fun isRootMode(): Boolean = prefs.getBoolean(KEY_USE_ROOT, false)

    fun setRootMode(useRoot: Boolean) {
        prefs.edit().putBoolean(KEY_USE_ROOT, useRoot).apply()
    }

    /**
     * Whether mixing runs through the smart audio-focus policy instead of the `TAKE_AUDIO_FOCUS`
     * app-op. The switches in [enabledPackages] mean the same either way; this only picks what
     * carries them out.
     */
    fun isSmartFocusEnabled(): Boolean = prefs.getBoolean(KEY_SMART_FOCUS, false)

    fun setSmartFocusEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SMART_FOCUS, enabled).apply()
    }

    /** Whether a voice prompt pauses the music it would otherwise lower the volume of. */
    fun isPauseInsteadOfDuck(): Boolean = prefs.getBoolean(KEY_PAUSE_INSTEAD_OF_DUCK, false)

    fun setPauseInsteadOfDuck(pause: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSE_INSTEAD_OF_DUCK, pause).apply()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_SMART_FOCUS = "smart_focus"
        const val KEY_PAUSE_INSTEAD_OF_DUCK = "pause_instead_of_duck"
        const val KEY_WARNING_DISMISSED = "warning_dismissed"
        const val KEY_USE_ROOT = "use_root"
    }
}
