package dev.quiet.launcher;

import android.app.Activity;
import android.app.SearchManager;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Quiet: distraction-aware home replacement. No analytics, network SDK or external dependencies. */
public final class MainActivity extends Activity {
    private static final int WIDGET_HOST_ID = 31091;
    private static final int REQUEST_PICK_WIDGET = 200;
    private static final int REQUEST_CONFIGURE_WIDGET = 201;
    private static final int REQUEST_AUTH = 202;
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
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                updateFocusClock();
                handler.postDelayed(this, 1000);
            }
        }, 1000);
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
        if (initialResumePending) {
            initialResumePending = false;
            return;
        }
        if (SystemClock.elapsedRealtime() - lastRefreshElapsed >= 15000) refreshAsync();
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
            case "home": renderHome(); break;
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

    private void renderHome() {
        String hour = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        String day = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new Date());
        TextView date = label(day); add(main, date, 21);
        TextView time = text(hour, 65, ink, false); time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockLive = time;
        time.setLetterSpacing(-0.04f); add(main, time, 5);
        TextView headline = text("Less noise. More life.", 15, muted, false); add(main, headline, -3);

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
        add(about, text("Quiet 0.3.0 — offline workspace preview", 14, ink, true), 0);
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
