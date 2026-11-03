package dev.quiet.launcher;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;

/** System assistant invocation opens the existing Londe voice console. */
public final class LondeVoiceSession extends VoiceInteractionSession {
    private static final String TAG = "LondeSystemAssistant";

    public LondeVoiceSession(Context context) { super(context); }

    @Override public void onShow(Bundle args, int flags) {
        super.onShow(args, flags);
        try {
            Intent intent = new Intent(getContext(), LondeActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("auto_listen", true);
            startAssistantActivity(intent);
            hide();
        } catch (Exception error) {
            Log.e(TAG, "Unable to launch Londe assistant UI", error);
            hide();
        }
    }
}
