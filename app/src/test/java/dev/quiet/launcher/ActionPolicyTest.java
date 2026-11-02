package dev.quiet.launcher;

import java.util.Arrays;
import java.util.Collections;

public final class ActionPolicyTest {
    private static final java.util.List<String> APPS = Arrays.asList("WhatsApp", "Google Maps");
    private static int count;
    private static IntentRouter.Step step(IntentRouter.Action a, int mins, String equip, String app) {
        return new IntentRouter.Step(a, mins, equip, app);
    }
    private static void yes(boolean good) {
        count++;
        if (!good) throw new AssertionError("Failure at case " + count);
    }
    private static IntentRouter.Plan plan(IntentRouter.Step... steps) {
        return ActionPolicy.validate("test", Arrays.asList(steps), APPS);
    }
    public static void main(String[] args) {
        yes(plan(step(IntentRouter.Action.START_FOCUS, 25, "", ""),
                step(IntentRouter.Action.WORKSPACE, 0, "", "")).valid());
        yes(plan(step(IntentRouter.Action.TRAIN, 30, "dumbbells", "")).valid());
        IntentRouter.Plan app = plan(step(IntentRouter.Action.APP, 0, "", "whatsapp"));
        yes(app.valid() && app.steps.get(0).appName.equals("WhatsApp"));
        yes(!plan(step(IntentRouter.Action.APP, 0, "", "Unknown")).valid());
        yes(!plan(step(IntentRouter.Action.START_FOCUS, 999, "", "")).valid());
        yes(!plan(step(IntentRouter.Action.TRAIN, 17, "", "")).valid());
        yes(!plan(step(IntentRouter.Action.WORKSPACE, 1, "", "")).valid());
        yes(!plan(step(IntentRouter.Action.WORKSPACE, 0, "", ""),
                step(IntentRouter.Action.NOTES, 0, "", "")).valid());
        yes(!plan(step(IntentRouter.Action.START_FOCUS, 25, "", ""),
                step(IntentRouter.Action.STOP_FOCUS, 0, "", "")).valid());
        yes(!ActionPolicy.validate("test", Collections.emptyList(), APPS).valid());
        System.out.println("ActionPolicy passed " + count + " tests");
    }
}
