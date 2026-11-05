package dev.quiet.launcher;

public final class LocalModelOutputTest {
    private static void eq(String actual, String expected) {
        if (!expected.equals(actual)) throw new AssertionError("Expected '" + expected + "' but got '" + actual + "'");
    }
    private static void check(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        eq(LocalModelOutput.clean("<think>Let me reason privately.</think>Kafka is an event log."),
                "Kafka is an event log.");
        eq(LocalModelOutput.clean("</think>Kafka is an event log."),
                "Kafka is an event log.");
        eq(LocalModelOutput.clean("<think>unfinished reasoning"), "");
        eq(LocalModelOutput.clean("<think>scratchpad</think>"), "");
        eq(LocalModelOutput.clean(" \\n</think> 42  "), "42");
        eq(LocalModelOutput.clean("Java blocks use braces { and }."), "Java blocks use braces { and }.");
        eq(LocalModelOutput.clean("<|im_start|>assistant\\nUseful answer.<|im_end|>"), "Useful answer.");
        check(!LocalModelOutput.shouldRetry("Explain a Java HashMap",
                        "HashMap stores keys and values using a hash table."),
                "Useful knowledge must not cause expensive retries");
        check(LocalModelOutput.shouldRetry("Explain a Java HashMap", ""),
                "Empty Qwen generation must be retried once");
        check(LocalModelOutput.shouldRetry("Explain a Java HashMap",
                        "Hello! How can I assist you today?"),
                "Canned greeting should trigger one real retry");
        check(LocalModelOutput.shouldRetry("Explain a Java HashMap",
                        "I couldn't answer that offline."),
                "Generic offline refusal should not be treated as success");
        check(!LocalModelOutput.shouldRetry("What's the weather today?", "27 degrees"),
                "Live data is not solved with a second offline guess");
        check(!LocalModelOutput.shouldRetry("What is my battery level?",
                        "{\\"tool\\":\\"get_battery\\"}"),
                "Authorized typed device tools must not be retried");
        System.out.println("LocalModelOutputTest OK");
    }
}
