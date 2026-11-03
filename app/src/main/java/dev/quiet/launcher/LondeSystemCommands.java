package dev.quiet.launcher;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.view.KeyEvent;

import java.util.Locale;

/** Immediate, explicit, low-risk phone controls and observations. */
final class LondeSystemCommands {
    private LondeSystemCommands() {}

    static String tryExecute(Activity context, String voice) {
        String q = voice == null ? "" : voice.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[!?।.,]+$", "").trim()
                .replaceFirst("^(?:hey londe|londe)[, ]+", "");
        if (q.matches("(?:battery|battery level|battery status|how much battery(?: is left)?|battery kitni hai|बैटरी कितनी है|बैटरी कितना बचा है)")) {
            BatteryManager battery = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            int percent = battery == null ? Integer.MIN_VALUE :
                    battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return percent < 0 || percent > 100
                    ? "Battery information isn't available."
                    : "Battery is at " + percent + " percent.";
        }

        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return null;
        try {
            if (q.matches("(?:volume up|increase volume|awaaz badhao|आवाज़ बढ़ाओ|आवाज बढ़ाओ)")) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE,
                        AudioManager.FLAG_SHOW_UI);
                return "Media volume increased.";
            }
            if (q.matches("(?:volume down|decrease volume|awaaz kam karo|आवाज़ कम करो|आवाज कम करो)")) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI);
                return "Media volume decreased.";
            }
            if (q.matches("(?:mute music|mute volume|mute audio|आवाज़ बंद करो)")) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
                return "Media muted.";
            }
            if (q.matches("(?:unmute music|unmute volume|unmute audio)")) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                return "Media unmuted.";
            }

            int key = 0;
            if (q.matches("(?:pause music|pause song|pause playback|music roko|gaana roko|गाना रोको)"))
                key = KeyEvent.KEYCODE_MEDIA_PAUSE;
            else if (q.matches("(?:resume music|play music|resume playback|gaana chalao|गाना चलाओ)"))
                key = KeyEvent.KEYCODE_MEDIA_PLAY;
            else if (q.matches("(?:next song|skip song|next track|agla gaana|अगला गाना)"))
                key = KeyEvent.KEYCODE_MEDIA_NEXT;
            else if (q.matches("(?:previous song|previous track|pichhla gaana|पिछला गाना)"))
                key = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            if (key != 0) {
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
                // A media command may be ignored if no app owns the media session.
                return "Sent the command to the current media player.";
            }
        } catch (SecurityException ex) {
            return "Android blocked that media control. Check app permissions.";
        }
        return null;
    }
}
