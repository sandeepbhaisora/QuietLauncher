package dev.quiet.launcher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Reads only the date requested, after the user grants READ_CALENDAR.
 * No event details are ever sent to a local/remote LLM.
 */
final class LocalCalendarReader {
    static String read(Context context, String question) {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED)
            return "I don't have calendar permission. Give Quiet calendar access to check your meetings.";
        boolean tomorrow = CalendarIntent.isTomorrow(question);
        boolean travel = CalendarIntent.isTravelQuestion(question);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY,0);
        cal.set(Calendar.MINUTE,0);
        cal.set(Calendar.SECOND,0);
        cal.set(Calendar.MILLISECOND,0);
        if(tomorrow)cal.add(Calendar.DAY_OF_YEAR,1);
        long start=cal.getTimeInMillis();
        cal.add(Calendar.DAY_OF_YEAR,1);
        long end=cal.getTimeInMillis();
        Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
        android.content.ContentUris.appendId(uri,start);
        android.content.ContentUris.appendId(uri,end);
        String[] columns={
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.ALL_DAY
        };
        List<String> events=new ArrayList<>();
        try (Cursor cursor=context.getContentResolver().query(
                uri.build(),columns,null,null,CalendarContract.Instances.BEGIN+" ASC")) {
            if (cursor==null) return "Android Calendar didn't return events. Check that your calendars are synced and visible.";
            SimpleDateFormat timeFormat=new SimpleDateFormat("h:mm a",Locale.getDefault());
            while(cursor.moveToNext() && events.size()<8) {
                String title=cursor.getString(0);
                long when=cursor.getLong(1);
                String location=cursor.getString(2);
                boolean allDay=cursor.getInt(3)!=0;
                if(title==null||title.trim().isEmpty())title="Untitled event";
                if(travel && (location==null || location.trim().isEmpty())) continue;
                String item=(allDay?"All day":timeFormat.format(new java.util.Date(when)))+
                        " · "+title.trim();
                if(location!=null&&!location.trim().isEmpty())
                    item+=" · "+location.trim();
                events.add(item);
            }
        } catch(SecurityException e) {
            return "Android denied Calendar access. Check Quiet's Calendar permission in App Settings.";
        } catch(Exception e) {
            android.util.Log.w("LondeCalendar","Calendar query failed",e);
            return "I couldn't read your device calendar. Check calendar sync or permissions.";
        }
        String day=tomorrow?"tomorrow":"today";
        if(events.isEmpty()) {
            return travel
                    ? "I found no calendar events with a location for "+day+
                      ". I can't tell whether you have other travel plans."
                    : "I found no events in your device calendar for "+day+
                      ". That doesn't rule out meetings or plans in calendars that aren't synced.";
        }
        StringBuilder answer=new StringBuilder();
        answer.append(travel?"Your calendar events with locations for ":"Your calendar events for ")
                .append(day).append(": ");
        for(int i=0;i<events.size();i++){
            if(i>0)answer.append("; ");
            answer.append(events.get(i));
        }
        if(travel) answer.append(". These are calendar locations, not necessarily all places you need to visit.");
        return answer.toString();
    }
    private LocalCalendarReader() {}
}
