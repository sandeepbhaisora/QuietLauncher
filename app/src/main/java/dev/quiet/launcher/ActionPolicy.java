package dev.quiet.launcher;

import java.util.ArrayList;
import java.util.List;

/** Reject untrusted AI-generated plans that violate the Android action contract. */
final class ActionPolicy {
    private ActionPolicy() {}

    static IntentRouter.Plan validate(String text, List<IntentRouter.Step> proposed,
                                      List<String> installed) {
        if (proposed == null || proposed.isEmpty() || proposed.size() > 3)
            return invalid(text, "AI returned an invalid number of steps");
        List<IntentRouter.Step> checked = new ArrayList<>();
        int destinations = 0;
        boolean start = false, stop = false;
        for (IntentRouter.Step s : proposed) {
            if (s == null || s.action == null) return invalid(text, "Unknown action");
            if (s.opensScreen()) destinations++;
            int mins = s.minutes;
            String equip = s.equipment == null ? "" : s.equipment;
            String app = s.appName == null ? "" : s.appName;
            switch (s.action) {
                case START_FOCUS:
                    if (mins < 1 || mins > 180 || !equip.isEmpty() || !app.isEmpty())
                        return invalid(text, "Invalid focus parameters");
                    start = true;
                    break;
                case STOP_FOCUS:
                    if (mins != 0 || !equip.isEmpty() || !app.isEmpty())
                        return invalid(text, "Unexpected stop parameters");
                    stop = true;
                    break;
                case TRAIN:
                    if (!(mins == 0 || mins == 20 || mins == 30 || mins == 45)
                            || !(equip.isEmpty() || equip.equals("dumbbells")
                            || equip.equals("bodyweight") || equip.equals("gym")) || !app.isEmpty())
                        return invalid(text, "Invalid training parameters");
                    break;
                case APP:
                    if (mins != 0 || !equip.isEmpty() || app.length() > 100)
                        return invalid(text, "Invalid app parameters");
                    String exact = null;
                    if (installed != null) for (String name : installed) {
                        if (name.equalsIgnoreCase(app)) { exact = name; break; }
                    }
                    if (exact == null) return invalid(text, "App is missing or hidden");
                    app = exact;
                    break;
                default:
                    if (mins != 0 || !equip.isEmpty() || !app.isEmpty())
                        return invalid(text, "Unexpected action parameters");
            }
            checked.add(new IntentRouter.Step(s.action, mins, equip, app));
        }
        if (destinations > 1) return invalid(text, "Only one screen may open");
        if (start && stop) return invalid(text, "Cannot start and stop focus together");
        return new IntentRouter.Plan(text, checked, null);
    }

    private static IntentRouter.Plan invalid(String text, String problem) {
        return new IntentRouter.Plan(text, new ArrayList<>(), problem);
    }
}
