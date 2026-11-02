package dev.quiet.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Offline-first, independent training surface. No network, health permissions,
 * background services, swiping interception or third-party dependencies.
 *
 * Rules-based suggestions are not AI diagnoses or measured muscle recovery.
 */
public final class TrainingActivity extends Activity {
    private static final String PREFS = "quiet_training_v1";
    private SharedPreferences prefs;
    private int background, surface, subtle, foreground, secondary, outline, highlight;
    private String tab = "plan";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView restClock;

    private static final class Exercise {
        final String name, group, hint;
        Exercise(String name, String group, String hint) {
            this.name = name; this.group = group; this.hint = hint;
        }
    }

    private static final Exercise[] DUMBBELLS = {
        new Exercise("Goblet squat", "Legs", "Keep your torso tall"),
        new Exercise("Romanian deadlift", "Legs", "Hinge at the hips"),
        new Exercise("Reverse lunge", "Legs", "Step back under control"),
        new Exercise("Dumbbell floor press", "Push", "Elbows about 45 degrees"),
        new Exercise("Overhead press", "Push", "Avoid leaning back"),
        new Exercise("Dumbbell triceps extension", "Push", "Move through a comfortable range"),
        new Exercise("One-arm dumbbell row", "Pull", "Pull your elbow toward your hip"),
        new Exercise("Hammer curl", "Pull", "Keep elbows near your sides"),
        new Exercise("Bent-over reverse fly", "Pull", "Use a light weight"),
        new Exercise("Side plank", "Core", "Brace gently and breathe"),
        new Exercise("Dead bug", "Core", "Keep lower back comfortable")
    };
    private static final Exercise[] BODYWEIGHT = {
        new Exercise("Bodyweight squat", "Legs", "Control the lowering phase"),
        new Exercise("Reverse lunge", "Legs", "Keep a steady stance"),
        new Exercise("Glute bridge", "Legs", "Pause briefly at the top"),
        new Exercise("Push-up", "Push", "Use an incline to make it easier"),
        new Exercise("Pike push-up", "Push", "Keep a comfortable shoulder range"),
        new Exercise("Incline push-up", "Push", "Choose a stable surface"),
        new Exercise("Reverse snow angel", "Pull", "Keep movements small and controlled"),
        new Exercise("Prone Y raise", "Pull", "Do not shrug your shoulders"),
        new Exercise("Prone W raise", "Pull", "Squeeze shoulder blades gently"),
        new Exercise("Plank", "Core", "Keep breathing"),
        new Exercise("Dead bug", "Core", "Move slowly")
    };
    private static final Exercise[] GYM = {
        new Exercise("Leg press", "Legs", "Do not lock out knees"),
        new Exercise("Romanian deadlift", "Legs", "Hinge at the hips"),
        new Exercise("Goblet squat", "Legs", "Use a manageable weight"),
        new Exercise("Chest press", "Push", "Control the movement"),
        new Exercise("Shoulder press", "Push", "Avoid arching your back"),
        new Exercise("Cable triceps pushdown", "Push", "Keep elbows near your sides"),
        new Exercise("Lat pulldown", "Pull", "Pull toward the upper chest"),
        new Exercise("Seated cable row", "Pull", "Avoid swinging"),
        new Exercise("Dumbbell curl", "Pull", "Avoid using momentum"),
        new Exercise("Plank", "Core", "Keep breathing"),
        new Exercise("Cable crunch", "Core", "Move slowly")
    };

