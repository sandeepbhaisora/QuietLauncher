package dev.quiet.launcher;

import android.content.Context;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Real model-backed, offline keyword spotter. The English GigaSpeech KWS model
 * has a configurable HEY LONDE phrase. This is not whole-speech recognition.
 * Call accept() only from one dedicated PCM reader thread.
 */
final class LondeKeywordDetector implements OfflineWakePipeline.Detector {
    private static final String ASSET_DIR = "londe_wake/";
    private static final String[] FILES = {
            "encoder.onnx", "decoder.onnx", "joiner.onnx",
            "tokens.txt", "keywords.txt"
    };

    private final KeywordSpotter kws;
    private final OnlineStream stream;

    static boolean available(Context context) {
        return OptionalModelPacks.installed(context, OptionalModelPacks.Pack.WAKE);
    }

    LondeKeywordDetector(Context context) throws Exception {
        if (!available(context))
            throw new IllegalStateException("Install Hey Londe detector in Quiet Settings first");
        File folder=OptionalModelPacks.modelDirectory(context,OptionalModelPacks.Pack.WAKE);
        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(new File(folder, FILES[0]).getAbsolutePath());
        transducer.setDecoder(new File(folder, FILES[1]).getAbsolutePath());
        transducer.setJoiner(new File(folder, FILES[2]).getAbsolutePath());
        OnlineModelConfig model = new OnlineModelConfig();
        model.setTransducer(transducer);
        model.setTokens(new File(folder, FILES[3]).getAbsolutePath());
        model.setNumThreads(1);
        model.setModelType("zipformer2");
        FeatureConfig features = new FeatureConfig();
        features.setSampleRate(16000);
        features.setFeatureDim(80);
        KeywordSpotterConfig config = new KeywordSpotterConfig();
        config.setFeatConfig(features);
        config.setModelConfig(model);
        config.setKeywordsFile(new File(folder, FILES[4]).getAbsolutePath());
        config.setKeywordsThreshold(0.35f);
        config.setKeywordsScore(1.5f);
        config.setNumTrailingBlanks(2);
        kws = new KeywordSpotter(null, config);
        stream = kws.createStream("");
    }

    @Override public boolean accept(short[] pcm, int count) {
        float[] samples = new float[count];
        for (int i = 0; i < count; i++) samples[i] = pcm[i] / 32768f;
        stream.acceptWaveform(samples, 16000);
        while (kws.isReady(stream)) {
            kws.decode(stream);
            String keyword = kws.getResult(stream).getKeyword();
            if (keyword != null && !keyword.trim().isEmpty()) {
                kws.reset(stream);
                return true;
            }
        }
        return false;
    }

    @Override public void close() {
        try { stream.release(); } finally { kws.release(); }
    }
}
