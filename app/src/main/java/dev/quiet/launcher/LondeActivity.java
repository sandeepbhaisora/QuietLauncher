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
import android.speech.tts.TextToSpeech;
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
    private static final int REQUEST_SPEECH = 510;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final List<LondeClient.Turn> memory = new ArrayList<>();
    private TextToSpeech speech;
    private boolean speechReady, readAloud = true, busy;
    private int requestGeneration = 0;
    private String model = "";
    private String currentAnswer = "";
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
        speech = new TextToSpeech(this, state -> {
            speechReady = state == TextToSpeech.SUCCESS;
            if (speechReady) speech.setLanguage(Locale.getDefault());
        });
        if (getIntent() != null && getIntent().getBooleanExtra("auto_listen", false))
            ui.postDelayed(this::listen, 450);
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
        answerScroll.setMaxScrollAmount(dp(180));
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
        TextView footer=text("MIC ACTIVE ONLY WHEN REQUESTED  ·  ACTIONS NEED APPROVAL",
                10,secondary,false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0,dp(12),0,0);
        root.addView(footer);
    }

    private void setPhase(Phase next, String label) {
        phase=next;
        status.setText(label);
        caption.setText("VOICE ASSISTANT  ·  "+next.name());
        microphone.setText(next==Phase.THINKING ? "··· WAIT" : "◉  TALK");
        orb.invalidate();
    }
    private void say(String value) {
        if (!readAloud || !speechReady) return;
        String spoken=value.length()>750 ? value.substring(0,750)+"…" : value;
        speech.speak(spoken,TextToSpeech.QUEUE_FLUSH,null,"londe-"+SystemClock.uptimeMillis());
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
        ui.postDelayed(()->{if(phase==Phase.SPEAKING)setPhase(Phase.READY,"TAP TO SPEAK");},1000);
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
    }
    private void listen() {
        if (busy) {toast("Londe is still working on your last request");return;}
        if (speech!=null)speech.stop();
        Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT,"Speak to Londe");
        setPhase(Phase.LISTENING,"LISTENING…");
        try {startActivityForResult(intent,REQUEST_SPEECH);}
        catch(Exception ex){setPhase(Phase.READY,"MIC UNAVAILABLE");
            toast("Android speech recognition isn't available. Tap TYPE instead.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=REQUEST_SPEECH)return;
        if(result!=RESULT_OK || data==null){
            setPhase(Phase.READY,"TAP TO SPEAK");return;
        }
        ArrayList<String> heard=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if(heard==null || heard.isEmpty()){
            setPhase(Phase.READY,"DIDN'T CATCH THAT");return;
        }
        accept(heard.get(0));
    }

    private void accept(String question) {
        if(busy)return;
        String q=question==null?"":question.trim();
        if(q.isEmpty())return;
        if(q.length()>1500){localAnswer("Input","Please keep requests under 1500 characters.");return;}
        lastRequest.setText("YOU  /  "+q);
        String normalized=q.toLowerCase(Locale.ROOT).replace('’','\'');
        if(normalized.matches(".*\\b(what(?:'s| is)?|tell me|current|now)\\b.*\\btime\\b.*")
                || normalized.matches(".*\\btime (?:right )?now\\b.*")) {
            localAnswer(q,"It's "+new SimpleDateFormat("h:mm a",Locale.getDefault())
                    .format(new Date())+".");
            return;
        }
        if(normalized.matches(".*\\b(what(?:'s| is)?|today's|which)\\b.*\\b(date|day)\\b.*")) {
            localAnswer(q,"Today is "+new SimpleDateFormat("EEEE, d MMMM yyyy",
                Locale.getDefault()).format(new Date())+".");
            return;
        }
        if(looksLikeAction(normalized)) {
            setPhase(Phase.ACTION,"ACTION TO REVIEW");
            showAnswer("I can help with that. Quiet will show you the action before anything runs.");
            say("I can do that. Please review the action.");
            ui.postDelayed(()->handoff(q),550);
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
                    ui.postDelayed(()->{if(!busy && phase==Phase.SPEAKING)
                        setPhase(Phase.READY,"TAP TO SPEAK");},1400);
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
        requestGeneration++;
        work.shutdownNow();
        ui.removeCallbacksAndMessages(null);
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
