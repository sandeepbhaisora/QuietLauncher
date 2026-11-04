package dev.quiet.launcher;

import java.util.Locale;

/** Safe exact end-of-session commands; never stop for a sentence containing exit. */
final class OfflineVoiceCommands {
    static boolean isExit(String text) {
        if(text==null)return false;
        String q=text.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[.!?।]+$", "").trim()
                .replaceFirst("^(?:hey\\s+(?:londe|londey|launde|laundey)|londe|londey|launde|laundey)[,\\s]+", "");
        return q.matches("exit|quit|close|stop listening|stop londe|go to sleep|"
                + "londe bas|bas kar|band ho ja|chup ho ja|so ja|"
                + "बंद हो जा|बस कर|सो जा|बाहर निकल");
    }
    private OfflineVoiceCommands(){}
}
