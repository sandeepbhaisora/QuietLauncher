package dev.quiet.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whitelisted, voice-authorized local actions only.
 * Does not automate third-party screens, send messages, or click security dialogs.
 * All selected apps must be installed, visible and unambiguous.
 */
final class LondeDeviceActions {
    static final class Result {
        final boolean handled;
        final String spoken;
        final Intent navigation;
        Result(boolean handled, String spoken, Intent navigation) {
            this.handled = handled;
            this.spoken = spoken;
            this.navigation = navigation;
        }
    }

    private LondeDeviceActions() {}

    static Result execute(Activity activity, String recognized) {
        PackageManager pm = activity.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> installed = pm.queryIntentActivities(query, 0);
        Set<String> names = new HashSet<>();
        for (ResolveInfo info : installed) {
            String name = info.loadLabel(pm).toString();
            if (name != null && !name.trim().isEmpty()) names.add(name);
        }
        String original = LondeCommands.normalize(recognized);
        if (original == null) return new Result(false, "", null);
        IntentRouter.Plan plan = IntentRouter.parse(original, new ArrayList<>(names));
        if (!plan.valid()) return new Result(false, "", null);
        plan = ActionPolicy.validate(original, plan.steps, new ArrayList<>(names));
        if (!plan.valid()) return new Result(false, "", null);

        StringBuilder confirmation = new StringBuilder();
        Intent navigate = null;
        SharedPreferences prefs = activity.getSharedPreferences("quiet_local_v1", Activity.MODE_PRIVATE);
        // Validate all steps before performing anything; avoid half-executed plans.
        for (IntentRouter.Step step : plan.steps) {
            switch (step.action) {
                case START_FOCUS: case STOP_FOCUS: break;
                case APP: {
                    int matches = 0;
                    for (ResolveInfo info : installed) {
                        if (step.appName.equalsIgnoreCase(info.loadLabel(pm).toString())) matches++;
                    }
                    if (matches != 1) return new Result(false, "", null);
                    break;
                }
                default:
                    return new Result(false, "", null);
            }
        }
        for (IntentRouter.Step step : plan.steps) {
            switch (step.action) {
                case START_FOCUS:
                    prefs.edit().putLong("focusUntil",
                            System.currentTimeMillis() + step.minutes * 60000L).apply();
                    confirmation.append("Focus ").append(step.minutes).append(" minutes set. ");
                    break;
                case STOP_FOCUS:
                    prefs.edit().remove("focusUntil").apply();
                    confirmation.append("Focus stopped. ");
                    break;
                case APP:
                    for (ResolveInfo info : installed) {
                        if (step.appName.equalsIgnoreCase(info.loadLabel(pm).toString())) {
                            String pkg = info.activityInfo.packageName;
                            Intent launch = pm.getLaunchIntentForPackage(pkg);
                            if (launch != null) {
                                navigate = launch;
                                confirmation.append("Opening ").append(step.appName).append(". ");
                            }
                            break;
                        }
                    }
                    if (navigate == null) return new Result(false, "", null);
                    break;
                default: return new Result(false, "", null);
            }
        }
        return new Result(true, confirmation.toString().trim(), navigate);
    }
}
