package dev.quiet.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Authoritative Android tools; model has no Android Context or permissions.
 * Only read-only calls run here. App launches go through the existing review.
 */
final class LocalToolExecutor {
    static String describe(Activity activity, LocalToolPolicy.Call call) {
        switch(call.kind){
            case BATTERY: {
                Intent battery=activity.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if(battery==null)return "Battery data isn't available from Android right now.";
                int level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
                int scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,100);
                int state=battery.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
                if(level<0||scale<=0)return "I couldn't read the battery level.";
                int percent=Math.round(level*100f/scale);
                boolean charging=state==BatteryManager.BATTERY_STATUS_CHARGING
                        ||state==BatteryManager.BATTERY_STATUS_FULL;
                return "Battery is at "+percent+" percent"+(charging?" and charging.":".");
            }
            case DEVICE_TIME:
                return "It's "+new SimpleDateFormat("h:mm a",Locale.getDefault()).format(new Date())
                        +" on your phone.";
            case FOCUS_STATUS: {
                long until=activity.getSharedPreferences("quiet_local_v1", Context.MODE_PRIVATE)
                        .getLong("focusUntil",0);
                long left=Math.max(0,until-System.currentTimeMillis());
                if(left<=0)return "No focus session is running.";
                return "Your focus session has about "+((left+59999)/60000)+" minutes left.";
            }
            default:
                return "This tool needs your review or is not available.";
        }
    }
    static boolean readOnly(LocalToolPolicy.Call call){
        return call.kind==LocalToolPolicy.Kind.BATTERY
                ||call.kind==LocalToolPolicy.Kind.DEVICE_TIME
                ||call.kind==LocalToolPolicy.Kind.FOCUS_STATUS;
    }
    private LocalToolExecutor(){}
}
