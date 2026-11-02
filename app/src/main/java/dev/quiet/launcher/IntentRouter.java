package dev.quiet.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A conservative, deterministic offline intent parser.
 *
 * This is NOT an LLM. It never executes actions, sees credentials, accesses
 * Android APIs, or makes network requests. The UI validates and executes
 * recognized steps only after any required confirmation.
 */
final class IntentRouter {
    enum Action {
        START_FOCUS, STOP_FOCUS, FOCUS_PICKER, TRAIN,
        WORKSPACE, NOTES, APP, APPS, USAGE, CALENDAR, SETTINGS
    }

    static final class Step {
        final Action action;
        final int minutes;
        final String equipment;
        final String appName;

        Step(Action action, int minutes, String equipment, String appName) {
            this.action = action;
            this.minutes = minutes;
            this.equipment = equipment;
            this.appName = appName;
        }

        boolean opensScreen() {
            return action != Action.START_FOCUS && action != Action.STOP_FOCUS;
        }

        String description() {
            switch (action) {
                case START_FOCUS: return "Start a " + minutes + "-minute focus session";
                case STOP_FOCUS: return "End your current focus session";
                case FOCUS_PICKER: return "Choose a focus duration";
                case TRAIN:
                    return "Open Training" + (minutes > 0 ? " for " + minutes + " minutes" : "")
                            + (equipment.isEmpty() ? "" : " with " + equipment);
                case WORKSPACE: return "Open Quiet Workspace";
                case NOTES: return "Open your quick notes";
                case APP: return "Launch " + appName;
                case APPS: return "Show all installed apps";
                case USAGE: return "Show screen-time statistics";
                case CALENDAR: return "Open your calendar";
                case SETTINGS: return "Open Quiet settings";
                default: return "Unknown action";
            }
        }
    }

    static final class Plan {
        final List<Step> steps;
        final String error;
        final String original;

        private Plan(String original, List<Step> steps, String error) {
            this.original = original;
            this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
            this.error = error;
        }
        boolean valid() { return error == null && !steps.isEmpty(); }
        boolean needsPreview() { return steps.size() > 1; }
    }

    private static final Pattern DURATION = Pattern.compile(
            "\\b(\\d{1,3})\\s*(?:minutes?|mins?|min)\\b");
    private static final Pattern APP_OPEN = Pattern.compile(
            "^(?:(?:please|can you|could you)\\s+)?(?:open|launch|start)\\s+(?:the\\s+)?(?:app\\s+)?(.+)$");
    private static final Pattern FOCUS = Pattern.compile(
            "\\b(focus|concentrat(?:e|ion)|deep work)\\b");
    private static final Pattern TRAIN = Pattern.compile(
            "\\b(train(?:ing)?|work\\s?out|workouts?|exercise|exercises|fitness)\\b");
    private static final Pattern NOTES = Pattern.compile(
            "\\b(notes?|notepad|jot|write it down)\\b");
    private static final Pattern WORKSPACE = Pattern.compile(
            "\\b(workspace|draft|documents?|articles?|summari[sz]e|summary|pdf|read)\\b");
    private static final Pattern STOP = Pattern.compile("\\b(stop|end|cancel|finish)\\b");

    private IntentRouter() { }

