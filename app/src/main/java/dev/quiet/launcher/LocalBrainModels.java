package dev.quiet.launcher;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Manages a user-initiated, resumable 484 MB GGUF download. Never auto-downloads. */
final class LocalBrainModels {
    private static final String TAG = "QuietLocalBrain";
    private static final String PREF = "quiet_local_brain";
    static final String FILE = "Qwen3-0.6B-Q4_K_M.gguf";
    static final String SHA256 = "3479875d3e4c726f7a20b2181f5e1536aefe9925f284f9ae9997a39a7e0d8dc9";
    private static final String URL = "https://huggingface.co/gvij/qwen3-0.6b-gguf/resolve/main/qwen3-0.6b-q4_k_m.gguf";
    private static final long MIN_SIZE = 440_000_000L;
    private static final long MAX_SIZE = 550_000_000L;

    // Optional, more capable model. This is a separate download: never increase
    // APK size or silently replace the small offline model.
    static final String STRONG_FILE = "Qwen3-1.7B-Q4_K_M.gguf";
    private static final String STRONG_URL =
            "https://huggingface.co/Antigma/Qwen3-1.7B-GGUF/resolve/main/qwen3-1.7b-q4_k_m.gguf";
    private static final String STRONG_SHA256 =
            "a7f6720f68f4a4567ebf7e3257041dd0b72077b518efe56890aec3516b59b9de";
    private static final long STRONG_MIN_SIZE = 1_100_000_000L;
    private static final long STRONG_MAX_SIZE = 1_400_000_000L;

    static boolean strongSelected(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean("strong_selected", false);
    }

    static String modelLabel(Context context) {
        return strongSelected(context) ? "Qwen3 1.7B" : "Qwen3 0.6B";
    }

    static File smallModel(Context context) {
        return new File(context.getExternalFilesDir("models"), FILE);
    }

    static File strongModel(Context context) {
        return new File(context.getExternalFilesDir("models"), STRONG_FILE);
    }

    static File model(Context context) {
        return strongSelected(context) ? strongModel(context) : smallModel(context);
    }

    static boolean strongInstalled(Context context) {
        File f = strongModel(context);
        return f.isFile() && f.length() >= STRONG_MIN_SIZE && f.length() <= STRONG_MAX_SIZE
                && context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                        .getBoolean("strong_verified", false);
    }

    static boolean installed(Context context) {
        if (strongSelected(context)) return strongInstalled(context);
        File f = smallModel(context);
        return f.isFile() && f.length() >= MIN_SIZE && f.length() <= MAX_SIZE
                && context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                        .getBoolean("verified", false);
    }

    static boolean smallInstalled(Context context) {
        File f = smallModel(context);
        return f.isFile() && f.length() >= MIN_SIZE && f.length() <= MAX_SIZE
                && context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                        .getBoolean("verified", false);
    }

    static void selectModel(Context context, boolean strong) {
        if (strong && !strongInstalled(context))
            throw new IllegalStateException("Download and verify Qwen3 1.7B first");
        if (!strong && !smallInstalled(context))
            throw new IllegalStateException("Download and verify Qwen3 0.6B first");
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean("strong_selected", strong).apply();
    }

