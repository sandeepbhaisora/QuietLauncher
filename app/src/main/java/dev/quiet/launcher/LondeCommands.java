package dev.quiet.launcher;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic and conservative device-command detection.
 * No AI answer may launch an app: actions always go to Quiet's preview/policy.
 */
final class LondeCommands {
    private static final Pattern TRAILING = Pattern.compile(
        "(?i)(?:\\s+(?:please|ek baar|ek bar|once|for me|zara|jara|bhai))*\\s*[.!?]*$");
    private static final Pattern HINGLISH = Pattern.compile(
        "(?iu)^(?:hey londe[, ]+|londe[, ]+)?(.{1,70}?)\\s+" +
        "(?:khol(?:o|na|do)?|open\\s+(?:kar(?:o|na|do)|kardo|kar\\s+do))\\s*$");
    private static final Pattern OPEN_EN = Pattern.compile(
        "(?i)^(?:hey londe[, ]+|londe[, ]+|please\\s+|can you\\s+|could you\\s+)*" +
        "(?:open|launch)\\s+(?:the\\s+)?(?:app\\s+)?(.{1,70}?)$");
    private static final Pattern FOCUS = Pattern.compile(
        "(?i)^(?:(?:please|hey londe|londe|can you|could you)\\s+)*" +
        "(?:(?:start|stop|begin|end)\\s+)?(?:a\\s+)?(?:focus|deep work)\\b.*");
    private static final Pattern TRAIN = Pattern.compile(
        "(?i)^(?:(?:please|hey londe|londe)\\s+)*" +
        "(?:(?:start|begin|open)\\s+)?(?:a\\s+)?(?:workout|training|exercise)\\b.*");
    private static final Pattern OTHER = Pattern.compile(
        "(?i)^(?:(?:please|hey londe|londe)\\s+)*" +
        "(?:show (?:all )?apps(?: drawer)?|open (?:settings|notes|workspace|calendar)|" +
        "show (?:my )?(?:screen time|usage)|stop (?:the |my )?focus(?: session)?)$");

    static String normalize(String original) {
        if (original == null) return null;
        String q = original.trim().replaceAll("\\s+", " ");
        if (q.isEmpty() || q.length() > 220) return null;
        String cleaned = TRAILING.matcher(q).replaceFirst("").trim();
        Matcher open = OPEN_EN.matcher(cleaned);
        if (open.matches()) return appCommand(open.group(1));
        Matcher hinglish = HINGLISH.matcher(cleaned);
        if (hinglish.matches()) return appCommand(hinglish.group(1));
        if (FOCUS.matcher(cleaned).matches() ||
                TRAIN.matcher(cleaned).matches() ||
                OTHER.matcher(cleaned).matches()) return cleaned;
        return null;
    }

    private static String appCommand(String app) {
        String name = app.trim().replaceAll("(?i)^(?:mera|meri|my|the)\\s+", "")
                .replaceAll("(?i)\\s+(?:app|application)$", "").trim();
        if (name.isEmpty() || name.length() > 70 ||
                name.matches("(?i).*(?:how|why|what|when|which|explain|who)\\b.*"))
            return null;
        return "open " + name;
    }
    private LondeCommands() {}
}
