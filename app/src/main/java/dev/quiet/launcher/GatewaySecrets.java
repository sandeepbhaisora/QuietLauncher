package dev.quiet.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Gateway access token encrypted with Android Keystore; not an OpenAI key. */
final class GatewaySecrets {
    private static final String ALIAS = "quiet_gateway_token_v1";
    private static final String PREF = "quiet_gateway_auth_v1";
    private GatewaySecrets() {}
    static void save(Context context, String token) throws Exception {
        if (token == null || token.trim().isEmpty()) { clear(context); return; }
        if (token.length() > 400) throw new IllegalArgumentException("Token is too long");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] data = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        boolean saved = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString("data", Base64.encodeToString(data, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).commit();
        if (!saved) throw new IllegalStateException("Failed to save token");
    }
    static String read(Context context) throws Exception {
        SharedPreferences pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String data = pref.getString("data", "");
        String iv = pref.getString("iv", "");
        if (data.isEmpty() || iv.isEmpty()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                Base64.decode(iv, Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)),
                StandardCharsets.UTF_8);
    }
    static boolean configured(Context context) {
        return !context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString("data", "").isEmpty();
    }
    static void clear(Context context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
    }
    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry)
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }
}
