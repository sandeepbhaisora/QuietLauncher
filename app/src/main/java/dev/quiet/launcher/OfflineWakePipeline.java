package dev.quiet.launcher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

/**
 * Microphone owner for an OFFLINE keyword spotter. Unlike SpeechRecognizer,
 * this receives raw PCM and must never transcribe arbitrary speech.
 *
 * A Detector backed by a real packaged KWS model must be supplied. Fails
 * closed otherwise: there is deliberately no Google recognizer fallback.
 */
final class OfflineWakePipeline {
    interface Detector {
        /** Feed 16-kHz mono PCM; return true only for the configured keyword. */
        boolean accept(short[] pcm, int count);
        void close();
    }
    interface Events {
        void onWake();
        void onError(String reason);
    }
    private final Context context;
    private final Events events;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private Thread worker;
    private AudioRecord microphone;
    private Detector detector;

    OfflineWakePipeline(Context context, Events events) {
        this.context = context;
        this.events = events;
    }

    synchronized boolean start(Detector provided) {
        if (provided == null) return false;
        if (running || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return false;
        detector = provided;
        running = true;
        worker = new Thread(this::capture, "quiet-offline-keyword");
        worker.start();
        return true;
    }

    private void capture() {
        final int rate = 16000;
        final int min = AudioRecord.getMinBufferSize(rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) { fail("Microphone configuration unavailable"); return; }
        try {
            microphone = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min * 2, rate));
            if (microphone.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("Microphone unavailable");
            microphone.startRecording();
            short[] chunk = new short[1600];
            while (running) {
                int size = microphone.read(chunk, 0, chunk.length);
                if (size < 0) throw new IllegalStateException("Microphone read: " + size);
                if (size > 0 && detector.accept(chunk, size)) {
                    running = false;
                    // Release the mic before starting command transcription.
                    releaseAudio();
                    main.post(events::onWake);
                    break;
                }
            }
        } catch (Exception error) {
            if (running) fail("Local wake detector stopped: " + error.getMessage());
        } finally {
            releaseAudio();
            Detector old = detector;
            detector = null;
            if (old != null) old.close();
            running = false;
        }
    }

    private void fail(String reason) {
        running = false;
        main.post(() -> events.onError(reason));
    }

    synchronized void stop() {
        running = false;
        releaseAudio();
    }

    private void releaseAudio() {
        AudioRecord mic = microphone;
        microphone = null;
        if (mic == null) return;
        try { mic.stop(); } catch (Exception ignored) {}
        mic.release();
    }

    boolean isRunning() { return running; }
}
