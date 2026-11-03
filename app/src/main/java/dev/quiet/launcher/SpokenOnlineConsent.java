package dev.quiet.launcher;

import java.util.Locale;

/**
 * Explicit, single-turn voice consent before ANY offline-to-online LLM fallback.
 *
 * No model text can authorize an upload. The user's fresh recognized yes/no is
 * checked locally, after the spoken question has finished. Silence, expired
 * responses and ambiguous phrases always result in NO network request.
 */
final class SpokenOnlineConsent {
    enum Reply { YES, NO, UNCLEAR }
    static final long RESPONSE_WINDOW_MS = 22_000L;
    private String question;
    private long listeningSince;

    void begin(String pendingQuestion) {
        question = pendingQuestion == null ? null : pendingQuestion.trim();
        if (question != null && question.isEmpty()) question = null;
        listeningSince = 0;
    }
    boolean pending() { return question != null; }
    boolean listening() { return pending() && listeningSince > 0; }
    void markListening(long now) {
        if (pending()) listeningSince = now;
    }
    boolean expired(long now) {
        return !listening() || now < listeningSince
                || now - listeningSince > RESPONSE_WINDOW_MS;
    }
    String approve(long now) {
        if (!pending() || expired(now)) {
            cancel();
            return null;
        }
        String approved = question;
        cancel();
        return approved;
    }
    void cancel() {
        question = null;
        listeningSince = 0;
    }

    /** Strict allowlist: "yes, do it" never means other unrelated device actions. */
    static Reply interpret(String transcript) {
        if (transcript == null) return Reply.UNCLEAR;
        String q = transcript.toLowerCase(Locale.ROOT).trim()
                .replace('’', '\'')
                .replaceAll("[.!?।,]+$", "").trim();
        q = q.replaceFirst("^(?:hey londe|londe)[,\\s]+", "").trim();
        if (q.isEmpty() || q.length() > 65) return Reply.UNCLEAR;
        if (q.matches("(?:no|nope|nah|not now|no thanks|don't|dont|cancel|"
                + "nahi|nahin|nhi|mat|rehne do|chhodo|नहीं|नही|मत|रहने दो|छोड़ो)"
                + "(?:\\s+(?:thanks|thank you|please|bhai))?"))
            return Reply.NO;
        // Negation takes precedence, including misheard combined phrases.
        if (q.matches("(?s).*(?:\\bno\\b|\\bnot\\b|\\bdon't\\b|\\bdont\\b|"
                + "\\bnahi\\b|\\bnahin\\b|\\bmat\\b|नहीं|मत).*"))
            return Reply.NO;
        if (q.matches("(?:yes|yeah|yep|yup|sure|okay|ok|go ahead|"
                + "haan|han|ha|bilkul|theek hai|जी हाँ|हाँ|हां|जी|ठीक है)"
                + "(?:\\s+(?:yes|haan|han|हाँ|go ahead|please|"
                + "use gpt|use chatgpt|use online|online|gpt|"
                + "kar do|karo|kar lo|use kar lo|use karo|"
                + "करो|कर दो|बिल्कुल))?"))
            return Reply.YES;
        return Reply.UNCLEAR;
    }

    /**
     * Local launcher -> voice-console handoff. Not an Android Intent payload,
     * so another application cannot inject arbitrary text via an exported
     * Activity. One-shot and expires if Londe does not open promptly.
     */
    private static String stagedQuestion;
    private static long stagedAt;
    static synchronized void stageForVoiceConsole(String q, long now) {
        stagedQuestion = q == null || q.trim().isEmpty() ? null : q.trim();
        stagedAt = now;
    }
    static synchronized String consumeForVoiceConsole(long now) {
        String result = now >= stagedAt && now - stagedAt <= 15_000L
                ? stagedQuestion : null;
        stagedQuestion = null;
        stagedAt = 0;
        return result;
    }
    private SpokenOnlineConsent(boolean unused) {}
    SpokenOnlineConsent() {}
}
