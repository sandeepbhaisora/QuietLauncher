package dev.quiet.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.speech.tts.TextToSpeech;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Londe: assistant chat surface; device actions are delegated for review. */
public final class LondeActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<LondeClient.Turn> history = new ArrayList<>();
    private LinearLayout messages;
    private ScrollView scroll;
    private EditText prompt;
    private TextView send;
    private boolean busy;
    private int generation;
    private TextToSpeech speech;
    private boolean speechReady, speakReplies;
    private String model = "";

    private int bg = Color.rgb(13,16,14), card = Color.rgb(27,33,29),
        ink = Color.rgb(230,236,228), muted = Color.rgb(165,176,165),
        accent = Color.rgb(168,197,161);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        boolean paper = "paper".equals(getSharedPreferences("quiet_local_v1",MODE_PRIVATE)
                .getString("theme","ink"));
        if (paper) {
            setTheme(R.style.AppThemePaper);
            bg = Color.rgb(242,240,233);card = Color.rgb(230,229,220);
            ink = Color.rgb(36,48,37);muted = Color.rgb(85,101,86);
            accent = Color.rgb(83,108,80);
        }
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildUi();
        speech = new TextToSpeech(this, status -> {
            speechReady = status == TextToSpeech.SUCCESS;
            if (speechReady) speech.setLanguage(Locale.getDefault());
        });
        addBubble(false,"Hey, I'm Londe. Ask me anything. For phone actions, use Do — I'll ask before running them.");
    }

    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable shape(int fill){
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill);d.setCornerRadius(dp(5));return d;
    }
    private TextView control(String label,Runnable action){
        TextView t=new TextView(this);t.setText(label);t.setTextColor(ink);t.setTextSize(13);
        t.setGravity(Gravity.CENTER);t.setPadding(dp(14),dp(13),dp(14),dp(13));
        t.setBackground(shape(card));t.setOnClickListener(v->action.run());return t;
    }
    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);root.setPadding(dp(14),dp(10),dp(14),dp(10));
        setContentView(root);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back=control("‹",this::finish);
        header.addView(back,new LinearLayout.LayoutParams(dp(44),dp(45)));
        TextView title=new TextView(this);title.setText("LONDE  /  ASSISTANT");
        title.setTextSize(17);title.setTypeface(null,Typeface.BOLD);title.setTextColor(ink);
        title.setPadding(dp(12),0,0,0);
        header.addView(title,new LinearLayout.LayoutParams(0,dp(45),1));
        header.addView(control("CLEAR",()->new AlertDialog.Builder(this)
            .setTitle("Clear conversation?")
            .setMessage("Clear the on-screen chat context? This does not revoke ChatGPT access.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Clear",(d,w)->{
                generation++;busy=false;history.clear();messages.removeAllViews();
                addBubble(false,"Fresh chat. What are we doing?");refreshSend();
            }).show()));
        root.addView(header);
        TextView privacy=new TextView(this);
        privacy.setText("Powered by your connected ChatGPT account · Context in this chat only");
        privacy.setTextSize(11);privacy.setTextColor(muted);
        privacy.setPadding(dp(5),dp(10),0,dp(10));root.addView(privacy);
        scroll=new ScrollView(this);scroll.setFillViewport(false);
        messages=new LinearLayout(this);messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(dp(2),dp(8),dp(2),dp(8));
        scroll.addView(messages);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout inputRow=new LinearLayout(this);inputRow.setGravity(Gravity.CENTER_VERTICAL);
        prompt=new EditText(this);prompt.setTextColor(ink);prompt.setHintTextColor(muted);
        prompt.setHint("Ask Londe anything…");prompt.setTextSize(15);
        prompt.setMinLines(1);prompt.setMaxLines(4);prompt.setSingleLine(false);
        prompt.setImeOptions(EditorInfo.IME_ACTION_SEND);
        prompt.setBackground(shape(card));prompt.setPadding(dp(12),dp(12),dp(12),dp(12));
        inputRow.addView(prompt,new LinearLayout.LayoutParams(0,-2,1));
        send=control("SEND",this::ask);
        LinearLayout.LayoutParams sendParams=new LinearLayout.LayoutParams(-2,dp(50));
        sendParams.leftMargin=dp(6);inputRow.addView(send,sendParams);
        root.addView(inputRow);
        LinearLayout actions=new LinearLayout(this);
        LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(-1,dp(45));
        actionParams.topMargin=dp(8);
        TextView voice=control("🎙  SPEAK",this::voice);
        actions.addView(voice,new LinearLayout.LayoutParams(0,-1,1));
        final TextView[] readRef = new TextView[1];
        TextView read=control("🔊  READ: OFF",()->{
            speakReplies=!speakReplies;
            if(!speakReplies&&speech!=null)speech.stop();
            readRef[0].setText(speakReplies?"🔊  READ: ON":"🔊  READ: OFF");
        });
        readRef[0]=read;
        LinearLayout.LayoutParams middle=new LinearLayout.LayoutParams(0,-1,1);
        middle.leftMargin=dp(6);actions.addView(read,middle);
        TextView doAction=control("DO  ↗",this::requestAction);
        LinearLayout.LayoutParams last=new LinearLayout.LayoutParams(0,-1,1);
        last.leftMargin=dp(6);actions.addView(doAction,last);
        root.addView(actions,actionParams);
        prompt.setOnEditorActionListener((v,action,event)->{
            if(action==EditorInfo.IME_ACTION_SEND){ask();return true;}return false;
        });
    }

    private TextView addBubble(boolean user,String content){
        TextView bubble=new TextView(this);
        bubble.setText(content);bubble.setTextColor(ink);bubble.setTextSize(15);
        bubble.setPadding(dp(14),dp(13),dp(14),dp(13));
        bubble.setBackground(shape(user?Color.rgb(52,75,57):card));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,-2);
        params.gravity=user?Gravity.END:Gravity.START;
        params.setMargins(user?dp(38):dp(4),dp(7),user?dp(4):dp(25),dp(7));
        messages.addView(bubble,params);
        scroll.post(()->scroll.fullScroll(ScrollView.FOCUS_DOWN));
        return bubble;
    }
    private void refreshSend(){
        send.setText(busy?"···":"SEND");
        send.setAlpha(busy?0.5f:1f);
    }
    private void ask(){
        if(busy)return;
        String q=prompt.getText().toString().trim();
        if(q.isEmpty())return;
        if(q.length()>1500){toast("Keep your message under 1500 characters");return;}
        if(!ChatGptAuth.canUsePlan(this)){
            new AlertDialog.Builder(this).setTitle("Connect ChatGPT")
                .setMessage("Connect ChatGPT in Quiet Settings first, then return here.")
                .setPositiveButton("Settings",(d,w)->{
                    Intent intent=new Intent(this,MainActivity.class);
                    intent.putExtra("londe_settings",true);startActivity(intent);
                }).setNegativeButton("Cancel",null).show();return;
        }
        prompt.setText("");addBubble(true,q);
        history.add(new LondeClient.Turn("user",q));
        if(history.size()>25)history.remove(0);
        TextView reply=addBubble(false,"Thinking…");
        List<LondeClient.Turn> context=new ArrayList<>(history);
        busy=true;refreshSend();
        int request=++generation;
        worker.execute(()->{
            try{
                LondeClient.Answer answer=LondeClient.ask(this,context,model,partial->
                    ui.post(()->{if(request==generation&&!isFinishing())reply.setText(partial);}));
                ui.post(()->{
                    if(request!=generation||isFinishing())return;
                    model=answer.model;
                    reply.setText(answer.text);
                    history.add(new LondeClient.Turn("assistant",answer.text));
                    if(history.size()>25)history.remove(0);
                    busy=false;refreshSend();
                    if(speakReplies&&speechReady)speech.speak(answer.text,
                        TextToSpeech.QUEUE_FLUSH,null,"londe-answer");
                });
            }catch(Exception ex){
                ui.post(()->{
                    if(request!=generation||isFinishing())return;
                    String problem=ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage();
                    reply.setText("Couldn't answer: "+problem);
                    busy=false;refreshSend();
                });
            }
        });
    }
    private void requestAction(){
        if(busy){toast("Finish the current reply first");return;}
        EditText entry=new EditText(this);
        entry.setText(prompt.getText());
        entry.setHint("Start focus for 30 minutes");
        entry.setPadding(dp(16),dp(15),dp(16),dp(15));
        new AlertDialog.Builder(this).setTitle("Ask Quiet to do something")
            .setMessage("Device actions use Quiet's separate planner. You'll review actions before they run.")
            .setView(entry).setNegativeButton("Cancel",null)
            .setPositiveButton("Preview",(d,w)->{
                String command=entry.getText().toString().trim();
                if(command.isEmpty()){toast("Enter an action");return;}
                Intent intent=new Intent(this,MainActivity.class);
                intent.putExtra("londe_action",command);
                startActivity(intent);
            }).show();
    }
    private void voice(){
        Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT,"Speak to Londe");
        try{startActivityForResult(intent,510);}catch(Exception ex){
            toast("Speech recognizer isn't available on this device");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==510&&result==RESULT_OK&&data!=null){
            ArrayList<String> matches=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(matches!=null&&!matches.isEmpty()){
                prompt.setText(matches.get(0));prompt.setSelection(prompt.length());
                ask();
            }
        }
    }
    @Override protected void onDestroy(){
        generation++;
        worker.shutdownNow();ui.removeCallbacksAndMessages(null);
        if(speech!=null){speech.stop();speech.shutdown();}
        super.onDestroy();
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
