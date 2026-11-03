package dev.quiet.launcher;

import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

public final class LondeSessionService extends VoiceInteractionSessionService {
    @Override public VoiceInteractionSession onNewSession(android.os.Bundle args) {
        return new LondeVoiceSession(this);
    }
}
