package dev.quiet.launcher;

/** Converts Markdown-formatted assistant output into speakable prose. */
final class SpeechTextCleaner {
    private SpeechTextCleaner() {}

    static String clean(String input) {
        if (input == null) return "";
        String s = input;
        s = s.replaceAll("(?s)`{3}[^\\n]*\\n.*?`{3}", " Code example omitted. ");
        s = s.replaceAll("!\\[([^]]*)\\]\\([^)]+\\)", "$1");
        s = s.replaceAll("\\[([^]]+)\\]\\([^)]+\\)", "$1");
        s = s.replaceAll("https?://\\S+", " ");
        s = s.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        s = s.replaceAll("(?m)^\\s*[-*+]\\s+", "");
        s = s.replaceAll("(?m)^\\s*\\d+[.)]\\s+", "");
        s = s.replaceAll("(?m)^\\s*>\\s?", "");
        s = s.replaceAll("(?m)^\\s*[-*_]{3,}\\s*$", "");
        s = s.replaceAll("[*`_~#|]", "");
        s = s.replaceAll("[\\p{So}\\p{Cn}]", " ");
        s = s.replaceAll("[\\t ]+", " ").replaceAll("\\n{3,}", "\\n\\n").trim();
        return s;
    }
}
