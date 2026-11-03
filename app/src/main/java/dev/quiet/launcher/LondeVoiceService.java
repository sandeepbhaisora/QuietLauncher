package dev.quiet.launcher;

import android.Manifest;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionService;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The actual Android default-assistant service. When explicitly enabled it
 * listens for only the offline HEY LONDE phrase and asks Android to show the
 * assistant session. It is NOT a continuously running full speech recognizer.
 */
public final class LondeVoiceService extends VoiceInteractionService {
    private static final String TAG = "LondeVoiceService";
    private static WeakReference<LondeVoiceService> current = new WeakReference<>(null);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private OfflineWakePipeline pipeline;
    private int generation;
    private boolean destroyed;
    private volatile boolean conversationOwnsMic;
    private int microphoneLeaseVersion;

    @Override public void onReady() {
        super.onReady();
        current = new WeakReference<>(this);
        ui.postDelayed(this::startWakeIfAllowed, 500);
    }

    /** Only a live, user-selected system assistant may listen persistently. */
    private boolean permitted() {
        return !destroyed
                && !conversationOwnsMic
                && isActiveService(this, new ComponentName(this, LondeVoiceService.class))
                && getSharedPreferences("quiet_local_v1", MODE_PRIVATE)
                       .getBoolean("londe_wake_enabled", false)
                && LondeKeywordDetector.available(this)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                       == PackageManager.PERMISSION_GRANTED;
    }

    private void startWakeIfAllowed() {
        if (!permitted() || pipeline != null) return;
        final int requested = ++generation;
        loader.execute(() -> {
            OfflineWakePipeline.Detector detector = null;
            try {
                detector = new LondeKeywordDetector(this);
                OfflineWakePipeline.Detector ready = detector;
                ui.post(() -> {
                    if (requested != generation || !permitted() || pipeline != null) {
                        ready.close();
                        return;
                    }
                    OfflineWakePipeline wake = new OfflineWakePipeline(this,
                            new OfflineWakePipeline.Events() {
                                @Override public void onWake() {
                                    pipeline = null;
                                    ++generation;
                                    try { showSession(new Bundle(), 0); }
                                    catch (Exception ex) {
                                        Log.e(TAG, "Failed to show Android assistant session", ex);
                                        ui.postDelayed(LondeVoiceService.this::startWakeIfAllowed, 2000);
                                    }
                                }

                                @Override public void onError(String reason) {
                                    Log.w(TAG, reason);
                                    pipeline = null;
                                    ui.postDelayed(LondeVoiceService.this::startWakeIfAllowed, 5000);
                                }
                            });
                    if (wake.start(ready)) {
                        pipeline = wake;
                        Log.i(TAG, "Offline HEY LONDE keyword spotter running");
                    } else ready.close();
                });
            } catch (Throwable error) {
                Log.e(TAG, "Wake model could not initialize", error);
                if (detector != null) detector.close();
                ui.postDelayed(this::startWakeIfAllowed, 10000);
            }
        });
    }

    private void stopWake() {
        ++generation;
        if (pipeline != null) {
            pipeline.stop();
            pipeline = null;
        }
    }

    static void refreshWake() {
        LondeVoiceService instance = current.get();
        if (instance != null) instance.ui.post(() -> {
            instance.stopWake();
            instance.startWakeIfAllowed();
        });
    }

    static void pauseWakeForSession() {
        LondeVoiceService instance = current.get();
        if (instance != null) instance.ui.post(() -> {
            // Invalidate a rearm that a previous Activity already scheduled.
            ++instance.microphoneLeaseVersion;
            instance.conversationOwnsMic = true;
            instance.stopWake();
        });
    }

    static void rearmWake() {
        LondeVoiceService instance = current.get();
        if (instance != null) instance.ui.post(() -> {
            final int lease = instance.microphoneLeaseVersion;
            instance.ui.postDelayed(() -> {
                // Home.onPause can run before Londe.onResume; a delayed Home
                // rearm must NOT steal Londe's recognizer microphone.
                if (instance.destroyed || lease != instance.microphoneLeaseVersion)
                    return;
                instance.conversationOwnsMic = false;
                instance.startWakeIfAllowed();
            }, 900);
        });
    }

    @Override public void onShutdown() {
        stopWake();
        super.onShutdown();
    }

    @Override public void onDestroy() {
        destroyed = true;
        stopWake();
        loader.shutdownNow();
        current.clear();
        super.onDestroy();
    }
}