    static Plan parse(String request, List<String> installedApps) {
        String input = request == null ? "" : request.trim();
        if (input.length() > 220) return fail(input, "Keep the request under 220 characters.");
        String normalized = input.toLowerCase(Locale.ROOT)
                .replaceAll("(\\d)\\s*-\\s*(minute|minutes|min|mins)", "$1 $2")
                .replaceAll("[!?.,;]+", " ")
                .replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) return fail(input, "Try: focus for 25 minutes and open notes.");
        // Multi-step clauses are explicit. We never guess additional actions.
        String[] clauses = normalized.split("\\s+(?:and then|then|and)\\s+");
        if (clauses.length > 3) return fail(input, "Quiet supports up to three actions at once.");
        List<Step> steps = new ArrayList<>();
        int navigations = 0;
        for (String clause : clauses) {
            Step step = interpret(clause, installedApps == null ? Collections.emptyList() : installedApps);
            if (step == null) {
                return fail(input, "I couldn't understand: \"" + clause + "\". Try Focus, Training, Workspace, Notes or Open an app.");
            }
            if (step.action == Action.FOCUS_PICKER && clauses.length > 1)
                return fail(input, "Specify a focus duration, e.g. 'focus for 25 minutes and open notes'.");
            steps.add(step);
            if (step.opensScreen()) navigations++;
        }
        if (navigations > 1) {
            return fail(input, "Choose one destination per request (e.g. focus 25 minutes and open Workspace).");
        }
        boolean start = false, stop = false;
        for (Step step : steps) {
            start |= step.action == Action.START_FOCUS;
            stop |= step.action == Action.STOP_FOCUS;
        }
        if (start && stop) return fail(input, "Cannot start and stop focus in the same request.");
        return new Plan(input, steps, null);
    }

    private static Step interpret(String phrase, List<String> apps) {
        String q = phrase.trim()
                .replaceAll("^(?:please|can you|could you|i want to|i need to|let's|lets)\\s+", "")
                .replaceAll("^(?:give me|help me|i have)\\s+", "").trim();

        if (FOCUS.matcher(q).find()) {
            if (STOP.matcher(q).find()) return step(Action.STOP_FOCUS);
            int mins = minutes(q);
            if (mins == -1) return null;
            if (mins == 0) return step(Action.FOCUS_PICKER);
            if (mins < 1 || mins > 180) return null;
            return new Step(Action.START_FOCUS, mins, "", "");
        }
        if (TRAIN.matcher(q).find()) {
            int mins = minutes(q);
            if (mins < 0 || (mins != 0 && mins != 20 && mins != 30 && mins != 45))
                return null;
            String equipment = q.matches(".*\\b(dumbbells?|weights?)\\b.*") ? "dumbbells"
                    : q.matches(".*\\b(bodyweight|body weight|no equipment)\\b.*") ? "bodyweight"
                    : q.matches(".*\\b(gym|machines?)\\b.*") ? "gym" : "";
            return new Step(Action.TRAIN, mins, equipment, "");
        }
        if (q.matches(".*\\b(screen time|usage|phone time|digital wellbeing)\\b.*"))
            return step(Action.USAGE);
        if (q.matches(".*\\b(calendar|schedule)\\b.*"))
            return step(Action.CALENDAR);
        if (NOTES.matcher(q).find()) return step(Action.NOTES);
        if (WORKSPACE.matcher(q).find()) return step(Action.WORKSPACE);
        if (q.matches("(?:show |open )?(?:all )?apps(?: drawer)?"))
            return step(Action.APPS);
        if (q.matches("(?:open |show )?settings")) return step(Action.SETTINGS);

        Matcher open = APP_OPEN.matcher(q);
        if (open.matches()) {
            String query = open.group(1).trim()
                    .replaceAll("\\s+(?:for me|please)$", "");
            if (query.isEmpty()) return null;
            List<String> matches = new ArrayList<>();
            for (String app : apps) if (app.toLowerCase(Locale.ROOT).equals(query))
                return new Step(Action.APP, 0, "", app);
            for (String app : apps) if (app.toLowerCase(Locale.ROOT).contains(query))
                matches.add(app);
            if (matches.size() == 1) return new Step(Action.APP, 0, "", matches.get(0));
            return null; // Never auto-select an ambiguous or unavailable app.
        }
        return null;
    }

    private static int minutes(String q) {
        Matcher m = DURATION.matcher(q);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); }
            catch (NumberFormatException ignored) { return -1; }
        }
        if (q.matches(".*\\b(half an hour|half hour)\\b.*")) return 30;
        if (q.matches(".*\\b(an hour|one hour)\\b.*")) return 60;
        // Bare number adjacent to focus/train, e.g. "focus 25".
        Matcher bare = Pattern.compile("\\b(\\d{1,3})\\b").matcher(q);
        if (bare.find()) {
            try { return Integer.parseInt(bare.group(1)); }
            catch (NumberFormatException ignored) { return -1; }
        }
        return 0;
    }

    private static Step step(Action action) { return new Step(action, 0, "", ""); }
    private static Plan fail(String input, String reason) {
        return new Plan(input, Collections.emptyList(), reason);
    }
}