    @Override public void onCreate(Bundle state) {
        SharedPreferences launcher = getSharedPreferences("quiet_local_v1", MODE_PRIVATE);
        setTheme("paper".equals(launcher.getString("theme", "ink"))
                ? R.style.AppThemePaper : R.style.AppTheme);
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (state != null) tab = state.getString("training.tab", "plan");
        palette(launcher.getString("theme", "ink"));
        render();
        handler.post(ticker);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("training.tab", tab);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (restClock != null) {
                long left = Math.max(0, (prefs.getLong("rest_until", 0) - System.currentTimeMillis() + 999) / 1000);
                restClock.setText(left == 0 ? "Rest timer ready" : "Rest  " + (left / 60) + ":" + String.format(Locale.US, "%02d", left % 60));
            }
            handler.postDelayed(this, 1000);
        }
    };

    private void palette(String selected) {
        if ("paper".equals(selected)) {
            background = rgb("#F2F0E9"); surface = rgb("#FFFFFF");
            subtle = rgb("#EAE9E3"); foreground = rgb("#171D1A");
            secondary = rgb("#727970"); outline = rgb("#D7DBD2"); highlight = rgb("#DBE7D8");
        } else if ("black".equals(selected)) {
            background = Color.BLACK; surface = rgb("#0F0F0F");
            subtle = rgb("#171717"); foreground = rgb("#F0F0EB");
            secondary = rgb("#979894"); outline = rgb("#323232"); highlight = rgb("#283329");
        } else {
            background = rgb("#101311"); surface = rgb("#1B201D");
            subtle = rgb("#262C27"); foreground = rgb("#F2F2EB");
            secondary = rgb("#929E94"); outline = rgb("#354038"); highlight = rgb("#2D3D31");
        }
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        getWindow().getDecorView().setSystemUiVisibility("paper".equals(selected)
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
    }
    private int rgb(String color) { return Color.parseColor(color); }
    private int dp(float px) { return (int) (getResources().getDisplayMetrics().density * px + 0.5f); }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    private LinearLayout col() { LinearLayout v = new LinearLayout(this); v.setOrientation(1); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(0); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private void add(LinearLayout target, View child, int space) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(space); target.addView(child, lp);
    }
    private TextView action(String text, Runnable onClick) {
        TextView button = text(text, 13, foreground, true);
        button.setGravity(Gravity.CENTER); button.setPadding(dp(13), dp(12), dp(13), dp(12));
        button.setBackground(shape(subtle, 14));
        button.setOnClickListener(v -> onClick.run()); return button;
    }
    private LinearLayout card() {
        LinearLayout view = col();
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        view.setBackground(shape(surface, 20));
        return view;
    }
    private void title(LinearLayout parent, String heading, String subtitle) {
        add(parent, text(heading, 32, foreground, true), 13);
        add(parent, text(subtitle, 13, secondary, false), 7);
    }
    private String today() { return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()); }
    private JSONArray array(String key) {
        try { return new JSONArray(prefs.getString(key, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }
    private void save(String key, JSONArray value) { prefs.edit().putString(key, value.toString()).apply(); }
    private String equipment() { return prefs.getString("equipment", "dumbbells"); }
    private String goal() { return prefs.getString("goal", "Build muscle"); }
    private int minutes() { return prefs.getInt("minutes", 30); }
    private int reps() {
        if ("Get stronger".equals(goal())) return 6;
        if ("Stay active".equals(goal())) return 12;
        return 10;
    }
    private int restSeconds() { return "Get stronger".equals(goal()) ? 90 : 60; }

    private void render() {
        restClock = null;
        LinearLayout screen = col();
        screen.setBackgroundColor(background);
        screen.setPadding(dp(18), dp(8), dp(18), dp(5));
        screen.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(18), insets.getSystemWindowInsetTop() + dp(8),
                    dp(18), insets.getSystemWindowInsetBottom() + dp(5));
            return insets;
        });
        LinearLayout header = row();
        TextView back = action("‹  QUIET", this::finish);
        header.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        TextView name = text("TRAINING /", 14, foreground, true);
        name.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        add(screen, header, 4);

        LinearLayout nav = row();
        String[] tabs = {"plan", "progress", "history"};
        for (String next : tabs) {
            TextView button = text(next.toUpperCase(Locale.US), 12,
                    next.equals(tab) ? foreground : secondary, next.equals(tab));
            button.setGravity(Gravity.CENTER);
            button.setPadding(dp(4), dp(14), dp(4), dp(14));
            if (next.equals(tab)) button.setBackground(shape(subtle, 12));
            nav.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
            button.setOnClickListener(v -> { tab = next; render(); });
        }
        add(screen, nav, 15);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout contents = col();
        contents.setPadding(dp(0), dp(0), dp(0), dp(40));
        scroll.addView(contents);
        if ("progress".equals(tab)) renderProgress(contents);
        else if ("history".equals(tab)) renderHistory(contents);
        else renderPlan(contents);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(screen);
    }

    private void choices(LinearLayout parent, String heading, String[] options, String selected,
                         java.util.function.Consumer<String> changed) {
        add(parent, text(heading.toUpperCase(Locale.US), 11, secondary, true), 18);
        LinearLayout line = row();
        for (String opt : options) {
            TextView button = text(opt, 12, foreground, selected.equals(opt));
            button.setGravity(Gravity.CENTER);
            button.setPadding(dp(8), dp(11), dp(8), dp(11));
            button.setBackground(shape(selected.equals(opt) ? highlight : surface, 12));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.rightMargin = dp(5);
            line.addView(button, params);
            button.setOnClickListener(v -> { changed.accept(opt); render(); });
        }
        add(parent, line, 8);
    }

    private List<Exercise> plan() {
        Exercise[] candidates = "bodyweight".equals(equipment()) ? BODYWEIGHT :
                ("gym".equals(equipment()) ? GYM : DUMBBELLS);
        int count = minutes() == 20 ? 4 : (minutes() == 45 ? 7 : 5);
        String[] order = {"Legs", "Push", "Pull", "Core", "Legs", "Push", "Pull"};
        int daySeed = Integer.parseInt(today()) % 17;
        List<Exercise> selected = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (int i = 0; i < count; i++) {
            List<Exercise> matches = new ArrayList<>();
            for (Exercise candidate : candidates) {
                if (order[i].equals(candidate.group) && !taken.contains(candidate.name))
                    matches.add(candidate);
            }
            if (matches.isEmpty()) continue;
            Exercise picked = matches.get((daySeed + (i / 4)) % matches.size());
            selected.add(picked); taken.add(picked.name);
        }
        return selected;
    }

    private int countSets(JSONArray sets, String exercise) {
        int count = 0;
        for (int i = 0; i < sets.length(); i++) {
            JSONObject set = sets.optJSONObject(i);
            if (set != null && exercise.equals(set.optString("exercise"))) count++;
        }
        return count;
    }
    private JSONObject latestSet(String exercise) {
        JSONArray sessions = array("sessions");
        for (int i = sessions.length() - 1; i >= 0; i--) {
            JSONObject session = sessions.optJSONObject(i);
            if (session == null) continue;
            JSONArray sets = session.optJSONArray("sets");
            if (sets == null) continue;
            for (int j = sets.length() - 1; j >= 0; j--) {
                JSONObject set = sets.optJSONObject(j);
                if (set != null && exercise.equals(set.optString("exercise"))) return set;
            }
        }
        return null;
    }
    private EditText numeric(String number, boolean decimal) {
        EditText entry = new EditText(this);
        entry.setSingleLine(true);
        entry.setTextSize(15); entry.setTextColor(foreground); entry.setHintTextColor(secondary);
        entry.setInputType(decimal ?  android.text.InputType.TYPE_CLASS_NUMBER |
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL : android.text.InputType.TYPE_CLASS_NUMBER);
        entry.setText(number);
        entry.setSelectAllOnFocus(true);
        entry.setPadding(dp(12), dp(8), dp(12), dp(8));
        entry.setBackground(shape(subtle, 12));
        return entry;
    }
    private void renderPlan(LinearLayout target) {
        title(target, "Train with intention.", "Simple sessions. Steady progress. All on your phone.");
        choices(target, "Goal", new String[]{"Build muscle", "Get stronger", "Stay active"},
                goal(), value -> prefs.edit().putString("goal", value).apply());
        choices(target, "Equipment", new String[]{"dumbbells", "bodyweight", "gym"},
                equipment(), value -> prefs.edit().putString("equipment", value).apply());
        String[] lengths = {"20 min", "30 min", "45 min"};
        choices(target, "Time available", lengths, minutes() + " min", value ->
                prefs.edit().putInt("minutes", Integer.parseInt(value.split(" ")[0])).apply());

        JSONArray active = array("active");
        LinearLayout status = card();
        add(status, text("TODAY'S SESSION", 11, secondary, true), 0);
        add(status, text(plan().size() + " exercises  ·  " + minutes() + " min target", 21, foreground, true), 8);
        add(status, text("Three sets per movement. Log actual reps and load below.", 12, secondary, false), 7);
        TextView completed = text(active.length() + " sets logged", 14, foreground, true);
        add(status, completed, 11);
        add(target, status, 23);

        restClock = text("", 15, foreground, true);
        LinearLayout restRow = row();
        restRow.addView(restClock, new LinearLayout.LayoutParams(0, -2, 1));
        restRow.addView(action("START REST", () -> {
            prefs.edit().putLong("rest_until", System.currentTimeMillis() + restSeconds() * 1000L).apply();
            ticker.run();
        }));
        add(target, restRow, 17);

        for (Exercise exercise : plan()) {
            LinearLayout exerciseCard = card();
            add(exerciseCard, text(exercise.group.toUpperCase(Locale.US) + "  ·  3 × " + reps() + " suggested", 11, secondary, true), 0);
            add(exerciseCard, text(exercise.name, 20, foreground, true), 8);
            add(exerciseCard, text(exercise.hint, 12, secondary, false), 5);
            JSONObject last = latestSet(exercise.name);
            if (last != null) {
                int lastReps = last.optInt("reps", reps());
                add(exerciseCard, text("Last logged: " + lastReps + " reps × " +
                        last.optDouble("kg", 0) + " kg. Aim for clean form before progressing.", 12, secondary, false), 7);
            }
            int already = countSets(active, exercise.name);
            TextView counter = text(already + " of 3 sets logged", 13, foreground, true);
            add(exerciseCard, counter, 11);
            LinearLayout fields = row();
            EditText repField = numeric(String.valueOf(reps()), false);
            EditText kgField = numeric(last == null ? "0" : String.valueOf(last.optDouble("kg", 0)), true);
            LinearLayout repsCol = col();
            repsCol.addView(text("REPS", 10, secondary, true));
            add(repsCol, repField, 4);
            LinearLayout weightCol = col();
            weightCol.addView(text("KG (0 = BODYWEIGHT)", 10, secondary, true));
            add(weightCol, kgField, 4);
            LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, -2, 1);
            a.rightMargin = dp(8);
            fields.addView(repsCol, a);
            fields.addView(weightCol, new LinearLayout.LayoutParams(0, -2, 1));
            add(exerciseCard, fields, 12);
            TextView log = action(already >= 3 ? "THREE SETS LOGGED" : "+ LOG SET", () -> {
                int logged = countSets(array("active"), exercise.name);
                if (logged >= 3) return;
                int doneReps;
                double load;
                try {
                    doneReps = Integer.parseInt(repField.getText().toString().trim());
                    load = Double.parseDouble(kgField.getText().toString().trim());
                } catch (Exception e) {
                    repField.setError("Enter valid reps and load"); return;
                }
                if (doneReps < 1 || doneReps > 200 || !Double.isFinite(load) || load < 0 || load > 1000) {
                    repField.setError("Check reps (1–200) and load (0–1000 kg)"); return;
                }
                try {
                    JSONObject set = new JSONObject();
                    set.put("exercise", exercise.name);
                    set.put("group", exercise.group);
                    set.put("reps", doneReps);
                    set.put("kg", load);
                    set.put("time", System.currentTimeMillis());
                    JSONArray updated = array("active"); updated.put(set); save("active", updated);
                    if (prefs.getLong("started", 0) == 0)
                        prefs.edit().putLong("started", System.currentTimeMillis()).apply();
                    int n = countSets(updated, exercise.name);
                    counter.setText(n + " of 3 sets logged");
                    completed.setText(updated.length() + " sets logged");
                    if (n >= 3) { log.setText("THREE SETS LOGGED"); log.setEnabled(false); log.setAlpha(.6f); }
                    prefs.edit().putLong("rest_until", System.currentTimeMillis() + restSeconds() * 1000L).apply();
                } catch (Exception ignored) { }
            });
            if (already >= 3) { log.setEnabled(false); log.setAlpha(.6f); }
            add(exerciseCard, log, 12);
            add(target, exerciseCard, 13);
        }
        add(target, action("FINISH & SAVE WORKOUT", this::finishWorkout), 22);
        if (active.length() > 0)
            add(target, action("DISCARD UNSAVED SETS", this::confirmDiscard), 10);
        add(target, text("Suggestions are general training guidance, not medical advice. Recovery status is an estimate.", 12, secondary, false), 15);
    }
    private void finishWorkout() {
        JSONArray active = array("active");
        if (active.length() == 0) {
            new AlertDialog.Builder(this).setMessage("Log at least one set before saving.").setPositiveButton("OK", null).show();
            return;
        }
        try {
            JSONObject session = new JSONObject();
            session.put("day", today());
            session.put("started", prefs.getLong("started", System.currentTimeMillis()));
            session.put("finished", System.currentTimeMillis());
            session.put("goal", goal());
            session.put("equipment", equipment());
            session.put("sets", active);
            JSONArray history = array("sessions");
            history.put(session); save("sessions", history);
            prefs.edit().remove("active").remove("started").remove("rest_until").apply();
            tab = "history"; render();
        } catch (Exception ignored) { }
    }
    private void confirmDiscard() {
        new AlertDialog.Builder(this).setTitle("Discard unsaved sets?")
            .setMessage("Your saved workout history will remain unchanged.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Discard", (d, w) -> {
                prefs.edit().remove("active").remove("started").remove("rest_until").apply();
                render();
            }).show();
    }

    private void renderProgress(LinearLayout target) {
        title(target, "Progress over noise.", "Consistency, not perfect numbers.");
        JSONArray sessions = array("sessions");
        LinearLayout summary = card();
        add(summary, text("SAVED WORKOUTS", 11, secondary, true), 0);
        add(summary, text(String.valueOf(sessions.length()), 42, foreground, true), 4);
        int total = 0;
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session != null && session.optJSONArray("sets") != null)
                total += session.optJSONArray("sets").length();
        }
        add(summary, text(total + " total logged sets", 13, secondary, false), 7);
        add(target, summary, 23);

        add(target, text("ESTIMATED MUSCLE RECOVERY", 11, secondary, true), 25);
        long now = System.currentTimeMillis();
        for (String group : new String[]{"Legs", "Push", "Pull", "Core"}) {
            long lastTime = 0;
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject session = sessions.optJSONObject(i);
                JSONArray sets = session == null ? null : session.optJSONArray("sets");
                if (sets == null) continue;
                for (int j = 0; j < sets.length(); j++) {
                    JSONObject set = sets.optJSONObject(j);
                    if (set != null && group.equals(set.optString("group")))
                        lastTime = Math.max(lastTime, set.optLong("time", 0));
                }
            }
            LinearLayout item = row(); item.setPadding(dp(15), dp(15), dp(15), dp(15));
            item.setBackground(shape(surface, 15));
            item.addView(text(group, 15, foreground, true), new LinearLayout.LayoutParams(0, -2, 1));
            String label = lastTime == 0 ? "No data" : (now - lastTime >= 48L * 3600000L
                    ? "48h+ since logged" : Math.max(0, (now - lastTime) / 3600000L) + "h since logged");
            item.addView(text(label, 12, secondary, false)); add(target, item, 8);
        }
        add(target, text("Based only on time since your last logged sets. This is not a measured recovery score; soreness, fatigue and performance matter too.", 12, secondary, false), 14);

        add(target, text("RECENT ACTIVITY", 11, secondary, true), 25);
        LinearLayout recent = card();
        long cutoff = now - 7L * 86400000L;
        int count = 0;
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session != null && session.optLong("finished", 0) >= cutoff) count++;
        }
        add(recent, text(count + " workouts in the last 7 days", 18, foreground, true), 0);
        add(recent, text("An easy way to spot consistency without streak pressure.", 12, secondary, false), 8);
        add(target, recent, 10);
    }

    private void renderHistory(LinearLayout target) {
        title(target, "Your training log.", "Private, saved locally on this device.");
        JSONArray sessions = array("sessions");
        if (sessions.length() == 0) {
            LinearLayout empty = card();
            add(empty, text("No saved workouts yet.", 19, foreground, true), 0);
            add(empty, text("Log a few sets in Plan, then save your session.", 13, secondary, false), 8);
            add(target, empty, 22);
            return;
        }
        for (int i = sessions.length() - 1; i >= Math.max(0, sessions.length() - 20); i--) {
            JSONObject session = sessions.optJSONObject(i);
            if (session == null) continue;
            JSONArray sets = session.optJSONArray("sets");
            if (sets == null) continue;
            String when = new SimpleDateFormat("EEE, d MMM · HH:mm", Locale.getDefault())
                    .format(new Date(session.optLong("finished", System.currentTimeMillis())));
            LinearLayout card = card();
            add(card, text(when, 12, secondary, true), 0);
            add(card, text(sets.length() + " sets  ·  " + session.optString("goal"), 19, foreground, true), 8);
            Set<String> movements = new java.util.LinkedHashSet<>();
            for (int j = 0; j < sets.length(); j++) {
                JSONObject set = sets.optJSONObject(j);
                if (set != null) movements.add(set.optString("exercise"));
            }
            StringBuilder names = new StringBuilder();
            for (String movement : movements) {
                if (names.length() > 0) names.append("  ·  ");
                names.append(movement);
            }
            add(card, text(names.toString(), 13, secondary, false), 9);
            add(target, card, 12);
        }
        add(target, text("Data stays in Quiet's app storage. Uninstalling or clearing app data removes this history.", 12, secondary, false), 20);
    }
}
