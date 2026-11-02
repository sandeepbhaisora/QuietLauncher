package dev.quiet.launcher;

import java.util.Arrays;
import java.util.List;

/** Standalone JVM smoke tests, independent of Android and third-party dependencies. */
public final class IntentRouterTest {
    private static final List<String> APPS = Arrays.asList(
            "WhatsApp", "Google Maps", "Phone", "Photos", "Photomath", "Chrome");
    private static int cases;

    private static IntentRouter.Plan good(String request, IntentRouter.Action... actions) {
        IntentRouter.Plan plan = IntentRouter.parse(request, APPS);
        check(plan.valid(), request + " -> " + plan.error);
        check(plan.steps.size() == actions.length, request + ": unexpected steps");
        for (int i = 0; i < actions.length; i++)
            check(plan.steps.get(i).action == actions[i], request + ": action " + i);
        return plan;
    }

    private static void bad(String request) {
        check(!IntentRouter.parse(request, APPS).valid(), request + " must be rejected");
    }

    private static void check(boolean okay, String message) {
        cases++;
        if (!okay) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        IntentRouter.Plan multi = good("Start a 45-minute focus session and open my notes",
                IntentRouter.Action.START_FOCUS, IntentRouter.Action.NOTES);
        check(multi.needsPreview(), "Multi-step actions need a preview");
        check(multi.steps.get(0).minutes == 45, "Focus minutes");

        IntentRouter.Plan workout = good("I have half an hour let's workout with dumbbells",
                IntentRouter.Action.TRAIN);
        check(workout.steps.get(0).minutes == 30, "Workout time");
        check("dumbbells".equals(workout.steps.get(0).equipment), "Workout equipment");

        good("focus for 25 minutes and open workspace",
                IntentRouter.Action.START_FOCUS, IntentRouter.Action.WORKSPACE);
        good("stop focus session", IntentRouter.Action.STOP_FOCUS);
        good("train 20 min at the gym", IntentRouter.Action.TRAIN);
        good("show screen time", IntentRouter.Action.USAGE);
        good("launch Google Maps", IntentRouter.Action.APP);
        good("open WhatsApp", IntentRouter.Action.APP);
        good("apps", IntentRouter.Action.APPS);
        good("open settings", IntentRouter.Action.SETTINGS);
        good("calendar", IntentRouter.Action.CALENDAR);
        good("focus", IntentRouter.Action.FOCUS_PICKER);
        good("read article", IntentRouter.Action.WORKSPACE);
        bad("focus 999 minutes");
        bad("train 17 minutes");
        bad("open unknown app");
        bad("open photo");
        bad("open notes and launch Chrome"); // two destinations
        bad("focus and open notes"); // missing duration
        bad("focus 25 minutes and stop focus"); // contradictory
        bad("turn off security permissions");
        bad("");
        System.out.println("IntentRouter tests passed (" + cases + " assertions).");
    }
}
