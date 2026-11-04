package dev.quiet.launcher;

import java.util.Arrays;
import java.util.Locale;

public final class LocalFirstRouterTest {
    private static void expect(String query, LocalFirstRouter.Kind kind) {
        LocalFirstRouter.Decision result = LocalFirstRouter.route(query);
        if (result.kind != kind)
            throw new AssertionError(query + " routed to " + result.kind + ", expected " + kind);
    }
    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
    public static void main(String[] args) {
        expect("What time is it?", LocalFirstRouter.Kind.LOCAL_ANSWER);
        expect("What is time?", LocalFirstRouter.Kind.LOCAL_ANSWER);
        expect("What's the time now?", LocalFirstRouter.Kind.LOCAL_ANSWER);
        expect("tell me current time", LocalFirstRouter.Kind.LOCAL_ANSWER);
        expect("Hey Laundey, what is time?", LocalFirstRouter.Kind.LOCAL_ANSWER);
        expect("Londey what's the time?", LocalFirstRouter.Kind.LOCAL_ANSWER);
        expect("time bata", LocalFirstRouter.Kind.LOCAL_ANSWER);
        expect("abe time kya ho raha hai pooch raha hun main", LocalFirstRouter.Kind.LOCAL_ANSWER);
        expect("bhai abhi time kya hai batao", LocalFirstRouter.Kind.LOCAL_ANSWER);
        expect("time kya ho raha hai", LocalFirstRouter.Kind.LOCAL_ANSWER);
        expect("abhi kitne baje hain bhai", LocalFirstRouter.Kind.LOCAL_ANSWER);
        expect("what is the time complexity of binary search", LocalFirstRouter.Kind.CHATGPT);
        expect("What is time in physics?", LocalFirstRouter.Kind.CHATGPT);
        expect("explain time series databases", LocalFirstRouter.Kind.CHATGPT);

        expect("what is today's date?", LocalFirstRouter.Kind.LOCAL_ANSWER);
        expect("अभी कितने बजे हैं?", LocalFirstRouter.Kind.LOCAL_ANSWER);
        expect("focus status", LocalFirstRouter.Kind.LOCAL_ANSWER);
        expect("Hey Londe, open Spotify", LocalFirstRouter.Kind.LOCAL_ACTION);
        expect("open WhatsApp", LocalFirstRouter.Kind.LOCAL_ACTION);
        expect("start focus for 25 minutes", LocalFirstRouter.Kind.LOCAL_ACTION);
        expect("what is focus?", LocalFirstRouter.Kind.CHATGPT);
        expect("explain Kafka consumer groups", LocalFirstRouter.Kind.CHATGPT);
        expect("why does Spotify use caching?", LocalFirstRouter.Kind.CHATGPT);
        expect("how do I open WhatsApp?", LocalFirstRouter.Kind.CHATGPT);
        expect("weather in Almora today", LocalFirstRouter.Kind.CHATGPT);
        expect("what is the time complexity of merge sort?", LocalFirstRouter.Kind.CHATGPT);
        expect("open an app that might not exist", LocalFirstRouter.Kind.LOCAL_ACTION);

        LocalFirstRouter.Decision focus = LocalFirstRouter.route("focus status");
        String inactive = LocalFirstRouter.localAnswer(focus, 1000, 0L, Locale.ENGLISH);
        check(inactive.contains("No focus"), "inactive focus status");
        String active = LocalFirstRouter.localAnswer(focus, 1000, 120000L, Locale.ENGLISH);
        check(active.contains("2 minutes"), "active focus status");
        LocalFirstRouter.Decision hindi = LocalFirstRouter.route("अभी कितने बजे हैं?");
        check(LocalFirstRouter.localAnswer(hindi, 1000, 0, Locale.ENGLISH).contains("अभी"),
                "Hindi answer");
        System.out.println("LocalFirstRouterTest passed");
    }
}
