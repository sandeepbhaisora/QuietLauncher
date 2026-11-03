package dev.quiet.launcher;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Opt-in, independently verified GGUF model selection. Never downloads automatically. */
final class LocalBrainModelCatalog {
    static final String SMALL="qwen3_06", LARGE="qwen3_17";
    private static final String CONFIG="quiet_model_selection_v2";
    private static final String BIG_FILE="Qwen3-1.7B-Q4_K_M.gguf";
    private static final String BIG_SHA="a7f6720f68f4a4567ebf7e3257041dd0b72077b518efe56890aec3516b59b9de";
    // SHA256 published in the upstream model's Hugging Face file detail.
    private static final String BIG_URL="https://huggingface.co/Antigma/Qwen3-1.7B-GGUF/resolve/main/qwen3-1.7b-q4_k_m.gguf";
    private static final long BIG_MIN=1_190_000_000L, BIG_MAX=1_350_000_000L;

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(CONFIG,Context.MODE_PRIVATE);
    }
    static String selected(Context c) { return prefs(c).getString("model",SMALL); }
    static String label(Context c) {
        return LARGE.equals(selected(c))?"QWEN3 1.7B · Q4_K_M":"QWEN3 0.6B · Q4_K_M";
    }
    static void select(Context c,String modelId) {
        if(!SMALL.equals(modelId)&&!LARGE.equals(modelId))
            throw new IllegalArgumentException("Unknown offline model");
        if(!selected(c).equals(modelId)&&LocalBrain.isLoaded())
            throw new IllegalStateException("Close and reopen Quiet before switching loaded models.");
        prefs(c).edit().putString("model",modelId).apply();
    }
    private static File bigFile(Context c) { return new File(c.getExternalFilesDir("models"),BIG_FILE); }
    static File model(Context c) { return LARGE.equals(selected(c))?bigFile(c):LocalBrainModels.model(c); }
    static boolean installed(Context c) {
        if(!LARGE.equals(selected(c)))return LocalBrainModels.installed(c);
        File f=bigFile(c);
        return f.isFile()&&f.length()>=BIG_MIN&&f.length()<=BIG_MAX&&prefs(c).getBoolean("verified",false);
    }
    static String status(Context c) {
        if(!LARGE.equals(selected(c)))return LocalBrainModels.status(c);
        if(installed(c))return "Ready · Qwen3 1.7B (4-bit)";
        long id=prefs(c).getLong("download_id",-1);
        if(id<0)return "Not installed · ~1.28 GB Wi-Fi download";
        DownloadManager dm=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
        if(dm==null)return "Download Manager unavailable";
        try(Cursor cursor=dm.query(new DownloadManager.Query().setFilterById(id))){
            if(cursor==null||!cursor.moveToFirst())return "Download missing · retry";
            int state=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if(state==DownloadManager.STATUS_SUCCESSFUL)return "Downloaded · SHA-256 verification required";
            if(state==DownloadManager.STATUS_FAILED)return "Download failed · retry";
            if(state==DownloadManager.STATUS_PAUSED)return "Paused · waiting for Wi-Fi";
            long bytes=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            return total>0?"Downloading · "+(bytes*100/total)+"%":"Download queued · Wi-Fi only";
        }catch(Exception e){return "Download status unavailable";}
    }
    static void startDownload(Context c) {
        if(!LARGE.equals(selected(c))){LocalBrainModels.startDownload(c);return;}
        if(installed(c)||needsVerification(c))return;
        DownloadManager dm=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
        if(dm==null)throw new IllegalStateException("Download Manager unavailable");
        long old=prefs(c).getLong("download_id",-1);
        if(old>=0){
            try(Cursor cursor=dm.query(new DownloadManager.Query().setFilterById(old))){
                if(cursor!=null&&cursor.moveToFirst()){
                    int state=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if(state==DownloadManager.STATUS_PENDING||state==DownloadManager.STATUS_RUNNING
                            ||state==DownloadManager.STATUS_PAUSED)return;
                }
            }
            dm.remove(old);
        }
        File file=bigFile(c);
        if(file.exists()&&!file.delete())throw new IllegalStateException("Cannot replace existing model");
        DownloadManager.Request req=new DownloadManager.Request(Uri.parse(BIG_URL));
        req.setTitle("Quiet · Qwen3 1.7B");
        req.setDescription("Local 4-bit model · ~1.28 GB · Wi-Fi only");
        req.setAllowedOverMetered(false);
        req.setAllowedOverRoaming(false);
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalFilesDir(c,"models",BIG_FILE);
        long id=dm.enqueue(req);
        prefs(c).edit().putLong("download_id",id).putBoolean("verified",false).apply();
    }
    static boolean needsVerification(Context c){
        if(!LARGE.equals(selected(c)))return LocalBrainModels.needsVerification(c);
        File f=bigFile(c);
        if(installed(c)||!f.isFile()||f.length()<BIG_MIN||f.length()>BIG_MAX)return false;
        long id=prefs(c).getLong("download_id",-1);
        if(id<0)return true;
        DownloadManager dm=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
        if(dm==null)return false;
        try(Cursor cursor=dm.query(new DownloadManager.Query().setFilterById(id))){
            return cursor!=null&&cursor.moveToFirst()&&
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            ==DownloadManager.STATUS_SUCCESSFUL;
        }catch(Exception e){return false;}
    }
    static boolean verify(Context c)throws Exception{
        if(!LARGE.equals(selected(c)))return LocalBrainModels.verify(c);
        File file=bigFile(c);
        if(!file.isFile()||file.length()<BIG_MIN||file.length()>BIG_MAX)
            throw new IllegalStateException("Qwen 1.7B download incomplete");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(FileInputStream stream=new FileInputStream(file)){
            byte[] buffer=new byte[1024*1024];int n;
            while((n=stream.read(buffer))!=-1)digest.update(buffer,0,n);
        }
        StringBuilder hex=new StringBuilder();
        for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
        boolean valid=BIG_SHA.equals(hex.toString());
        prefs(c).edit().putBoolean("verified",valid).apply();
        if(!valid){file.delete();throw new IllegalStateException("SHA-256 mismatch. Unsafe download discarded.");}
        return true;
    }
    static void delete(Context c){
        if(!LARGE.equals(selected(c))){LocalBrainModels.delete(c);return;}
        if(LocalBrain.isLoaded())throw new IllegalStateException("Restart Quiet before deleting a loaded model.");
        long id=prefs(c).getLong("download_id",-1);
        DownloadManager dm=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
        if(id>=0&&dm!=null)dm.remove(id);
        File f=bigFile(c);
        if(f.exists()&&!f.delete())throw new IllegalStateException("Could not delete model");
        prefs(c).edit().remove("download_id").putBoolean("verified",false).apply();
    }
    private LocalBrainModelCatalog(){}
}
