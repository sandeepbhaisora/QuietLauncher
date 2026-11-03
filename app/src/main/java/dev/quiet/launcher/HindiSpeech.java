package dev.quiet.launcher;

import java.util.Locale;
import java.util.regex.Pattern;

/** Text-only Hindi/Hinglish pronunciation helpers. Never translates factual content. */
final class HindiSpeech {
    private HindiSpeech() {}

    private static final Locale HINDI = Locale.forLanguageTag("hi-IN");
    // Do not classify ordinary English by generic words like "main" or "to".
    private static final Pattern ROMAN_HINDI = Pattern.compile(
            "(?i)(?<![a-z])(?:haan|han|bhai|badhiya|bol(?:o)?|kya|kaise|"
            + "kaisa|kaisi|kyun|kyu|nahi|nahin|theek|shukriya|namaste|"
            + "achha|accha|achchha|raha|rahi|hoon|hun|batao|"
            + "aaj|abhi|kitna|kitni|kahan|kaha|chalo|"
            + "hai|hain)(?![a-z])");

    static boolean containsDevanagari(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u0900' && c <= '\u097F') return true;
        }
        return false;
    }

    static boolean shouldSpeakHindi(String text) {
        return containsDevanagari(text) ||
                (text != null && ROMAN_HINDI.matcher(SpeechTextCleaner.clean(text)).find());
    }

    static Locale languageFor(String text) {
        return shouldSpeakHindi(text) ? HINDI : Locale.ENGLISH;
    }

    static String prepare(String raw, Locale voiceLocale) {
        String s = SpeechTextCleaner.clean(raw);
        if (s.isEmpty() || voiceLocale == null || !"hi".equals(voiceLocale.getLanguage()))
            return s;
        // Common conversational Roman Hindi is converted *for speech only*.
        // Deliberately leave English technical terms, names and unknown words untouched.
        String[][] words = {
            {"theek hai", "ठीक है"}, {"kya haal", "क्या हाल"},
            {"kaise ho", "कैसे हो"}, {"kaisa hai", "कैसा है"},
            {"kya kar rahe ho", "क्या कर रहे हो"},
            {"haan", "हाँ"}, {"han", "हाँ"}, {"bhai", "भाई"},
            {"badhiya", "बढ़िया"}, {"namaste", "नमस्ते"},
            {"shukriya", "शुक्रिया"}, {"theek", "ठीक"},
            {"bolo", "बोलो"}, {"bol", "बोल"}, {"batao", "बताओ"},
            {"bata", "बता"}, {"achha", "अच्छा"}, {"accha", "अच्छा"},
            {"achchha", "अच्छा"}, {"kya", "क्या"},
            {"kaise", "कैसे"}, {"kaisa", "कैसा"}, {"kaisi", "कैसी"},
            {"kyun", "क्यों"}, {"kyu", "क्यों"}, {"nahi", "नहीं"},
            {"nahin", "नहीं"}, {"kahan", "कहाँ"}, {"kitna", "कितना"},
            {"kitni", "कितनी"}, {"raha", "रहा"}, {"rahi", "रही"},
            {"hoon", "हूँ"}, {"hun", "हूँ"}, {"hain", "हैं"},
            {"hai", "है"}, {"ho", "हो"}, {"tu", "तू"},
            {"tum", "तुम"}, {"mera", "मेरा"}, {"meri", "मेरी"},
            {"aaj", "आज"}, {"abhi", "अभी"}, {"kal", "कल"},
            {"chalo", "चलो"}, {"kar", "कर"}, {"karo", "करो"},
            {"main", "मैं"}, {"mein", "में"}, {"aur", "और"},
            {"sab", "सब"}, {"kuch", "कुछ"}
        };
        for (String[] pair : words) {
            s = s.replaceAll("(?i)(?<![\\p{L}])" + Pattern.quote(pair[0])
                    + "(?![\\p{L}])", pair[1]);
        }
        return s.trim();
    }
}
