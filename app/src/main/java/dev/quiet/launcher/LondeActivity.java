package dev.quiet.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.RecognitionListener;
import android.speech.tts.UtteranceProgressListener;
import android.Manifest;
import android.content.pm.PackageManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Londe's transient voice console, not a chat timeline.
 * Never listens in the background or executes model-generated device actions.
 */
public final class LondeActivity extends Activity {
    private static final int REQUEST_MIC = 511;
    private static final int REQUEST_WAKE_TEST_MIC = 512;
    private static final int REQUEST_CALENDAR = 513;
    private String pendingCalendarQuestion;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final List<LondeClient.Turn> memory = new ArrayList<>();
    // Cloud-only context: never replay Qwen answers influenced by private notes.
    private final List<LondeClient.Turn> onlineMemory = new ArrayList<>();
    private TextToSpeech speech;
    private LocalNeuralVoice neuralVoice;
    private ThinkingHum thinkingHum;
    private final SpokenOnlineConsent onlineConsent = new SpokenOnlineConsent();
    private SpeechRecognizer recognizer;
    private OfflineWakePipeline wakeDiagnostic;
    private int wakeDiagnosticGeneration;
    private boolean activeSession, recognizerRunning, inForeground=true;
    private String selectedEngine="";
    private int silenceCount;
    private boolean usingOnDeviceRecognizer, triedAlternateRecognizer;
    private boolean recognizerReady;
    private int recognitionFailures, recognizerGeneration, recognitionAttempt;
    private long recognizerStartedAt;
    private static final long SPEECH_START_TIMEOUT_MS = 8000L;
    private boolean speechReady, readAloud = true, busy;
    private int requestGeneration = 0;
    private String model = "";
    private String currentAnswer = "";
    private Runnable pendingVoiceNavigation;
    private EditText keyboard;
    private TextView answer, caption, status, lastRequest, microphone, speaker, keyboardToggle;
    private LinearLayout keyboardPanel, actionRow;
    private ScrollView answerScroll;
    private LondeOrbView orb;
    private int background, surface, ink, secondary, accent;
    private boolean paper;
    private enum Phase { READY, LISTENING, THINKING, SPEAKING, ACTION }
    private Phase phase = Phase.READY;

    @Override public void onCreate(Bundle saved) {
        paper = "paper".equals(getSharedPreferences("quiet_local_v1", MODE_PRIVATE)
                .getString("theme", "ink"));
        setTheme(paper ? R.style.LondeOverlayPaper : R.style.LondeOverlayDark);
        super.onCreate(saved);
        background = paper ? Color.rgb(237,236,228) : Color.rgb(7,21,16);
        surface = paper ? Color.rgb(224,228,217) : Color.rgb(18,44,36);
        ink = paper ? Color.rgb(35,47,37) : Color.rgb(239,251,243);
        secondary = paper ? Color.rgb(91,110,93) : Color.rgb(160,189,173);
        accent = paper ? Color.rgb(70,116,73) : Color.rgb(164,246,197);
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        lp.gravity = Gravity.BOTTOM;
        lp.dimAmount = 0.92f;
        window.setAttributes(lp);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        createConsole();
        selectedEngine=getSharedPreferences("londe_voice_v1",MODE_PRIVATE).getString("tts_engine","");
        neuralVoice = new LocalNeuralVoice(this);
        thinkingHum = new ThinkingHum(this);
        if (getSharedPreferences("londe_voice_v1", MODE_PRIVATE)
                .getBoolean("neural_crash_warning_pending", false)) {
            getSharedPreferences("londe_voice_v1", MODE_PRIVATE).edit()
                    .remove("neural_crash_warning_pending").apply();
            new AlertDialog.Builder(this).setTitle("Local voice failed")
                    .setMessage("Quiet detected an interrupted neural speech session and switched to Android voice to prevent repeated crashes. You can try neural voice again in settings.")
                    .setPositiveButton("OK", null).show();
        }

        initSpeech();
        // Only Quiet's in-process Home flow can stage a pending voice approval;
        // exported Intent extras cannot impersonate user consent.
        String pendingHomeQuestion = SpokenOnlineConsent.consumeForVoiceConsole(
                SystemClock.elapsedRealtime());
        if (pendingHomeQuestion != null) {
            ui.postDelayed(() -> beginSpokenOnlineConsent(pendingHomeQuestion), 900);
            return;
        }
        if (getIntent() != null && Intent.ACTION_ASSIST.equals(getIntent().getAction()))
            getIntent().putExtra("auto_listen", true);
        if (getIntent() != null && getIntent().getBooleanExtra("wake_diagnostic", false)) {
            ui.postDelayed(this::startWakeDiagnostic, 550);
        } else if (getIntent() != null && getIntent().getBooleanExtra("auto_listen", false)) {
            ui.postDelayed(this::ensureListening, 650);
        }
    }

