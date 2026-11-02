package dev.quiet.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The first Quiet Workspace prototype.
 *
 * An explicit Android share target and local reader/editor. It does not call an
 * AI service, fetch URLs, scrape apps or upload data in the background. Users
 * must explicitly tap "Ask ChatGPT" to share content outside Quiet.
 */
public final class WorkspaceActivity extends Activity {
    private static final int MAX_CHARS = 24000;
    private static final int MAX_FILE_BYTES = 64000;
    private static final String PREFS = "quiet_workspace_v1";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private String page = "overview";
    private String text = "";
    private String sourceLabel = "New workspace";
    private String link = "";
    private boolean truncated;
    private int background, surface, mutedSurface, ink, secondary, outline;
    private LinearLayout body;
    private boolean loading;
    private long documentVersion;

    @Override public void onCreate(Bundle state) {
        SharedPreferences launcher = getSharedPreferences("quiet_local_v1", MODE_PRIVATE);
        String theme = launcher.getString("theme", "ink");
        setTheme("paper".equals(theme) ? R.style.AppThemePaper : R.style.AppTheme);
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setPalette(theme);
        if (state != null) {
            page = state.getString("workspace.page", "overview");
            text = state.getString("workspace.text", "");
            sourceLabel = state.getString("workspace.label", "New workspace");
            link = state.getString("workspace.link", "");
            truncated = state.getBoolean("workspace.truncated", false);
        } else {
            loadIntent(getIntent(), true);
        }
        render();
    }

    @Override public void onNewIntent(Intent incoming) {
        super.onNewIntent(incoming);
        setIntent(incoming);
        loadIntent(incoming, false);
        page = "overview";
        render();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("workspace.page", page);
        out.putString("workspace.text", text);
        out.putString("workspace.label", sourceLabel);
        out.putString("workspace.link", link);
        out.putBoolean("workspace.truncated", truncated);
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void loadIntent(Intent intent, boolean initial) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_PROCESS_TEXT.equals(action)) {
            CharSequence shared = Intent.ACTION_PROCESS_TEXT.equals(action)
                    ? intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                    : intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (shared != null && shared.length() > 0) {
                accept(String.valueOf(shared), "Shared from another app");
                return;
            }
            // Some apps send .txt or code files as content URIs.
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null && "content".equalsIgnoreCase(stream.getScheme())
                    && intent.getType() != null && intent.getType().startsWith("text/")) {
                final long generation = ++documentVersion;
                loading = true;
                text = "";
                sourceLabel = "Reading shared text...";
                io.execute(() -> {
                    String result = null;
                    try (InputStream input = getContentResolver().openInputStream(stream)) {
                        if (input != null) {
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                            byte[] buffer = new byte[4096];
                            int count;
                            while (bytes.size() <= MAX_FILE_BYTES
                                    && (count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                            result = bytes.toString("UTF-8");
                        }
                    } catch (Exception ignored) { }
                    final String loaded = result;
                    handler.post(() -> {
                        if (isFinishing() || isDestroyed() || generation != documentVersion) return;
                        loading = false;
                        if (loaded == null) accept("Could not read this shared text file.", "Open in Quiet");
                        else accept(loaded, "Shared text file");
                        page = "overview";
                        render();
                    });
                });
                return;
            }
            accept("This version supports shared text and links. Share text from another app to get started.",
                    "Open in Quiet");
            return;
        }
        if (!initial) accept("", "New workspace");
        else {
            text = prefs.getString("saved_text", "");
            sourceLabel = text.isEmpty() ? "New workspace" : prefs.getString("saved_label", "Saved workspace");
            detectLink();
        }
    }

    private void accept(String value, String from) {
        ++documentVersion;
        loading = false;
        truncated = value.length() > MAX_CHARS;
        text = truncated ? value.substring(0, MAX_CHARS) : value;
        sourceLabel = from;
        detectLink();
    }

    private void detectLink() {
        link = "";
        String candidate = text.trim();
        if (candidate.length() > 2048) return;
        String[] tokens = candidate.split("\\s+");
        for (String token : tokens) {
            if (token.startsWith("https://") || token.startsWith("http://")) {
                try {
                    URI uri = URI.create(token);
                    if (uri.getHost() != null && !uri.getHost().isEmpty()) {
                        link = uri.toString();
                        return;
                    }
                } catch (Exception ignored) { }
            }
        }
    }

