package dev.quiet.launcher;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Embedded neural speech via sherpa-onnx + Supertonic 3.
 * No Android TTS service, network calls, token or separate speech application.
 * The model is packaged into the APK, copied to private storage and loaded lazily.
 *
 * Generation is serialized on a background executor; callbacks return on main.
 */
final class LocalNeuralVoice {
    interface Listener {
        void finished(boolean success, String error);
    }

    private static final String TAG = "QuietNeuralVoice";
    private static final String ASSET_DIR = "quiet_voice/";
    private static final String[] FILES = {
        "duration_predictor.int8.onnx", "text_encoder.int8.onnx",
        "vector_estimator.int8.onnx", "vocoder.int8.onnx",
        "tts.json", "unicode_indexer.bin", "voice.bin"
    };

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile AudioTrack playing;
    private volatile boolean closed;
    private OfflineTts tts;

    LocalNeuralVoice(Context context) {
        this.context = context.getApplicationContext();
    }

    static boolean modelPackaged(Context context) {
        try (InputStream in = context.getAssets().open(ASSET_DIR + "tts.json")) {
            return in.read() >= 0;
        } catch (Exception ex) {
            return false;
        }
    }

    void speak(String raw, Listener listener) {
        final String clean = SpeechTextCleaner.clean(raw);
        final int request = sequence.incrementAndGet();
        stopTrack();
        if (closed || clean.isEmpty()) {
            deliver(listener, false, "No text to synthesize");
            return;
        }
        worker.execute(() -> {
            try {
                if (closed || request != sequence.get()) return;
                ensureReady();
                if (closed || request != sequence.get()) return;

                boolean hindi = HindiSpeech.containsDevanagari(clean)
                        || clean.matches("(?i)(?s).*\\b(haan|bhai|bolo|theek hai|shukriya)\\b.*");
                String text = hindi ? HindiSpeech.prepare(clean, Locale.forLanguageTag("hi-IN")) : clean;
                GenerationConfig config = new GenerationConfig();
                config.setSid(6);
                config.setSpeed(1.0f);
                config.setNumSteps(8);
                Map<String, String> extra = new HashMap<>();
                extra.put("lang", hindi ? "hi" : "en");
                config.setExtra(extra);

                GeneratedAudio audio = tts.generateWithConfigAndCallback(
                        text, config, samples -> request == sequence.get() && !closed ? 1 : 0);
                if (closed || request != sequence.get()) return;
                float[] samples = audio.getSamples();
                int sampleRate = audio.getSampleRate();
                if (samples == null || samples.length == 0 || sampleRate <= 0) {
                    throw new IllegalStateException("Neural model returned no audio");
                }
                playPcm(samples, sampleRate, request);
                if (!closed && request == sequence.get()) deliver(listener, true, null);
            } catch (Throwable ex) {
                Log.e(TAG, "Neural speech failed", ex);
                if (!closed && request == sequence.get()) {
                    deliver(listener, false, "Local voice failed: " + ex.getClass().getSimpleName());
                }
            }
        });
    }

    private void playPcm(float[] samples, int sampleRate, int request) {
        int minBytes = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) throw new IllegalStateException("Unsupported audio sample rate");
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(minBytes, 8192))
                .build();
        playing = track;
        try {
            track.play();
            short[] pcm = new short[4096];
            for (int index = 0; index < samples.length && request == sequence.get() && !closed; ) {
                int n = Math.min(pcm.length, samples.length - index);
                for (int i = 0; i < n; i++) {
                    float value = Math.max(-1f, Math.min(1f, samples[index + i]));
                    pcm[i] = (short) (value * 32767f);
                }
                int consumed = 0;
                while (consumed < n && request == sequence.get() && !closed) {
                    int written = track.write(pcm, consumed, n - consumed,
                            AudioTrack.WRITE_BLOCKING);
                    if (written <= 0) throw new IllegalStateException("Android audio output failed");
                    consumed += written;
                }
                index += n;
            }
            // AudioTrack.write() returns after buffering, not after audible playback.
            // Wait for queued PCM to drain before restarting speech recognition.
            if (!closed && request == sequence.get()) {
                long durationMs = Math.min(120000, 1000L * samples.length / sampleRate);
                long elapsedMs = 0;
                // Waiting by playback head position avoids a fixed 30-second UI lock.
                while (request == sequence.get() && !closed && elapsedMs < durationMs + 2000) {
                    long head = track.getPlaybackHeadPosition() & 0xffffffffL;
                    if (head >= samples.length) break;
                    Thread.sleep(30);
                    elapsedMs += 30;
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            if (playing == track) playing = null;
            try { track.pause(); track.flush(); track.release(); }
            catch (Exception ignored) { }
        }
    }

    private void ensureReady() throws Exception {
        if (tts != null) return;
        if (!modelPackaged(context)) {
            throw new IllegalStateException("Offline voice assets missing from APK");
        }
        File model = new File(context.getFilesDir(), "quiet_supertonic_3");
        if (!model.isDirectory() && !model.mkdirs())
            throw new IllegalStateException("Cannot create model directory");
        for (String name : FILES) {
            File dest = new File(model, name);
            if (dest.isFile() && dest.length() > 0) continue;
            File staged = new File(model, name + ".partial");
            try (InputStream in = context.getAssets().open(ASSET_DIR + name);
                 FileOutputStream out = new FileOutputStream(staged)) {
                byte[] bytes = new byte[65536];
                int n;
                while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
                out.getFD().sync();
            }
            if (!staged.renameTo(dest))
                throw new IllegalStateException("Cannot install local model: " + name);
        }
        // The Android AAR exposes Kotlin data classes (setters), unlike the
        // separate desktop Java example with builder() helpers.
        OfflineTtsSupertonicModelConfig supertonic = new OfflineTtsSupertonicModelConfig();
        supertonic.setDurationPredictor(new File(model, FILES[0]).getAbsolutePath());
        supertonic.setTextEncoder(new File(model, FILES[1]).getAbsolutePath());
        supertonic.setVectorEstimator(new File(model, FILES[2]).getAbsolutePath());
        supertonic.setVocoder(new File(model, FILES[3]).getAbsolutePath());
        supertonic.setTtsJson(new File(model, FILES[4]).getAbsolutePath());
        supertonic.setUnicodeIndexer(new File(model, FILES[5]).getAbsolutePath());
        supertonic.setVoiceStyle(new File(model, FILES[6]).getAbsolutePath());

        OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
        modelConfig.setSupertonic(supertonic);
        modelConfig.setNumThreads(2);
        modelConfig.setDebug(false);
        OfflineTtsConfig config = new OfflineTtsConfig();
        config.setModel(modelConfig);
        tts = new OfflineTts(null, config);
    }

    void stop() {
        sequence.incrementAndGet();
        stopTrack();
    }

    private void stopTrack() {
        AudioTrack track = playing;
        if (track != null) {
            try { track.pause(); track.flush(); } catch (Exception ignored) { }
        }
    }

    void close() {
        closed = true;
        stop();
        // Run JNI cleanup after any currently executing synthesis. Never release
        // an OfflineTts object concurrently with its native generate() call.
        worker.execute(() -> {
            if (tts != null) {
                try { tts.release(); } catch (Exception ignored) { }
                tts = null;
            }
        });
        worker.shutdown();
    }

    private void deliver(Listener listener, boolean success, String error) {
        if (listener != null) main.post(() -> {
            if (!closed) listener.finished(success, error);
        });
    }
}
