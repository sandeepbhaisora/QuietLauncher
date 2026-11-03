package dev.quiet.launcher;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** User-initiated optional ONNX model packs. No models are baked into the APK. */
final class OptionalModelPacks {
    enum Pack {
        VOICE("voice", "Supertonic 3 neural voice",
                "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
                "tts-models", "quiet_supertonic_3",
                new String[]{"duration_predictor.int8.onnx", "text_encoder.int8.onnx",
                    "vector_estimator.int8.onnx", "vocoder.int8.onnx",
                    "tts.json", "unicode_indexer.bin", "voice.bin"},
                120_000_000L, 200_000_000L),
        WAKE("wake", "Hey Londe wake detector",
                "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01",
                "kws-models", "londe_kws_v1",
                new String[]{"encoder.onnx", "decoder.onnx", "joiner.onnx",
                    "tokens.txt", "keywords.txt"},
                1_000_000L, 85_000_000L);

        final String id, title, archiveName, release, folder;
        final String[] files;
        final long minimumBytes, maximumBytes;
        Pack(String id, String title, String archiveName, String release,
             String folder, String[] files, long minimumBytes, long maximumBytes) {
            this.id=id; this.title=title; this.archiveName=archiveName;
            this.release=release; this.folder=folder; this.files=files;
            this.minimumBytes=minimumBytes; this.maximumBytes=maximumBytes;
        }
    }

    private static final String TAG="OptionalModelPacks";
    private static final String PREF="quiet_optional_packs";
    private static final long MAX_ARCHIVE=240_000_000L;

    static File modelDirectory(Context context, Pack pack) {
        return new File(context.getFilesDir(), pack.folder);
    }

    static boolean installed(Context context, Pack pack) {
        // Recognize models extracted by old (large) APKs, preserving downloads
        // and user data during the migration to slimmer APKs.
        return validDirectory(modelDirectory(context, pack), pack);
    }

    private static boolean validDirectory(File folder, Pack pack) {
        if (!folder.isDirectory()) return false;
        long total=0;
        for (String file : pack.files) {
            File f=new File(folder,file);
            if (!f.isFile() || f.length()<128 || f.length()>pack.maximumBytes) return false;
            total+=f.length();
        }
        return total>=pack.minimumBytes && total<=pack.maximumBytes;
    }

