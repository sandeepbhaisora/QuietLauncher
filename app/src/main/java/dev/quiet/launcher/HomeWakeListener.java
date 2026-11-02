package dev.quiet.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Experimental foreground-only "Hey Londe" detector.
 *
 * Only uses Android's ON-DEVICE speech recognizer; never falls back to
 * potentially cloud-backed SpeechRecognizer for ambient wake detection.
 * This is a limited prototype, not an energy-efficient hotword DSP model.
 * It must not be used while app is backgrounded or on another page.
 */
final class HomeWakeListener {
    interface Callbacks {
        void onState(String state);
        void onWake();
        void onRequest(String request);
        void onUnavailable(String why);
    }
    private final Activity owner;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Callbacks callbacks;
    private SpeechRecognizer recognizer;
    private boolean armed, listening, waitingForRequest;
    private int errors;
    private int generation;
    private long awakeUntil;

    HomeWakeListener(Activity owner, Callbacks callbacks) {
        this.owner = owner;
        this.callbacks = callbacks;
    }
    static boolean available(Activity activity) {
        return android.os.Build.VERSION.SDK_INT >= 31 &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(activity);
    }
    void start() {
        if (armed) return;
        if (owner.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            callbacks.onUnavailable("Microphone permission is required.");
            return;
        }
        if (!available(owner)) {
            callbacks.onUnavailable("This phone has no available on-device speech recognizer. Wake listening is off.");
            return;
        }
        armed=true;
        errors=0;
        generation++;
        try {
            recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(owner);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    callbacks.onState(waitingForRequest ? "I'M LISTENING…" : "SAY “HEY LONDE”");
                }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rms) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onError(int error) {
                    listening=false;
                    if (!armed) return;
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                            error == SpeechRecognizer.ERROR_CLIENT) {
                        stop();callbacks.onUnavailable("Microphone or speech recognizer unavailable.");
                        return;
                    }
                    // Exponential backoff rather than a hot restart loop.
                    errors=Math.min(8,errors+1);
                    if (errors>=8) {
                        stop();
                        callbacks.onUnavailable("Wake detection paused after repeated recognition errors.");
                        return;
                    }
                    schedule(Math.min(4500,400*errors));
                }
                @Override public void onResults(Bundle result) {
                    listening=false;
                    errors=0;
                    ArrayList<String> options=result.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION);
                    if (options != null && !options.isEmpty())
                        handle(options.get(0));
                    if (armed) schedule(300);
                }
                @Override public void onPartialResults(Bundle result) {}
                @Override public void onEvent(int type, Bundle args) {}
            });
            schedule(50);
        } catch (Exception ex) {
            stop();
            callbacks.onUnavailable("Unable to start the on-device recognizer.");
        }
    }
    private void handle(String text) {
        if (!armed || text == null) return;
        String spoken=text.trim();
        if (spoken.isEmpty()) return;
        if (waitingForRequest) {
            if (System.currentTimeMillis()>awakeUntil) {
                waitingForRequest=false;
            } else {
                waitingForRequest=false;
                String request=withoutWake(spoken);
                if (request.isEmpty()) return;
                callbacks.onRequest(request);
                stop();
                return;
            }
        }
        String lower=spoken.toLowerCase(Locale.ROOT);
        // Recognition variants help with Indian-English pronunciation, but
        // avoid interpreting an arbitrary question as a wake command.
        String[] triggers={"hey londe","hey london","hello londe","hey londay","hi londe"};
        for (String word : triggers) {
            int pos=lower.indexOf(word);
            if (pos>=0 && pos<=8) {
                callbacks.onWake();
                String after=spoken.substring(pos+word.length()).trim()
                        .replaceFirst("^[,.:;!?\\s]+","");
                if (!after.isEmpty()) {
                    callbacks.onRequest(after);
                    stop();
                } else {
                    waitingForRequest=true;
                    awakeUntil=System.currentTimeMillis()+12000;
                    callbacks.onState("I'M LISTENING…");
                }
                return;
            }
        }
    }
    private String withoutWake(String spoken) {
        return spoken.replaceFirst("(?i)^(?:hey|hello|hi)\\s+(?:londe|london|londay)\\s*[,.:!?]*\\s*","").trim();
    }
    private void schedule(int millis) {
        int cycle=generation;
        main.postDelayed(()->{
            if (!armed || cycle!=generation || listening || recognizer==null) return;
            if (waitingForRequest && System.currentTimeMillis()>awakeUntil) {
                waitingForRequest=false;
                callbacks.onState("SAY “HEY LONDE”");
            }
            Intent request=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            request.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,2);
            request.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false);
            request.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
            try {listening=true;recognizer.startListening(request);}
            catch(Exception ex){
                listening=false;
                stop();
                callbacks.onUnavailable("Wake recognition could not continue.");
            }
        },millis);
    }
    void stop() {
        armed=false;listening=false;waitingForRequest=false;
        generation++;
        if(recognizer!=null) {
            try {recognizer.cancel();recognizer.destroy();}catch(Exception ignored){}
            recognizer=null;
        }
        main.removeCallbacksAndMessages(null);
    }
    boolean isRunning() {return armed;}
}
