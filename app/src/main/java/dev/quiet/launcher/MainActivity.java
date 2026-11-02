package dev.quiet.launcher;

import android.app.Activity;
import android.app.SearchManager;
import android.speech.RecognizerIntent;
import android.content.ActivityNotFoundException;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Quiet: distraction-aware home replacement. No analytics, network SDK or external dependencies. */
public final class MainActivity extends Activity {
    private static final int WIDGET_HOST_ID = 31091;
    private static final int REQUEST_PICK_WIDGET = 200;
    private static final int REQUEST_CONFIGURE_WIDGET = 201;
    private static final int REQUEST_AUTH = 202;
    private static final int REQUEST_VOICE_COMMAND = 204;
    private static final String PREFS = "quiet_local_v1";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private PackageManager pm;
    private AppWidgetHost widgetHost;
    private AppWidgetManager widgetManager;
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private static final String[] MAIN_PAGES = {"home", "apps", "root", "inbox", "settings"};
    private boolean refreshRunning;
    private final Map<String, Integer> groupDisplayLimits = new HashMap<>();
    private final List<App> apps = new ArrayList<>();
    private final Map<String, Long> usage = new HashMap<>();
    private final Map<String, Boolean> expanded = new HashMap<>();
    private String pendingProtectedPackage;
    private int pendingWidgetId = -1;
    private String page = "home";
    private String theme = "ink";
    private int bg, surface, surface2, ink, muted, line, accent;
    private LinearLayout shell, main, top;
    private TextView focusLive, clockLive;
    private boolean hasUsage;
    private boolean initialResumePending = true;
    private boolean setupPromptScheduled;
    private long lastRefreshElapsed;

    private static final class App {
        final String name, packageName, group;
        final Intent launchIntent;
        App(String n, String p, String g, Intent intent) {
            name = n; packageName = p; group = g; launchIntent = intent;
        }
    }

