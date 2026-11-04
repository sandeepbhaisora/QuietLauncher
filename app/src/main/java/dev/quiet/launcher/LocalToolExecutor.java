package dev.quiet.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.media.AudioManager;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
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
            case VOLUME: {
                AudioManager audio=(AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);
                if(audio==null)return "Android audio service is unavailable.";
                int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int level=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                return max>0?"Media volume is "+Math.round(level*100f/max)+" percent.":"Media volume is unavailable.";
            }
            case SCREEN_TIME: {
                AppOpsManager ops=(AppOpsManager)activity.getSystemService(Context.APP_OPS_SERVICE);
                UsageStatsManager usage=(UsageStatsManager)activity.getSystemService(Context.USAGE_STATS_SERVICE);
                if(ops==null||usage==null)return "Screen-time data is unavailable.";
                int permission=ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),activity.getPackageName());
                if(permission!=AppOpsManager.MODE_ALLOWED)return "Allow Quiet Usage Access in Android Settings to read screen time.";
                java.util.Calendar start=java.util.Calendar.getInstance();
                start.set(java.util.Calendar.HOUR_OF_DAY,0);
                start.set(java.util.Calendar.MINUTE,0);
                start.set(java.util.Calendar.SECOND,0);
                start.set(java.util.Calendar.MILLISECOND,0);
                Map<String,UsageStats> stats=usage.queryAndAggregateUsageStats(start.getTimeInMillis(),System.currentTimeMillis());
                if(stats==null||stats.isEmpty())return "Android has no screen-time records for today.";
                long total=0;
                for(UsageStats item:stats.values())
                    if(item!=null&&!activity.getPackageName().equals(item.getPackageName()))
                        total+=Math.max(0,item.getTotalTimeInForeground());
                long minutes=total/60000;
                return "Android reports approximately "+(minutes/60)+" hours and "+(minutes%60)+
                        " minutes of foreground app usage today. This can include overlapping activity.";
            }
            case INSTALLED_APPS: {
                PackageManager pm=activity.getPackageManager();
                Intent intent=new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                TreeSet<String> names=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for(ResolveInfo info:pm.queryIntentActivities(intent,0))
                    if(info!=null&&info.activityInfo!=null&&info.loadLabel(pm)!=null)
                        names.add(info.loadLabel(pm).toString().trim());
                names.remove("");
                if(names.isEmpty())return "Android returned no launchable apps.";
                ArrayList<String> list=new ArrayList<>(names);
                int count=Math.min(8,list.size());
                return "I can see "+names.size()+" launchable apps. Examples: "+
                        String.join(", ",list.subList(0,count))+
                        (names.size()>count?". Open the app drawer for the full list.":".");
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
                ||call.kind==LocalToolPolicy.Kind.FOCUS_STATUS
                ||call.kind==LocalToolPolicy.Kind.VOLUME
                ||call.kind==LocalToolPolicy.Kind.SCREEN_TIME
                ||call.kind==LocalToolPolicy.Kind.INSTALLED_APPS;
    }
    private LocalToolExecutor(){}
}
