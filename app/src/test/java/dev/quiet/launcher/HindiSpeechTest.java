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
        System.out.println("HindiSpeechTest passed");
    }
}
