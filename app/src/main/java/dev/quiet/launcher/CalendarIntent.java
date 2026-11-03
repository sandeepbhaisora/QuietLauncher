package dev.quiet.launcher;

import java.util.Locale;

/** Ground personal schedule questions in Android Calendar, never Qwen training data. */
final class CalendarIntent {
    static boolean isPersonalSchedule(String text) {
        if (text == null) return false;
        String q = text.toLowerCase(Locale.ROOT).replace('’','\'').trim();
        if (q.matches("(?s).*(what is a meeting|what are meetings|explain meetings|"
                + "how to schedule|how do calendars work|calendar api|meeting etiquette).*"))
            return false;
        return q.matches("(?s).*(\\b(my |any |have |scheduled |upcoming |today'?s |tomorrow'?s )"
                + "(meetings?|appointments?|events?|calendar|schedule)\\b).*")
                || q.matches("(?s).*(\\bdo i have (?:any |to )?(?:meetings?|appointments?|"
                + "go anywhere|be somewhere|travel|go out)\\b).*")
                || q.matches("(?s).*(\\bwhere (?:do i|am i) (?:have to|need to|going to) go\\b).*")
                || q.matches("(?s).*(\\bwhat(?:'s| is) (?:on|in) my calendar\\b|"
                + "\\bwhat(?:'s| do i have) (?:planned|scheduled)\\b|"
                + "\\bmy plans (?:today|tomorrow)\\b).*")
                || q.matches("(?s).*(आज.*(?:मेरी|मेरा|कोई).*(?:मीटिंग|अपॉइंटमेंट)|"
                + "मेरी.*(?:मीटिंग|कैलेंडर)|"
                + "aaj.*(?:meri|koi).*(?:meeting|appointment)|"
                + "kya.*(?:meri|koi).*(?:meeting|appointment)).*");
    }

    static boolean isTravelQuestion(String text) {
        if (text == null) return false;
        String q=text.toLowerCase(Locale.ROOT);
        return q.matches("(?s).*(\\bgo anywhere\\b|\\bgo out\\b|\\bgoing anywhere\\b|"
                + "\\bwhere (?:do i|am i) (?:have to|need to|going to) go\\b|"
                + "\\btravel today\\b).*");
    }

    static boolean isTomorrow(String text) {
        return text != null && text.toLowerCase(Locale.ROOT)
                .matches("(?s).*(\\btomorrow\\b|\\bkal\\b|कल).*");
    }
    private CalendarIntent() {}
}