    @Override public void onCreate(Bundle state) {
        // Select the OS theme before Activity creation. Platform AlertDialogs
        // inherit it, including multi-selects, buttons and text inputs.
        SharedPreferences startupPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if ("paper".equals(startupPrefs.getString("theme", "ink"))) {
            setTheme(R.style.AppThemePaper);
        } else {
            setTheme(R.style.AppTheme);
        }
        super.onCreate(state);
        prefs = startupPrefs;
        if (state != null) page = state.getString("quiet.page", "home");
        pm = getPackageManager();
        widgetManager = AppWidgetManager.getInstance(this);
        widgetHost = new AppWidgetHost(this, WIDGET_HOST_ID);
        setPalette();
        render();
        refreshAsync();
        if(getIntent()!=null){
            if(getIntent().getBooleanExtra("londe_settings",false))show("settings");
            else if(getIntent().hasExtra("londe_action"))
                handler.post(()->handleLondeAction(getIntent().getStringExtra("londe_action")));
            getIntent().removeExtra("londe_action");
            getIntent().removeExtra("londe_settings");
        }
        // A one-time welcome prompt, not a forced login. Existing installations
        // are also offered it once, without resetting any saved launcher data.
        if (state == null && !setupPromptScheduled) {
            setupPromptScheduled = true;
            handler.postDelayed(this::maybeShowWelcome, 800);
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                updateFocusClock();
                handler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if(intent==null)return;
        if(intent.getBooleanExtra("londe_settings",false)){
            show("settings");
        }else if(intent.hasExtra("londe_action")){
            String action=intent.getStringExtra("londe_action");
            handleLondeAction(action);
        }
        intent.removeExtra("londe_action");
        intent.removeExtra("londe_settings");
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("quiet.page", page);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        background.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onStart() {
        super.onStart();
        try { widgetHost.startListening(); } catch (Exception ignored) { }
    }

    @Override protected void onStop() {
        try { widgetHost.stopListening(); } catch (Exception ignored) { }
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        // Browser-based ChatGPT OAuth completes only after Quiet becomes active again.
        ChatGptAuth.onLauncherResumed();
        if (initialResumePending) {
            initialResumePending = false;
            return;
        }
        if (SystemClock.elapsedRealtime() - lastRefreshElapsed >= 15000) refreshAsync();
        else if ("home".equals(page)) render();
    }

    @Override protected void onPause() {
        ChatGptAuth.onLauncherPaused();
        super.onPause();
    }

    // Intentionally no activity-wide gesture interception in v0.2:
    // normal tapping, lists and scrolling should take precedence.
    private void navigateRelative(int delta) {
        for (int i = 0; i < MAIN_PAGES.length; i++) {
            if (MAIN_PAGES[i].equals(page)) {
                int target = i + delta;
                if (target >= 0 && target < MAIN_PAGES.length) show(MAIN_PAGES[target]);
                return;
            }
        }
        if (!"home".equals(page)) show("home");
    }

    private void refreshAsync() {
        if (refreshRunning || background.isShutdown()) return;
        refreshRunning = true;
        lastRefreshElapsed = SystemClock.elapsedRealtime();
        background.execute(() -> {
            List<App> resultApps = discoverApps();
            boolean allowed = hasUsageAccess();
            Map<String, Long> resultUsage = allowed ? discoverUsage() : new HashMap<>();
            handler.post(() -> {
                refreshRunning = false;
                if (isFinishing() || isDestroyed()) return;
                apps.clear();
                apps.addAll(resultApps);
                usage.clear();
                usage.putAll(resultUsage);
                hasUsage = allowed;
                // Never recreate the tree while someone is typing a search.
                if (!(getCurrentFocus() instanceof EditText)) render();
            });
        });
    }

    @Override public void onBackPressed() {
        if (!page.equals("home")) show("home");
        // Home screens should stay on screen, not reveal a blank Activity.
    }

    private int dp(float x) { return (int)(getResources().getDisplayMetrics().density * x + 0.5f); }
    private int c(String hex) { return Color.parseColor(hex); }
    private boolean isPaper() { return "paper".equals(theme); }
    private void setPalette() {
        theme = prefs.getString("theme", "ink");
        if (isPaper()) {
            bg = c("#F2F0E9"); surface = c("#FFFFFF"); surface2 = c("#EAE9E3");
            ink = c("#171D1A"); muted = c("#727970"); line = c("#D7DBD2"); accent = c("#E0E8DA");
        } else if ("black".equals(theme)) {
            bg = Color.BLACK; surface = c("#0F0F0F"); surface2 = c("#171717");
            ink = c("#F0F0EB"); muted = c("#979894"); line = c("#323232"); accent = c("#283329");
        } else {
            bg = c("#101311"); surface = c("#1B201D"); surface2 = c("#262C27");
            ink = c("#F2F2EB"); muted = c("#929E94"); line = c("#354038"); accent = c("#2D3D31");
        }
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(isPaper() ?
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
    }

    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        // Quiet's visual language is intentionally architectural: 4dp maximum,
        // never pill-shaped or oversized rounded corners.
        d.setColor(color);
        d.setCornerRadius(dp(Math.min(radius, 4)));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }
    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setFontFeatureSettings("kern");
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }
    private TextView label(String s) {
        TextView t = text(s.toUpperCase(Locale.ROOT), 11, muted, true);
        t.setLetterSpacing(0.15f);
        return t;
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout.LayoutParams block(int mt) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(mt); return p;
    }
    private void space(LinearLayout v, int height) { View spacer = new View(this); v.addView(spacer, new LinearLayout.LayoutParams(1, dp(height))); }
    private LinearLayout card() {
        LinearLayout layout = column(); layout.setPadding(dp(17), dp(16), dp(17), dp(16));
        layout.setBackground(shape(surface, 22, line)); return layout;
    }
    private void add(LinearLayout parent, View v, int top) { parent.addView(v, block(top)); }
    private TextView pill(String title, Runnable action) {
        TextView t = text(title, 12, ink, true);
        t.setGravity(Gravity.CENTER); t.setPadding(dp(13), dp(9), dp(13), dp(9));
        t.setBackground(shape(surface2, 25, line));
        if (action != null) t.setOnClickListener(v -> action.run());
        return t;
    }
    private void section(LinearLayout view, String title, String actionTitle, Runnable action) {
        LinearLayout r = row(); TextView h = label(title);
        r.addView(h, new LinearLayout.LayoutParams(0, -2, 1));
        if (actionTitle != null) {
            TextView a = text(actionTitle, 12, muted, true);
            a.setOnClickListener(v -> { if (action != null) action.run(); });
            r.addView(a);
        }
        add(view, r, 23);
    }

    private void show(String next) { if (next.equals(page) && shell != null) return; page = next; render(); }
    private void render() {
        if (prefs == null) return;
        setPalette(); focusLive = null; clockLive = null;
        shell = column(); shell.setBackgroundColor(bg);
        shell.setPadding(dp(18), dp(7), dp(18), dp(6));
        // Android 15+ draws behind system bars by default. Respect both insets.
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(18), insets.getSystemWindowInsetTop() + dp(7),
                    dp(18), insets.getSystemWindowInsetBottom() + dp(6));
            return insets;
        });
        top = row(); top.setPadding(dp(2), dp(5), dp(2), dp(12));
        TextView brand = text("QUIET /", 16, ink, true);
        brand.setLetterSpacing(0.08f);
        brand.setOnClickListener(v -> show("home"));
        top.addView(brand, new LinearLayout.LayoutParams(0, dp(35), 1));
        if (page.equals("home") || page.equals("apps") || page.equals("root")
                || page.equals("inbox") || page.equals("settings")) {
            TextView back = pill("‹", () -> navigateRelative(-1));
            back.setContentDescription("Previous screen");
            top.addView(back);
        }
        TextView tag = pill("○  " + page.toUpperCase(Locale.ROOT), null);
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(-2, -2);
        tagParams.leftMargin = dp(7);
        tagParams.rightMargin = dp(7);
        top.addView(tag, tagParams);
        if (page.equals("home") || page.equals("apps") || page.equals("root")
                || page.equals("inbox") || page.equals("settings")) {
            TextView next = pill("›", () -> navigateRelative(1));
            next.setContentDescription("Next screen");
            top.addView(next);
        }
        shell.addView(top);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false); scroll.setVerticalScrollBarEnabled(false);
        main = column(); main.setPadding(dp(1), 0, dp(1), dp(14));
        scroll.addView(main);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        switch (page) {
            case "home": renderAiHome(); break;
            case "classic": renderHome(); break;
            case "intent": renderAiHome(); break;
            case "apps": renderApps(""); break;
            case "root": renderRoot(); break;
            case "inbox": renderInbox(); break;
            case "usage": renderUsage(); break;
            case "widgets": renderWidgets(); break;
            default: renderSettings(); break;
        }
        shell.addView(bottomNav());
        // Build the whole screen detached, then attach once per navigation.
        setContentView(shell);
    }

    private LinearLayout bottomNav() {
        LinearLayout nav = row();
        nav.setPadding(dp(1), dp(7), dp(1), dp(3));
        View border = new View(this);
        border.setBackgroundColor(line);
        shell.addView(border, new LinearLayout.LayoutParams(-1, dp(1)));
        String[][] tabs = {{"⌂", "Home", "home"}, {"▦", "Apps", "apps"},
                {"◷", "Day", "root"}, {"▤", "Chats", "inbox"}, {"⚙", "Settings", "settings"}};
        for (String[] tab : tabs) {
            boolean active = page.equals(tab[2]) || (page.equals("widgets") && tab[2].equals("root"));
            LinearLayout cell = column();
            cell.setGravity(Gravity.CENTER);
            cell.setContentDescription(tab[1]);
            TextView symbol = text(tab[0], 19, active ? ink : muted, false);
            symbol.setGravity(Gravity.CENTER);
            TextView name = text(tab[1], 10, active ? ink : muted, active);
            name.setGravity(Gravity.CENTER);
            cell.addView(symbol);
            cell.addView(name);
            if (active) cell.setBackground(shape(surface2, 13, 0));
            nav.addView(cell, new LinearLayout.LayoutParams(0, dp(54), 1));
            cell.setOnClickListener(v -> show(tab[2]));
        }
        return nav;
    }

    private void openWorkspace() { startSafe(new Intent(this, WorkspaceActivity.class)); }


    /**
     * Intent-first UI with context grounded in on-device state. This is a local
     * command router and a visual assistant, NOT a running LLM or voice service.
     */
    private void renderAiHome() {
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        String greeting = hour < 12 ? "Good morning." : (hour < 17 ? "Good afternoon." : "Good evening.");
        String date = new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(now.getTime());
        LinearLayout meta = row();
        meta.addView(label(date.toUpperCase(Locale.ROOT)), new LinearLayout.LayoutParams(0, -2, 1));
        clockLive = text(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now.getTime()),
                15, muted, true);
        meta.addView(clockLive);
        add(main, meta, 13);

        LinearLayout hero = card();
        hero.setPadding(dp(14), dp(18), dp(14), dp(18));
        LinearLayout heroRow = row();
        LinearLayout copy = column();
        add(copy, label("QUIET  /  INTENT HOME"), 0);
        add(copy, text(greeting, 25, ink, true), 11);
        add(copy, text(focusActive() ? "One task at a time." : "What matters next?",
                17, ink, false), 7);
        add(copy, text("A calmer way to use your phone.", 12, muted, false), 7);
        heroRow.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        QuietFace face = new QuietFace();
        face.setContentDescription("Quiet mascot, a smiling friendly face");
        heroRow.addView(face, new LinearLayout.LayoutParams(dp(92), dp(108)));
        hero.addView(heroRow);
        add(main, hero, 13);

        // Keep the primary input obvious, functional, and independently scrollable.
        LinearLayout commandCard = card();
        add(commandCard, label("TELL QUIET WHAT TO DO"), 0);
        EditText command = new EditText(this);
        command.setSingleLine(true);
        command.setTextSize(15);
        command.setTextColor(ink);
        command.setHintTextColor(muted);
        command.setHint("Focus for 25 min, train, open Maps…");
        command.setImeOptions(EditorInfo.IME_ACTION_GO);
        command.setPadding(dp(12), dp(13), dp(12), dp(13));
        command.setBackground(shape(surface2, 4, line));
        add(commandCard, command, 11);
        Runnable execute = () -> runSmartCommand(command.getText().toString());
        command.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO) { execute.run(); return true; }
            return false;
        });
        LinearLayout commandButtons = row();
        TextView run = pill("PREVIEW / RUN  →", execute);
        commandButtons.addView(run, new LinearLayout.LayoutParams(0, dp(49), 1));
        TextView speak = pill("🎙  SPEAK", this::startVoiceInput);
        LinearLayout.LayoutParams micParams = new LinearLayout.LayoutParams(-2, dp(49));
        micParams.leftMargin = dp(8);
        commandButtons.addView(speak, micParams);
        run.setContentDescription("Run local command");
        add(commandCard, commandButtons, 11);
        TextView askAi = pill("ASK AI  ✧  OPTIONAL", () -> requestAiPlan(command.getText().toString()));
        add(commandCard, askAi, 9);
        TextView chat = pill("USE CHATGPT ACCOUNT  ✦",
                () -> requestChatGptPlan(command.getText().toString()));
        add(commandCard, chat, 9);
        add(commandCard, pill("ASK LONDE  ✦  CHAT", this::openLonde), 9);
        add(main, commandCard, 13);
        add(main, text("Local action router. Tap Speak for Android's speech service (it may use the network). No always-on listening.",
                11, muted, false), 8);

        section(main, "SUGGESTED FOR YOU", "VIEW ALL  ↗", () -> show("root"));
        boolean hasDraft = !getSharedPreferences("quiet_workspace_v1", MODE_PRIVATE)
                .getString("saved_text", "").trim().isEmpty();
        boolean hasWorkout = recentWorkoutTime() > 0;
        int activeSets = activeTrainingSets();
        if (focusActive()) {
            add(main, smartTile("CONTINUE FOCUS", "Your session is still running",
                    "◉", this::focusDialog), 10);
        } else if (activeSets > 0) {
            add(main, smartTile("FINISH YOUR WORKOUT", activeSets + " set(s) waiting to be saved",
                    "↗", this::openTraining), 10);
        } else if (hasDraft) {
            add(main, smartTile("RESUME WORKSPACE", "Continue your locally saved draft",
                    "↗", this::openWorkspace), 10);
        } else {
            add(main, smartTile(hour >= 17 ? "TRAIN FOR 30 MIN" : "START A FOCUS SESSION",
                    hour >= 17 ? "A balanced session for today" : "Create time for one important task",
                    "↗", hour >= 17 ? () -> startTraining(30) : this::focusDialog), 10);
        }

        LinearLayout topActions = row();
        topActions.addView(actionTile("TRAIN", "Workout plan",
                "▤", () -> openTraining()), new LinearLayout.LayoutParams(0, dp(104), 1));
        spaceHorizontal(topActions, 9);
        topActions.addView(actionTile("WORKSPACE", hasDraft ? "Resume draft" : "Read or write",
                "▧", this::openWorkspace), new LinearLayout.LayoutParams(0, dp(104), 1));
        add(main, topActions, 9);
        LinearLayout secondActions = row();
        secondActions.addView(actionTile("FOCUS", focusActive() ? "In progress" : "Protect your time",
                "◎", this::focusDialog), new LinearLayout.LayoutParams(0, dp(104), 1));
        spaceHorizontal(secondActions, 9);
        secondActions.addView(actionTile("APPS", "Everything installed",
                "▦", () -> show("apps")), new LinearLayout.LayoutParams(0, dp(104), 1));
        add(main, secondActions, 9);

        section(main, "YOUR CURRENT CONTEXT", null, null);
        LinearLayout context = card();
        if (focusActive()) {
            focusLive = text(remainingFocus(), 28, ink, true);
            add(context, label("FOCUS SESSION  ·  REMAINING"), 0);
            add(context, focusLive, 6);
            add(context, text("Tap to adjust or stop", 12, muted, false), 4);
            context.setOnClickListener(v -> focusDialog());
        } else if (activeSets > 0) {
            add(context, text(activeSets + " workout sets logged", 19, ink, true), 0);
            add(context, text("Your unfinished training session is saved on this phone.",
                    12, muted, false), 6);
            context.setOnClickListener(v -> openTraining());
        } else {
            add(context, text("A clean slate.", 19, ink, true), 0);
            add(context, text(hasWorkout ? "Your workouts are saved in Quiet Training." :
                    "Start a focus session or choose a useful action above.", 12, muted, false), 7);
        }
        add(main, context, 9);

        section(main, "QUICK ACCESS", "CLASSIC VIEW  ↗", () -> show("classic"));
        List<App> fav = favorites();
        if (!fav.isEmpty()) {
            LinearLayout quick = row();
            for (int i = 0; i < Math.min(3, fav.size()); i++) {
                App a = fav.get(i);
                TextView shortcut = pill(a.name, () -> open(a));
                shortcut.setSingleLine(true);
                shortcut.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
                if (i > 0) lp.leftMargin = dp(7);
                quick.addView(shortcut, lp);
            }
            add(main, quick, 9);
        }
        add(main, pill("OPEN CLASSIC HOME  ↗", () -> show("classic")), 13);
        space(main, 24);
    }

    private LinearLayout smartTile(String title, String detail, String icon, Runnable action) {
        LinearLayout tile = row();
        tile.setPadding(dp(15), dp(15), dp(15), dp(15));
        tile.setBackground(shape(accent, 4, line));
        LinearLayout info = column();
        add(info, text(title, 16, ink, true), 0);
        add(info, text(detail, 12, muted, false), 7);
        tile.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        tile.addView(text(icon, 24, ink, true));
        tile.setOnClickListener(v -> action.run());
        return tile;
    }

    private LinearLayout actionTile(String name, String subtitle, String symbol, Runnable action) {
        LinearLayout tile = column();
        tile.setPadding(dp(13), dp(13), dp(11), dp(11));
        tile.setBackground(shape(surface, 4, line));
        add(tile, text(symbol, 23, ink, false), 0);
        add(tile, text(name, 15, ink, true), 9);
        add(tile, text(subtitle, 11, muted, false), 5);
        tile.setOnClickListener(v -> action.run());
        tile.setContentDescription(name + ", " + subtitle);
        return tile;
    }

    private long recentWorkoutTime() {
        try {
            String raw = getSharedPreferences("quiet_training_v1", MODE_PRIVATE)
                    .getString("sessions", "[]");
            JSONArray sessions = new JSONArray(raw);
            JSONObject latest = sessions.optJSONObject(sessions.length() - 1);
            return latest == null ? 0L : latest.optLong("finished", 0);
        } catch (Exception ignored) { return 0L; }
    }

    private int activeTrainingSets() {
        try {
            String raw = getSharedPreferences("quiet_training_v1", MODE_PRIVATE)
                    .getString("active", "[]");
            return new JSONArray(raw).length();
        } catch (Exception ignored) { return 0; }
    }

    private void startTraining(int minutes) {
        if (minutes == 20 || minutes == 30 || minutes == 45) {
            getSharedPreferences("quiet_training_v1", MODE_PRIVATE)
                    .edit().putInt("minutes", minutes).apply();
        }
        openTraining();
    }

    private void maybeShowWelcome() {
        if (isFinishing() || isDestroyed() || prefs.getBoolean("welcome_chatgpt_v081", false))
            return;
        prefs.edit().putBoolean("welcome_chatgpt_v081", true).apply();
        if (ChatGptAuth.isConnected(this)) return;
        new AlertDialog.Builder(this)
            .setTitle("Welcome to Quiet")
            .setMessage("Keep your phone calm and useful. Connect ChatGPT to try AI planning, or continue offline. Sign-in is optional and you can connect later in Settings.")
            .setNegativeButton("Continue offline", null)
            .setPositiveButton("Connect ChatGPT", (d, w) -> beginChatGptSignIn())
            .show();
    }

    private void checkChatGptAuthInBrowser() {
        try {
            Intent check = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://auth.openai.com/.well-known/openid-configuration"));
            check.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(check);
        } catch (Exception ex) {
            Toast.makeText(this, "No browser could open the authentication test.",
                Toast.LENGTH_LONG).show();
        }
    }

    private void diagnoseChatGptConnection() {
        new AlertDialog.Builder(this).setTitle("Checking Quiet's network")
            .setMessage("Testing DNS and HTTPS inside the launcher, without sending credentials.")
            .setPositiveButton("OK",null).show();
        background.execute(() -> {
            String report = ChatGptAuth.diagnose(this);
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(this).setTitle("Quiet connection diagnostics")
                    .setMessage(report)
                    .setPositiveButton("OK",null)
                    .setNeutralButton("Copy", (d,w) -> {
                        android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("Quiet network report", report));
                    }).show();
            });
        });
    }

    private void beginChatGptSignIn() {
        Toast.makeText(this, "Opening ChatGPT sign-in...", Toast.LENGTH_SHORT).show();
        ChatGptAuth.signIn(this, background, handler, (ok, message) -> {
            if (isFinishing() || isDestroyed()) return;
            new AlertDialog.Builder(this)
                .setTitle(ok ? "ChatGPT connected" : "Connection not completed")
                .setMessage(message)
                .setPositiveButton("OK", null).show();
            if (ok) render();
        });
    }

    private void chatGptAccountDialog() {
        boolean connected = ChatGptAuth.isConnected(this);
        boolean permitted = connected && ChatGptAuth.canUsePlan(this);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this);
        if (connected) {
            dialog.setTitle("ChatGPT connection")
                .setMessage(ChatGptAuth.account(this) + "\n\n" +
                    (permitted ? "Sign-in and AI planning permission are saved." :
                    "Signed in, but AI planning permission has not been granted."))
                .setPositiveButton(permitted ? "Done" : "Review permissions",
                    (d, w) -> { if (!permitted) beginChatGptSignIn(); })
                .setNeutralButton("Disconnect", (d, w) -> {
                    ChatGptAuth.disconnect(this); render();
                })
                .setNegativeButton("Cancel", null);
        } else {
            dialog.setTitle("Connect ChatGPT")
                .setMessage("Your browser sign-in is only complete after Quiet securely receives a token. If it fails to connect, test the authentication host in your browser.")
                .setPositiveButton("Sign in", (d,w) -> beginChatGptSignIn())
                .setNeutralButton("Diagnose network", (d,w) -> diagnoseChatGptConnection())
                .setNegativeButton("Not now", null);
        }
        dialog.show();
    }

    private void openLonde() {
        startActivity(new Intent(this, LondeActivity.class));
    }

    private void handleLondeAction(String command) {
        if(command==null||command.trim().isEmpty())return;
        String query=command.trim();
        if(query.length()>220){
            Toast.makeText(this,"Keep your action under 220 characters",Toast.LENGTH_LONG).show();
            return;
        }
        page="home";
        render();
        handler.postDelayed(()-> {
            if(!isFinishing()&&!isDestroyed())requestChatGptPlan(query);
        },250);
    }

    private void requestChatGptPlan(String input) {
        String query=input==null?"":input.trim();
        if(query.isEmpty()||query.length()>220){
            Toast.makeText(this,"Enter a command up to 220 characters",Toast.LENGTH_LONG).show();
            return;
        }
        if (query.toLowerCase(Locale.ROOT).matches(".*\\b(what(?:'s| is)?|tell|show|current)\\b.*\\b(time|clock)\\b.*|.*\\btime right now\\b.*")) {
            String time = new SimpleDateFormat("h:mm a", Locale.getDefault())
                    .format(new Date());
            new AlertDialog.Builder(this).setTitle("Current time")
                .setMessage("It's " + time + " on your phone.")
                .setPositiveButton("OK", null).show();
            return;
        }
        // Calendar questions need actual calendar data, not a model-generated action.
        if (query.toLowerCase(Locale.ROOT).matches(".*\\b(meetings?|appointments?|calendar|schedule)\\b.*")
                && query.toLowerCase(Locale.ROOT).matches(".*\\b(what|when|do i|have|show|today|tomorrow)\\b.*")) {
            new AlertDialog.Builder(this).setTitle("Your meetings")
                .setMessage("ChatGPT account sign-in doesn't grant access to your Google Calendar. Open your calendar to see real meetings.")
                .setPositiveButton("Open calendar", (d,w) -> {
                    Intent open = new Intent(Intent.ACTION_MAIN);
                    open.addCategory(Intent.CATEGORY_APP_CALENDAR);
                    startSafe(open);
                })
                .setNegativeButton("Cancel", null).show();
            return;
        }
        if (!ChatGptAuth.canUsePlan(this)) {
            if (ChatGptAuth.isConnected(this)) {
                new AlertDialog.Builder(this).setTitle("ChatGPT connected")
                    .setMessage("Your connection is saved, but AI planning permission is not granted. Review it in Settings.")
                    .setPositiveButton("Review", (d,w) -> chatGptAccountDialog())
                    .setNegativeButton("Cancel", null).show();
            } else chatGptAccountDialog();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Ask ChatGPT to plan?")
            .setMessage("Send only this text to OpenAI?\n\n"+query+
                "\n\nYou will review any proposed actions before they run.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Continue",(d,w)-> {
                Toast.makeText(this,"Asking ChatGPT...",Toast.LENGTH_SHORT).show();
                background.execute(()-> {
                    try {
                        List<IntentRouter.Step> proposed=ChatGptPlanner.plan(this,query);
                        handler.post(()->{
                            if(isDestroyed()||isFinishing())return;
                            List<String> installed=new ArrayList<>();
                            for(App a:apps)if(!getSet("hidden").contains(a.packageName))
                                installed.add(a.name);
                            IntentRouter.Plan safe=ActionPolicy.validate(query,proposed,installed);
                            if(safe.valid())previewPlan(safe);
                            else new AlertDialog.Builder(this).setTitle("Plan rejected")
                                    .setMessage(safe.error).setPositiveButton("OK",null).show();
                        });
                    } catch(Exception e) {
                        handler.post(()-> {
                            if(isDestroyed()||isFinishing())return;
                            new AlertDialog.Builder(this).setTitle("ChatGPT request failed")
                                .setMessage("No actions were run. " + e.getClass().getSimpleName()
                                    + ": " + (e.getMessage() == null ? "Unknown failure" : e.getMessage())
                                    + "\\n\\nDon't paste access tokens or sensitive request data.")
                                .setPositiveButton("OK",null).show();
                        });
                    }
                });
            }).show();
    }

    // Only the explicitly entered text is sent to a configured HTTPS gateway.
    private void requestAiPlan(String input) {
        String query = input.trim();
        if (query.isEmpty() || query.length() > 220) {
            Toast.makeText(this, "Enter 1-220 characters first", Toast.LENGTH_LONG).show();
            return;
        }
        SharedPreferences gateway = getSharedPreferences("quiet_gateway_config_v1", MODE_PRIVATE);
        String endpoint = gateway.getString("endpoint", "");
        if (!CloudPlanClient.validEndpoint(endpoint) || !GatewaySecrets.configured(this)) {
            new AlertDialog.Builder(this).setTitle("AI gateway not configured")
                    .setMessage("Set your private HTTPS /plan endpoint and gateway access token in Settings. Offline commands work without this.")
                    .setPositiveButton("Configure", (d, w) -> configureGateway())
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Send this command to your AI gateway?")
                .setMessage(query + "\n\nOnly this text will be sent. Your gateway forwards it to its model provider and may incur costs.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send", (d, w) -> {
                    Toast.makeText(this, "Requesting a plan...", Toast.LENGTH_SHORT).show();
                    background.execute(() -> {
                        try {
                            String token = GatewaySecrets.read(this);
                            List<IntentRouter.Step> response = CloudPlanClient.request(endpoint, token, query);
                            handler.post(() -> {
                                if (isFinishing() || isDestroyed()) return;
                                List<String> allowed = new ArrayList<>();
                                for (App app : apps) if (!getSet("hidden").contains(app.packageName))
                                    allowed.add(app.name);
                                IntentRouter.Plan plan = ActionPolicy.validate(query, response, allowed);
                                if (!plan.valid()) {
                                    new AlertDialog.Builder(this).setTitle("Plan rejected")
                                            .setMessage(plan.error).setPositiveButton("OK", null).show();
                                    return;
                                }
                                previewPlan(plan);
                            });
                        } catch (Exception ex) {
                            handler.post(() -> {
                                if (!isFinishing() && !isDestroyed())
                                    new AlertDialog.Builder(this).setTitle("AI planner unavailable")
                                            .setMessage("Your request was not executed. Check the gateway URL, token and server availability. Local commands still work.")
                                            .setPositiveButton("OK", null).show();
                            });
                        }
                    });
                }).show();
    }

    private void previewPlan(IntentRouter.Plan plan) {
        StringBuilder preview = new StringBuilder();
        for (int i = 0; i < plan.steps.size(); i++)
            preview.append(i + 1).append(". ").append(plan.steps.get(i).description()).append("\n");
        new AlertDialog.Builder(this).setTitle("Review AI action plan")
                .setMessage(preview.toString().trim())
                .setNegativeButton("Cancel", null)
                .setPositiveButton("RUN PLAN", (d, w) -> {
                    // App/permission constraints can change while a dialog is open.
                    List<String> allowed = new ArrayList<>();
                    for (App app : apps) if (!getSet("hidden").contains(app.packageName))
                        allowed.add(app.name);
                    IntentRouter.Plan safe = ActionPolicy.validate(plan.original, plan.steps, allowed);
                    if (safe.valid()) executePlan(safe);
                    else Toast.makeText(this, "Plan no longer valid", Toast.LENGTH_LONG).show();
                }).show();
    }

    private void configureGateway() {
        SharedPreferences cfg = getSharedPreferences("quiet_gateway_config_v1", MODE_PRIVATE);
        LinearLayout fields = column();
        fields.setPadding(dp(22), dp(8), dp(22), dp(4));
        EditText url = new EditText(this);
        url.setSingleLine(true); url.setTextColor(ink); url.setHintTextColor(muted);
        url.setTextSize(14); url.setHint("https://your-server.example/plan");
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(cfg.getString("endpoint", ""));
        fields.addView(url);
        EditText token = new EditText(this);
        token.setSingleLine(true); token.setTextColor(ink); token.setHintTextColor(muted);
        token.setTextSize(14); token.setHint(GatewaySecrets.configured(this)
                ? "Saved token (leave blank to keep)" : "Gateway access token");
        token.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(token);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Optional AI gateway")
                .setMessage("Configure your own HTTPS server. Never put an OpenAI API key here.")
                .setView(fields)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Disconnect", (d, w) -> {
                    GatewaySecrets.clear(this);
                    cfg.edit().remove("endpoint").apply();
                    Toast.makeText(this, "AI gateway disconnected", Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("Save", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String target = url.getText().toString().trim();
            if (!CloudPlanClient.validEndpoint(target)) {
                url.setError("Use an HTTPS URL ending in /plan"); return;
            }
            try {
                String value = token.getText().toString();
                if (!value.isEmpty()) GatewaySecrets.save(this, value);
                if (!GatewaySecrets.configured(this)) {
                    token.setError("Enter your gateway access token"); return;
                }
                cfg.edit().putString("endpoint", target).apply();
                Toast.makeText(this, "Gateway settings saved", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } catch (Exception e) {
                token.setError("Could not securely save token");
            }
        }));
        dialog.show();
    }

    private void runSmartCommand(String input) {
        List<String> installed = new ArrayList<>();
        for (App app : apps) installed.add(app.name);
        IntentRouter.Plan plan = IntentRouter.parse(input, installed);
        if (!plan.valid()) {
            new AlertDialog.Builder(this).setTitle("Quiet couldn't understand")
                    .setMessage(plan.error)
                    .setPositiveButton("OK", null).show();
            return;
        }
        if (plan.needsPreview()) {
            StringBuilder preview = new StringBuilder();
            for (int i = 0; i < plan.steps.size(); i++)
                preview.append(i + 1).append(". ").append(plan.steps.get(i).description()).append("\\n");
            new AlertDialog.Builder(this).setTitle("Review these actions")
                    .setMessage(preview.toString().trim())
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Run actions", (dialog, which) -> executePlan(plan)).show();
        } else {
            executePlan(plan);
        }
    }

    private void executePlan(IntentRouter.Plan plan) {
        // Stateful updates first, then a single navigation. The parser rejects
        // commands requiring two destinations. Nothing runs without a user tap.
        IntentRouter.Step destination = null;
        for (IntentRouter.Step step : plan.steps) {
            if (step.action == IntentRouter.Action.START_FOCUS) {
                prefs.edit().putLong("focusUntil",
                        System.currentTimeMillis() + step.minutes * 60000L).apply();
                Toast.makeText(this, "Focus started for " + step.minutes + " minutes",
                        Toast.LENGTH_SHORT).show();
            } else if (step.action == IntentRouter.Action.STOP_FOCUS) {
                prefs.edit().remove("focusUntil").apply();
                Toast.makeText(this, "Focus stopped", Toast.LENGTH_SHORT).show();
            } else destination = step;
        }
        if (destination == null) { render(); return; }
        switch (destination.action) {
            case FOCUS_PICKER: focusDialog(); break;
            case TRAIN:
                if (!destination.equipment.isEmpty()) {
                    getSharedPreferences("quiet_training_v1", MODE_PRIVATE).edit()
                            .putString("equipment", destination.equipment).apply();
                }
                if (destination.minutes > 0) startTraining(destination.minutes);
                else openTraining();
                break;
            case WORKSPACE: openWorkspace(); break;
            case NOTES: editNote(); break;
            case APP:
                for (App a : apps) if (a.name.equals(destination.appName)) { open(a); break; }
                break;
            case APPS: show("apps"); break;
            case USAGE: show("usage"); break;
            case CALENDAR: openCalendar(); break;
            case SETTINGS: show("settings"); break;
            default: render(); break;
        }
    }

    private void startVoiceInput() {
        // Uses the system/provider recognition Activity. Android can choose a
        // cloud-powered recognizer; Quiet does not claim offline privacy.
        Intent speech = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speech.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speech.putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell Quiet what you want to do");
        speech.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            startActivityForResult(speech, REQUEST_VOICE_COMMAND);
        } catch (Exception ex) {
            Toast.makeText(this, "No speech recognition app is available. Use the keyboard.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Original two-eye character drawn with simple native shapes. */
    private final class QuietFace extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean blinkClosed;
        private final Runnable blink = new Runnable() {
            @Override public void run() {
                if (!isAttachedToWindow()) return;
                blinkClosed = true;
                invalidate();
                postDelayed(() -> {
                    blinkClosed = false;
                    invalidate();
                    if (isAttachedToWindow()) postDelayed(blink, 4700);
                }, 155);
            }
        };
        QuietFace() { super(MainActivity.this); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            postDelayed(blink, 3100);
        }
        @Override protected void onDetachedFromWindow() {
            removeCallbacks(blink);
            super.onDetachedFromWindow();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            float scale = Math.min(w / 110f, h / 110f);
            canvas.save();
            canvas.translate((w - 110 * scale) / 2f, (h - 110 * scale) / 2f);
            canvas.scale(scale, scale);
            paint.setShader(new RadialGradient(54, 45, 66,
                    new int[]{isPaper() ? c("#C5D9C1") : c("#A6CFA8"),
                            isPaper() ? c("#9BB79A") : c("#47664E")},
                    null, Shader.TileMode.CLAMP));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawOval(9, 13, 103, 104, paint);
            paint.setShader(null);
            paint.setColor(isPaper() ? c("#1E3926") : c("#F5F9EB"));
            paint.setStrokeWidth(4.3f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            Path leftEye = new Path();
            if (blinkClosed) { leftEye.moveTo(29, 54); leftEye.lineTo(43, 54); }
            else { leftEye.moveTo(29, 53); leftEye.quadTo(36, 45, 43, 53); }
            canvas.drawPath(leftEye, paint);
            Path rightEye = new Path();
            if (blinkClosed) { rightEye.moveTo(67, 54); rightEye.lineTo(81, 54); }
            else { rightEye.moveTo(67, 53); rightEye.quadTo(74, 45, 81, 53); }
            canvas.drawPath(rightEye, paint);
            Path smile = new Path();
            smile.moveTo(48, 69);
            smile.quadTo(56, 76, 64, 69);
            canvas.drawPath(smile, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.restore();
        }
    }

    // An opt-in, keyboard-first concept screen. No fake AI outputs, microphone
    // claims, network access, or additional permissions.
    private void renderIntent() {
        add(main, label("QUIET  /  INTENT PREVIEW"), 20);
        add(main, text("What do you want\\nto do?", 33, ink, true), 12);
        add(main, text("Less browsing. More doing.", 14, muted, false), 6);

        LinearLayout faceCard = card();
        faceCard.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout faceRow = row();
        TextView face = text("◡‿◡", 38, ink, false);
        face.setGravity(Gravity.CENTER);
        face.setBackground(shape(accent, 4, 0));
        faceRow.addView(face, new LinearLayout.LayoutParams(dp(92), dp(80)));
        LinearLayout status = column();
        status.setPadding(dp(18), 0, 0, 0);
        add(status, text("Hello from Quiet.", 19, ink, true), 0);
        add(status, text("Your tools, on your terms.", 12, muted, false), 8);
        faceRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        faceCard.addView(faceRow);
        add(main, faceCard, 21);

        EditText command = new EditText(this);
        command.setSingleLine(true);
        command.setTextSize(15);
        command.setTextColor(ink);
        command.setHintTextColor(muted);
        command.setHint("Try: train, focus 25, open WhatsApp");
        command.setPadding(dp(13), dp(16), dp(13), dp(16));
        command.setBackground(shape(surface, 4, line));
        command.setImeOptions(EditorInfo.IME_ACTION_GO);
        add(main, command, 18);
        Runnable submit = () -> runIntentCommand(command.getText().toString());
        command.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO) { submit.run(); return true; }
            return false;
        });
        TextView submitButton = pill("RUN COMMAND  →", submit);
        add(main, submitButton, 10);
        add(main, text("Offline commands only. No AI model, microphone, or cloud processing.",
                11, muted, false), 10);

        section(main, "DO SOMETHING USEFUL", null, null);
        LinearLayout first = row();
        first.addView(intentAction("TRAIN", "A focused workout", this::openTraining),
                new LinearLayout.LayoutParams(0, dp(108), 1));
        spaceHorizontal(first, 9);
        first.addView(intentAction("WORKSPACE", "Read or write", this::openWorkspace),
                new LinearLayout.LayoutParams(0, dp(108), 1));
        add(main, first, 10);

        LinearLayout second = row();
        second.addView(intentAction("FOCUS", "Protect your time", this::focusDialog),
                new LinearLayout.LayoutParams(0, dp(108), 1));
        spaceHorizontal(second, 9);
        second.addView(intentAction("ALL APPS", "Open intentionally", () -> show("apps")),
                new LinearLayout.LayoutParams(0, dp(108), 1));
        add(main, second, 9);

        section(main, "IN PROGRESS", null, null);
        LinearLayout active = card();
        add(active, text(focusActive() ? "Focus in progress" : "No active focus session",
                17, ink, true), 0);
        add(active, text(focusActive() ? remainingFocus() + " remaining" :
                "Choose Focus to start a distraction-free session.", 12, muted, false), 7);
        active.setOnClickListener(v -> focusDialog());
        add(main, active, 10);
        add(main, pill("← BACK TO HOME", () -> show("home")), 23);
        space(main, 22);
    }

    private LinearLayout intentAction(String name, String subtitle, Runnable callback) {
        LinearLayout tile = column();
        tile.setPadding(dp(12), dp(15), dp(10), dp(12));
        tile.setBackground(shape(surface, 4, line));
        add(tile, text(name, 15, ink, true), 0);
        add(tile, text(subtitle, 12, muted, false), 8);
        tile.setOnClickListener(v -> callback.run());
        return tile;
    }

    private void spaceHorizontal(LinearLayout row, int widthDp) {
        row.addView(new View(this), new LinearLayout.LayoutParams(dp(widthDp), 1));
    }

    private void runIntentCommand(String input) {
        String q = input.trim();
        if (q.isEmpty()) return;
        if (!handleCommand(q)) {
            Toast.makeText(this, "Try: train, workspace, focus 25, apps, or open an app",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void renderHome() {
        String hour = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        String day = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new Date());
        TextView date = label(day); add(main, date, 21);
        TextView time = text(hour, 65, ink, false); time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockLive = time;
        time.setLetterSpacing(-0.04f); add(main, time, 5);
        TextView headline = text("Less noise. More life.", 15, muted, false); add(main, headline, -3);

        // Optional AI-style concept preview; local controls only, not an AI model.
        LinearLayout intentPreview = card();
        add(intentPreview, label("NEW  /  INTENT PREVIEW"), 0);
        add(intentPreview, text("Do something, not everything.", 18, ink, true), 7);
        add(intentPreview, text("One command bar · four purposeful tools · zero feed", 12, muted, false), 5);
        intentPreview.setOnClickListener(v -> show("intent"));
        add(main, intentPreview, 20);

        LinearLayout meter = row(); meter.setPadding(dp(15), dp(13), dp(15), dp(13));
        meter.setBackground(shape(surface, 20, line));
        LinearLayout left = column();
        left.addView(label("TODAY'S SCREEN TIME"));
        left.addView(text(hasUsage ? duration(totalUsage()) : "Enable access", 20, ink, true));
        meter.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        TextView focus = pill(focusActive() ? "●  FOCUS ON" : "◎  START FOCUS", this::focusDialog);
        meter.addView(focus); add(main, meter, 26);
        meter.setOnClickListener(v -> show("usage"));

        section(main, "YOUR ESSENTIALS", "EDIT  ↗", this::editFavorites);
        List<App> favorites = favorites();
        for (int i = 0; i < favorites.size(); i += 2) {
            LinearLayout tiles = row();
            for (int j = i; j < Math.min(i + 2, favorites.size()); j++) {
                App app = favorites.get(j);
                LinearLayout tile = column(); tile.setPadding(dp(16), dp(16), dp(14), dp(16));
                tile.setBackground(shape(surface, 20, line));
                TextView monogram = text(initial(app.name), 25, ink, false);
                monogram.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                tile.addView(monogram);
                space(tile, 13);
                TextView name = text(app.name, 14, ink, true); name.setSingleLine(true);
                tile.addView(name);
                String mins = hasUsage ? durationShort(usage.getOrDefault(app.packageName, 0L)) : app.group;
                TextView sub = text(mins, 11, muted, false); tile.addView(sub);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(117), 1);
                if (j > i) cp.leftMargin = dp(10);
                tiles.addView(tile, cp);
                tile.setOnClickListener(v -> open(app));
                tile.setOnLongClickListener(v -> { appOptions(app); return true; });
            }
            if (favorites.size() - i == 1) tiles.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            add(main, tiles, 10);
        }
        TextView all = pill("↑   Swipe up for all apps", () -> show("apps"));
        add(main, all, 17);

        section(main, "QUIET WORKSPACE", "OPEN  ↗", this::openWorkspace);
        LinearLayout work = card();
        add(work, text("Open anything.", 21, ink, true), 0);
        add(work, text("Share text or links · read · edit · ask ChatGPT", 12, muted, false), 7);
        work.setOnClickListener(v -> openWorkspace());
        add(main, work, 11);

        section(main, "FOCUS SESSION", "ADJUST  ↗", this::focusDialog);
        LinearLayout focusCard = card();
        LinearLayout focusR = row();
        focusLive = text(focusActive() ? remainingFocus() : "25:00", 42, ink, false);
        focusLive.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        focusR.addView(focusLive, new LinearLayout.LayoutParams(0, -2, 1));
        TextView focusButton = pill(focusActive() ? "STOP" : "BEGIN", this::focusDialog);
        focusR.addView(focusButton); focusCard.addView(focusR);
        add(focusCard, text(focusActive() ? "One thing at a time." : "Make space for uninterrupted attention.", 12, muted, false), 7);
        add(main, focusCard, 10);

        section(main, "QUICK THOUGHT", "EDIT  ↗", this::editNote);
        LinearLayout note = card();
        String content = prefs.getString("note", "What's the one thing that matters today?");
        add(note, text(content, 15, ink, false), 0);
        note.setOnClickListener(v -> editNote()); add(main, note, 10);
        space(main, 20);
    }

    private void renderApps(String query) {
        TextView h = text("Everything,\nwithout the noise.", 31, ink, true);
        h.setLineSpacing(dp(2), 1f); add(main, h, 17);
        EditText search = new EditText(this);
        search.setSingleLine(true); search.setTextSize(15); search.setTextColor(ink); search.setHintTextColor(muted);
        search.setHint("Search apps or type ‘focus 30’");
        search.setPadding(dp(18), dp(13), dp(16), dp(13));
        search.setBackground(shape(surface, 18, line));
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        add(main, search, 19);
        if (!query.isEmpty()) search.setText(query);
        LinearLayout results = column(); add(main, results, 7);
        Runnable fill = () -> renderAppList(results, search.getText().toString().trim());
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int cnt, int after) { }
            public void onTextChanged(CharSequence s, int st, int before, int cnt) { fill.run(); }
            public void afterTextChanged(Editable e) { }
        });
        search.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO) return handleCommand(search.getText().toString());
            return false;
        });
        fill.run();
    }

    private void renderAppList(LinearLayout result, String q) {
        result.removeAllViews();
        List<App> visible = new ArrayList<>();
        for (App a : apps) {
            if (getSet("hidden").contains(a.packageName)) continue;
            if (q.isEmpty() || a.name.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))
                    || a.group.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))) visible.add(a);
        }
        if (!q.isEmpty()) {
            add(result, label(visible.size() + " MATCHING APPS"), 17);
            int shown = 0;
            int queryLimit = groupDisplayLimits.getOrDefault("$search", 40);
            for (App a : visible) {
                if (shown++ >= queryLimit) break;
                appLine(result, a, false);
            }
            if (visible.size() > queryLimit) {
                add(result, pill("SHOW MORE MATCHES", () -> {
                    groupDisplayLimits.put("$search", queryLimit + 40);
                    renderAppList(result, q);
                }), 13);
            }
            if (visible.isEmpty()) add(result, text("No matching apps.", 14, muted, false), 13);
            TextView web = pill("SEARCH THE WEB FOR  \"" + q + "\"  ↗", () -> webSearch(q));
            add(result, web, 16);
        } else {
            for (String group : Arrays.asList("Essentials", "Messaging", "Work", "Tools", "Creative", "Media", "Games", "Other")) {
                List<App> filtered = new ArrayList<>();
                for (App a : visible) if (group.equals(a.group)) filtered.add(a);
                if (filtered.isEmpty()) continue;
                boolean isOpen = expanded.getOrDefault(group, group.equals("Essentials") || group.equals("Messaging"));
                LinearLayout header = row(); header.setPadding(dp(4), dp(12), dp(4), dp(12));
                TextView name = text(group, 19, ink, true);
                header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                header.addView(text(filtered.size() + "   " + (isOpen ? "⌃" : "⌄"), 14, muted, false));
                add(result, header, 8);
                header.setOnClickListener(v -> { expanded.put(group, !isOpen); renderAppList(result, ""); });
                if (isOpen) {
                    int maxVisible = groupDisplayLimits.getOrDefault(group, 20);
                    int shown = 0;
                    for (App a : filtered) {
                        if (shown++ >= maxVisible) break;
                        appLine(result, a, true);
                    }
                    if (filtered.size() > maxVisible) {
                        TextView more = pill("SHOW MORE " + group.toUpperCase(Locale.ROOT)
                                + "  ·  " + (filtered.size() - maxVisible) + " remaining", () -> {
                            groupDisplayLimits.put(group, maxVisible + 25);
                            renderAppList(result, "");
                        });
                        add(result, more, 11);
                    }
                }
            }
        }
    }

    private void appLine(LinearLayout parent, App a, boolean small) {
        LinearLayout r = row();
        r.setPadding(dp(12), dp(small ? 9 : 12), dp(11), dp(small ? 9 : 12));
        r.setBackground(shape(surface, 16, 0));
        TextView icon = text(initial(a.name), 17, ink, false); icon.setGravity(Gravity.CENTER);
        icon.setBackground(shape(surface2, 13, 0));
        r.addView(icon, new LinearLayout.LayoutParams(dp(43), dp(43)));
        LinearLayout info = column(); info.setPadding(dp(13), 0, 0, 0);
        TextView n = text(a.name, 15, ink, true); n.setSingleLine(true); info.addView(n);
        info.addView(text(hasUsage ? durationShort(usage.getOrDefault(a.packageName, 0L)) + " today" : a.group, 11, muted, false));
        r.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(text("↗", 17, muted, false));
        add(parent, r, 7);
        r.setOnClickListener(v -> open(a));
        r.setOnLongClickListener(v -> { appOptions(a); return true; });
    }

    private void openTraining() { startSafe(new Intent(this, TrainingActivity.class)); }

    private void renderRoot() {
        add(main, text("Your day,\nin one place.", 34, ink, true), 18);
        add(main, text("Tools that help you finish, not scroll.", 14, muted, false), 7);
        section(main, "PLANNER", "CALENDAR  ↗", this::openCalendar);
        LinearLayout calendar = card();
        Calendar now = Calendar.getInstance();
        add(calendar, label(new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(now.getTime())), 0);
        add(calendar, text(new SimpleDateFormat("EEEE · d", Locale.getDefault()).format(now.getTime()), 29, ink, true), 7);
        add(calendar, text("Tap to open your calendar", 12, muted, false), 7);
        calendar.setOnClickListener(v -> openCalendar()); add(main, calendar, 9);

        section(main, "QUIET WORKSPACE", "OPEN  ↗", this::openWorkspace);
        LinearLayout workspace = card();
        add(workspace, text("Open in Quiet.", 21, ink, true), 0);
        add(workspace, text("Text · links · local editing · ChatGPT handoff", 12, muted, false), 7);
        workspace.setOnClickListener(v -> openWorkspace());
        add(main, workspace, 9);

        section(main, "QUIET TRAINING", "OPEN  ↗", this::openTraining);
        LinearLayout training = card();
        add(training, text("Train with intention.", 21, ink, true), 0);
        add(training, text("Smart workout plans · set logging · progress · offline", 12, muted, false), 7);
        training.setOnClickListener(v -> openTraining()); add(main, training, 9);

        section(main, "TIME & ATTENTION", "USAGE  ↗", () -> show("usage"));
        LinearLayout focus = card();
        focusLive = text(focusActive() ? remainingFocus() : "25:00", 36, ink, false);
        add(focus, focusLive, 0);
        add(focus, text(focusActive() ? "Focus is running" : "Start a focused work block", 13, muted, false), 6);
        focus.setOnClickListener(v -> focusDialog()); add(main, focus, 9);

        section(main, "NOTEPAD", "WRITE  ↗", this::editNote);
        LinearLayout notes = card();
        add(notes, text(prefs.getString("note", "Tap to jot down a thought."), 15, ink, false), 0);
        notes.setOnClickListener(v -> editNote()); add(main, notes, 9);

        section(main, "ANDROID WIDGETS", "MANAGE  ↗", () -> show("widgets"));
        LinearLayout widgets = card();
        add(widgets, text("Add your calendar, music, weather or other Android widgets.", 14, ink, false), 0);
        widgets.setOnClickListener(v -> show("widgets")); add(main, widgets, 9);
        space(main, 25);
    }

    private void renderInbox() {
        add(main, text("Conversations,\\nwithout the noise.", 31, ink, true), 18);
        add(main, text("This direct-install edition does not read notifications, OTPs, or message contents.", 14, muted, false), 8);
        section(main, "YOUR MESSAGING APPS", null, null);
        int count = 0;
        for (App app : apps) {
            if (!"Messaging".equals(app.group) || getSet("hidden").contains(app.packageName)) continue;
            LinearLayout item = row();
            item.setPadding(dp(15), dp(13), dp(15), dp(13));
            item.setBackground(shape(surface, 16, line));
            item.addView(text(app.name, 16, ink, true), new LinearLayout.LayoutParams(0, -2, 1));
            item.addView(text("›", 22, muted, false));
            add(main, item, 9);
            item.setOnClickListener(v -> open(app));
            count++;
        }
        if (count == 0) add(main, text("No messaging apps found.", 14, muted, false), 18);
        space(main, 16);
        add(main, text("For incoming alerts, use Android's regular notification shade.", 12, muted, false), 8);
    }

    private void renderUsage() {
        add(main, text("Time is yours.", 33, ink, true), 15);
        add(main, text("A little awareness goes a long way.", 14, muted, false), 6);
        if (!hasUsage) {
            LinearLayout access = card();
            add(access, text("Enable Usage Access to see how long each app was used today.", 15, ink, false), 0);
            add(access, pill("ENABLE SCREEN TIME  ↗", () -> startSafe(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))), 12);
            add(main, access, 20); return;
        }
        LinearLayout total = card();
        add(total, label("TOTAL SCREEN TIME · TODAY"), 0);
        add(total, text(duration(totalUsage()), 49, ink, false), 6);
        add(main, total, 23);
        section(main, "MOST USED APPS", null, null);
        List<App> sorted = new ArrayList<>(apps);
        sorted.sort((a, b) -> Long.compare(usage.getOrDefault(b.packageName, 0L), usage.getOrDefault(a.packageName, 0L)));
        int seen = 0;
        for (App a : sorted) {
            long ms = usage.getOrDefault(a.packageName, 0L);
            if (ms < 60000 || seen++ >= 20) continue;
            LinearLayout r = row(); r.setPadding(dp(14), dp(14), dp(14), dp(14));
            r.setBackground(shape(surface, 14, 0));
            r.addView(text(a.name, 14, ink, false), new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(text(duration(ms), 14, muted, true)); add(main, r, 7);
            r.setOnClickListener(v -> appOptions(a));
        }
        space(main, 16);
        add(main, text("Usage values come from Android and may differ slightly from Digital Wellbeing.", 12, muted, false), 8);
    }

    private void renderWidgets() {
        add(main, text("Useful little things.", 31, ink, true), 17);
        add(main, text("Real Android widgets. Keep only what matters.", 14, muted, false), 8);
        add(main, pill("+  ADD A WIDGET", this::pickWidget), 20);
        String saved = prefs.getString("widgets", "");
        for (String token : saved.split(",")) {
            if (token.isEmpty()) continue;
            try {
                int id = Integer.parseInt(token);
                AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(id);
                if (info == null) continue;
                LinearLayout container = card();
                add(container, label(info.label), 0);
                AppWidgetHostView widget = widgetHost.createView(this, id, info);
                int h = Math.max(120, Math.min(310, info.minHeight));
                add(container, widget, 12);
                ViewGroup.LayoutParams lp = widget.getLayoutParams(); lp.height = dp(h); widget.setLayoutParams(lp);
                TextView remove = pill("REMOVE WIDGET", () -> removeWidget(id)); add(container, remove, 10);
                add(main, container, 14);
            } catch (Exception ignored) { }
        }
    }

    private void renderSettings() {
        add(main, text("Make it yours.", 34, ink, true), 17);
        add(main, text("No account. No ads. Your phone, your rules.", 14, muted, false), 5);
        section(main, "HOME", null, null);
        setting("Classic home", "Original dashboard and essential app tiles", () -> show("classic"));
        setting("Intent-first home", "Default home · local commands and recommendations", () -> show("home"));
        section(main, "LONDE ASSISTANT", null, null);
        setting("Ask Londe", "Conversational AI · follow-up questions · voice · optional spoken replies", this::openLonde);
        section(main, "CHATGPT", null, null);
        setting(ChatGptAuth.isConnected(this) ? "ChatGPT account · Connected" : "Connect ChatGPT",
                ChatGptAuth.account(this) + " · Official open-source sign-in",
                this::chatGptAccountDialog);
        section(main, "AI PLANNER", null, null);
        setting("Configure AI gateway", "Optional HTTPS server · explicit sharing only", this::configureGateway);
        section(main, "WORKSPACE", null, null);
        setting("Open in Quiet", "Read, edit and work with shared text and links", this::openWorkspace);
        section(main, "PHONE", null, null);
        setting("Set Quiet as home", "Choose your default Android launcher", () -> startSafe(new Intent(Settings.ACTION_HOME_SETTINGS)));
        setting("Appearance", "Ink · Paper · True black", this::chooseTheme);
        setting("Favourite apps", "Choose up to six essentials", this::editFavorites);
        setting("Hide apps", "Remove apps from Quiet's app drawer", () -> selectApps("hidden", "Hide apps"));
        setting("Protected apps", "Require your device PIN or biometric to launch from Quiet", () -> selectApps("protected", "Protect apps"));
        setting("Distracting apps", "Add a moment of intention before launching", () -> selectApps("distracting", "Distracting apps"));
        section(main, "ACCESS", null, null);
        setting("Screen time permission", hasUsage ? "On" : "Off · tap to enable", () -> startSafe(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        setting("Manage Android widgets", "Choose useful home widgets", () -> show("widgets"));
        section(main, "ABOUT", null, null);
        LinearLayout about = card();
        add(about, text("Quiet 0.7.0 — optional model planner", 14, ink, true), 0);
        add(about, text("Offline by default. No subscription, account, analytics or ad SDK. Notifications are handled by Android. App hiding and protection only apply inside Quiet.", 12, muted, false), 8);
        add(main, about, 10); space(main, 25);
    }

    private void setting(String title, String subtitle, Runnable onClick) {
        LinearLayout r = row(); r.setPadding(dp(14), dp(15), dp(14), dp(15));
        r.setBackground(shape(surface, 16, 0));
        LinearLayout words = column(); words.addView(text(title, 15, ink, true));
        words.addView(text(subtitle, 12, muted, false));
        r.addView(words, new LinearLayout.LayoutParams(0, -2, 1)); r.addView(text("›", 22, muted, false));
        add(main, r, 7); r.setOnClickListener(v -> onClick.run());
    }

    private void updateFocusClock() {
        if (focusLive != null) focusLive.setText(focusActive() ? remainingFocus() : "25:00");
        if (clockLive != null) clockLive.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
    }
    private boolean focusActive() { return System.currentTimeMillis() < prefs.getLong("focusUntil", 0); }
    private String remainingFocus() {
        long sec = Math.max(0, (prefs.getLong("focusUntil", 0) - System.currentTimeMillis() + 999) / 1000);
        return String.format(Locale.getDefault(), "%02d:%02d", sec / 60, sec % 60);
    }
    private void focusDialog() {
        String[] times = focusActive() ? new String[]{"Stop this focus session", "Keep focusing"} :
                new String[]{"15 minutes", "25 minutes", "30 minutes", "45 minutes", "60 minutes"};
        new AlertDialog.Builder(this).setTitle(focusActive() ? "Focus is running" : "Choose a focus session")
            .setItems(times, (d, which) -> {
                if (focusActive()) { if (which == 0) prefs.edit().remove("focusUntil").apply(); }
                else {
                    int[] mins = {15, 25, 30, 45, 60};
                    prefs.edit().putLong("focusUntil", System.currentTimeMillis() + mins[which] * 60000L).apply();
                }
                render();
            }).show();
    }
    private void editNote() {
        EditText note = new EditText(this);
        note.setText(prefs.getString("note", ""));
        note.setMinLines(3); note.setGravity(Gravity.TOP); note.setPadding(dp(18), dp(15), dp(18), dp(15));
        new AlertDialog.Builder(this).setTitle("Quick thought").setView(note)
            .setPositiveButton("Save locally", (d, w) -> {
                prefs.edit().putString("note", note.getText().toString()).apply(); render();
            }).setNegativeButton("Cancel", null).show();
    }
    private void chooseTheme() {
        String[] names = {"Midnight ink", "Warm paper", "True black"};
        new AlertDialog.Builder(this).setTitle("Appearance").setSingleChoiceItems(names,
            theme.equals("paper") ? 1 : theme.equals("black") ? 2 : 0,
            (dialog, which) -> {
                prefs.edit().putString("theme", new String[]{"ink", "paper", "black"}[which]).apply();
                dialog.dismiss();
                // Dialog/window theme is chosen before onCreate, so changing
                // appearance requires a normal Activity recreation.
                recreate();
            }).setNegativeButton("Cancel", null).show();
    }

    private List<App> discoverApps() {
        List<App> fresh = new ArrayList<>();
        Intent i = new Intent(Intent.ACTION_MAIN); i.addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) continue;
            String p = ri.activityInfo.packageName;
            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_LAUNCHER);
            launch.setClassName(p, ri.activityInfo.name);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            String n = String.valueOf(ri.loadLabel(pm));
            boolean exists = false;
            for (App a : fresh) if (a.packageName.equals(p)) { exists = true; break; }
            if (!exists) fresh.add(new App(n, p, groupFor(n, p), launch));
        }
        fresh.sort(Comparator.comparing(a -> a.name.toLowerCase(Locale.ROOT)));
        return fresh;
    }
    private String groupFor(String title, String pkg) {
        String s = (pkg + " " + title).toLowerCase(Locale.ROOT);
        if (contains(s, "whatsapp", "telegram", "signal", "messages", "messenger", "instagram", "discord", "snapchat", "chat")) return "Messaging";
        if (contains(s, "camera", "dialer", "phone", "contacts", "maps", "clock", "calendar", "settings")) return "Essentials";
        if (contains(s, "gmail", "slack", "zoom", "teams", "docs", "meet", "drive", "notion", "outlook", "github")) return "Work";
        if (contains(s, "youtube", "netflix", "spotify", "primevideo", "music", "podcast", "photos", "gallery")) return "Media";
        if (contains(s, "canva", "lightroom", "capcut", "editor", "drawing", "sketch")) return "Creative";
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai.category == ApplicationInfo.CATEGORY_GAME) return "Games";
            if (ai.category == ApplicationInfo.CATEGORY_AUDIO || ai.category == ApplicationInfo.CATEGORY_VIDEO) return "Media";
            if (ai.category == ApplicationInfo.CATEGORY_PRODUCTIVITY) return "Work";
        } catch (Exception ignored) { }
        if (contains(s, "calculator", "files", "filemanager", "play store", "browser", "chrome")) return "Tools";
        return "Other";
    }
    private boolean contains(String s, String... terms) {
        for (String t : terms) if (s.contains(t)) return true;
        return false;
    }
    private List<App> favorites() {
        List<App> result = new ArrayList<>();
        String saved = prefs.getString("favorites", "");
        if (!saved.isEmpty()) {
            for (String pkg : saved.split(",")) {
                for (App a : apps) if (a.packageName.equals(pkg) && !getSet("hidden").contains(pkg)) {
                    result.add(a); break;
                }
                if (result.size() >= 6) break;
            }
        }
        if (result.isEmpty()) {
            for (String pattern : new String[]{"dialer", "phone", "whatsapp", "camera", "messages", "maps", "calendar", "chrome", "settings"}) {
                for (App a : apps) {
                    if (a.packageName.toLowerCase(Locale.ROOT).contains(pattern) || a.name.toLowerCase(Locale.ROOT).contains(pattern)) {
                        if (!result.contains(a) && !getSet("hidden").contains(a.packageName)) { result.add(a); break; }
                    }
                }
                if (result.size() >= 6) break;
            }
            for (App a : apps) {
                if (result.size() >= 6) break;
                if (!result.contains(a) && !getSet("hidden").contains(a.packageName)) result.add(a);
            }
        }
        return result;
    }
    private String initial(String t) {
        if (t.isEmpty()) return "·";
        String[] parts = t.trim().split("\\s+");
        return parts.length > 1 ? (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase(Locale.ROOT) :
                t.substring(0, 1).toUpperCase(Locale.ROOT);
    }
    private Set<String> getSet(String key) {
        return new LinkedHashSet<>(prefs.getStringSet(key, Collections.emptySet()));
    }
    private void setKey(String key, Set<String> packages) { prefs.edit().putStringSet(key, new HashSet<>(packages)).apply(); }
    private void editFavorites() {
        List<App> eligible = new ArrayList<>();
        for (App a : apps) if (!getSet("hidden").contains(a.packageName)) eligible.add(a);
        String[] names = new String[eligible.size()]; boolean[] checked = new boolean[eligible.size()];
        Set<String> selected = new LinkedHashSet<>();
        String current = prefs.getString("favorites", "");
        if (!current.isEmpty()) selected.addAll(Arrays.asList(current.split(",")));
        else for (App a : favorites()) selected.add(a.packageName);
        for (int i = 0; i < eligible.size(); i++) {
            names[i] = eligible.get(i).name;
            checked[i] = selected.contains(eligible.get(i).packageName);
        }
        new AlertDialog.Builder(this).setTitle("Your six essentials")
            .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> {
                String pkg = eligible.get(which).packageName;
                if (isChecked) {
                    if (selected.size() >= 6) {
                        ((AlertDialog) dialog).getListView().setItemChecked(which, false);
                        Toast.makeText(this, "Choose up to six apps", Toast.LENGTH_SHORT).show();
                    } else selected.add(pkg);
                } else selected.remove(pkg);
            }).setPositiveButton("Save", (d, w) -> {
                prefs.edit().putString("favorites", String.join(",", selected)).apply(); render();
            }).setNegativeButton("Cancel", null).show();
    }
    private void selectApps(String key, String title) {
        String[] names = new String[apps.size()]; boolean[] selected = new boolean[apps.size()];
        Set<String> newSet = getSet(key);
        for (int i = 0; i < apps.size(); i++) {
            names[i] = apps.get(i).name; selected[i] = newSet.contains(apps.get(i).packageName);
        }
        new AlertDialog.Builder(this).setTitle(title).setMultiChoiceItems(names, selected,
            (d, which, checked) -> { if (checked) newSet.add(apps.get(which).packageName); else newSet.remove(apps.get(which).packageName); })
            .setPositiveButton("Save", (d, w) -> { setKey(key, newSet); render(); })
            .setNegativeButton("Cancel", null).show();
    }
    private void appOptions(App a) {
        String[] items = {"Open", "Add to favourites", "Remove from favourites", "Hide / unhide", "Protect / unprotect", "Mark distracting / normal", "Android app info"};
        new AlertDialog.Builder(this).setTitle(a.name).setItems(items, (d, which) -> {
            switch (which) {
                case 0: open(a); break;
                case 1: {
                    List<String> fav = new ArrayList<>();
                    for (App item : favorites()) if (!fav.contains(item.packageName)) fav.add(item.packageName);
                    if (!fav.contains(a.packageName)) {
                        if (fav.size() >= 6) fav.remove(fav.size() - 1);
                        fav.add(a.packageName);
                    }
                    prefs.edit().putString("favorites", String.join(",", fav)).apply(); render(); break;
                }
                case 2: {
                    List<String> fav = new ArrayList<>();
                    for (App item : favorites()) if (!item.packageName.equals(a.packageName)) fav.add(item.packageName);
                    prefs.edit().putString("favorites", String.join(",", fav)).apply(); render(); break;
                }
                case 3: toggleSet("hidden", a.packageName); break;
                case 4: toggleSet("protected", a.packageName); break;
                case 5: toggleSet("distracting", a.packageName); break;
                case 6: startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName))); break;
            }
        }).show();
    }
    private void toggleSet(String key, String pkg) {
        Set<String> set = getSet(key);
        if (!set.add(pkg)) set.remove(pkg);
        setKey(key, set); render();
    }
    private void open(App a) {
        if (getSet("protected").contains(a.packageName)) {
            KeyguardManager lock = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (lock != null && lock.isDeviceSecure()) {
                Intent auth = lock.createConfirmDeviceCredentialIntent("Unlock " + a.name, "Verify to launch from Quiet");
                if (auth != null) {
                    pendingProtectedPackage = a.packageName;
                    startActivityForResult(auth, REQUEST_AUTH); return;
                }
            } else {
                Toast.makeText(this, "Set up a device screen lock first", Toast.LENGTH_LONG).show(); return;
            }
        }
        maybeLaunch(a);
    }
    private void maybeLaunch(App a) {
        boolean restricted = getSet("distracting").contains(a.packageName);
        long allowedUntil = prefs.getLong("lease_" + a.packageName, 0);
        if (restricted && System.currentTimeMillis() > allowedUntil) {
            new AlertDialog.Builder(this).setTitle("Open " + a.name + "?")
                .setMessage("Is this what you came here to do?")
                .setPositiveButton("Open for 5 minutes", (d, which) -> {
                    prefs.edit().putLong("lease_" + a.packageName, System.currentTimeMillis() + 300000L).apply();
                    launch(a);
                })
                .setNeutralButton("Open anyway", (d, which) -> launch(a))
                .setNegativeButton("Not now", null).show();
        } else launch(a);
    }
    private void launch(App a) { startSafe(new Intent(a.launchIntent)); }
    private void launchPackage(String pkg) {
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) startSafe(i);
    }
    private String appLabel(String pkg) {
        for (App a : apps) if (a.packageName.equals(pkg)) return a.name;
        try { return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); }
        catch (Exception ignored) { return pkg; }
    }
    private void webSearch(String query) {
        try {
            Intent web = new Intent(Intent.ACTION_WEB_SEARCH);
            web.putExtra(SearchManager.QUERY, query);
            startActivity(web);
        } catch (Exception ex) {
            startSafe(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))));
        }
    }
    private void openCalendar() {
        Intent intent = new Intent(Intent.ACTION_MAIN); intent.addCategory(Intent.CATEGORY_APP_CALENDAR);
        startSafe(intent);
    }
    private boolean handleCommand(String input) {
        String q = input.toLowerCase(Locale.ROOT).trim();
        if (q.equals("train") || q.equals("training") || q.equals("workout")
                || q.equals("fitness")) { openTraining(); return true; }
        if (q.equals("work") || q.equals("workspace") || q.equals("read")
                || q.equals("summarize")) { openWorkspace(); return true; }
        if (q.equals("apps") || q.equals("all apps")) { show("apps"); return true; }
        if (q.equals("calendar")) { openCalendar(); return true; }
        if (q.equals("settings")) { show("settings"); return true; }
        if (q.equals("focus")) { focusDialog(); return true; }
        if (q.startsWith("focus ")) {
            try {
                int mins = Integer.parseInt(q.substring(6).trim().replaceAll("[^0-9]", ""));
                if (mins < 1 || mins > 180) return false;
                prefs.edit().putLong("focusUntil", System.currentTimeMillis() + mins * 60000L).apply();
                show("home"); return true;
            } catch (NumberFormatException ignored) { }
        }
        if (q.equals("usage") || q.equals("screen time") || q.equals("today's screen time")) { show("usage"); return true; }
        if (q.equals("notes") || q.equals("note")) { editNote(); return true; }
        if (q.startsWith("open ")) {
            String name = q.substring(5).trim();
            for (App a : apps) if (a.name.toLowerCase(Locale.ROOT).equals(name)) { open(a); return true; }
            for (App a : apps) if (a.name.toLowerCase(Locale.ROOT).contains(name)) { open(a); return true; }
        }
        return false;
    }
    private boolean hasUsageAccess() {
        try {
            AppOpsManager manager = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            int result = manager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
            return result == AppOpsManager.MODE_ALLOWED;
        } catch (Exception ignored) { return false; }
    }
    private Map<String, Long> discoverUsage() {
        Map<String, Long> found = new HashMap<>();
        try {
            UsageStatsManager us = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            if (us == null) return found;
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            Map<String, UsageStats> stats = us.queryAndAggregateUsageStats(cal.getTimeInMillis(), System.currentTimeMillis());
            if (stats != null) for (Map.Entry<String, UsageStats> entry : stats.entrySet()) {
                long ms = entry.getValue().getTotalTimeInForeground();
                if (ms > 0) found.put(entry.getKey(), ms);
            }
        } catch (Exception ignored) { }
        return found;
    }
    private long totalUsage() {
        long total = 0;
        for (long ms : usage.values()) total += ms;
        return total;
    }
    private String duration(long time) {
        long minutes = time / 60000;
        if (minutes >= 60) return (minutes / 60) + "h " + (minutes % 60) + "m";
        return minutes + "m";
    }
    private String durationShort(long time) { return duration(time); }
    private void startSafe(Intent intent) {
        try { startActivity(intent); }
        catch (Exception e) { Toast.makeText(this, "No app available for that action", Toast.LENGTH_SHORT).show(); }
    }
    private void pickWidget() {
        try {
            pendingWidgetId = widgetHost.allocateAppWidgetId();
            Intent pick = new Intent(AppWidgetManager.ACTION_APPWIDGET_PICK);
            pick.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId);
            startActivityForResult(pick, REQUEST_PICK_WIDGET);
        } catch (Exception ignored) {
            if (pendingWidgetId != -1) { widgetHost.deleteAppWidgetId(pendingWidgetId); pendingWidgetId = -1; }
            Toast.makeText(this, "Widget picker not available", Toast.LENGTH_SHORT).show();
        }
    }
    private void saveWidget(int id) {
        if (id <= 0) return;
        String saved = prefs.getString("widgets", "");
        if (!Arrays.asList(saved.split(",")).contains(String.valueOf(id)))
            prefs.edit().putString("widgets", saved.isEmpty() ? String.valueOf(id) : saved + "," + id).apply();
        pendingWidgetId = -1; show("widgets");
    }
    private void removeWidget(int id) {
        Set<String> ids = new LinkedHashSet<>(Arrays.asList(prefs.getString("widgets", "").split(",")));
        ids.remove(String.valueOf(id)); ids.remove("");
        prefs.edit().putString("widgets", String.join(",", ids)).apply();
        try { widgetHost.deleteAppWidgetId(id); } catch (Exception ignored) { }
        show("widgets");
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_VOICE_COMMAND) {
            if (result == RESULT_OK && data != null) {
                ArrayList<String> matches = data.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS);
                if (matches != null && !matches.isEmpty()) {
                    String recognized = matches.get(0);
                    new AlertDialog.Builder(this).setTitle("You said")
                            .setMessage(recognized)
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Interpret", (d, w) -> runSmartCommand(recognized))
                            .show();
                }
            }
            return;
        }
        if (request == REQUEST_AUTH) {
            if (result == RESULT_OK && pendingProtectedPackage != null) {
                String p = pendingProtectedPackage; pendingProtectedPackage = null;
                for (App a : apps) if (a.packageName.equals(p)) { maybeLaunch(a); break; }
            } else pendingProtectedPackage = null;
            return;
        }
        if (request == REQUEST_PICK_WIDGET || request == REQUEST_CONFIGURE_WIDGET) {
            int id = data != null ? data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId) : pendingWidgetId;
            if (result != RESULT_OK) {
                if (id > 0) try { widgetHost.deleteAppWidgetId(id); } catch (Exception ignored) { }
                pendingWidgetId = -1; return;
            }
            if (request == REQUEST_CONFIGURE_WIDGET) { saveWidget(id); return; }
            AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(id);
            if (info == null) { if (id > 0) widgetHost.deleteAppWidgetId(id); return; }
            if (info.configure != null) {
                try {
                    Intent configure = new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE);
                    configure.setComponent(info.configure);
                    configure.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
                    startActivityForResult(configure, REQUEST_CONFIGURE_WIDGET);
                } catch (Exception ignored) { saveWidget(id); }
            } else saveWidget(id);
        }
    }
}
