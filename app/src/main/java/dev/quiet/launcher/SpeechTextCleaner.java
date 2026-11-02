package dev.quiet.launcher;

/** Strip presentation markup before sending assistant replies to a speech engine. */
final class SpeechTextCleaner {
    private SpeechTextCleaner() {}

    static String clean(String input) {
        if (input == null) return "";
        String s = input;
        s = s.replaceAll("(?s)`{3}[^\\n]*\\n.*?`{3}", " Code snippet omitted. ");
        s = s.replaceAll("!\\[([^]]*)\\]\\([^)]+\\)", "$1");
        s = s.replaceAll("\\[([^]]+)\\]\\([^)]+\\)", "$1");
        s = s.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        s = s.replaceAll("(?m)^\\s*[-*+]\\s+", "");
        s = s.replaceAll("(?m)^\\s*\\d+[.)]\\s+", "");
        s = s.replaceAll("(?m)^\\s*>\\s?", "");
        s = s.replaceAll("(?m)^\\s*[-*_]{3,}\\s*$", "");
        s = s.replaceAll("[*`_~#|]", "");
        s = s.replaceAll("(?<!\\w)\\\\([\\\\*_~`])", "$1");
        s = s.replaceAll("[\\p{So}\\p{Cn}]", " ");
        s = s.replaceAll("[\\t ]+", " ").replaceAll("\\n{3,}", "\\n\\n").trim();
        return s;
    }
}
