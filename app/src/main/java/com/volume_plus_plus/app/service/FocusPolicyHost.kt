package com.volume_plus_plus.app.service

import android.content.Context
import android.media.AudioFocusInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiopolicy.AudioPolicy
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.annotation.RequiresApi
import com.volume_plus_plus.app.audio.FocusEngine
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Smart audio focus, hosted in the privileged process. It registers itself with the platform as the
 * device's external focus policy, which hands it every focus request and abandon instead of letting
 * the platform decide; [FocusEngine] makes the decisions, and this class carries them out.
 *
 * ## Why this process
 *
 * Registering a policy needs MODIFY_AUDIO_ROUTING, a signature-level permission the shell and root
 * UIDs have and the app's own UID cannot hold. The policy has to be owned by whoever registered it
 * too: it is a Binder callback, so it lives and dies with this process. That is a feature — if
 * Shizuku or root goes away the platform drops the policy and audio focus is back to stock at once,
 * with nothing left behind to clean up.
 *
 * ## What changes while it is registered
 *
 * - The platform's own focus stack is empty, so only requests made *after* registration are known.
 *   An app already playing keeps playing; it joins the engine's stack the next time it asks.
 * - The platform stops ducking, so ducks are done here ([PlayerDucker]) and the app is not told.
 * - The app's own `TAKE_AUDIO_FOCUS` op is irrelevant: requests arrive here before it is consulted.
 *
 * ## The AttributionSource
 *
 * `registerAudioPolicy` takes an AttributionSource on recent platforms, and the platform rejects one
 * whose uid is not the caller's. `AudioManager.registerAudioPolicy` fills in the *app's* uid, which
 * is never ours, so the service is called directly and the source is built from this process's own
 * identity. Every other parameter is mapped by type, since this signature has changed between
 * releases.
 *
 * Tested on Android 17 with the shell UID. Other releases and root are expected to work the same way
 * but have not been tried; any failure comes back as an `ERROR` string rather than an exception.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal class FocusPolicyHost(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val ducker = PlayerDucker(audioManager)
    private val engine = FocusEngine().apply { duckByPolicy = true }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var audioService: Any? = null
    private var policy: Any? = null
    private var modeListener: Any? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    /** Whether the policy is registered and answering requests. */
    @get:Synchronized
    val isActive: Boolean get() = policy != null

    /**
     * Brings the policy in line with the user's choice: registers it when [enabled] and not yet
     * registered, hands it the current [mixed] packages either way, or tears it down. Idempotent.
     * Returns "" on success, otherwise a diagnostic beginning with "ERROR".
     */
    @Synchronized
    fun apply(enabled: Boolean, mixed: Set<String>, pauseInsteadOfDuck: Boolean): String {
        if (!enabled) {
            stop()
            return ""
        }
        synchronized(engine) {
            engine.mixed = mixed
            engine.pauseInsteadOfDuck = pauseInsteadOfDuck
        }
        if (policy != null) return ""
        return try {
            start()
            ""
        } catch (e: Throwable) {
            stop()
            val cause = e.root()
            "ERROR: ${cause.javaClass.simpleName}: ${cause.message}"
        }
    }

    /** Unregisters the policy and puts back anything it had ducked. Safe to call when not running. */
    @Synchronized
    fun stop() {
        val registered = policy
        unwatchCallMode()
        unwatchPlayers()
        // Whatever was ducked is ducked in the app that plays it, not here, so a policy that went away
        // without lowering the duck would leave that app quiet.
        synchronized(engine) { engine.releaseAll() }.forEach { execute(it) }
        if (registered != null) {
            runCatching {
                interfaceClass.methods.first { it.name == "unregisterAudioPolicy" }
                    .invoke(audioService, registered.call("cb"))
            }
        }
        policy = null
        audioService = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    // ── registration ──────────────────────────────────────────────────────────────────────────────

    private fun start() {
        val service = audioServiceBinder()
        val looper = HandlerThread("focus-policy").also { it.start(); thread = it }.looper
        handler = Handler(looper)
        val built = buildPolicy(looper)
        synchronized(engine) { engine.reset() }
        // Set before registering: requests can arrive as soon as the call returns.
        audioService = service
        policy = built
        val registration = interfaceClass.methods.first { it.name == "registerAudioPolicy" }.let {
            it.invoke(service, *registerArguments(it, built))
        }
        check(registration is String) { "registerAudioPolicy returned $registration" }
        built.call("setRegistration", registration)
        watchCallMode()
        watchPlayers()
    }

    private fun audioServiceBinder(): Any {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, Context.AUDIO_SERVICE) as? IBinder
            ?: error("no audio service")
        return Class.forName("android.media.IAudioService\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    private fun buildPolicy(looper: Looper): Any {
        val builderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val builder = builderClass.getConstructor(Context::class.java).newInstance(context)
        builder.call("setLooper", looper)
        builder.call("setAudioPolicyFocusListener", FocusListener())
        builder.call("setIsAudioFocusPolicy", true)
        return builder.call("build")!!
    }

    /** Maps each parameter by type: booleans in the order the platform lists them, the rest by name. */
    private fun registerArguments(method: Method, built: Any): Array<Any?> {
        val flags = listOf("hasFocusListener", "isFocusPolicy", "isTestFocusPolicy", "isVolumeController")
            .map { runCatching { built.call(it) as Boolean }.getOrDefault(false) }
        var nextFlag = 0
        return method.parameterTypes.map { type ->
            when {
                type == Boolean::class.javaPrimitiveType -> flags.getOrElse(nextFlag++) { false }
                type.name.endsWith("AudioPolicyConfig") -> built.call("getConfig")
                type.name.endsWith("IAudioPolicyCallback") -> built.call("cb")
                type.name == ATTRIBUTION_SOURCE -> attributionSource()
                else -> null // IMediaProjection: only for policies that capture, which this one does not
            }
        }.toTypedArray()
    }

    /** This process's own identity: the platform requires the source's uid to be the caller's. */
    private fun attributionSource(): Any {
        val uid = Process.myUid()
        val builderClass = Class.forName("$ATTRIBUTION_SOURCE\$Builder")
        val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(uid)
        builder.call("setPackageName", if (uid == SHELL_UID) SHELL_PACKAGE else "android")
        return builder.call("build")!!
    }

    // ── what the policy listens to ────────────────────────────────────────────────────────────────

    /** Every focus event the platform forwards, on the policy's own thread. */
    private inner class FocusListener : AudioPolicy.AudioPolicyFocusListener() {
        override fun onAudioFocusRequest(afi: AudioFocusInfo, requestResult: Int) =
            process { engine.request(entryOf(afi)) }

        override fun onAudioFocusAbandon(afi: AudioFocusInfo) =
            process { engine.abandon(entryOf(afi).key) }
    }

    /**
     * Whether the phone is in a call, ringing or in a VoIP call. Read once now and then followed, on
     * Android 12 and up; before that only a call's own focus request marks one (see [FocusEngine]).
     */
    private fun watchCallMode() {
        process { engine.setModeInCall(inCallMode(audioManager.mode)) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val listener = AudioManager.OnModeChangedListener { mode ->
            process { engine.setModeInCall(inCallMode(mode)) }
        }
        audioManager.addOnModeChangedListener({ handler?.post(it) }, listener)
        modeListener = listener
    }

    private fun unwatchCallMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        (modeListener as? AudioManager.OnModeChangedListener)?.let {
            runCatching { audioManager.removeOnModeChangedListener(it) }
        }
        modeListener = null
    }

    /**
     * An app being ducked may open a new player (the next track) while the duck is on, and that one
     * would play at full volume. Ducking an already ducked player is a no-op, so every change is
     * simply answered by ducking the same apps again.
     */
    private fun watchPlayers() {
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                synchronized(engine) { engine.holders.filter { it.ducked } }
                    .map { (it.handle as AudioFocusInfo).clientUid }
                    .distinct()
                    .forEach { ducker.duck(it) }
            }
        }
        audioManager.registerAudioPlaybackCallback(callback, handler)
        playbackCallback = callback
    }

    private fun unwatchPlayers() {
        playbackCallback?.let { runCatching { audioManager.unregisterAudioPlaybackCallback(it) } }
        playbackCallback = null
    }

    // ── engine ↔ platform ─────────────────────────────────────────────────────────────────────────

    /** Feeds one event to the engine and carries out what it decides. */
    private fun process(event: () -> List<FocusEngine.Action>) {
        synchronized(engine) { event() }.forEach { execute(it) }
    }

    private fun execute(action: FocusEngine.Action) {
        runCatching {
            when (action) {
                is FocusEngine.Action.Reply ->
                    sendToPlatform("setFocusRequestResultFromExtPolicy", action.entry, action.result)
                is FocusEngine.Action.Dispatch ->
                    sendToPlatform("dispatchFocusChange", action.entry, action.change)
                is FocusEngine.Action.Duck -> ducker.duck(action.entry.afi.clientUid)
                is FocusEngine.Action.Unduck -> ducker.unduck(action.entry.afi.clientUid)
            }
        }
    }

    /** IAudioService's focus calls all take the request, an int and this policy's callback. */
    private fun sendToPlatform(name: String, entry: FocusEngine.Entry, value: Int) {
        val registered = policy ?: return
        interfaceClass.methods.first { it.name == name }
            .invoke(audioService, entry.afi, value, registered.call("cb"))
    }

    private fun entryOf(afi: AudioFocusInfo) = FocusEngine.Entry(
        key = "${afi.clientUid}/${afi.clientId}",
        pkg = afi.packageName,
        usage = afi.attributes.usage,
        gain = afi.gainRequest,
        flags = afi.flags,
        handle = afi,
    )

    private val FocusEngine.Entry.afi: AudioFocusInfo get() = handle as AudioFocusInfo

    private fun inCallMode(mode: Int) =
        mode == AudioManager.MODE_RINGTONE ||
            mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION

    // ── reflection helpers ────────────────────────────────────────────────────────────────────────

    private fun Any.call(name: String, vararg args: Any?): Any? {
        val method = (javaClass.declaredMethods + javaClass.methods)
            .first { it.name == name && it.parameterTypes.size == args.size }
            .apply { isAccessible = true }
        return method.invoke(this, *args)
    }

    private fun Throwable.root(): Throwable =
        (this as? InvocationTargetException)?.targetException ?: this

    private companion object {
        const val ATTRIBUTION_SOURCE = "android.content.AttributionSource"
        const val SHELL_UID = 2000
        const val SHELL_PACKAGE = "com.android.shell"

        val interfaceClass: Class<*> by lazy { Class.forName("android.media.IAudioService") }
    }
}
