package dev.quiet.launcher;

import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import java.util.Locale;
import java.util.Set;

/** Align Android TTS language with each reply, retaining a compatible selected voice. */
final class AndroidSpeechOutput {
    private AndroidSpeechOutput() {}

    static String prepare(TextToSpeech engine, String text, String preferredName) {
        Locale desired = HindiSpeech.languageFor(text);
        Voice active = engine.getVoice();
        if (active == null || !desired.getLanguage().equals(active.getLocale().getLanguage())) {
            Voice best = null;
            Set<Voice> choices = engine.getVoices();
            if (choices != null) {
                for (Voice candidate : choices) {
                    if (!desired.getLanguage().equals(candidate.getLocale().getLanguage())
                            || candidate.isNetworkConnectionRequired()) continue;
                    if (candidate.getName().equals(preferredName)) {
                        best = candidate;
                        break;
                    }
                    if (best == null || candidate.getQuality() > best.getQuality())
                        best = candidate;
                }
            }
            if (best != null) {
                engine.setVoice(best);
            } else if (engine.isLanguageAvailable(desired) >= TextToSpeech.LANG_AVAILABLE) {
                engine.setLanguage(desired);
            }
        } else if (preferredName != null && !preferredName.isEmpty()
                && !preferredName.equals(active.getName())) {
            Set<Voice> choices = engine.getVoices();
            if (choices != null) {
                for (Voice candidate : choices) {
                    if (candidate.getName().equals(preferredName)
                            && desired.getLanguage().equals(candidate.getLocale().getLanguage())) {
                        engine.setVoice(candidate);
                        break;
                    }
                }
            }
        }
        Voice actual = engine.getVoice();
        return HindiSpeech.prepare(text, actual == null ? desired : actual.getLocale());
    }
}
