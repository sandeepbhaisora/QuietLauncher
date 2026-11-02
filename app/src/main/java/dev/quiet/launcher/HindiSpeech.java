package dev.quiet.launcher;

import java.util.Locale;

/** Speech-friendly normalization for bilingual assistant replies.
 * Does not translate arbitrary text or require an online service.
 */
final class HindiSpeech {
    private HindiSpeech() {}

    static boolean containsDevanagari(String text) {
        return text != null && text.matches("(?s).*[\\u0900-\\u097F].*");
    }

    static String prepare(String raw, Locale voiceLocale) {
        String s=SpeechTextCleaner.clean(raw);
        if (s.isEmpty()) return s;
        boolean hindi=voiceLocale!=null && "hi".equals(voiceLocale.getLanguage());
        if (!hindi) return s;
        // A handful of standard Romanized response fillers that routinely
        // cause Hindi neural voices to spell out English letters.
        s=s.replaceAll("(?i)\\bhaan\\b", "हाँ");
        s=s.replaceAll("(?i)\\bbhai\\b", "भाई");
        s=s.replaceAll("(?i)\\bnamaste\\b", "नमस्ते");
        s=s.replaceAll("(?i)\\btheek hai\\b", "ठीक है");
        s=s.replaceAll("(?i)\\bbolo\\b", "बोलो");
        s=s.replaceAll("(?i)\\bshukriya\\b", "शुक्रिया");
        s=s.replaceAll("(?i)\\bachha\\b", "अच्छा");
        s=s.replaceAll("(?i)\\bkya\\b", "क्या");
        s=s.replaceAll("(?i)\\bkaise\\b", "कैसे");
        s=s.replaceAll("(?i)\\bhoon\\b", "हूँ");
        return s.trim();
    }
}
