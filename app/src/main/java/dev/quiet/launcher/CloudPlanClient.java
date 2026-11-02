package dev.quiet.launcher;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Single HTTPS call, only the explicitly submitted text, no redirects. */
final class CloudPlanClient {
    private CloudPlanClient() {}

    static boolean validEndpoint(String raw) {
        try {
            URI url = new URI(raw);
            return "https".equalsIgnoreCase(url.getScheme()) && url.getHost() != null
                    && url.getUserInfo() == null && url.getFragment() == null
                    && (url.getPort() == -1 || url.getPort() == 443)
                    && "/plan".equals(url.getPath()) && url.getQuery() == null;
        } catch (Exception e) { return false; }
    }

    static List<IntentRouter.Step> request(String endpoint, String token, String command)
            throws Exception {
        if (!validEndpoint(endpoint)) throw new IllegalArgumentException("Use an HTTPS /plan URL");
        if (command == null || command.trim().isEmpty() || command.length() > 220)
            throw new IllegalArgumentException("Command must be 1-220 characters");
        if (token == null || token.isEmpty()) throw new IllegalArgumentException("Missing gateway token");
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            byte[] body = new JSONObject().put("text", command).toString()
                    .getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            int code = connection.getResponseCode();
            if (code != 200) throw new IllegalStateException("Gateway HTTP " + code);
            String json;
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (out.size() + n > 16384) throw new IllegalStateException("Response too large");
                    out.write(buffer, 0, n);
                }
                json = out.toString("UTF-8");
            }
            JSONArray array = new JSONObject(json).getJSONArray("steps");
            if (array.length() < 1 || array.length() > 3)
                throw new IllegalStateException("Invalid step count");
            List<IntentRouter.Step> result = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject s = array.getJSONObject(i);
                result.add(new IntentRouter.Step(
                        IntentRouter.Action.valueOf(s.getString("action")),
                        s.getInt("minutes"), s.getString("equipment"), s.getString("appName")));
            }
            return result;
        } finally { connection.disconnect(); }
    }
}
