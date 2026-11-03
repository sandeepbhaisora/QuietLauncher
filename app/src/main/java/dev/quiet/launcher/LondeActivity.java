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
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final List<LondeClient.Turn> memory = new ArrayList<>();
    private TextToSpeech speech;
    private LocalNeuralVoice neuralVoice;
    private SpeechRecognizer recognizer;
    private boolean activeSession, recognizerRunning, inForeground=true;
    private String selectedEngine="";
    private int silenceCount;
    private boolean speechReady, readAloud = true, busy;
    private int requestGeneration = 0;
    private String model = "";
    private String currentAnswer = "";
    private Runnable pendingVoiceNavigation;
    private EditText keyboard;
    private TextView answer, caption, status, lastRequest, microphone, speaker, keyboardToggle;
    private LinearLayout keyboardPanel, actionRow;
    private ScrollView answerScroll;
    private Orb orb;
    private int background, surface, ink, secondary, accent;
    private boolean paper;
    private enum Phase { READY, LISTENING, THINKING, SPEAKING, ACTION }
    private Phase phase = Phase.READY;

    @Override public void onCreate(Bundle saved) {
        paper = "paper".equals(getSharedPreferences("quiet_local_v1", MODE_PRIVATE)
                .getString("theme", "ink"));
        setTheme(paper ? R.style.LondeOverlayPaper : R.style.LondeOverlayDark);
        super.onCreate(saved);
        background = paper ? Color.rgb(237,236,228) : Color.rgb(17,21,19);
        surface = paper ? Color.rgb(224,228,217) : Color.rgb(32,40,35);
        ink = paper ? Color.rgb(35,47,37) : Color.rgb(232,238,231);
        secondary = paper ? Color.rgb(91,110,93) : Color.rgb(158,176,160);
        accent = paper ? Color.rgb(70,116,73) : Color.rgb(157,215,168);
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.BOTTOM;
        lp.dimAmount = 0.64f;
        window.setAttributes(lp);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        createConsole();
        selectedEngine=getSharedPreferences("londe_voice_v1",MODE_PRIVATE).getString("tts_engine","");
        neuralVoice = new LocalNeuralVoice(this);
        if (getSharedPreferences("londe_voice_v1", MODE_PRIVATE)
                .getBoolean("neural_crash_warning_pending", false)) {
            getSharedPreferences("londe_voice_v1", MODE_PRIVATE).edit()
                    .remove("neural_crash_warning_pending").apply();
            new AlertDialog.Builder(this).setTitle("Local voice failed")
                    .setMessage("Quiet detected an interrupted neural speech session and switched to Android voice to prevent repeated crashes. You can try neural voice again in settings.")
                    .setPositiveButton("OK", null).show();
        }

        initSpeech();
        if (getIntent() != null && Intent.ACTION_ASSIST.equals(getIntent().getAction()))
            getIntent().putExtra("auto_listen", true);
        if (getIntent() != null && getIntent().getBooleanExtra("auto_listen", false))
            ui.postDelayed(this::listen, 450);
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
                        ? "Offline voice active. Testing Hindi..." : "Voice model missing from this APK.");
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
                if(activeSession)listen();
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
                if(activeSession)listen();
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
    private void createConsole() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(14), dp(20), dp(22));
        root.setBackground(panel(background, 10));
        setContentView(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("LONDE  /  QUIET OS", 15, ink, true);
        brand.setLetterSpacing(.06f);
        top.addView(brand, new LinearLayout.LayoutParams(0, dp(36), 1));
        top.addView(button("✕", this::finish), new LinearLayout.LayoutParams(dp(50), dp(40)));
        root.addView(top);
        caption = text("VOICE ASSISTANT  ·  READY", 11, secondary, true);
        caption.setLetterSpacing(.11f);
        root.addView(caption);

        FrameLayout core = new FrameLayout(this);
        LinearLayout.LayoutParams coreParams = new LinearLayout.LayoutParams(-1, dp(200));
        coreParams.topMargin = dp(4);
        root.addView(core, coreParams);
        orb = new Orb();
        FrameLayout.LayoutParams orbLayout =
            new FrameLayout.LayoutParams(dp(220), dp(200), Gravity.CENTER);
        core.addView(orb, orbLayout);
        orb.setContentDescription("Londe voice control. Tap to speak.");
        orb.setOnClickListener(v -> listen());

        status = text("TAP TO SPEAK", 16, ink, true);
        status.setGravity(Gravity.CENTER);
        status.setLetterSpacing(.06f);
        root.addView(status);
        lastRequest = text("Your assistant, not another chat app.", 12, secondary, false);
        lastRequest.setGravity(Gravity.CENTER);
        lastRequest.setMaxLines(2);
        lastRequest.setPadding(dp(12),dp(8),dp(12),dp(4));
        root.addView(lastRequest);

        answerScroll = new ScrollView(this);
        answerScroll.setFillViewport(false);
        answer = text("Ask a question, or tell me what to do on your phone.", 15, ink, false);
        answer.setLineSpacing(dp(4),1f);
        answer.setGravity(Gravity.CENTER_HORIZONTAL);
        answer.setPadding(dp(16),dp(13),dp(16),dp(13));
        answerScroll.addView(answer);
        LinearLayout.LayoutParams answerParams = new LinearLayout.LayoutParams(-1,-2);
        answerParams.topMargin=dp(12);
        root.addView(answerScroll,answerParams);
        answerScroll.setBackground(panel(surface, 5));
        answerScroll.setMinimumHeight(dp(73));
        answerScroll.setVerticalScrollBarEnabled(false);
        // Avoid growing into a second full-screen chat window.
        answerScroll.getLayoutParams().height = dp(170);

        keyboardPanel = new LinearLayout(this);
        keyboardPanel.setGravity(Gravity.CENTER_VERTICAL);
        keyboard = new EditText(this);
        keyboard.setTextColor(ink); keyboard.setHintTextColor(secondary);
        keyboard.setHint("Ask or give a command...");
        keyboard.setTextSize(15); keyboard.setSingleLine(true);
        keyboard.setImeOptions(EditorInfo.IME_ACTION_GO);
        keyboard.setPadding(dp(12),dp(12),dp(12),dp(12));
        keyboard.setBackground(panel(surface, 5));
        keyboardPanel.addView(keyboard,new LinearLayout.LayoutParams(0,dp(49),1));
        TextView go = button("GO  →",this::submitKeyboard);
        LinearLayout.LayoutParams goParams=new LinearLayout.LayoutParams(dp(72),dp(49));
        goParams.leftMargin=dp(7);
        keyboardPanel.addView(go,goParams);
        keyboard.setOnEditorActionListener((v,action,event)->{
            if(action==EditorInfo.IME_ACTION_GO){submitKeyboard();return true;}return false;
        });
        keyboardPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-1,dp(51));
        kp.topMargin=dp(10);
        root.addView(keyboardPanel,kp);

        actionRow = new LinearLayout(this);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1,dp(52));
        actionParams.topMargin=dp(12);
        microphone = button("◉  TALK", this::listen);
        actionRow.addView(microphone,new LinearLayout.LayoutParams(0,-1,1));
        keyboardToggle = button("⌨  TYPE",this::toggleKeyboard);
        LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(0,-1,1);
        mid.leftMargin=dp(7);actionRow.addView(keyboardToggle,mid);
        speaker = button("◖))  ON",this::toggleSound);
        speaker.setOnLongClickListener(v->{voiceSettings();return true;});
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0,-1,1);
        right.leftMargin=dp(7);actionRow.addView(speaker,right);
        root.addView(actionRow,actionParams);

        LinearLayout shortcuts=new LinearLayout(this);
        shortcuts.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sh=new LinearLayout.LayoutParams(-1,dp(46));
        sh.topMargin=dp(9);
        TextView focus=button("FOCUS 25",()->accept("Start focus for 25 minutes"));
        shortcuts.addView(focus,new LinearLayout.LayoutParams(0,-1,1));
        TextView apps=button("APPS",()->accept("Show all apps"));
        LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(0,-1,1);
        a.leftMargin=dp(7);shortcuts.addView(apps,a);
        TextView train=button("TRAIN 30",()->accept("Start a workout for 30 minutes"));
        LinearLayout.LayoutParams tr=new LinearLayout.LayoutParams(0,-1,1);
        tr.leftMargin=dp(7);shortcuts.addView(train,tr);
        root.addView(shortcuts,sh);
        TextView footer=text("LONG-PRESS SOUND FOR VOICES  ·  ACTIONS NEED APPROVAL",
                10,secondary,false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0,dp(12),0,0);
        root.addView(footer);
    }

    private void setPhase(Phase next, String label) {
        phase=next;
        status.setText(label);
        caption.setText("VOICE ASSISTANT  ·  "+next.name());
        microphone.setText(activeSession ? "■  STOP" : "◉  TALK");
        orb.invalidate();
    }
    private void say(String value) {
        if (!readAloud) return;
        String shortened = value.length()>750 ? value.substring(0,750)+"…" : value;
        if (useLocalVoice()) {
            neuralVoice.speak(shortened, (success,error) -> {
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
        String spoken = HindiSpeech.prepare(shortened,
                speech.getVoice()!=null ? speech.getVoice().getLocale() : Locale.getDefault());
        int result=speech.speak(spoken, TextToSpeech.QUEUE_FLUSH, null,
                "londe-"+SystemClock.uptimeMillis());
        if(result==TextToSpeech.ERROR)voiceFinished();
    }
    private boolean canSpeak() {
        return readAloud && (useLocalVoice() || speechReady);
    }
    private void showAnswer(String result) {
        currentAnswer=result;
        answer.setText(result);
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
        readAloud=!readAloud;
        speaker.setText(readAloud?"◖))  ON":"◖))  OFF");
        if(!readAloud && speech!=null)speech.stop();
        if(!readAloud && neuralVoice!=null)neuralVoice.stop();
    }
    private void voiceFinished(){
        if(!inForeground||isFinishing())return;
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
    private void listen(){
        if(activeSession){
            activeSession=false;
            if(recognizer!=null){recognizer.cancel();recognizerRunning=false;}
            if(speech!=null)speech.stop();
            if(neuralVoice!=null)neuralVoice.stop();
            setPhase(Phase.READY,"SESSION STOPPED");return;
        }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQUEST_MIC);return;
        }
        if(!SpeechRecognizer.isRecognitionAvailable(this)){
            toast("Speech recognition unavailable. Try TYPE.");return;
        }
        activeSession=true;silenceCount=0;startRecognition();
    }
    private void startRecognition(){
        if(!activeSession||busy||!inForeground||recognizerRunning)return;
        if(recognizer==null){
            recognizer=SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
                ? SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                : SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener(){
                @Override public void onReadyForSpeech(Bundle b){setPhase(Phase.LISTENING,"LISTENING…");}
                @Override public void onBeginningOfSpeech(){silenceCount=0;}
                @Override public void onRmsChanged(float v){}
                @Override public void onBufferReceived(byte[] b){}
                @Override public void onEndOfSpeech(){setPhase(Phase.THINKING,"PROCESSING…");}
                @Override public void onError(int code){
                    recognizerRunning=false;
                    if(!activeSession||!inForeground)return;
                    if(code==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS||++silenceCount>=5){
                        activeSession=false;setPhase(Phase.READY,"SESSION PAUSED");return;
                    }
                    ui.postDelayed(()->startRecognition(),900);
                }
                @Override public void onResults(Bundle b){
                    recognizerRunning=false;
                    ArrayList<String> words=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if(words!=null&&!words.isEmpty()){silenceCount=0;accept(words.get(0));}
                    else if(activeSession)ui.postDelayed(()->startRecognition(),550);
                }
                @Override public void onPartialResults(Bundle b){}
                @Override public void onEvent(int type,Bundle extras){}
            });
        }
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN");
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            // Where the recognizer supports it, understand both Hindi and
            // Indian English in the same hands-free voice session.
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                    RecognizerIntent.LANGUAGE_SWITCH_BALANCED);
            ArrayList<String> languages = new ArrayList<>();
            languages.add("hi-IN");
            languages.add("en-IN");
            i.putStringArrayListExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, languages);
        }
        try{recognizerRunning=true;recognizer.startListening(i);}
        catch(Exception e){recognizerRunning=false;activeSession=false;setPhase(Phase.READY,"MIC UNAVAILABLE");}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==REQUEST_MIC&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)listen();
    }
    @Override protected void onPause(){
        inForeground=false;
        if(recognizer!=null){recognizer.cancel();recognizerRunning=false;}
        super.onPause();
    }
    @Override protected void onResume(){
        super.onResume();inForeground=true;
        if(activeSession&&!busy)ui.postDelayed(()->startRecognition(),550);
    }

    private void accept(String question) {
        if(busy)return;
        String q=question==null?"":question.trim();
        if(q.isEmpty())return;
        if(q.length()>1500){localAnswer("Input","Please keep requests under 1500 characters.");return;}
        lastRequest.setText("YOU  /  "+q);
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
        String deviceResult = LondeSystemCommands.tryExecute(this, q);
        if (deviceResult != null) {
            localAnswer(q, deviceResult);
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
        if(!ChatGptAuth.canUsePlan(this)){
            showAnswer("Connect your ChatGPT account in Quiet Settings to answer questions.");
            setPhase(Phase.READY,"NOT CONNECTED");
            return;
        }
        busy=true;
        setPhase(Phase.THINKING,"THINKING…");
        showAnswer("Working on it…");
        memory.add(new LondeClient.Turn("user",q));
        if(memory.size()>24)memory.remove(0);
        List<LondeClient.Turn> context=new ArrayList<>(memory);
        final int id=++requestGeneration;
        work.execute(()->{
            try{
                LondeClient.Answer result=LondeClient.ask(this,context,model,partial->
                    ui.post(()->{if(id==requestGeneration&&!isFinishing())showStreaming(partial);}));
                ui.post(()->{
                    if(id!=requestGeneration||isFinishing())return;
                    model=result.model;
                    memory.add(new LondeClient.Turn("assistant",result.text));
                    if(memory.size()>24)memory.remove(0);
                    showAnswer(result.text);
                    busy=false;
                    setPhase(Phase.SPEAKING,"REPLY READY");
                    say(result.text);
                    if(!canSpeak())voiceFinished();
                });
            }catch(Exception ex){
                ui.post(()->{
                    if(id!=requestGeneration||isFinishing())return;
                    // Do not expose token, headers, request bodies or internal stack traces.
                    String message=ex.getMessage()==null?"Connection unavailable":ex.getMessage();
                    if(message.length()>160)message=message.substring(0,160);
                    showAnswer("I couldn't complete that: "+message);
                    busy=false;
                    setPhase(Phase.READY,"TRY AGAIN");
                });
            }
        });
    }
    private void showStreaming(String partial) {
        if(phase!=Phase.THINKING)return;
        currentAnswer=partial;
        answer.setText(partial);
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
        LondeVoiceService.rearmWake();
        requestGeneration++;
        work.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        activeSession=false;
        if(recognizer!=null)recognizer.destroy();
        if(neuralVoice!=null)neuralVoice.close();
        if(speech!=null){speech.stop();speech.shutdown();}
        super.onDestroy();
    }
    private void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_LONG).show();}

    /** Lightweight pulsing listening indicator drawn natively, not an image asset. */
    private final class Orb extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Orb() {super(LondeActivity.this);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx=getWidth()/2f,cy=getHeight()/2f;
            float unit=Math.min(getWidth(),getHeight());
            boolean active=phase==Phase.LISTENING||phase==Phase.THINKING
                    ||phase==Phase.SPEAKING;
            float pulse=active?(float)Math.sin(SystemClock.uptimeMillis()/250.0):0f;
            float r=unit*.215f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            for(int i=0;i<3;i++){
                paint.setColor(accent);
                paint.setAlpha((int)((active?115:45)/(i+1)));
                canvas.drawCircle(cx,cy,r+dp(22+i*16)+(active?pulse*dp(3+i*2):0),paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(accent);paint.setAlpha(36);
            canvas.drawCircle(cx,cy,r+dp(12),paint);
            paint.setColor(accent);paint.setAlpha(210);
            canvas.drawCircle(cx,cy,r+(active?pulse*dp(2):0),paint);
            paint.setColor(background);paint.setAlpha(235);
            canvas.drawCircle(cx,cy,r*.60f,paint);
            paint.setColor(accent);paint.setAlpha(240);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            paint.setTextSize(dp(23));
            canvas.drawText(phase==Phase.LISTENING?"···":phase==Phase.THINKING?"✦":"◉",
                    cx,cy+dp(8),paint);
            paint.setAlpha(255);
            if(active&&isAttachedToWindow())postInvalidateDelayed(42);
        }
    }
}
