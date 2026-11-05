package dev.quiet.launcher;

import java.util.Locale;

/**
 * Pure Qwen output normalization and retry decision. No device or network access.
 * Some Qwen3 chat templates supply the opening <think> token in the prompt;
 * generated text then begins with </think> even in non-thinking mode.
 */
final class LocalModelOutput {
    static String clean(String raw) {
        if (raw == null) return "";
        String body = raw.trim();
        // Drop complete private reasoning blocks before any content is shown.
        body = body.replaceAll("(?is)<think>.*?</think>", "").trim();
        // The opening tag can already be in the chat template, leaving only a close tag.
        int lastClose = body.toLowerCase(Locale.ROOT).lastIndexOf("</think>");
        if (lastClose >= 0) body = body.substring(lastClose + "</think>".length()).trim();
        // An unfinished reasoning segment is NEVER a user-visible answer.
        if (body.toLowerCase(Locale.ROOT).contains("<think>")) return "";
        // Rare model outputs include chat delimiters despite templated completion.
        body = body.replaceFirst("(?is)^<\\|im_start\\|>assistant\\s*", "")
                .replaceFirst("(?is)<\\|im_end\\|>\\s*$", "").trim();
        return body;
    }

    static boolean shouldRetry(String question, String normalized) {
        // A news/weather/live question cannot be satisfied by retrying an offline model.
        if (LocalReplyPolicy.requiresLiveInformation(question)) return false;
        if (LocalReplyPolicy.usableLocalAnswer(question, normalized)) return false;
        // Legitimate typed tool calls have a separate, validated execution path.
        return LocalToolPolicy.fromModel(question, normalized).kind == LocalToolPolicy.Kind.NONE;
    }

    private LocalModelOutput() {}
}
