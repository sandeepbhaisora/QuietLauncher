package dev.quiet.launcher;

import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionService;

/**
 * System-registered assistant entry point. User must explicitly select Quiet
 * as default assistant; this does not bypass Android microphone restrictions.
 */
public final class LondeVoiceService extends VoiceInteractionService {
    @Override public void onReady() { super.onReady(); }

    @Override public void onLaunchVoiceAssistFromKeyguard() {
        // No silent unlock or background activity launch from a locked device.
        showSession(new Bundle(), SHOW_SOURCE_ASSIST_GESTURE);
    }
}