    private void initSpeech(){
        if(speech!=null){speech.stop();speech.shutdown();}
        speechReady=false;
        TextToSpeech.OnInitListener listener=state->{
            speechReady=state==TextToSpeech.SUCCESS;
            if(speechReady){
                speech.setLanguage(Locale.getDefault());
                speech.setSpeechRate(0.95f);
                speech.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                    @Override public void onStart(String id){}
                    @Override public void onDone(String id){ui.post(()->voiceFinished());}
                    @Override public void onError(String id){ui.post(()->voiceFinished());}
                });
                String preferred=getSharedPreferences("londe_voice_v1",MODE_PRIVATE).getString("tts_voice","");
                if(!preferred.isEmpty() && speech.getVoices()!=null)
                    for(Voice v:speech.getVoices())if(preferred.equals(v.getName())){
                        speech.setVoice(v);break;
                    }
            }
        };
        speech=selectedEngine.isEmpty()?new TextToSpeech(this,listener):
            new TextToSpeech(this,listener,selectedEngine);
    }

    private boolean useLocalVoice() {
        return "neural".equals(getSharedPreferences("londe_voice_v1", MODE_PRIVATE)
                .getString("voice_mode", "neural"));
    }
    private void voiceSettings(){
        new AlertDialog.Builder(this).setTitle("Londe speech")
            .setMessage("Quiet now includes offline Supertonic 3 neural synthesis. No API calls are needed to speak. Choose Android TTS only if needed.")
            .setPositiveButton("Offline neural (default)", (d,w) -> {
                getSharedPreferences("londe_voice_v1", MODE_PRIVATE).edit()
                        .putString("voice_mode","neural").apply();
                toast(LocalNeuralVoice.modelPackaged(this)
                        ? "Offline neural voice active" : "Install Supertonic voice pack in Quiet Settings first");
                say("नमस्ते भाई, मैं लोंडे हूँ। बताओ क्या करना है?");
            })
            .setNeutralButton("Android fallback", (d,w) -> {
                getSharedPreferences("londe_voice_v1", MODE_PRIVATE).edit()
                        .putString("voice_mode","android").apply();
                pickEngine();
            })
            .setNegativeButton("Cancel", null).show();
    }
    private void pickEngine(){
        java.util.List<TextToSpeech.EngineInfo> engines=speech.getEngines();
        if(engines==null||engines.isEmpty()){toast("No TTS engines found");return;}
        String[] labels=new String[engines.size()];
        for(int i=0;i<labels.length;i++)labels[i]=engines.get(i).label;
        new AlertDialog.Builder(this).setTitle("Speech engine")
            .setItems(labels,(d,index)->{
                selectedEngine=engines.get(index).name;
                getSharedPreferences("londe_voice_v1",MODE_PRIVATE).edit().putString("tts_engine",selectedEngine)
                    .remove("tts_voice").apply();
                if(activeSession)stopListening();
                initSpeech();
                toast("Engine changed. Try another reply.");
            }).setNegativeButton("Cancel",null).show();
    }
    private void pickVoice(){
        java.util.List<Voice> voices=new java.util.ArrayList<>();
        if(speech.getVoices()!=null)
            for(Voice v:speech.getVoices())
                if(v.getLocale().getLanguage().equals(Locale.getDefault().getLanguage()))
                    voices.add(v);
        if(voices.isEmpty()){toast("No voices for your device language");return;}
        voices.sort((a,b)->Integer.compare(b.getQuality(),a.getQuality()));
        String[] labels=new String[voices.size()];
        for(int i=0;i<labels.length;i++){
            Voice v=voices.get(i);
            labels[i]=v.getName()+(v.isNetworkConnectionRequired()?" · network":" · offline");
        }
        new AlertDialog.Builder(this).setTitle("Installed voices")
            .setItems(labels,(d,index)->{
                Voice v=voices.get(index);
                speech.setVoice(v);
                getSharedPreferences("londe_voice_v1",MODE_PRIVATE).edit().putString("tts_voice",v.getName()).apply();
                if(activeSession)stopListening();
                say("Hi, I'm Londe. How does this voice sound?");
            }).setNegativeButton("Cancel",null).show();
    }

    private int dp(float v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }
    private GradientDrawable panel(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }
    private TextView text(String value, int size, int color, boolean strong) {
        TextView v = new TextView(this);
        v.setText(value); v.setTextColor(color); v.setTextSize(size);
        if (strong) v.setTypeface(null, android.graphics.Typeface.BOLD);
        return v;
    }
    private TextView button(String value, Runnable action) {
        TextView v = text(value, 13, ink, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12), dp(11), dp(12), dp(11));
        v.setBackground(panel(surface, 5));
        v.setOnClickListener(x -> action.run());
        return v;
    }
    private void space(LinearLayout target, int amount) {
        View v = new View(this);
        target.addView(v, new LinearLayout.LayoutParams(1, dp(amount)));
    }
    private GradientDrawable rounded(int start,int finish,int radius){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{start,finish});
        d.setCornerRadius(dp(radius));
        if(!paper)d.setStroke(dp(1),Color.rgb(49,91,73));
        return d;
    }
    private TextView consoleControl(String label,Runnable action){
        TextView b=text(label,14,ink,true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12),dp(12),dp(12),dp(12));
        b.setBackground(rounded(surface,paper?surface:Color.rgb(14,33,27),19));
        b.setOnClickListener(v->action.run());
        return b;
    }
    private void createConsole() {
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(19),dp(19),dp(19),dp(22));
        root.setBackgroundColor(background);
        setContentView(root);

        LinearLayout top=new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand= new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title=text("✦   Londe",24,ink,true);
        brand.addView(title);
        TextView tag=text("P R I V A T E   V O I C E   A S S I S T A N T",
                10,secondary,true);
        brand.addView(tag);
        top.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(consoleControl("✕",this::finish),
                new LinearLayout.LayoutParams(dp(52),dp(49)));
        root.addView(top);
        caption=text("VOICE ASSISTANT  ·  READY",11,secondary,true);
        caption.setLetterSpacing(.10f);
        caption.setPadding(dp(4),dp(12),0,0);
        root.addView(caption);

        ScrollView content=new ScrollView(this);
        content.setFillViewport(false);
        content.setVerticalScrollBarEnabled(false);
        LinearLayout body=new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0,dp(4),0,dp(12));
        content.addView(body);
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));

        FrameLayout core=new FrameLayout(this);
        body.addView(core,new LinearLayout.LayoutParams(-1,dp(258)));
        orb=new LondeOrbView(this);
        orb.setPalette(background,accent);
        orb.setState("ready");
        FrameLayout.LayoutParams orbParams=new FrameLayout.LayoutParams(
                dp(292),dp(255),Gravity.CENTER);
        core.addView(orb,orbParams);
        orb.setContentDescription("Tap Londe to start voice recognition");
        orb.setOnClickListener(v->ensureListening());

        status=text("READY TO LISTEN",24,ink,true);
        status.setGravity(Gravity.CENTER);
        body.addView(status);
        lastRequest=text("Speak naturally with Londe.",13,secondary,false);
        lastRequest.setGravity(Gravity.CENTER);
        lastRequest.setMaxLines(3);
        lastRequest.setPadding(dp(10),dp(9),dp(10),dp(9));
        body.addView(lastRequest);

        LinearLayout replyCard=new LinearLayout(this);
        replyCard.setOrientation(LinearLayout.VERTICAL);
        replyCard.setPadding(dp(17),dp(15),dp(17),dp(15));
        replyCard.setBackground(rounded(surface,
                paper?surface:Color.rgb(14,31,26),23));
        TextView replyLabel=text("✦  LONDE  ·  RESPONSE",12,accent,true);
        replyLabel.setLetterSpacing(.04f);
        replyCard.addView(replyLabel);

        answerScroll=new ScrollView(this);
        answerScroll.setVerticalScrollBarEnabled(false);
        answer=text("Ask a question, or ask me to do something on your phone.",
                16,ink,false);
        answer.setLineSpacing(dp(4),1f);
        answer.setPadding(0,dp(12),0,dp(8));
        answerScroll.addView(answer);
        replyCard.addView(answerScroll,new LinearLayout.LayoutParams(-1,dp(155)));
        LinearLayout.LayoutParams replyParams=new LinearLayout.LayoutParams(-1,-2);
        replyParams.topMargin=dp(16);
        body.addView(replyCard,replyParams);

        keyboardPanel=new LinearLayout(this);
        keyboardPanel.setGravity(Gravity.CENTER_VERTICAL);
        keyboard=new EditText(this);
        keyboard.setTextColor(ink);
        keyboard.setHintTextColor(secondary);
        keyboard.setHint("Ask or give a command…");
        keyboard.setSingleLine(true);
        keyboard.setTextSize(15);
        keyboard.setImeOptions(EditorInfo.IME_ACTION_GO);
        keyboard.setPadding(dp(14),dp(12),dp(14),dp(12));
        keyboard.setBackground(rounded(surface,surface,15));
        keyboardPanel.addView(keyboard,new LinearLayout.LayoutParams(0,dp(52),1));
        TextView send=consoleControl("SEND",this::submitKeyboard);
        LinearLayout.LayoutParams sendParams=new LinearLayout.LayoutParams(dp(70),dp(52));
        sendParams.leftMargin=dp(8);
        keyboardPanel.addView(send,sendParams);
        keyboard.setOnEditorActionListener((v,action,event)->{
            if(action==EditorInfo.IME_ACTION_GO){submitKeyboard();return true;}
            return false;
        });
        keyboardPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams inputParams=new LinearLayout.LayoutParams(-1,dp(54));
        inputParams.bottomMargin=dp(10);
        root.addView(keyboardPanel,inputParams);

        actionRow=new LinearLayout(this);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        keyboardToggle=consoleControl("⌨  TYPE",this::toggleKeyboard);
        actionRow.addView(keyboardToggle,new LinearLayout.LayoutParams(0,dp(57),1));
        microphone=consoleControl("🎙  TALK",this::ensureListening);
        microphone.setBackground(rounded(
                paper?accent:Color.rgb(32,91,65),
                paper?accent:Color.rgb(26,125,77),24));
        microphone.setOnLongClickListener(v->{stopListening();return true;});
        LinearLayout.LayoutParams micParams=new LinearLayout.LayoutParams(0,dp(57),1.25f);
        micParams.leftMargin=dp(9);
        actionRow.addView(microphone,micParams);
        speaker=consoleControl("◖))  ON",this::toggleSound);
        speaker.setOnLongClickListener(v->{voiceSettings();return true;});
        LinearLayout.LayoutParams soundParams=new LinearLayout.LayoutParams(0,dp(57),1);
        soundParams.leftMargin=dp(9);
        actionRow.addView(speaker,soundParams);
        root.addView(actionRow);

        LinearLayout shortcuts=new LinearLayout(this);
        shortcuts.setGravity(Gravity.CENTER_VERTICAL);
        String[] actions={"◎  FOCUS","▦  APPS","◈  TRAIN"};
        Runnable[] run={
                ()->accept("Start focus for 25 minutes"),
                ()->accept("Show all apps"),
                ()->accept("Start a workout for 30 minutes")
        };
        for(int i=0;i<actions.length;i++){
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(43),1);
            if(i>0)p.leftMargin=dp(7);
            TextView control=consoleControl(actions[i],run[i]);
            control.setTextSize(11);
            shortcuts.addView(control,p);
        }
        LinearLayout.LayoutParams shortcutsParams=new LinearLayout.LayoutParams(-1,dp(43));
        shortcutsParams.topMargin=dp(12);
        root.addView(shortcuts,shortcutsParams);

        TextView footer=text("LOCAL BY DEFAULT  ·  ONLINE GPT NEEDS SPOKEN PERMISSION",
                10,secondary,false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0,dp(13),0,0);
        root.addView(footer);
    }

    private void setPhase(Phase next, String label) {
        phase=next;
        status.setText(label);
        caption.setText("VOICE ASSISTANT  ·  "+next.name());
        microphone.setText(activeSession ? "◉  LISTENING" : "🎙  TALK");
        if(orb!=null)orb.setState(next.name().toLowerCase(Locale.ROOT));
    }
    private void say(String value) {
        if (thinkingHum != null) thinkingHum.stop();
        if (!readAloud) return;
        String shortened = value.length()>750 ? value.substring(0,750)+"…" : value;
        if (useLocalVoice() && LocalNeuralVoice.modelPackaged(this)) {
            neuralVoice.speak(shortened, (success,error) -> {
                if (!success && onlineConsent.pending()) {
                    onlineConsent.cancel();
                    showAnswer("The voice model couldn't ask permission. Nothing was sent online.");
                    setPhase(Phase.READY, "VOICE PERMISSION REQUEST FAILED");
                    return;
                }
                if (!success) {
                    status.setText("LOCAL VOICE ERROR");
                    toast(error == null ? "Local voice unavailable" : error);
                }
                voiceFinished();
            });
            return;
        }
        if (!speechReady || speech == null) {
            voiceFinished();
            return;
        }
        String preferred = getSharedPreferences("londe_voice_v1", MODE_PRIVATE)
                .getString("tts_voice", "");
        String spoken = AndroidSpeechOutput.prepare(speech, shortened, preferred);
        int result=speech.speak(spoken, TextToSpeech.QUEUE_FLUSH, null,
                "londe-"+SystemClock.uptimeMillis());
        if(result==TextToSpeech.ERROR) {
            if (onlineConsent.pending()) {
                onlineConsent.cancel();
                showAnswer("Speech playback failed. No GPT request was sent.");
                setPhase(Phase.READY,"VOICE PERMISSION REQUEST FAILED");
            } else voiceFinished();
        }
    }
    private boolean canSpeak() {
        return readAloud && ((useLocalVoice() && LocalNeuralVoice.modelPackaged(this)) || speechReady);
    }
    private String answerSource = "LONDE · LOCAL TOOLS";
    private void showAnswer(String result) {
        currentAnswer=result;
        answer.setText("SOURCE  /  " + answerSource + "\n\n" + result);
        answerScroll.post(()->answerScroll.scrollTo(0,0));
    }
    private void localAnswer(String request,String response) {
        lastRequest.setText("YOU  /  "+request);
        showAnswer(response);
        setPhase(Phase.SPEAKING,"DONE");
        say(response);
        if(!canSpeak())voiceFinished();
    }
    private void submitKeyboard() {
        String input=keyboard.getText().toString().trim();
        if (input.isEmpty()) return;
        keyboard.setText("");
        keyboardPanel.setVisibility(View.GONE);
        keyboardToggle.setText("⌨  TYPE");
        accept(input);
    }
    private void toggleKeyboard() {
        boolean show=keyboardPanel.getVisibility()!=View.VISIBLE;
        keyboardPanel.setVisibility(show?View.VISIBLE:View.GONE);
        keyboardToggle.setText(show?"⌨  CLOSE":"⌨  TYPE");
        if(show){keyboard.requestFocus();}
        else keyboard.clearFocus();
    }
    private void toggleSound() {
        if (onlineConsent.pending()) {
            onlineConsent.cancel();
            releaseRecognizer();
            showAnswer("Voice permission request cancelled. No data was sent online.");
            setPhase(Phase.READY,"GPT PERMISSION CANCELLED");
        }
        readAloud=!readAloud;
        speaker.setText(readAloud?"◖))  ON":"◖))  OFF");
        if(!readAloud && speech!=null)speech.stop();
        if(!readAloud && neuralVoice!=null)neuralVoice.stop();
    }
    private void voiceFinished(){
        if(!inForeground||isFinishing())return;
        if (onlineConsent.pending() && !readAloud) {
            onlineConsent.cancel();
            showAnswer("Voice is muted. GPT online permission was not granted.");
            setPhase(Phase.READY,"VOICE CONFIRMATION UNAVAILABLE");
            return;
        }
        if (onlineConsent.pending()) {
            // Speech output finished: only now open the microphone.
            if (!onlineConsent.listening()) {
                onlineConsent.markListening(SystemClock.elapsedRealtime());
                setPhase(Phase.READY, "SAY YES OR NO · VOICE ONLY");
                final int consentGeneration=requestGeneration;
                ui.postDelayed(() -> {
                    if (onlineConsent.pending() && consentGeneration==requestGeneration
                            && inForeground && !isFinishing()) startRecognition();
                }, 350);
                ui.postDelayed(() -> {
                    if (onlineConsent.pending() && consentGeneration==requestGeneration) {
                        onlineConsent.cancel();
                        releaseRecognizer();
                        answerSource="LONDE · OFFLINE ONLY";
                        localAnswer("GPT permission", "No voice confirmation received. I kept your question offline.");
                    }
                }, SpokenOnlineConsent.RESPONSE_WINDOW_MS);
            }
            return;
        }
        if (pendingVoiceNavigation != null) {
            Runnable next = pendingVoiceNavigation;
            pendingVoiceNavigation = null;
            next.run();
            return;
        }
        if(activeSession&&!busy){
            setPhase(Phase.READY,"YOUR TURN");
            ui.postDelayed(()->{if(activeSession&&!busy&&inForeground)startRecognition();},550);
        } else if(!busy) setPhase(Phase.READY,"TAP TO SPEAK");
    }
    private void startWakeDiagnostic() {
        if (wakeDiagnostic != null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            setPhase(Phase.READY, "WAKE TEST · MIC PERMISSION");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_WAKE_TEST_MIC);
            return;
        }
        if (!LondeKeywordDetector.available(this)) {
            setPhase(Phase.READY, "HEY LONDE MODEL NOT INSTALLED");
            showAnswer("Open Quiet Settings → LONDE ASSISTANT → Download Hey Londe detector. Install it, then reopen this test.");
            return;
        }
        LondeVoiceService.pauseWakeForSession();
        final int id = ++wakeDiagnosticGeneration;
        setPhase(Phase.LISTENING, "LOADING LOCAL WAKE MODEL…");
        showAnswer("Foreground keyword test. Say 'Hey Londe' clearly. It should detect the phrase without a tap. Tap TALK to switch to normal speech recognition.");
        work.execute(() -> {
            OfflineWakePipeline.Detector detector;
            try {
                detector = new LondeKeywordDetector(this);
            } catch (Throwable error) {
                ui.post(() -> {
                    if (id != wakeDiagnosticGeneration || isFinishing()) return;
                    setPhase(Phase.READY, "WAKE MODEL LOAD FAILED");
                    showAnswer("Offline wake model failed to load: " +
                            error.getClass().getSimpleName() + ". This is separate from speech recognition.");
                });
                return;
            }
            ui.post(() -> {
                if (id != wakeDiagnosticGeneration || isFinishing() || !inForeground) {
                    detector.close();
                    return;
                }
                OfflineWakePipeline pipeline = new OfflineWakePipeline(this,
                        new OfflineWakePipeline.Events() {
                            @Override public void onWake() {
                                wakeDiagnostic = null;
                                ++wakeDiagnosticGeneration;
                                setPhase(Phase.READY, "HEY LONDE DETECTED!");
                                showAnswer("Wake phrase detected locally. Starting speech recognition…");
                                ui.postDelayed(LondeActivity.this::ensureListening, 500);
                            }
                            @Override public void onAudioLevel(int level) {
                                if(wakeDiagnostic!=null && !isFinishing())
                                    setPhase(Phase.LISTENING,
                                            "WAKE TEST · MIC " + level + "% · SAY HEY LONDE");
                            }
                            @Override public void onError(String reason) {
                                wakeDiagnostic = null;
                                setPhase(Phase.READY, "WAKE MICROPHONE ERROR");
                                showAnswer("Wake-word test failed: " + reason);
                            }
                        });
                if (pipeline.start(detector)) {
                    wakeDiagnostic = pipeline;
                    setPhase(Phase.LISTENING, "SAY 'HEY LONDE' NOW");
                } else {
                    detector.close();
                    setPhase(Phase.READY, "WAKE TEST COULD NOT START");
                    showAnswer("Failed to start wake microphone. Check Quiet's microphone permission.");
                }
            });
        });
    }

    private void stopWakeDiagnostic() {
        ++wakeDiagnosticGeneration;
        if (wakeDiagnostic != null) {
            wakeDiagnostic.stop();
            wakeDiagnostic = null;
        }
    }

    /** Start-only: automatic activation or a quick second tap cannot stop listening. */
    private void ensureListening() {
        if (isFinishing() || isDestroyed()) return;
        stopWakeDiagnostic();
        if (activeSession) {
            if (busy || phase == Phase.SPEAKING) return;
            if (recognizerRunning) {
                if (!recognizerReady &&
                        SystemClock.uptimeMillis() - recognizerStartedAt > 3000L) {
                    // A second tap is a deliberate recovery from a stuck STARTING state.
                    releaseRecognizer();
                    setPhase(Phase.READY, "RESTARTING SPEECH SERVICE…");
                    ui.postDelayed(this::startRecognition, 450);
                } else {
                    setPhase(Phase.LISTENING, recognizerReady
                            ? "LISTENING · SPEAK NOW" : "STARTING SPEECH SERVICE…");
                }
            } else {
                startRecognition();
            }
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            setPhase(Phase.READY, "MICROPHONE PERMISSION REQUIRED");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        boolean systemAvailable = SpeechRecognizer.isRecognitionAvailable(this);
        boolean offlineAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
        if (!systemAvailable && !offlineAvailable) {
            setPhase(Phase.READY, "SPEECH SERVICE NOT INSTALLED");
            showAnswer("Android has no speech-recognition service. Enable or install your phone's Speech Recognition & Synthesis service. TYPE still works.");
            return;
        }
        // Default system recognition normally has the most reliable language
        // support on Samsung. On-device availability does not guarantee en-IN
        // or Hindi models are actually downloaded.
        usingOnDeviceRecognizer = !systemAvailable && offlineAvailable;
        triedAlternateRecognizer = usingOnDeviceRecognizer;
        activeSession = true;
        recognitionFailures = 0;
        releaseRecognizer();
        LondeVoiceService.pauseWakeForSession();
        setPhase(Phase.READY, "PREPARING SPEECH SERVICE…");
        ui.postDelayed(this::startRecognition, 550);
    }

    private void releaseRecognizer() {
        recognizerGeneration++;
        recognitionAttempt++;
        recognizerRunning = false;
        recognizerReady = false;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
    }

    /** Long-press TALK is an explicit stop. */
    private void stopListening() {
        stopWakeDiagnostic();
        onlineConsent.cancel();
        activeSession = false;
        releaseRecognizer();
        if (speech != null) speech.stop();
        if (neuralVoice != null) neuralVoice.stop();
        setPhase(Phase.READY, "SESSION STOPPED · TAP TO RESTART");
    }

    private String recognizerIssue(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "Speech-service network timeout";
            case SpeechRecognizer.ERROR_NETWORK: return "Speech service needs a connection";
            case SpeechRecognizer.ERROR_AUDIO: return "Microphone unavailable or in use";
            case SpeechRecognizer.ERROR_SERVER: return "Speech server unavailable";
            case SpeechRecognizer.ERROR_CLIENT: return "Speech service interrupted";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "No speech heard";
            case SpeechRecognizer.ERROR_NO_MATCH: return "Speech heard but no words recognized";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "Speech recognizer busy";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Microphone permission denied";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS: return "Too many speech requests";
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED: return "Speech recognizer disconnected";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: return "Speech language unsupported";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "Speech language pack not downloaded";
            default: return "Speech recognition error " + error;
        }
    }

    private void stopWithSpeechError(String reason) {
        onlineConsent.cancel();
        activeSession = false;
        releaseRecognizer();
        setPhase(Phase.READY, "SPEECH INPUT NEEDS ATTENTION");
        showAnswer(reason + ". Tap TALK to try again, or use TYPE. Check Android microphone access and the default Speech Recognition & Synthesis service.");
    }

    private boolean useOfflineAlternative(String reason) {
        if (usingOnDeviceRecognizer || triedAlternateRecognizer
                || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) return false;
        triedAlternateRecognizer = true;
        usingOnDeviceRecognizer = true;
        releaseRecognizer();
        setPhase(Phase.READY, reason + " · TRYING ON-DEVICE SPEECH");
        ui.postDelayed(this::startRecognition, 1000);
        return true;
    }

    private void speechError(int error, int generation) {
        if (generation != recognizerGeneration || !activeSession || !inForeground) return;
        recognizerRunning = false;
        recognizerReady = false;
        recognitionAttempt++; // Disarm both watchdog callbacks.

        String reason = recognizerIssue(error);
        if (onlineConsent.pending()) {
            onlineConsent.cancel();
            releaseRecognizer();
            answerSource="LONDE · OFFLINE ONLY";
            localAnswer("GPT permission", "I couldn't hear a clear voice confirmation. I won't use GPT online.");
            return;
        }
        android.util.Log.w("LondeSpeech", "Recognizer error " + error + " · " + reason +
                " · offline=" + usingOnDeviceRecognizer);

        if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            recognitionFailures++;
            if (recognitionFailures >= 2) {
                stopWithSpeechError("I couldn't transcribe what you said after two attempts");
            } else {
                setPhase(Phase.READY, reason.toUpperCase(Locale.ROOT) + " · RETRYING ONCE");
                ui.postDelayed(this::startRecognition, 1300);
            }
            return;
        }
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                error == SpeechRecognizer.ERROR_AUDIO) {
            stopWithSpeechError(reason);
            return;
        }
        if (useOfflineAlternative(reason)) return;
        stopWithSpeechError(reason);
    }

    private void startRecognition() {
        if (!activeSession || busy || !inForeground || recognizerRunning
                || phase == Phase.SPEAKING) return;
        if (recognizer == null) {
            try {
                recognizer = usingOnDeviceRecognizer
                        ? SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                        : SpeechRecognizer.createSpeechRecognizer(this);
                final int generation = ++recognizerGeneration;
                recognizer.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle b) {
                        if (generation != recognizerGeneration || !activeSession) return;
                        recognizerReady = true;
                        setPhase(Phase.LISTENING, onlineConsent.pending()
                                ? "GPT PERMISSION · SAY YES OR NO" : "LISTENING · SPEAK NOW");
                    }
                    @Override public void onBeginningOfSpeech() {
                        if (generation != recognizerGeneration) return;
                        silenceCount = 0;
                        setPhase(Phase.LISTENING, "HEARING YOUR VOICE…");
                    }
                    @Override public void onRmsChanged(float v) {}
                    @Override public void onBufferReceived(byte[] b) {}
                    @Override public void onEndOfSpeech() {
                        if (generation == recognizerGeneration)
                            setPhase(Phase.THINKING, "TRANSCRIBING YOUR WORDS…");
                    }
                    @Override public void onError(int code) {
                        speechError(code, generation);
                    }
                    @Override public void onResults(Bundle b) {
                        if (generation != recognizerGeneration || !activeSession) return;
                        recognizerRunning = false;
                        recognizerReady = false;
                        recognitionAttempt++;
                        ArrayList<String> words = b.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION);
                        if (words != null && !words.isEmpty() &&
                                words.get(0) != null && !words.get(0).trim().isEmpty()) {
                            recognitionFailures = 0;
                            String heard=words.get(0).trim();
                            if (onlineConsent.pending()) handleSpokenConsent(heard);
                            else accept(heard);
                        } else {
                            speechError(SpeechRecognizer.ERROR_NO_MATCH, generation);
                        }
                    }
                    @Override public void onPartialResults(Bundle b) {}
                    @Override public void onEvent(int type, Bundle extras) {}
                });
            } catch (Exception error) {
                android.util.Log.e("LondeSpeech", "Speech engine init failed", error);
                if (!useOfflineAlternative("System speech service unavailable"))
                    stopWithSpeechError("Android could not initialize speech recognition");
                return;
            }
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        // Do not force en-IN: the installed recognizer may lack that language.
        // Allow the user's configured speech language, plus detection on newer
        // Android versions when the provider supports it.
        if (android.os.Build.VERSION.SDK_INT >= 34)
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
        final int generation = recognizerGeneration;
        final int attempt = ++recognitionAttempt;
        recognizerReady = false;
        recognizerRunning = true;
        recognizerStartedAt = SystemClock.uptimeMillis();
        setPhase(Phase.LISTENING, usingOnDeviceRecognizer
                ? "STARTING OFFLINE SPEECH SERVICE…" : "STARTING SYSTEM SPEECH SERVICE…");
        try {
            recognizer.startListening(intent);
        } catch (Exception error) {
            android.util.Log.e("LondeSpeech", "Speech engine start failed", error);
            if (!useOfflineAlternative("System speech service failed"))
                stopWithSpeechError("Speech service could not start");
            return;
        }
        ui.postDelayed(() -> {
            if (generation != recognizerGeneration || attempt != recognitionAttempt ||
                    !activeSession || !inForeground || !recognizerRunning ||
                    recognizerReady) return;
            android.util.Log.w("LondeSpeech", "Speech recognizer never became ready");
            if (!useOfflineAlternative("System speech startup timed out"))
                stopWithSpeechError("Speech service never became ready");
        }, SPEECH_START_TIMEOUT_MS);

        // Protect against a recognizer that reports onReady but never delivers
        // results or an error. Avoid an endlessly stuck LISTENING state.
        ui.postDelayed(() -> {
            if (generation != recognizerGeneration || attempt != recognitionAttempt ||
                    !activeSession || !inForeground || !recognizerRunning) return;
            stopWithSpeechError("Speech recognizer stopped responding");
        }, 23000L);
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if (request == REQUEST_CALENDAR) {
            String question=pendingCalendarQuestion;
            pendingCalendarQuestion=null;
            if (question != null) {
                if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                    answerCalendar(question);
                else {
                    answerSource="ANDROID CALENDAR · PERMISSION REQUIRED";
                    localAnswer(question,
                            "Calendar permission was not granted. I can't check your meetings. You can enable Calendar access in Android App Settings.");
                }
            }
            return;
        }
        if (request == REQUEST_WAKE_TEST_MIC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                startWakeDiagnostic();
            else {
                setPhase(Phase.READY, "WAKE MIC PERMISSION DENIED");
                showAnswer("Grant Quiet microphone permission in Samsung App info → Permissions.");
            }
            return;
        }
        if(request==REQUEST_MIC&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)ensureListening();
        else if(request==REQUEST_MIC){setPhase(Phase.READY,"MIC PERMISSION DENIED");
            showAnswer("Please grant microphone access to Quiet in Android App info → Permissions.");}
    }
    @Override protected void onPause(){
        stopWakeDiagnostic();
        onlineConsent.cancel();
        inForeground=false;
        if (thinkingHum != null) thinkingHum.stop();
        releaseRecognizer();
        super.onPause();
    }
    @Override protected void onResume(){
        super.onResume();inForeground=true;
        LondeVoiceService.pauseWakeForSession();
        if(activeSession&&!busy)ui.postDelayed(()->startRecognition(),550);
    }

    private void answerCalendar(String question) {
        answerSource = "ANDROID CALENDAR · ON-DEVICE";
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            pendingCalendarQuestion = question;
            setPhase(Phase.READY, "CALENDAR PERMISSION REQUIRED");
            showAnswer("I need Calendar permission to check your actual meetings. Android will ask for access. I will not invent your schedule.");
            requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, REQUEST_CALENDAR);
            return;
        }
        busy = true;
        setPhase(Phase.THINKING, "READING YOUR CALENDAR");
        final int id=++requestGeneration;
        work.execute(() -> {
            String result=LocalCalendarReader.read(this,question);
            ui.post(() -> {
                if (isFinishing() || id != requestGeneration) return;
                busy=false;
                answerSource="ANDROID CALENDAR · ON-DEVICE";
                localAnswer(question,result);
            });
        });
    }

    private void accept(String question) {
        if(busy)return;
        if (onlineConsent.pending()) {
            // TYPE never substitutes for spoken approval. Only fresh STT may approve.
            answerSource="LONDE · AWAITING SPOKEN PERMISSION";
            showAnswer("Please answer aloud with yes or no. No question has been sent online.");
            return;
        }
        String q=question==null?"":question.trim();
        if(q.isEmpty())return;
        if(q.length()>1500){localAnswer("Input","Please keep requests under 1500 characters.");return;}
        lastRequest.setText("YOU  /  "+q);
        answerSource = "LONDE · LOCAL TOOLS";
        String simple = q.toLowerCase(Locale.ROOT).trim().replaceAll("[.!?।]+$", "");
        if (simple.matches("(?:hey londe[, ]+)?(?:stop londe|stop listening|go to sleep|बस कर|बंद हो जा|सो जा|chup ho ja|londe bas|londe stop)")) {
            activeSession = false;
            if (recognizer != null) { recognizer.cancel(); recognizerRunning = false; }
            setPhase(Phase.READY, "GOING TO SLEEP");
            showAnswer("Okay. Say Hey Londe when you need me.");
            say("ठीक है भाई, फिर बुला लेना।");
            ui.postDelayed(this::finish, 2400);
            return;
        }
        String remembered = LocalMemoryStore.command(this, q);
        if (remembered != null) {
            if (LocalMemoryPolicy.parse(q).command == LocalMemoryPolicy.Command.FORGET_ALL) {
                memory.clear();
                onlineMemory.clear();
            }
            answerSource = "LONDE · PRIVATE ON-DEVICE MEMORY";
            localAnswer(q, remembered);
            return;
        }
        if (PhoneAssistantActions.tryHandle(this, q, response -> {
            answerSource="ANDROID · PHONE SKILLS";
            localAnswer(q,response);
        })) return;
        if (CalendarIntent.isPersonalSchedule(q)) {
            answerCalendar(q);
            return;
        }
        LocalFirstRouter.Decision routing = LocalFirstRouter.route(q);
        if (routing.kind == LocalFirstRouter.Kind.LOCAL_ANSWER) {
            long focusUntil = getSharedPreferences("quiet_local_v1", MODE_PRIVATE)
                    .getLong("focusUntil", 0L);
            localAnswer(q, LocalFirstRouter.localAnswer(routing,
                    System.currentTimeMillis(), focusUntil, Locale.getDefault()));
            return;
        }
        if (routing.kind == LocalFirstRouter.Kind.LOCAL_ACTION) {
            LondeDeviceActions.Result executed =
                    LondeDeviceActions.execute(this, routing.action);
            if (executed.handled) {
                if (executed.navigation != null) {
                    Intent destination = executed.navigation;
                    activeSession = false;
                    pendingVoiceNavigation = () -> {
                        try { startActivity(destination); }
                        catch (Exception error) { toast("Unable to launch requested app"); }
                        finish();
                    };
                }
                localAnswer(q, executed.spoken);
                return;
            }
            setPhase(Phase.ACTION, "ACTION TO REVIEW");
            showAnswer("That action needs review in Quiet before it can run.");
            say("I'll show the action for review.");
            ui.postDelayed(() -> handoff(routing.action), 550);
            return;
        }
        LocalMemoryStore.countQuestion(this, q);
        if (LocalBrainModels.installed(this) &&
                getSharedPreferences("quiet_local_v1", MODE_PRIVATE)
                    .getBoolean("londe_local_brain_enabled", true)) {
            busy = true;
            thinkingHum.begin();
            answerSource = "QWEN3 0.6B · ON-DEVICE";
            setPhase(Phase.THINKING, "LOCAL AI · QWEN3");
            showAnswer("Thinking on your phone · no internet needed…");
            StringBuilder context = new StringBuilder();
            int first = Math.max(0, memory.size() - 6);
            for (int i = first; i < memory.size(); i++) {
                LondeClient.Turn turn = memory.get(i);
                context.append(turn.role).append(": ")
                        .append(turn.text.length() > 180 ?
                                turn.text.substring(0, 180) : turn.text)
                        .append("\n");
            }
            final int id = ++requestGeneration;
            LocalBrain.ask(this, q, context.toString(), new LocalBrain.Callback() {
                @Override public void onResult(String text, String action, boolean fallback,
                                               float speed) {
                    if (isFinishing() || id != requestGeneration) return;
                    busy = false;
                    thinkingHum.stop();
                    if (fallback) {
                        askChatGptFallback(q);
                        return;
                    }
                    if (action != null) {
                        String permitted = LondeCommands.normalize(action);
                        if (permitted == null) {
                            askChatGptFallback(q);
                            return;
                        }
                        setPhase(Phase.ACTION, "LOCAL AI · REVIEW ACTION");
                        showAnswer("Local model suggests: " + permitted +
                                "\nReview this action in Quiet before anything runs.");
                        say("I'll show the action for your review.");
                        ui.postDelayed(() -> handoff(permitted), 650);
                        return;
                    }
                    LocalSessionContext.record(q, text);
                    memory.add(new LondeClient.Turn("user", q));
                    memory.add(new LondeClient.Turn("assistant", text));
                    while (memory.size() > 24) memory.remove(0);
                    showAnswer(text);
                    setPhase(Phase.SPEAKING,
                            String.format(Locale.ROOT, "OFFLINE QWEN3 · %.1f TOK/S", speed));
                    say(text);
                    if (!canSpeak()) voiceFinished();
                }
                @Override public void onError(String reason) {
                    if (isFinishing() || id != requestGeneration) return;
                    busy = false;
                    thinkingHum.stop();
                    android.util.Log.w("LondeLocalBrain", "Local Qwen failed: " + reason);
                    askChatGptFallback(q);
                }
            });
            return;
        }
        askChatGptFallback(q);
    }

    /** Qwen failure is never silently passed to an unavailable cloud service. */
    private void askChatGptFallback(String q) {
        if (!CloudAvailability.online(this)) {
            answerSource = "QUIET · OFFLINE FALLBACK STATUS";
            localAnswer(q, LocalReplyPolicy.offlineMessage());
            setPhase(Phase.READY, "OFFLINE · NO CHATGPT REQUEST");
            return;
        }
        if (!ChatGptAuth.canUsePlan(this)) {
            answerSource = "QUIET · CHATGPT UNAVAILABLE";
            localAnswer(q, LocalReplyPolicy.unauthorizedMessage());
            setPhase(Phase.READY, "CHATGPT NOT AUTHORIZED");
            return;
        }
        beginSpokenOnlineConsent(q);
    }

    private void beginSpokenOnlineConsent(String question) {
        if (isFinishing() || isDestroyed() || !inForeground) return;
        if (!CloudAvailability.online(this) || !ChatGptAuth.canUsePlan(this)) {
            answerSource="LONDE · OFFLINE ONLY";
            localAnswer(question, "GPT online is unavailable. I won't send your question.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
                || (!SpeechRecognizer.isRecognitionAvailable(this)
                    && !SpeechRecognizer.isOnDeviceRecognitionAvailable(this))) {
            answerSource="LONDE · OFFLINE ONLY";
            showAnswer("Voice confirmation isn't available. Your question stays offline.");
            setPhase(Phase.READY,"VOICE CONFIRMATION UNAVAILABLE");
            return;
        }
        activeSession=true;
        releaseRecognizer();
        LondeVoiceService.pauseWakeForSession();
        // No button, remembered permission, or model-generated assent can approve.
        if (!canSpeak()) {
            answerSource="LONDE · OFFLINE ONLY";
            showAnswer("Voice output isn't ready, so I can't ask your permission aloud. Nothing was sent.");
            setPhase(Phase.READY, "VOICE OUTPUT UNAVAILABLE");
            return;
        }
        onlineConsent.begin(question);
        answerSource="LONDE · ASKING FOR ONLINE PERMISSION";
        String prompt="I couldn't answer that offline. Shall I use GPT online? Please say yes or no.";
        showAnswer(prompt);
        setPhase(Phase.SPEAKING,"SPOKEN PERMISSION REQUEST");
        say(prompt);
    }

    private void handleSpokenConsent(String heard) {
        if (!onlineConsent.pending()) return;
        SpokenOnlineConsent.Reply decision=SpokenOnlineConsent.interpret(heard);
        String question=decision==SpokenOnlineConsent.Reply.YES
                ? onlineConsent.approve(SystemClock.elapsedRealtime()) : null;
        if (decision!=SpokenOnlineConsent.Reply.YES || question==null) {
            onlineConsent.cancel();
            answerSource="LONDE · OFFLINE ONLY";
            localAnswer("GPT permission", decision==SpokenOnlineConsent.Reply.NO
                    ? "Okay, I'll keep it offline."
                    : "I didn't hear a clear yes. Nothing was sent online.");
            return;
        }
        if (!CloudAvailability.online(this) || !ChatGptAuth.canUsePlan(this)) {
            answerSource="LONDE · OFFLINE ONLY";
            localAnswer("GPT permission", "GPT isn't available now. Your question wasn't sent.");
            return;
        }
        answerSource="CHATGPT · ONLINE · VOICE APPROVED";
        askChatGpt(question);
    }

    private void askChatGpt(String q) {
        // All automatic callers must pass through spoken user consent.
        if(!CloudAvailability.online(this) || !ChatGptAuth.canUsePlan(this)){
            showAnswer("Connect your ChatGPT account in Quiet Settings to answer questions.");
            setPhase(Phase.READY,"NOT CONNECTED");
            return;
        }
        answerSource = "CHATGPT · ONLINE";
        busy=true;
        thinkingHum.begin();
        setPhase(Phase.THINKING,"THINKING…");
        showAnswer("Working on it…");
        onlineMemory.add(new LondeClient.Turn("user",q));
        if(onlineMemory.size()>24)onlineMemory.remove(0);
        List<LondeClient.Turn> context=new ArrayList<>(onlineMemory);
        final int id=++requestGeneration;
        work.execute(()->{
            try{
                LondeClient.Answer result=LondeClient.ask(this,context,model,partial->
                    ui.post(()->{if(id==requestGeneration&&!isFinishing())showStreaming(partial);}));
                ui.post(()->{
                    if(id!=requestGeneration||isFinishing())return;
                    model=result.model;
                    thinkingHum.stop();
                    answerSource = "CHATGPT · " + result.model + " · ONLINE";
                    onlineMemory.add(new LondeClient.Turn("assistant",result.text));
                    if(onlineMemory.size()>24)onlineMemory.remove(0);
                    memory.add(new LondeClient.Turn("user",q));
                    memory.add(new LondeClient.Turn("assistant",result.text));
                    while(memory.size()>24)memory.remove(0);
                    showAnswer(result.text);
                    busy=false;
                    setPhase(Phase.SPEAKING,"REPLY READY");
                    say(result.text);
                    if(!canSpeak())voiceFinished();
                });
            }catch(Exception ex){
                ui.post(()->{
                    if(id!=requestGeneration||isFinishing())return;
                    // Never narrate raw OpenAI connection exceptions to the user.
                    android.util.Log.w("LondeChatFallback", "ChatGPT request failed", ex);
                    thinkingHum.stop();
                    showAnswer(LocalReplyPolicy.cloudFailedMessage());
                    busy=false;
                    setPhase(Phase.READY,"TRY AGAIN");
                });
            }
        });
    }
    private void showStreaming(String partial) {
        if(phase!=Phase.THINKING)return;
        currentAnswer=partial;
        answer.setText("SOURCE  /  " + answerSource + "\n\n" + partial);
    }
    private boolean looksLikeAction(String q) {
        // Explicit imperative commands only. Questions go to the assistant model.
        return q.matches("^(?:please |could you |can you |hey londe |londe )?" +
            "(?:open|launch|start|stop|begin|show|list|take me to|set up)\\b.*")
            || q.matches("^(?:i want to |i need to )?(?:focus|work ?out|train)\\b.*")
            || q.matches("^(?:whatsapp|instagram|maps|camera) (?:kholo|khol)\\b.*");
    }
    private void handoff(String command) {
        if(isFinishing()||isDestroyed())return;
        Intent intent=new Intent(this,MainActivity.class);
        intent.putExtra("londe_action",command);
        startActivity(intent);
        finish();
    }

    @Override protected void onDestroy() {
        onlineConsent.cancel();
        stopWakeDiagnostic();
        LondeVoiceService.rearmWake();
        requestGeneration++;
        work.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        activeSession=false;
        releaseRecognizer();
        if(thinkingHum!=null)thinkingHum.close();
        if(neuralVoice!=null)neuralVoice.close();
        if(speech!=null){speech.stop();speech.shutdown();}
        super.onDestroy();
    }
    private void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_LONG).show();}

}
