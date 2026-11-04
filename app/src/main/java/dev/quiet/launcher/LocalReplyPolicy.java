package dev.quiet.launcher;

import java.util.Locale;

/**
 * Pure policy; Qwen is always attempted before these response checks for
 * general questions. Trusted device facts/actions remain local.
 */
final class LocalReplyPolicy {
    static boolean requiresLiveInformation(String question) {
        if (question == null) return false;
        String q = question.toLowerCase(Locale.ROOT).trim();
        if (q.matches("(?s).*(\\blatest\\b|\\bbreaking\\b|\\blive\\b|\\bright now\\b|\\bcurrent(?:ly)?\\b|\\brecent news\\b).*"))
            return true;
        if (q.matches("(?s).*(\\bnews\\b|\\bweather\\b|\\bforecast\\b|\\bscore\\b|\\bstock price\\b|\\bmarket price\\b|\\btraffic\\b).*")
                && q.matches("(?s).*(\\btoday\\b|\\btomorrow\\b|\\bnow\\b|\\bthis week\\b|\\brecent\\b|\\bwhat's the\\b|\\bhow's the\\b|\\bhow is the\\b|\\bwhat is the weather\\b).*"))
            return true;
        return q.matches("(?s).*(आज का मौसम|अभी मौसम|आज की खबर|ताज़ा खबर|आज का स्कोर).*");
    }

    static boolean usableLocalAnswer(String question, String answer) {
        if (answer == null) return false;
        String text = answer.trim();
        if (text.length() < 2 || text.length() > 900) return false;
        if (requiresLiveInformation(question)) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        // An unrelated canned greeting/date isn't a successful local answer.
        if (lower.matches("(?s)^(hello|hi|hey)[!,. ]*(?:i am |i'm )?(?:londe|an ai assistant)?[!,. ]*"
                + "(?:how (?:can|may) i (?:help|assist) you|what can i do for you).*$")
                || lower.matches("(?s)^(?:how can i (?:help|assist) you|what can i do for you)[?.! ]*$"))
            return false;
        boolean askedForDate = q.matches("(?s).*(?:\\bdate\\b|\\bday\\b|\\btoday's date\\b|"
                + "कौन सा दिन|आज की तारीख).*");
        if (!askedForDate && lower.matches("(?s)^(?:yes,? )?(?:today is |it's |it is )?"
                + "(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)"
                + "[, .]+.*(?:20[2-9][0-9]).*"))
            return false;
        if (CalendarIntent.isPersonalSchedule(question)) return false;
        if (lower.contains("<think") || lower.contains("</think>")
                || lower.startsWith("{\"type\"")) return false;
        if (lower.matches("(?s).*\\[(?:fallback|error)(?::|\\]|\\s).*"))
            return false;
        // Tiny local models can respond with identity/introduction filler
        // instead of addressing a technical or conversational question.
        boolean greeted = q.matches("(?s)^\\s*(hi|hello|hey|namaste|नमस्ते)[!.,? ]*$");
        if (!greeted && lower.matches("(?s)^\\s*(?:hello[!,. ]+|hi[!,. ]+)?"
                + "(?:i(?:'m| am) (?:londe|an? (?:ai )?assistant)|"
                + "as an ai language model|i(?:'m| am) here to (?:help|assist))\\b.*"))
            return false;
        if (lower.replaceAll("[^\\p{L}\\p{N}]+", " ").trim()
                .equals(q.replaceAll("[^\\p{L}\\p{N}]+", " ").trim()))
            return false;
        if ((lower.contains("openai") || lower.contains("chatgpt"))
                && (lower.contains("unreachable") || lower.contains("unable to reach")
                    || lower.contains("cannot connect") || lower.contains("can't connect")
                    || lower.contains("connection failed"))) return false;
        if (lower.matches("(?s)^\\s*(i (?:can'?t|cannot|do not|don't) (?:access|browse|retrieve|check) (?:live|real-time|current|the internet).*"
                + "|(?:sorry,?\\s*)?i (?:don'?t|do not) (?:know|have) (?:that |the )?(?:live|current|latest).*"
                + "|(?:i(?:'m| am) )?unable to answer.*)"))
            return false;
        return true;
    }

    static String offlineMessage() {
        return "Qwen tried locally but couldn't produce a reliable answer. "
                + "Your phone is offline, so I didn't contact ChatGPT. "
                + "Connect to the internet to use the fallback.";
    }

    static String unauthorizedMessage() {
        return "Qwen couldn't answer this one. ChatGPT fallback is not connected or authorized. "
                + "Check the ChatGPT connection in Quiet Settings.";
    }

    static String cloudFailedMessage() {
        return "Qwen couldn't answer this one, and the ChatGPT fallback failed. "
                + "Check your internet connection and ChatGPT authorization, or try another question.";
    }

    private LocalReplyPolicy() {}
}
