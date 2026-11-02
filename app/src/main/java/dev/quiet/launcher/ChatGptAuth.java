package dev.quiet.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Official open-source Sign in with ChatGPT OAuth flow (public client + PKCE).
 * No password, OpenAI API key or backend server is used here.
 * All networking, including the loopback callback listener, runs off the UI thread.
 */
final class ChatGptAuth {
    interface Callback { void finished(boolean success, String message); }
    private static final String AUTH_URL = "https://auth.openai.com/api/accounts/authorize";
    private static final String TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token";
    private static final String JWKS_URL = "https://auth.openai.com/.well-known/jwks.json";
    private static final String REVOCATION_URL = "https://auth.openai.com/api/accounts/oauth/revoke";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final String PREF = "quiet_chatgpt_session_v1";
    private static final String KEY = "quiet_chatgpt_keystore_v1";
    private static final String SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private static final SecureRandom RNG = new SecureRandom();

    private ChatGptAuth() {}

    static boolean isConnected(Context context) {
        try { return readSession(context) != null; }
        catch (Exception ex) { return false; }
    }
    static String account(Context context) {
        try {
            JSONObject j = readSession(context);
            if (j == null) return "Not connected";
            String email = j.optString("email", "");
            return email.isEmpty() ? "ChatGPT connected" : email;
        } catch (Exception ex) { return "Saved connection could not be opened"; }
    }
    static boolean canUsePlan(Context context) {
        try {
            JSONObject j = readSession(context);
            return j != null && hasScope(j.optString("scope", ""), "chatgpt.tokens.use.direct");
        } catch (Exception ex) { return false; }
    }