    static void startDownload(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (smallInstalled(context) || needsVerification(context)) return;
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) throw new IllegalStateException("Download Manager unavailable");
        long previous = prefs.getLong("download_id", -1L);
        if (previous >= 0) {
            try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(previous))) {
                if (cursor != null && cursor.moveToFirst()) {
                    int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (state == DownloadManager.STATUS_PENDING
                            || state == DownloadManager.STATUS_RUNNING
                            || state == DownloadManager.STATUS_PAUSED) return;
                }
            }
            manager.remove(previous);
        }
        File f = smallModel(context);
        // Android DownloadManager requires destination to not exist on enqueue.
        if (f.exists() && !f.delete()) throw new IllegalStateException("Delete older model first");
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(URL));
        req.setTitle("Quiet OS · Qwen3 local AI");
        req.setDescription("Offline GGUF model, approximately 484 MB. Wi-Fi only.");
        req.setAllowedOverMetered(false);
        req.setAllowedOverRoaming(false);
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalFilesDir(context, "models", FILE);
        long id = manager.enqueue(req);
        prefs.edit().putLong("download_id", id).putBoolean("verified", false).apply();
    }

    static String status(Context context) {
        if (smallInstalled(context)) return "Ready · Qwen3 0.6B installed";
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long id = prefs.getLong("download_id", -1);
        if (id < 0) return "Not installed · 484 MB Wi-Fi download";
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) return "Download Manager unavailable";
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) return "Download missing. Retry.";
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            if (status == DownloadManager.STATUS_SUCCESSFUL) return "Downloaded · verify model before using";
            if (status == DownloadManager.STATUS_FAILED) return "Download failed · retry";
            if (status == DownloadManager.STATUS_PAUSED) return "Paused · waiting for Wi-Fi";
            if (total > 0) return "Downloading · " + (bytes * 100 / total) + "%";
            return "Download queued · Wi-Fi required";
        } catch (Exception ex) {
            Log.e(TAG, "Download status query failed", ex);
            return "Download status unavailable";
        }
    }

    static boolean needsVerification(Context context) {
        File f = smallModel(context);
        if (smallInstalled(context) || !f.isFile() || f.length() < MIN_SIZE
                || f.length() > MAX_SIZE) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long downloadId = prefs.getLong("download_id", -1L);
        if (downloadId < 0) return true; // Existing manually restored model.
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) return false;
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(downloadId))) {
            if (cursor == null || !cursor.moveToFirst()) return false;
            return cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    == DownloadManager.STATUS_SUCCESSFUL;
        } catch (Exception ex) { return false; }
    }

    static boolean verify(Context context) throws Exception {
        File f = smallModel(context);
        if (!f.isFile() || f.length() < MIN_SIZE || f.length() > MAX_SIZE)
            throw new IllegalStateException("GGUF download incomplete; retry on Wi-Fi");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(f)) {
            byte[] block = new byte[1024 * 1024];
            int n;
            while ((n = input.read(block)) != -1) md.update(block, 0, n);
        }
        byte[] digest = md.digest();
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        boolean valid = SHA256.equals(hex.toString());
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean("verified", valid).apply();
        if (!valid) {
            f.delete();
            throw new IllegalStateException("Model checksum mismatch. Download discarded.");
        }
        return true;
    }

    static void delete(Context context) {
        if (LocalBrain.isLoaded()) throw new IllegalStateException("Close Quiet to unload the model first");
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long id = prefs.getLong("download_id", -1);
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (id >= 0 && dm != null) dm.remove(id);
        File f = smallModel(context);
        if (f.exists() && !f.delete()) throw new IllegalStateException("Could not remove model");
        prefs.edit().remove("download_id").putBoolean("verified", false).apply();
    }
    static String strongStatus(Context context) {
        if (strongInstalled(context))
            return strongSelected(context) ? "Active · Qwen3 1.7B (on-device)"
                    : "Installed · tap to activate Qwen3 1.7B";
        SharedPreferences prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        long id=prefs.getLong("strong_download_id",-1L);
        if (id<0) return "Not downloaded · ~1.28 GB over Wi-Fi";
        DownloadManager manager=(DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager==null) return "Download Manager unavailable";
        try (Cursor cursor=manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor==null || !cursor.moveToFirst()) return "Download missing · retry";
            int state=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (state==DownloadManager.STATUS_SUCCESSFUL)
                return "Downloaded · verify Qwen3 1.7B before use";
            if (state==DownloadManager.STATUS_FAILED) return "Download failed · retry";
            if (state==DownloadManager.STATUS_PAUSED) return "Paused · waiting for Wi-Fi";
            long total=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            long downloaded=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            return total>0 ? "Downloading · "+Math.min(100,downloaded*100/total)+"%"
                    : "Download queued · Wi-Fi required";
        } catch(Exception e) { return "Download state unavailable"; }
    }

    static void downloadStrong(Context context) {
        if (strongInstalled(context) || strongNeedsVerification(context)) return;
        DownloadManager manager=(DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager==null) throw new IllegalStateException("Android Download Manager unavailable");
        SharedPreferences prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        long previous=prefs.getLong("strong_download_id",-1L);
        if (previous>=0) {
            try(Cursor c=manager.query(new DownloadManager.Query().setFilterById(previous))) {
                if(c!=null && c.moveToFirst()) {
                    int st=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if(st==DownloadManager.STATUS_RUNNING || st==DownloadManager.STATUS_PAUSED
                            || st==DownloadManager.STATUS_PENDING) return;
                }
            }
            manager.remove(previous);
        }
        File f=strongModel(context);
        if(f.exists() && !f.delete())throw new IllegalStateException("Cannot replace older model");
        DownloadManager.Request req=new DownloadManager.Request(Uri.parse(STRONG_URL));
        req.setTitle("Quiet · Qwen3 1.7B stronger AI");
        req.setDescription("Optional local AI model · ~1.28 GB · Wi-Fi only");
        req.setAllowedOverMetered(false);
        req.setAllowedOverRoaming(false);
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalFilesDir(context,"models",STRONG_FILE);
        long id=manager.enqueue(req);
        prefs.edit().putLong("strong_download_id",id).putBoolean("strong_verified",false).apply();
    }

    static boolean strongNeedsVerification(Context context) {
        File f=strongModel(context);
        if(strongInstalled(context) || !f.isFile()
                || f.length()<STRONG_MIN_SIZE || f.length()>STRONG_MAX_SIZE) return false;
        long id=context.getSharedPreferences(PREF,Context.MODE_PRIVATE).getLong("strong_download_id",-1L);
        if(id<0) return true;
        DownloadManager manager=(DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE);
        if(manager==null)return false;
        try(Cursor c=manager.query(new DownloadManager.Query().setFilterById(id))) {
            return c!=null && c.moveToFirst() &&
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            ==DownloadManager.STATUS_SUCCESSFUL;
        } catch(Exception e) { return false; }
    }

    static void verifyStrong(Context context) throws Exception {
        File f=strongModel(context);
        if(!strongNeedsVerification(context))
            throw new IllegalStateException("The stronger model has not finished downloading");
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(FileInputStream in=new FileInputStream(f)) {
            byte[] buf=new byte[1024*1024];int n;
            while((n=in.read(buf))!=-1)md.update(buf,0,n);
        }
        StringBuilder hash=new StringBuilder();
        for(byte b:md.digest())hash.append(String.format(Locale.ROOT,"%02x",b&0xff));
        boolean verified=STRONG_SHA256.equals(hash.toString());
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit()
                .putBoolean("strong_verified",verified).apply();
        if(!verified) {
            f.delete();
            throw new IllegalStateException("Stronger Qwen model checksum mismatch; deleted");
        }
    }

    private LocalBrainModels() {}
}
