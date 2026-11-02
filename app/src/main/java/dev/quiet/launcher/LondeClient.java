package dev.quiet.launcher;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Conversational ChatGPT-plan client. Does not execute Android actions.
 * Caller runs requests on a worker; history is explicitly provided in input,
 * since subscription-sharing inference requires store=false and stream=true.
 */
final class LondeClient {
    interface Progress { void onText(String partial); }

    static final class Turn {
        final String role, text;
        Turn(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }
    static final class Answer {
        final String text, model;
        Answer(String text, String model) {
            this.text = text;
            this.model = model;
        }
    }

    static Answer ask(Context context, List<Turn> history, String preferredModel,
                      Progress progress) throws Exception {
        String accessToken = ChatGptAuth.accessToken(context);
        String model = preferredModel == null || preferredModel.isEmpty()
                ? ChatGptPlanner.availableModel(accessToken) : preferredModel;

        JSONArray input = new JSONArray();
        int start = Math.max(0, history.size() - 12);
        for (int i = start; i < history.size(); i++) {
            Turn turn = history.get(i);
            if (!"assistant".equals(turn.role) && !"user".equals(turn.role)) continue;
            if (turn.text == null || turn.text.isEmpty()) continue;
            input.put(new JSONObject()
                    .put("role", turn.role)
                    .put("content", turn.text.length() > 3000 ?
                            turn.text.substring(0, 3000) : turn.text));
        }
        if (input.length() == 0) throw new IllegalArgumentException("No question to answer");

        String today = new SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH).format(new Date());
        String instructions = "You are Londe, a helpful conversational assistant inside Quiet, " +
            "an Android home launcher. Be natural, clear and concise. Reply in the language " +
            "the user uses, including Hindi/Hinglish. Answer questions normally, not as JSON " +
            "or action plans. The current phone date is " + today + ". " +
            "You cannot inspect the phone's calendar, current screen, apps, contacts, " +
            "notifications, precise location, files, ChatGPT chat history or other accounts. " +
            "You have no live web search in this version. Do not invent private or current " +
            "facts; briefly state limitations when relevant. You can explain how to perform " +
            "actions, but do not claim to have performed them. Quiet has a separate Do button " +
            "for device actions requiring confirmation. Keep responses suitable for phone reading.";

        JSONObject payload = new JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("store", false)
            .put("stream", true)
            .put("input", input);

        HttpURLConnection http = (HttpURLConnection)
            new URL("https://api.openai.com/v1/responses").openConnection();
        http.setConnectTimeout(12000);
        http.setReadTimeout(60000);
        http.setInstanceFollowRedirects(false);
        http.setRequestMethod("POST");
        http.setDoOutput(true);
        http.setRequestProperty("Authorization", "Bearer " + accessToken);
        http.setRequestProperty("Content-Type", "application/json");
        http.setRequestProperty("Accept", "text/event-stream");
        try {
            try (OutputStream out = http.getOutputStream()) {
                out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = http.getResponseCode();
            if (status != 200) throw new IllegalStateException(httpError(http, status));

            StringBuilder answer = new StringBuilder();
            long notified = 0;
            boolean completed = false;
            try (InputStream stream = http.getInputStream();
                 BufferedReader reader = new BufferedReader(
                     new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted())
                        throw new InterruptedException("Assistant request canceled");
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6);
                    if ("[DONE]".equals(data)) break;
                    JSONObject event = new JSONObject(data);
                    String type = event.optString("type", "");
                    if ("response.output_text.delta".equals(type)) {
                        answer.append(event.optString("delta", ""));
                        if (answer.length() > 24000)
                            throw new IllegalStateException("Answer is too long");
                        long now = SystemClock.elapsedRealtime();
                        if (now - notified >= 150) {
                            progress.onText(answer.toString());
                            notified = now;
                        }
                    } else if ("response.completed".equals(type)) {
                        completed = true;
                    } else if ("response.failed".equals(type)
                            || "response.incomplete".equals(type)
                            || "error".equals(type)) {
                        JSONObject response = event.optJSONObject("response");
                        JSONObject err = response == null ? event.optJSONObject("error")
                            : response.optJSONObject("error");
                        String code = err == null ? "" : err.optString("code", "");
                        throw new IllegalStateException("Inference " + type +
                                safeCode(code));
                    }
                }
            }
            if (!completed || answer.length() == 0)
                throw new IllegalStateException("Model stream ended without a completed answer");
            progress.onText(answer.toString());
            return new Answer(answer.toString().trim(), model);
        } finally {
            http.disconnect();
        }
    }

    private static String httpError(HttpURLConnection http, int status) {
        String code = "";
        try (InputStream input = http.getErrorStream()) {
            if (input != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = input.read(buf)) > 0 && out.size() < 8192)
                    out.write(buf, 0, Math.min(n, 8192 - out.size()));
                JSONObject json = new JSONObject(out.toString("UTF-8"));
                JSONObject error = json.optJSONObject("error");
                if (error != null) code = error.optString("code", "");
            }
        } catch (Exception ignored) { }
        if (status == 401) return "Session expired or permission revoked. Reconnect ChatGPT in Quiet Settings.";
        if (status == 429) return "ChatGPT usage limit reached. Try again later.";
        if (status == 403) return "This model request was not permitted for this account.";
        return "ChatGPT request HTTP " + status + safeCode(code);
    }

    private static String safeCode(String candidate) {
        return candidate != null && candidate.matches("[A-Za-z0-9_-]{1,80}")
            ? " (" + candidate + ")" : "";
    }
}