    static void signIn(Activity activity, ExecutorService executor, Handler ui, Callback callback) {
        executor.execute(() -> {
            try (ServerSocket listener = new ServerSocket(
                    0, 1, InetAddress.getByName("127.0.0.1"))) {
                listener.setSoTimeout(180000);
                String redirect = "http://127.0.0.1:" + listener.getLocalPort() + "/auth/callback";
                String state = randomToken(32), nonce = randomToken(32), verifier = randomToken(48);
                String challenge = url64(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
                JSONObject old = readSession(activity);
                SharedPreferences prefs = activity.getSharedPreferences(PREF, Context.MODE_PRIVATE);
                // A completed browser registration may be followed by a failed token exchange.
                // Save its issued client ID independently so the next attempt reuses it.
                String issued = old == null ? prefs.getString("pending_client_id", "")
                        : old.optString("client_id", "");
                boolean newClient = issued.isEmpty();
                String clientId = newClient ? "dynamic_agent_client" : issued;
                String host = prefs.getString("host_id", "");
                if (host.isEmpty()) {
                    host = "urn:uuid:" + UUID.randomUUID();
                    if (!prefs.edit().putString("host_id", host).commit())
                        throw new IllegalStateException("Could not save host ID");
                }
                StringBuilder url = new StringBuilder(AUTH_URL)
                    .append("?response_type=code")
                    .append("&client_id=").append(enc(clientId))
                    .append("&redirect_uri=").append(enc(redirect))
                    .append("&scope=").append(enc(SCOPE))
                    .append("&resource=").append(enc(RESOURCE))
                    .append("&state=").append(enc(state))
                    .append("&nonce=").append(enc(nonce))
                    .append("&code_challenge_method=S256")
                    .append("&code_challenge=").append(enc(challenge))
                    .append("&ext_agent_host_id=").append(enc(host));
                if (newClient) url.append("&agent_name_hint=").append(enc("Quiet Launcher"));
                else if (old != null) {
                    if (!old.optString("id_token", "").isEmpty())
                        url.append("&id_token_hint=").append(enc(old.optString("id_token")));
                    if (!old.optString("email", "").isEmpty())
                        url.append("&login_hint=").append(enc(old.optString("email")));
                }

                ui.post(() -> {
                    try {
                        Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()));
                        browser.addCategory(Intent.CATEGORY_BROWSABLE);
                        activity.startActivity(browser);
                    } catch (Exception ex) {
                        // Listener timeout produces a controlled error if browser is unavailable.
                        callback.finished(false, "No browser available for ChatGPT sign-in.");
                    }
                });
                Map<String,String> parameters = waitForCallback(listener);
                if (!state.equals(parameters.get("state")))
                    throw new SecurityException("Sign-in state did not match");
                if (parameters.containsKey("error"))
                    throw new IllegalStateException("ChatGPT sign-in was canceled or denied");
                String code = parameters.get("code");
                if (code == null || code.isEmpty())
                    throw new IllegalStateException("No authorization code received");
                String callbackClient = parameters.get("client_id");
                if (newClient) {
                    if (callbackClient == null || !callbackClient.matches("oaiapp_[A-Za-z0-9_-]{8,150}"))
                        throw new SecurityException("OpenAI did not return a registered client ID");
                    issued = callbackClient;
                    // Store only the public registration ID, never the authorization code.
                    if (!prefs.edit().putString("pending_client_id", issued).commit())
                        throw new IllegalStateException("Could not save ChatGPT registration");
                } else if (callbackClient != null && !callbackClient.equals(issued))
                    throw new SecurityException("OAuth client ID changed");
                JSONObject tokens = formPost(TOKEN_URL,
                    "grant_type", "authorization_code",
                    "client_id", issued, "code", code,
                    "code_verifier", verifier, "redirect_uri", redirect,
                    "resource", RESOURCE);
                String idToken = tokens.getString("id_token");
                JSONObject identity = validateIdToken(idToken, issued, nonce);
                if (old != null && !old.optString("subject").equals(identity.getString("sub")))
                    throw new SecurityException("Sign-in account changed unexpectedly");
                String granted = tokens.optString("scope", "");
                JSONObject session = new JSONObject();
                session.put("client_id", issued);
                session.put("subject", identity.getString("sub"));
                session.put("email", identity.optString("email", ""));
                session.put("id_token", idToken);
                session.put("access_token", tokens.getString("access_token"));
                session.put("refresh_token", tokens.optString("refresh_token", ""));
                session.put("scope", granted);
                session.put("expires_at", System.currentTimeMillis()
                    + tokens.optLong("expires_in", 3600) * 1000L);
                saveSession(activity, session);
                prefs.edit().remove("pending_client_id").apply();
                boolean usage = hasScope(granted, "chatgpt.tokens.use.direct");
                finish(ui, callback, true, usage
                    ? "ChatGPT connected. Model usage is authorized."
                    : "ChatGPT signed in, but AI usage permission was not granted.");
            } catch (Exception ex) {
                finish(ui, callback, false, "Sign-in failed: " + friendlyError(ex));
            }
        });
    }

    private static Map<String,String> waitForCallback(ServerSocket listener) throws Exception {
        // A random port on 127.0.0.1, only one callback and one use of the code.
        try (Socket socket = listener.accept()) {
            socket.setSoTimeout(6000);
            if (!socket.getInetAddress().isLoopbackAddress())
                throw new SecurityException("Non-loopback connection");
            BufferedReader input = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            String line = input.readLine();
            if (line == null || line.length() > 8192 || !line.startsWith("GET "))
                throw new SecurityException("Invalid callback");
            String[] parts = line.split(" ");
            if (parts.length < 3 || !parts[1].startsWith("/auth/callback?"))
                throw new SecurityException("Unexpected callback path");
            Map<String,String> values = new LinkedHashMap<>();
            for (String pair : parts[1].substring(parts[1].indexOf('?')+1).split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2)
                    values.put(URLDecoder.decode(kv[0], "UTF-8"),
                               URLDecoder.decode(kv[1], "UTF-8"));
            }
            byte[] body = ("<html><body style=\"font-family:sans-serif;padding:40px\">"
                    + "<h2>Authorization received</h2>"
                    + "<p>Return to Quiet Launcher to check whether your connection finished.</p>"
                    + "<p>This browser page does not confirm that the app is connected.</p>"
                    + "</body></html>").getBytes(StandardCharsets.UTF_8);
            byte[] headers = ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8"
                    + "\r\nCache-Control: no-store\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            OutputStream output = socket.getOutputStream();
            output.write(headers);
            output.write(body);
            output.flush();
            return values;
        }
    }

    private static JSONObject validateIdToken(String jwt, String clientId, String nonce)
            throws Exception {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) throw new SecurityException("Invalid ID token");
        JSONObject header = new JSONObject(new String(
                Base64.decode(parts[0], Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8));
        if (!"RS256".equals(header.optString("alg")))
            throw new SecurityException("Unexpected token signing algorithm");
        String kid = header.getString("kid");
        JSONObject jwks = getJson(JWKS_URL, null);
        JSONArray keys = jwks.getJSONArray("keys");
        boolean signatureValid = false;
        for (int i=0; i<keys.length(); i++) {
            JSONObject key = keys.getJSONObject(i);
            if (!kid.equals(key.optString("kid")) || !"RSA".equals(key.optString("kty")))
                continue;
            BigInteger n = new BigInteger(1, Base64.decode(
                    key.getString("n"), Base64.URL_SAFE | Base64.NO_WRAP));
            BigInteger e = new BigInteger(1, Base64.decode(
                    key.getString("e"), Base64.URL_SAFE | Base64.NO_WRAP));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(n, e)));
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            signatureValid = verifier.verify(Base64.decode(
                    parts[2], Base64.URL_SAFE | Base64.NO_WRAP));
            break;
        }
        if (!signatureValid) throw new SecurityException("ID token signature failed");
        JSONObject identity = new JSONObject(new String(
                Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8));
        long now = System.currentTimeMillis()/1000;
        if (!"https://auth.openai.com".equals(identity.optString("iss")))
            throw new SecurityException("Unexpected token issuer");
        Object aud = identity.get("aud");
        boolean audience = aud instanceof String && clientId.equals(aud);
        if (aud instanceof JSONArray) {
            JSONArray arr = (JSONArray)aud;
            for (int i=0; i<arr.length(); i++) audience |= clientId.equals(arr.optString(i));
        }
        if (!audience || !nonce.equals(identity.optString("nonce"))
                || identity.optLong("exp", 0) < now-60
                || identity.optLong("iat", Long.MAX_VALUE) > now+300
                || identity.optString("sub").isEmpty())
            throw new SecurityException("ID token verification failed");
        return identity;
    }

    static synchronized String accessToken(Context context) throws Exception {
        JSONObject session = readSession(context);
        if (session == null || !hasScope(session.optString("scope"), "chatgpt.tokens.use.direct"))
            throw new IllegalStateException("Connect ChatGPT and allow plan usage first");
        if (session.optLong("expires_at") > System.currentTimeMillis()+90000)
            return session.getString("access_token");
        String refresh = session.optString("refresh_token");
        if (refresh.isEmpty()) throw new IllegalStateException("Please reconnect ChatGPT");
        JSONObject result = formPost(TOKEN_URL,
                "grant_type","refresh_token","client_id",session.getString("client_id"),
                "refresh_token",refresh,"resource",RESOURCE);
        session.put("access_token", result.getString("access_token"));
        String replacement = result.optString("refresh_token", "");
        if (replacement.isEmpty()) throw new IllegalStateException("Refresh token missing");
        session.put("refresh_token",replacement);
        if (result.has("scope")) session.put("scope",result.getString("scope"));
        session.put("expires_at",System.currentTimeMillis()
                +result.optLong("expires_in",3600)*1000L);
        if (!hasScope(session.optString("scope"),"chatgpt.tokens.use.direct"))
            throw new IllegalStateException("Plan usage no longer granted");
        saveSession(context,session);
        return session.getString("access_token");
    }

    static void disconnect(Context context) {
        // Local disconnect. Account holders may also revoke access in ChatGPT settings.
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().remove("encrypted").remove("iv")
                .remove("pending_client_id").apply();
    }

    private static boolean hasScope(String grants, String scope) {
        for (String s : grants.split(" ")) if (s.equals(scope)) return true;
        return false;
    }

    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RNG.nextBytes(buffer);
        return url64(buffer);
    }
    private static String url64(byte[] value) {
        return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }
    private static byte[] sha256(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }
    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }
    private static JSONObject formPost(String endpoint, String... fields) throws Exception {
        // Browser approval is not the end of sign-in: a second HTTPS request
        // exchanges the short-lived authorization code. A transient DNS lookup
        // failure should not immediately discard an otherwise valid attempt.
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return formPostOnce(endpoint, fields);
            } catch (java.net.UnknownHostException ex) {
                if (attempt == 2) throw ex;
                Thread.sleep(attempt == 0 ? 500L : 1300L);
            }
        }
        throw new IllegalStateException("Token exchange failed");
    }

    private static JSONObject formPostOnce(String endpoint, String... fields) throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i=0; i<fields.length; i+=2) {
            if (i>0) body.append('&');
            body.append(enc(fields[i])).append('=').append(enc(fields[i+1]));
        }
        HttpURLConnection c = (HttpURLConnection)new URL(endpoint).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode()!=200) throw new IllegalStateException(
                    "OpenAI authorization rejected the request (HTTP " + c.getResponseCode()+")");
            return jsonFromInput(c);
        } finally { c.disconnect(); }
    }

    static JSONObject getJson(String endpoint, String bearer) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return getJsonOnce(endpoint, bearer);
            } catch (java.net.UnknownHostException ex) {
                if (attempt == 2) throw ex;
                Thread.sleep(attempt == 0 ? 500L : 1300L);
            }
        }
        throw new IllegalStateException("Network request failed");
    }

    private static JSONObject getJsonOnce(String endpoint, String bearer) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(endpoint).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        if (bearer != null) c.setRequestProperty("Authorization","Bearer "+bearer);
        try {
            if(c.getResponseCode()!=200)throw new IllegalStateException(
                    "OpenAI request failed (HTTP " + c.getResponseCode()+")");
            return jsonFromInput(c);
        } finally {c.disconnect();}
    }
    private static JSONObject jsonFromInput(HttpURLConnection c) throws Exception {
        try (java.io.InputStream input = c.getInputStream();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] b=new byte[4096]; int n;
            while((n=input.read(b))!=-1){
                if(out.size()+n>262144)throw new IllegalStateException("Response too large");
                out.write(b,0,n);
            }
            return new JSONObject(out.toString("UTF-8"));
        }
    }
    private static synchronized void saveSession(Context context, JSONObject session)
            throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted=cipher.doFinal(session.toString().getBytes(StandardCharsets.UTF_8));
        boolean ok=context.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit()
            .putString("encrypted",Base64.encodeToString(encrypted,Base64.NO_WRAP))
            .putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
            .commit();
        if(!ok)throw new IllegalStateException("Could not save session");
    }
    private static synchronized JSONObject readSession(Context context) throws Exception {
        SharedPreferences pref=context.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        String encrypted=pref.getString("encrypted","");
        String iv=pref.getString("iv","");
        if(encrypted.isEmpty()||iv.isEmpty())return null;
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),
                new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
        byte[] original=cipher.doFinal(Base64.decode(encrypted,Base64.NO_WRAP));
        return new JSONObject(new String(original,StandardCharsets.UTF_8));
    }
    private static SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry entry=ks.getEntry(KEY,null);
        if(entry instanceof KeyStore.SecretKeyEntry)
            return ((KeyStore.SecretKeyEntry)entry).getSecretKey();
        KeyGenerator generator=KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY,
                KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }
    private static String friendlyError(Exception e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.UnknownHostException)
                return "DNS lookup failed for auth.openai.com. Browser approval may have succeeded, "
                    + "but Quiet could not exchange the code. Try mobile data instead of Wi-Fi, "
                    + "or check VPN/Private DNS settings. Use Check in Browser to test.";
            if (cause instanceof java.net.SocketTimeoutException)
                return "Network or browser callback timed out. Check your connection and try again.";
            if (cause instanceof java.net.ConnectException)
                return "Could not establish a connection to OpenAI authentication. "
                    + "Try a different network, then sign in again.";
        }
        if (e instanceof SecurityException)
            return "A security validation failed. Your connection was not saved.";
        return e.getMessage() == null ? "Unable to finish sign-in." : e.getMessage();
    }
    private static void finish(Handler ui, Callback cb, boolean good, String message) {
        ui.post(() -> cb.finished(good,message));
    }
}
