package dev.quiet.launcher;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Offline-first routing, shared by the launcher and voice console.
 * Only verified device facts and known deterministic commands run locally.
 * Other questions go to the existing, explicitly-authorized ChatGPT connection.
 *
 * This class is not a local LLM, and it never executes Android actions.
 */
final class LocalFirstRouter {
    enum Kind { LOCAL_ANSWER, LOCAL_ACTION, CHATGPT }
    enum Fact { NONE, TIME, DATE, FOCUS_STATUS }

    static final class Decision {
        final Kind kind;
        final Fact fact;
        final String action;
        final boolean hindi;

        private Decision(Kind kind, Fact fact, String action, boolean hindi) {
            this.kind = kind;
            this.fact = fact;
            this.action = action;
            this.hindi = hindi;
        }
    }

    static Decision route(String request) {
        String raw = request == null ? "" : request.trim();
        boolean hindi = raw.matches("(?s).*[\\u0900-\\u097F].*")
                || hasRomanHindi(raw.toLowerCase(Locale.ROOT));
        String q = raw.toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replaceAll("[.!?।]+$", "")
                .trim();
        q = q.replaceFirst("^(?:hey londe|londe)[, ]+", "").trim();

        if (isTimeQuestion(q)) return fact(Fact.TIME, hindi);
        if (isDateQuestion(q)) return fact(Fact.DATE, hindi);
        if (isFocusStatusQuestion(q)) return fact(Fact.FOCUS_STATUS, hindi);

        // Questions about an action are NOT device commands: e.g.
        // "What is focus?" must go to ChatGPT, not open a focus picker.
        if (!isKnowledgeQuestion(q)) {
            String action = LondeCommands.normalize(raw);
            if (action != null) {
                return new Decision(Kind.LOCAL_ACTION, Fact.NONE, action, hindi);
            }
        }
        return new Decision(Kind.CHATGPT, Fact.NONE, null, hindi);
    }

    private static Decision fact(Fact fact, boolean hindi) {
        return new Decision(Kind.LOCAL_ANSWER, fact, null, hindi);
    }

    private static boolean hasRomanHindi(String q) {
        return q.contains("kitne baje") || q.contains("samay kya")
                || q.contains("aaj ki") || q.contains("aaj ka")
                || q.contains("kitna bacha") || q.contains("kitni der");
    }

    private static boolean isTimeQuestion(String q) {
        return q.equals("time") || q.equals("time now")
                || q.equals("current time") || q.equals("what time is it")
                || q.equals("what's the time") || q.equals("what is the time")
                || q.equals("tell me the time") || q.equals("what time is it now")
                || q.equals("kitne baje hain") || q.equals("abhi kitne baje hain")
                || q.equals("samay kya hai") || q.contains("अभी कितने बजे")
                || q.equals("कितने बजे हैं") || q.equals("समय क्या है");
    }

    private static boolean isDateQuestion(String q) {
        return q.equals("date") || q.equals("date today")
                || q.equals("today's date") || q.equals("what's the date")
                || q.equals("what is the date") || q.equals("what's today's date")
                || q.equals("what is today's date") || q.equals("what day is it")
                || q.equals("what day is today") || q.equals("aaj ka din kya hai")
                || q.equals("aaj ki tareekh kya hai") || q.equals("आज कौन सा दिन है")
                || q.equals("आज की तारीख क्या है") || q.equals("आज क्या दिन है");
    }

    private static boolean isFocusStatusQuestion(String q) {
        return q.equals("focus status") || q.equals("focus time left")
                || q.equals("how much focus time is left")
                || q.equals("how much time is left on focus")
                || q.equals("is focus running") || q.equals("focus kitna bacha hai")
                || q.equals("focus kitni der bachi hai")
                || q.equals("फोकस कितना बचा है")
                || q.equals("फोकस चल रहा है");
    }

    private static boolean isKnowledgeQuestion(String q) {
        return q.startsWith("what ") || q.startsWith("what's ")
                || q.startsWith("how ") || q.startsWith("why ")
                || q.startsWith("who ") || q.startsWith("where ")
                || q.startsWith("when ") || q.startsWith("which ")
                || q.startsWith("explain ") || q.startsWith("tell me about ")
                || q.startsWith("is it true ") || q.startsWith("क्या ")
                || q.startsWith("क्यों ") || q.startsWith("कैसे ")
                || q.startsWith("kaise ") || q.startsWith("kyun ")
                || q.startsWith("kya hota ");
    }

    static String localAnswer(Decision decision, long nowMillis, long focusUntil,
                              Locale locale) {
        if (decision.kind != Kind.LOCAL_ANSWER) throw new IllegalArgumentException("Not local");
        Locale effective = locale == null ? Locale.getDefault() : locale;
        switch (decision.fact) {
            case TIME: {
                String clock = new SimpleDateFormat("h:mm a", effective).format(new Date(nowMillis));
                return decision.hindi ? "अभी " + clock + " बजे हैं।" : "It's " + clock + ".";
            }
            case DATE: {
                String date = new SimpleDateFormat("EEEE, d MMMM yyyy", effective)
                        .format(new Date(nowMillis));
                return decision.hindi ? "आज " + date + " है।" : "Today is " + date + ".";
            }
            case FOCUS_STATUS: {
                if (focusUntil <= nowMillis) {
                    return decision.hindi ? "अभी कोई फोकस सेशन नहीं चल रहा है।"
                            : "No focus session is running.";
                }
                long minutes = Math.max(1L, (focusUntil - nowMillis + 59999) / 60000);
                return decision.hindi ? "फोकस में लगभग " + minutes + " मिनट बाकी हैं।"
                        : "About " + minutes + " minutes left in your focus session.";
            }
            default: throw new IllegalStateException("Unknown local fact");
        }
    }

    private LocalFirstRouter() {}
}
