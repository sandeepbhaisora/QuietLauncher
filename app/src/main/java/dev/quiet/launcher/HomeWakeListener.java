package dev.quiet.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opt-in, foreground HOME-only voice wake prototype.
 * Uses Android on-device recognition (never the generic cloud-backed fallback).
 * This is not a hardware hotword engine or a screen-off assistant.
 */
final class HomeWakeListener {
    interface Callbacks {
        void onState(String state);
        void onWake();
        void onHeard(String transcript, boolean finalResult);
        void onRequest(String request);
        void onUnavailable(String why);
    }

    // Only a completed, explicit wake phrase can arm command listening.
    // Avoid permissive aliases such as "hello London" that caused false wakes.
    private static final Pattern WAKE = Pattern.compile(
        "^(?:hey\\s+londe|हे\\s+लोंडे|हे\\s+लौंडे)" +
        "(?=$|[\\s,.;:!?])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final long COMMAND_WINDOW_MS = 20000L;
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Callbacks callback;
    private SpeechRecognizer recognizer;
    private boolean enabled, listening, waitingCommand, awaitingAcknowledgement;
    private int failures, generation;
    private long requestDeadline;

    HomeWakeListener(Activity activity, Callbacks callback) {
        this.activity=activity;
        this.callback=callback;
    }
    static boolean available(Activity activity) {
        return android.os.Build.VERSION.SDK_INT >= 31 &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(activity);
    }
    void start() {
        if (enabled) return;
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) {
            callback.onUnavailable("Microphone permission not granted.");
            return;
        }
        if (!available(activity)) {
            callback.onUnavailable("On-device recognition is not available on this phone.");
            return;
        }
        enabled=true;
        failures=0;
        generation++;
        try {
            recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(activity);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    if (enabled) callback.onState(waitingCommand ?
                            "LONDE LISTENING · SAY REQUEST" : "SAY HEY LONDE");
                }
                @Override public void onBeginningOfSpeech() {
                    if (enabled) callback.onState("HEARING YOU…");
                }
                @Override public void onRmsChanged(float rms) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {
                    if (enabled) callback.onState("RECOGNIZING SPEECH…");
                }
                @Override public void onError(int error) {
                    listening=false;
                    if (!enabled) return;
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        stop();
                        callback.onUnavailable("Microphone permission was denied.");
                        return;
                    }
                    if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                        stop();
                        callback.onUnavailable("On-device language model is unavailable.");
                        return;
                    }
                    if (error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) {
                        stop();
                        callback.onUnavailable("Android rate-limited repeated listening. Turn wake off and retry later.");
                        return;
                    }
                    failures++;
                    if (failures >= 6) {
                        stop();
                        callback.onUnavailable("Speech recognition stopped after repeated errors (" +
                                error + "). Enable wake again to retry.");
                        return;
                    }
                    callback.onState("SPEECH RETRY " + failures + " · CODE " + error);
                    schedule(Math.min(5000, 900 * failures));
                }
                @Override public void onResults(Bundle results) {
                    listening=false;
                    if (!enabled) return;
                    ArrayList<String> guesses=results.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION);
                    if (guesses != null && !guesses.isEmpty()) {
                        failures=0;
                        callback.onHeard(guesses.get(0), true);
                        process(guesses);
                    } else callback.onState(waitingCommand ?
                            "I DIDN'T CATCH THAT · SAY REQUEST" :
                            "I DIDN'T CATCH THAT · SAY HEY LONDE");
                    if (enabled && !awaitingAcknowledgement) schedule(450);
                }
                @Override public void onPartialResults(Bundle results) {
                    if (!enabled) return;
                    ArrayList<String> guesses=results.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION);
                    if (guesses != null && !guesses.isEmpty())
                        callback.onHeard(guesses.get(0), false);
                }
                @Override public void onEvent(int eventType, Bundle params) {}
            });
            callback.onState("STARTING ON-DEVICE LISTENING…");
            schedule(100);
        } catch (Exception ex) {
            stop();
            callback.onUnavailable("Could not start on-device speech recognition.");
        }
    }

    private void process(ArrayList<String> guesses) {
        if (waitingCommand) {
            if (System.currentTimeMillis() > requestDeadline) {
                waitingCommand=false;
                callback.onState("WAKE EXPIRED · SAY HEY LONDE");
            } else {
                for (String guess:guesses) {
                    String text=stripWake(guess).trim();
                    if (text.isEmpty()) continue; // Repeating the wake alone is not a command
                    waitingCommand=false;
                    callback.onRequest(text);
                    stop();
                    return;
                }
                callback.onState("HEARD WAKE · NOW SAY YOUR REQUEST");
                return;
            }
        }
        for (String guess:guesses) {
            Matcher m=WAKE.matcher(guess.trim());
            if (!m.find()) continue;
            String rest=guess.trim().substring(m.end()).replaceFirst("^[\\s,.;:!?]+","").trim();
            if (!rest.isEmpty()) {
                callback.onRequest(rest);
                stop();
                return;
            }
            waitingCommand=true;
            requestDeadline=System.currentTimeMillis()+COMMAND_WINDOW_MS;
            awaitingAcknowledgement=true;
            callback.onState("HEY LONDE HEARD · READY FOR REQUEST");
            callback.onWake();
            // TTS callback resumes listening. A fallback handles slow/unavailable TTS.
            handler.postDelayed(this::resumeAfterAcknowledgement,2600);
            return;
        }
        callback.onState("HEARD SPEECH, BUT NOT HEY LONDE");
    }

    private String stripWake(String phrase) {
        String clean=phrase==null?"":phrase.trim();
        Matcher wake=WAKE.matcher(clean);
        if (wake.find()) return clean.substring(wake.end()).replaceFirst("^[\\s,.;:!?]+","").trim();
        // Recognizers sometimes omit the wake word on the second utterance.
        return clean;
    }
    void resumeAfterAcknowledgement() {
        if (!enabled || !awaitingAcknowledgement) return;
        awaitingAcknowledgement=false;
        callback.onState("LONDE READY · SPEAK YOUR REQUEST");
        schedule(300);
    }
    private void schedule(long delayMs) {
        final int cycle=generation;
        handler.postDelayed(()-> {
            if (!enabled || cycle!=generation || listening || recognizer==null ||
                    awaitingAcknowledgement) return;
            if (waitingCommand && System.currentTimeMillis()>requestDeadline) {
                waitingCommand=false;
                callback.onState("WAKE EXPIRED · SAY HEY LONDE");
            }
            Intent request=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            request.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
            request.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,5);
            request.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
            request.putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault().toLanguageTag());
            try {
                listening=true;
                recognizer.startListening(request);
            } catch(Exception e) {
                listening=false;
                stop();
                callback.onUnavailable("Android recognizer failed to resume.");
            }
        },delayMs);
    }
    void stop() {
        enabled=false;listening=false;waitingCommand=false;awaitingAcknowledgement=false;
        generation++;
        handler.removeCallbacksAndMessages(null);
        if (recognizer!=null) {
            try {recognizer.cancel();recognizer.destroy();} catch(Exception ignored) {}
            recognizer=null;
        }
    }
    boolean isRunning() {return enabled;}
}