    private void setPalette(String theme) {
        if ("paper".equals(theme)) {
            background = Color.parseColor("#F2F0E9");
            surface = Color.WHITE;
            mutedSurface = Color.parseColor("#EAE9E3");
            ink = Color.parseColor("#171D1A");
            secondary = Color.parseColor("#727970");
            outline = Color.parseColor("#D7DBD2");
        } else if ("black".equals(theme)) {
            background = Color.BLACK;
            surface = Color.parseColor("#0F0F0F");
            mutedSurface = Color.parseColor("#171717");
            ink = Color.parseColor("#F0F0EB");
            secondary = Color.parseColor("#979894");
            outline = Color.parseColor("#323232");
        } else {
            background = Color.parseColor("#101311");
            surface = Color.parseColor("#1B201D");
            mutedSurface = Color.parseColor("#262C27");
            ink = Color.parseColor("#F2F2EB");
            secondary = Color.parseColor("#929E94");
            outline = Color.parseColor("#354038");
        }
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        getWindow().getDecorView().setSystemUiVisibility("paper".equals(theme)
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
    }

    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density + .5f);
    }
    private GradientDrawable shape(int color, boolean bordered) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(4));
        if (bordered) d.setStroke(dp(1), outline);
        return d;
    }
    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }
    private TextView label(String t, int size, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(size);
        v.setTextColor(ink);
        v.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }
    private void add(LinearLayout parent, View view, int marginTop) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(marginTop);
        parent.addView(view, lp);
    }
    private TextView button(String caption, Runnable action) {
        TextView b = label(caption, 13, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(13), dp(10), dp(13));
        b.setBackground(shape(mutedSurface, true));
        if (action != null) b.setOnClickListener(v -> action.run());
        return b;
    }
    private void heading(String heading, String hint) {
        add(body, label(heading, 28, true), 18);
        TextView subtitle = label(hint, 13, false);
        subtitle.setTextColor(secondary);
        add(body, subtitle, 6);
    }
    private void cardText(String title, String description) {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(shape(surface, true));
        add(card, label(title, 15, true), 0);
        TextView d = label(description, 13, false);
        d.setTextColor(secondary);
        add(card, d, 7);
        add(body, card, 12);
    }
    private void render() {
        LinearLayout shell = column();
        shell.setBackgroundColor(background);
        shell.setPadding(dp(17), dp(7), dp(17), dp(6));
        shell.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(17), insets.getSystemWindowInsetTop() + dp(7),
                    dp(17), insets.getSystemWindowInsetBottom() + dp(6));
            return insets;
        });

        LinearLayout header = row();
        TextView back = button("‹  QUIET", this::finish);
        header.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        TextView title = label("WORKSPACE /", 14, true);
        title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        add(shell, header, 4);

        LinearLayout navigation = row();
        for (String candidate : new String[]{"overview", "content", "actions"}) {
            TextView tab = button(candidate.toUpperCase(Locale.ROOT), () -> {
                page = candidate;
                render();
            });
            if (page.equals(candidate)) tab.setBackground(shape(surface, true));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
            params.setMargins(dp(2), 0, dp(2), 0);
            navigation.addView(tab, params);
        }
        add(shell, navigation, 16);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        body = column();
        body.setPadding(dp(1), 0, dp(1), dp(28));
        scroll.addView(body);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if ("content".equals(page)) renderContent();
        else if ("actions".equals(page)) renderActions();
        else renderOverview();
        setContentView(shell);
    }

    private void renderOverview() {
        heading("Open anything.", "One interface for content you choose to share.");
        if (loading) {
            cardText("Reading", "Loading your shared text file locally...");
            return;
        }
        if (text.isEmpty()) {
            cardText("Start here", "Open a webpage in Chrome or text in another app, tap Share, then choose Open in Quiet.");
            add(body, button("PASTE FROM CLIPBOARD", this::paste), 16);
            add(body, button("OPEN SAVED WORKSPACE", this::restore), 10);
            return;
        }
        cardText(sourceLabel, link.isEmpty() ? typeLabel() + "  ·  " + text.length()
                + " characters" : "Website · " + hostName());
        if (truncated) cardText("Preview limited", "Only the first 24,000 characters are displayed.");
        add(body, label("PREVIEW", 11, true), 21);
        TextView preview = label(text.length() > 1300 ? text.substring(0, 1300) + "..." : text, 14, false);
        preview.setTextIsSelectable(true);
        preview.setPadding(dp(14), dp(15), dp(14), dp(15));
        preview.setBackground(shape(surface, true));
        add(body, preview, 9);
        add(body, button("READ / EDIT CONTENT", () -> { page = "content"; render(); }), 15);
        add(body, button("VIEW ACTIONS", () -> { page = "actions"; render(); }), 10);
        add(body, button("SAVE LOCALLY", this::save), 10);
    }

    private String typeLabel() {
        String candidate = text.trim();
        if (candidate.contains("public class ") || candidate.contains("import java.")
                || candidate.contains("function ") || candidate.contains("const ")
                || candidate.contains("def ")) return "Code-like text";
        if (candidate.contains("\n# ") || candidate.startsWith("# ")) return "Structured text";
        return "Text";
    }

    private String hostName() {
        try { return new URI(link).getHost(); }
        catch (Exception ignored) { return "Shared link"; }
    }

    private void renderContent() {
        heading("Content.", "Select, edit or copy without navigating a desktop UI.");
        if (text.isEmpty()) {
            cardText("Nothing opened yet", "Share text or a URL to Quiet, or paste from the clipboard.");
            add(body, button("PASTE FROM CLIPBOARD", this::paste), 15);
            return;
        }
        TextView content = label(text, 14, false);
        content.setTextIsSelectable(true);
        content.setPadding(dp(13), dp(15), dp(13), dp(15));
        content.setBackground(shape(surface, true));
        if ("Code-like text".equals(typeLabel())) {
            content.setTypeface(Typeface.MONOSPACE);
            content.setTextSize(13);
        }
        add(body, content, 12);
        add(body, button("EDIT TEXT", this::edit), 14);
        add(body, button("COPY TEXT", this::copy), 10);
    }

    private void renderActions() {
        heading("Do something.", "Actions only run when you choose them.");
        if (text.isEmpty()) {
            cardText("No content", "Share something to Quiet to unlock relevant actions.");
            add(body, button("PASTE FROM CLIPBOARD", this::paste), 12);
            return;
        }
        if (!link.isEmpty()) add(body, button("OPEN LINK IN BROWSER", this::openLink), 14);
        add(body, button("ASK CHATGPT TO SUMMARIZE", () -> sendToChatGPT("Summarize the following content.")), 12);
        add(body, button("ASK CHATGPT TO EXPLAIN", () -> sendToChatGPT("Explain the following clearly.")), 10);
        if ("Code-like text".equals(typeLabel()))
            add(body, button("ASK CHATGPT TO REVIEW CODE", () ->
                    sendToChatGPT("Review this code. Identify issues, suggest concrete improvements.")), 10);
        add(body, button("COPY", this::copy), 10);
        add(body, button("EDIT", this::edit), 10);
        add(body, button("SAVE TO QUIET", this::save), 10);
        add(body, button("CLEAR CURRENT CONTENT", this::clear), 10);
        cardText("Privacy", "Quiet does not upload shared content. Asking ChatGPT explicitly sends it to the selected application via Android's share system. No API key or paid API is used.");
    }

    private void edit() {
        EditText input = new EditText(this);
        input.setText(text);
        input.setTextColor(ink);
        input.setTextSize(14);
        input.setGravity(Gravity.TOP);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(7);
        input.setMaxLines(15);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        new AlertDialog.Builder(this).setTitle("Edit in Quiet")
                .setView(input)
                .setPositiveButton("Apply", (d, which) -> {
                    accept(input.getText().toString(), "Edited in Quiet");
                    page = "content";
                    render();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void paste() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData item = clipboard == null ? null : clipboard.getPrimaryClip();
        if (item == null || item.getItemCount() == 0) {
            toast("Clipboard is empty");
            return;
        }
        CharSequence value = item.getItemAt(0).coerceToText(this);
        if (value == null || value.length() == 0) {
            toast("No text in clipboard");
            return;
        }
        accept(value.toString(), "Pasted into Quiet");
        page = "overview";
        render();
    }

    private void copy() {
        ((ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("Quiet content", text));
        toast("Copied");
    }

    private void save() {
        prefs.edit().putString("saved_text", text)
                .putString("saved_label", sourceLabel).apply();
        toast("Saved locally in Quiet");
    }

    private void restore() {
        String saved = prefs.getString("saved_text", "");
        if (saved.isEmpty()) { toast("No saved workspace yet"); return; }
        accept(saved, prefs.getString("saved_label", "Saved workspace"));
        page = "overview";
        render();
    }

    private void clear() {
        new AlertDialog.Builder(this).setTitle("Clear current content?")
                .setMessage("This clears the open view. Saved workspace content stays until you overwrite it.")
                .setPositiveButton("Clear", (d, w) -> {
                    accept("", "New workspace");
                    page = "overview";
                    render();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void openLink() {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link))); }
        catch (Exception e) { toast("No browser available"); }
    }

    private void sendToChatGPT(String request) {
        String message = request + "\n\n" + text;
        // User must explicitly tap the action. Never send automatically.
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, message);
        share.setPackage("com.openai.chatgpt");
        try {
            startActivity(share);
        } catch (Exception e) {
            // Not all Android installations support ACTION_SEND in ChatGPT.
            // Offer a user-controlled share chooser rather than silently sending.
            share.setPackage(null);
            try { startActivity(Intent.createChooser(share, "Continue in ChatGPT or another app")); }
            catch (Exception noApp) { toast("No compatible app installed"); }
        }
    }

    private void toast(String t) { Toast.makeText(this, t, Toast.LENGTH_SHORT).show(); }
}
