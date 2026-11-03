package dev.quiet.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import java.util.function.Consumer;

/** Fast, local, permission-gated phone skills. Never silently sends a message. */
final class PhoneAssistantActions {
    private PhoneAssistantActions() {}

    static boolean tryHandle(Activity activity, String question, Consumer<String> reply) {
        PhoneAssistantIntent action=PhoneAssistantIntent.parse(question);
        switch(action.kind) {
            case SPEAK_HINDI:
                activity.getSharedPreferences("quiet_local_v1", Activity.MODE_PRIVATE)
                        .edit().putString("londe_reply_language", "hi").apply();
                reply.accept("हाँ भाई, अब हिंदी में बात करेंगे।");
                return true;
            case SPEAK_ENGLISH:
                activity.getSharedPreferences("quiet_local_v1", Activity.MODE_PRIVATE)
                        .edit().putString("londe_reply_language", "en").apply();
                reply.accept("Sure, I'll reply in English.");
                return true;
            case WHATSAPP_NOTIFICATIONS:
            case NOTIFICATIONS:
                boolean whatsapp=action.kind==PhoneAssistantIntent.Kind.WHATSAPP_NOTIFICATIONS;
                if (!LondeNotificationListener.includedInBuild(activity)) {
                    reply.accept("This standard Quiet APK does not include notification access. "
                            + "Londe can open apps and prepare messages, but it cannot "
                            + "read WhatsApp or other notifications.");
                    return true;
                }
                if(!LondeNotificationListener.authorized(activity) ||
                        !LondeNotificationListener.connected()) {
                    reply.accept("Notification access is needed to check your phone. "
                            + "Only currently visible notifications can be read, not WhatsApp chat history.");
                    new AlertDialog.Builder(activity)
                            .setTitle("Allow local notification access?")
                            .setMessage("Android grants broad notification access, which can include private messages. "
                                    + "Quiet reads active notifications only when you ask. "
                                    + "Their contents aren't sent to Qwen or ChatGPT. "
                                    + "Enable Quiet in Android Notification Access settings.")
                            .setPositiveButton("Open Android settings", (d,w)->{
                                try { activity.startActivity(new Intent(
                                        Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
                                catch(Exception e) { reply.accept("Could not open Notification Access settings."); }
                            })
                            .setNegativeButton("Not now",null).show();
                    return true;
                }
                reply.accept(LondeNotificationListener.readActive(whatsapp));
                return true;
            case WHATSAPP_COMPOSE:
                composeWhatsapp(activity,action,reply);
                return true;
            case SMS_COMPOSE:
                composeSms(activity,action,reply);
                return true;
            default:
                return false;
        }
    }

    static void requestNotificationAccess(Activity activity) {
        if (!LondeNotificationListener.includedInBuild(activity)) {
            new AlertDialog.Builder(activity)
                    .setTitle("Notification access not included")
                    .setMessage("This standard APK excludes Android's privileged notification "
                            + "listener to address a Google Play Protect restriction for manually "
                            + "downloaded apps. Other Londe features remain available. "
                            + "The full edition requires a permitted distribution route.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Londe notification access")
                .setMessage("To check WhatsApp messages, enable Quiet in Android Notification Access. "
                        + "Android grants access to notifications from multiple apps, not only WhatsApp. "
                        + "Quiet reads currently visible notifications on request and never gives them to an AI model.")
                .setPositiveButton("Android settings", (d,w) -> {
                    try { activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
                    catch(Exception ignored) { }
                })
                .setNegativeButton("Cancel",null).show();
    }

    private static void composeWhatsapp(Activity activity,PhoneAssistantIntent intent,
                                        Consumer<String> reply) {
        if(intent.message.isEmpty()) {
            reply.accept("What should the WhatsApp message say? For example: "
                    + "Send WhatsApp saying I'll be late.");
            return;
        }
        String recipient=intent.recipient.isEmpty()?"the recipient you select in WhatsApp"
                : intent.recipient;
        new AlertDialog.Builder(activity).setTitle("Prepare WhatsApp message")
                .setMessage("To: " + recipient + "\n\nMessage: " + intent.message
                        + "\n\nQuiet will open a WhatsApp draft. Select the correct chat and tap Send yourself.")
                .setPositiveButton("Open WhatsApp draft",(d,w)-> {
                    Intent share=new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_TEXT,intent.message);
                    share.setPackage("com.whatsapp");
                    try { activity.startActivity(share); }
                    catch(Exception first) {
                        share.setPackage("com.whatsapp.w4b");
                        try { activity.startActivity(share); }
                        catch(Exception second) {
                            reply.accept("WhatsApp isn't available to compose a message.");
                            return;
                        }
                    }
                    reply.accept("WhatsApp draft opened. Choose the recipient and tap Send. Nothing has been sent yet.");
                })
                .setNegativeButton("Cancel",null).show();
        reply.accept("Review the WhatsApp message before opening the draft.");
    }

    private static void composeSms(Activity activity,PhoneAssistantIntent action,
                                   Consumer<String> reply) {
        if(action.message.isEmpty()) {
            reply.accept("What should the SMS say? Example: Send SMS saying I'll be late.");
            return;
        }
        new AlertDialog.Builder(activity).setTitle("Prepare SMS")
                .setMessage("Message: " + action.message
                        + "\n\nSelect the recipient and send it yourself in Messages.")
                .setPositiveButton("Open SMS draft",(d,w)-> {
                    Intent sms=new Intent(Intent.ACTION_SENDTO,Uri.parse("smsto:"));
                    sms.putExtra("sms_body",action.message);
                    try {
                        activity.startActivity(sms);
                        reply.accept("SMS draft opened. Choose the recipient and tap Send. Nothing has been sent yet.");
                    } catch(Exception e) { reply.accept("No SMS composer is available on this phone."); }
                })
                .setNegativeButton("Cancel",null).show();
        reply.accept("Review the SMS message before opening the draft.");
    }
}
