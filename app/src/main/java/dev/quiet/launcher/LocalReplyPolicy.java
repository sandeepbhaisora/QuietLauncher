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
                && q.matches("(?s).*(\\btoday\\b|\\btomorrow\\b|\\bnow\\b|\\bthis week\\b|\\brecent\\b|\\bwhat(?:'s| is)\\b|\\bhow(?:'s| is)\\b).*"))
            return true;
        return q.matches("(?s).*(आज का मौसम|अभी मौसम|आज की खबर|ताज़ा खबर|आज का स्कोर).*");
    }

    static boolean usableLocalAnswer(String question, String answer) {
        if (answer == null) return false;
        String text = answer.trim();
        if (text.length() < 3 || text.length() > 900) return false;
        if (requiresLiveInformation(question)) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("<think") || lower.contains("</think>")
                || lower.startsWith("{\"type\"")) return false;
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
