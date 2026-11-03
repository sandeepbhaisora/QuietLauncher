package dev.quiet.launcher;

public final class SpokenOnlineConsentTest {
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
    private static void reply(String spoken, SpokenOnlineConsent.Reply expected) {
        check(SpokenOnlineConsent.interpret(spoken) == expected, "Voice: "+spoken);
    }
    public static void main(String[] args) {
        reply("yes", SpokenOnlineConsent.Reply.YES);
        reply("Yes use GPT", SpokenOnlineConsent.Reply.YES);
        reply("haan kar lo", SpokenOnlineConsent.Reply.YES);
        reply("हाँ", SpokenOnlineConsent.Reply.YES);
        reply("जी हाँ", SpokenOnlineConsent.Reply.YES);
        reply("go ahead", SpokenOnlineConsent.Reply.YES);
        reply("no", SpokenOnlineConsent.Reply.NO);
        reply("nahi", SpokenOnlineConsent.Reply.NO);
        reply("रहने दो", SpokenOnlineConsent.Reply.NO);
        reply("not now", SpokenOnlineConsent.Reply.NO);
        reply("yes but don't", SpokenOnlineConsent.Reply.NO);
        reply("yes send a message", SpokenOnlineConsent.Reply.UNCLEAR);
        reply("what's the weather", SpokenOnlineConsent.Reply.UNCLEAR);
        reply("", SpokenOnlineConsent.Reply.UNCLEAR);
        SpokenOnlineConsent c = new SpokenOnlineConsent();
        c.begin("What are Kafka partitions?");
        check(c.approve(1_000L) == null, "No approval before voice prompt finishes");
        c.begin("What is a race condition?");
        c.markListening(1_000L);
        check(c.approve(1_000L + SpokenOnlineConsent.RESPONSE_WINDOW_MS + 1) == null,
                "Expired approval must not send");
        c.begin("Why does my app crash?");
        c.markListening(1_000L);
        check("Why does my app crash?".equals(c.approve(1_001L)), "Explicit approval");
        check(!c.pending(), "One-shot approval");
        SpokenOnlineConsent.stageForVoiceConsole("What is Kotlin?", 4_000L);
        check("What is Kotlin?".equals(SpokenOnlineConsent.consumeForVoiceConsole(4_001L)),
                "Handoff");
        check(SpokenOnlineConsent.consumeForVoiceConsole(4_002L) == null, "One-shot handoff");
        SpokenOnlineConsent.stageForVoiceConsole("Sensitive question", 8_000L);
        check(SpokenOnlineConsent.consumeForVoiceConsole(24_000L) == null, "Handoff TTL");
        System.out.println("SpokenOnlineConsentTest passed");
    }
}
