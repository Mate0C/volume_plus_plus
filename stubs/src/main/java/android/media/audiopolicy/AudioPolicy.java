package android.media.audiopolicy;

import android.media.AudioFocusInfo;

/**
 * Stub: only the focus listener base class, which the app has to subclass. Everything else on
 * AudioPolicy (Builder, getConfig, cb, ...) is reached by reflection.
 */
public class AudioPolicy {
    public static abstract class AudioPolicyFocusListener {
        public void onAudioFocusGrant(AudioFocusInfo afi, int requestResult) {}
        public void onAudioFocusLoss(AudioFocusInfo afi, boolean wasNotified) {}
        public void onAudioFocusRequest(AudioFocusInfo afi, int requestResult) {}
        public void onAudioFocusAbandon(AudioFocusInfo afi) {}
    }
}
