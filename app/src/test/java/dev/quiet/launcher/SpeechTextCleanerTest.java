package dev.quiet.launcher;
public final class SpeechTextCleanerTest {
    private static void expect(String input, String output) {
        String actual = SpeechTextCleaner.clean(input);
        if (!output.equals(actual)) throw new AssertionError("Expected [" + output + "] but got [" + actual + "]");
    }
    public static void main(String[] args) {
        expect("**Hello** *friend*", "Hello friend");
        expect("### Hello", "Hello");
        expect("[Spotify](https://spotify.com) is ready", "Spotify is ready");
        expect("**Done** ✅", "Done");
        expect("https://example.com", "");
        expect(null, "");
    }
}