    static String status(Context context, Pack pack) {
        if (installed(context, pack)) return "Installed · works offline";
        DownloadManager mgr=manager(context);
        long id=prefs(context).getLong(pack.id+"_download",-1);
        if (id<0 || mgr==null) return "Not installed · optional download";
        try (Cursor cursor=mgr.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor==null || !cursor.moveToFirst()) return "Download missing · retry";
            int st=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (st==DownloadManager.STATUS_SUCCESSFUL) return "Downloaded · tap to install";
            if (st==DownloadManager.STATUS_FAILED) return "Download failed · retry";
            if (st==DownloadManager.STATUS_PAUSED) return "Paused · waiting for Wi-Fi";
            long sofar=cursor.getLong(cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total=cursor.getLong(cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            if (total>0) return "Downloading · "+Math.min(100,sofar*100/total)+"%";
            return "Download queued · Wi-Fi only";
        } catch (Exception e) { return "Download status unavailable"; }
    }

    static synchronized void download(Context context, Pack pack) {
        if (installed(context,pack)) return;
        DownloadManager manager=manager(context);
        if(manager==null)throw new IllegalStateException("Android Download Manager unavailable");
        long previous=prefs(context).getLong(pack.id+"_download",-1L);
        if(previous>=0) {
            try (Cursor cursor=manager.query(new DownloadManager.Query().setFilterById(previous))) {
                if(cursor!=null && cursor.moveToFirst()) {
                    int status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if(status==DownloadManager.STATUS_PENDING||status==DownloadManager.STATUS_RUNNING||
                            status==DownloadManager.STATUS_PAUSED||status==DownloadManager.STATUS_SUCCESSFUL)
                        return;
                }
            }
            manager.remove(previous);
        }
        File archive=archive(context,pack);
        if(archive.exists() && !archive.delete())
            throw new IllegalStateException("Unable to replace previous model archive");
        String url="https://github.com/k2-fsa/sherpa-onnx/releases/download/"
                +pack.release+"/"+pack.archiveName+".tar.bz2";
        DownloadManager.Request request=new DownloadManager.Request(Uri.parse(url));
        request.setTitle("Quiet · "+pack.title);
        request.setDescription("Optional offline model; install from Quiet Settings after download");
        request.setAllowedOverMetered(false);
        request.setAllowedOverRoaming(false);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(context,"optional_models",pack.id+".tar.bz2");
        long id=manager.enqueue(request);
        prefs(context).edit().putLong(pack.id+"_download",id).apply();
    }

    static void install(Context context, Pack pack) throws Exception {
        if (installed(context,pack)) return;
        if (!"Downloaded · tap to install".equals(status(context,pack)))
            throw new IllegalStateException("Model download hasn't finished");
        File archive=archive(context,pack);
        if(!archive.isFile() || archive.length()<1024 || archive.length()>MAX_ARCHIVE)
            throw new IOException("Incomplete or oversized model archive");
        File target=modelDirectory(context,pack);
        File staged=new File(context.getFilesDir(),pack.folder+".staging");
        deleteTree(staged);
        if(!staged.mkdirs()) throw new IOException("Not enough storage for model installation");
        Set<String> needed=new HashSet<>(Arrays.asList(pack.files));
        long total=0L;
        try (TarArchiveInputStream tar=new TarArchiveInputStream(
                new BZip2CompressorInputStream(
                        new BufferedInputStream(new FileInputStream(archive)),false))) {
            TarArchiveEntry entry;
            byte[] buf=new byte[64*1024];
            while((entry=tar.getNextTarEntry())!=null) {
                if (!entry.isFile()) continue;
                String name=entry.getName();
                int slash=name.lastIndexOf('/');
                String basename=name.substring(slash+1);
                if(!needed.contains(basename)) continue;
                // Fail closed against archive traversal, links and unexpected paths.
                if(!name.equals(pack.archiveName+"/"+basename) ||
                        entry.getSize()<128 || entry.getSize()>pack.maximumBytes)
                    throw new IOException("Invalid model archive entry");
                File outFile=new File(staged,basename);
                if(outFile.exists()) throw new IOException("Duplicate model entry");
                total+=entry.getSize();
                if(total>pack.maximumBytes)throw new IOException("Model archive too large");
                try(FileOutputStream out=new FileOutputStream(outFile)) {
                    long remaining=entry.getSize();
                    while(remaining>0) {
                        int n=tar.read(buf,0,(int)Math.min(buf.length,remaining));
                        if(n<=0)throw new IOException("Truncated model archive");
                        out.write(buf,0,n);
                        remaining-=n;
                    }
                    out.getFD().sync();
                }
            }
        } catch(Exception e) {
            deleteTree(staged);
            throw e;
        }
        if (!validDirectory(staged,pack)) {
            deleteTree(staged);
            throw new IOException("Model archive did not contain the expected files");
        }
        // Replace only AFTER a complete extraction has passed validation.
        deleteTree(target);
        if(!staged.renameTo(target))throw new IOException("Couldn't finalize model installation");
        // Archive can be re-downloaded later; clearing frees extra device storage.
        if(!archive.delete())Log.w(TAG,"Model archive remains on disk");
        prefs(context).edit().remove(pack.id+"_download").apply();
    }

    private static File archive(Context context, Pack pack) {
        return new File(context.getExternalFilesDir("optional_models"),pack.id+".tar.bz2");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }

    private static DownloadManager manager(Context context) {
        return (DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    private static void deleteTree(File file) {
        if(file.isDirectory()) {
            File[] children=file.listFiles();
            if(children!=null)for(File child:children)deleteTree(child);
        }
        if(file.exists() && !file.delete())Log.w(TAG,"Could not delete "+file.getName());
    }

    private OptionalModelPacks() {}
}
