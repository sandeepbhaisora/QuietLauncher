package dev.quiet.launcher;

public final class LocalReplyPolicyTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(LocalReplyPolicy.usableLocalAnswer("Explain Kafka", "Kafka is a log-based messaging system."),
                "Local knowledge must be accepted");
        check(LocalReplyPolicy.usableLocalAnswer("Why is the sky blue?", "Rayleigh scattering."),
                "Short explanations work");
        check(!LocalReplyPolicy.usableLocalAnswer("What's the weather today?", "It is 27°C and sunny."),
                "Live weather must escalate after local attempt");
        check(!LocalReplyPolicy.usableLocalAnswer("Latest AI news", "OpenAI released X today."),
                "Latest news must escalate after local attempt");
        check(!LocalReplyPolicy.usableLocalAnswer("Explain Java", "Unable to reach OpenAI."),
                "Invented OpenAI connectivity errors must not be presented");
        check(!LocalReplyPolicy.usableLocalAnswer("Explain Java", "<think>internal scratchpad</think>"),
                "Reasoning traces must not surface");
        check(!LocalReplyPolicy.usableLocalAnswer("Explain Java", ""),
                "Empty results must escalate");
        check(!LocalReplyPolicy.usableLocalAnswer("Explain Java", "I cannot access live information."),
                "Explicit local refusal must escalate");
        check(LocalReplyPolicy.usableLocalAnswer("What is weather?", "Weather is the state of the atmosphere."),
                "Static educational questions must not be falsely treated as live");
        check(LocalReplyPolicy.offlineMessage().contains("didn't contact ChatGPT"),
                "Offline failure should truthfully disclose no cloud call");
        System.out.println("LocalReplyPolicyTest OK");
    }
}
