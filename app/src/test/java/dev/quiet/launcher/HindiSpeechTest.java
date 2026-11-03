package dev.quiet.launcher;
import java.util.Locale;
public final class HindiSpeechTest {
    private static void assertContains(String actual,String expected) {
        if(!actual.contains(expected)) throw new AssertionError("Expected "+expected+" in "+actual);
    }
    public static void main(String[] args) {
        String hindi=HindiSpeech.prepare("**Haan bhai**, bolo!",new Locale("hi","IN"));
        assertContains(hindi,"हाँ भाई");
        assertContains(hindi,"बोलो");
        String english=HindiSpeech.prepare("**Haan bhai**, bolo!",Locale.ENGLISH);
        assertContains(english,"Haan bhai");
        if(!HindiSpeech.containsDevanagari(hindi))throw new AssertionError("Missing Devanagari");
        if (!HindiSpeech.shouldSpeakHindi("Badhiya bhai, tu bata kaisa hai?"))
            throw new AssertionError("Hinglish detection failed");
        assertContains(HindiSpeech.prepare("Badhiya bhai, tu bata kaisa hai?",
                new Locale("hi", "IN")), "बढ़िया भाई");
        assertContains(HindiSpeech.prepare("Badhiya bhai, tu bata kaisa hai?",
                new Locale("hi", "IN")), "कैसा है");
        if (HindiSpeech.shouldSpeakHindi("A race condition occurs when threads overlap."))
            throw new AssertionError("Technical English must stay English");
        if (HindiSpeech.shouldSpeakHindi("Explain the main thread"))
            throw new AssertionError("English 'main' must not imply Hindi");
        System.out.println("HindiSpeechTest passed");
    }
}
