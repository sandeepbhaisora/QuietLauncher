package dev.quiet.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Private app-storage memory, OFF by default. No LLM extraction, embeddings,
 * network access, notification text, calendar data, or raw chat transcripts.
 * Explicit voice notes and coarse anonymous topic counts only.
 */
final class LocalMemoryStore {
    private static final String PREF="londe_local_memory_v1";
    private static final String ENABLED="enabled", FACTS="facts", COUNTS="counts";
    private static final int LIMIT=12;
    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }
    static boolean enabled(Context c){return prefs(c).getBoolean(ENABLED,false);}
    static void enable(Context c, boolean value) {
        prefs(c).edit().putBoolean(ENABLED,value).apply();
    }
    static synchronized void clear(Context c) {
        prefs(c).edit().remove(FACTS).remove(COUNTS).apply();
    }
    private static JSONArray facts(Context c) {
        try{return new JSONArray(prefs(c).getString(FACTS,"[]"));}
        catch(Exception e){return new JSONArray();}
    }
    private static JSONObject counts(Context c){
        try{return new JSONObject(prefs(c).getString(COUNTS,"{}"));}
        catch(Exception e){return new JSONObject();}
    }
    static synchronized String command(Context c, String request) {
        LocalMemoryPolicy.Parsed parsed=LocalMemoryPolicy.parse(request);
        if(parsed.command==LocalMemoryPolicy.Command.NONE)return null;
        if(parsed.command==LocalMemoryPolicy.Command.FORGET_ALL) {
            clear(c);
            return "Local memories and question-topic counts cleared from this device.";
        }
        if(!enabled(c))
            return "Local memory is off. Enable it under Quiet Settings → Local AI → On-device memory.";
        if(parsed.command==LocalMemoryPolicy.Command.SHOW)return describe(c);
        if(!LocalMemoryPolicy.safeFact(parsed.fact))
            return "I can't save that note. Keep it short and avoid passwords, personal identifiers, medical or other sensitive details.";
        JSONArray existing=facts(c);
        JSONArray next=new JSONArray();
        String note=parsed.fact.trim();
        for(int i=0;i<existing.length();i++){
            String old=existing.optString(i,"");
            if(!old.equalsIgnoreCase(note) && next.length()<LIMIT-1)next.put(old);
        }
        next.put(note);
        prefs(c).edit().putString(FACTS,next.toString()).apply();
        return "Remembered on this phone: "+note;
    }
    static synchronized void countQuestion(Context c,String question){
        if(!enabled(c))return;
        // Only a category, not the question, is saved.
        String category=LocalMemoryPolicy.topic(question);
        JSONObject old=counts(c);
        try{old.put(category, Math.min(1000,old.optInt(category,0)+1));}
        catch(Exception ignored){return;}
        prefs(c).edit().putString(COUNTS,old.toString()).apply();
    }
    static synchronized String prompt(Context c){
        if(!enabled(c))return "";
        JSONArray values=facts(c);
        StringBuilder hints=new StringBuilder();
        for(int i=0;i<values.length();i++){
            String note=values.optString(i,"");
            if(!LocalMemoryPolicy.safeFact(note))continue;
            if(hints.length()>0)hints.append("; ");
            if(hints.length()+note.length()>310)break;
            hints.append(note);
        }
        String top=topTopics(c);
        if(!top.isEmpty()){
            if(hints.length()>0)hints.append(". ");
            hints.append("Frequent topics: ").append(top);
        }
        return hints.toString();
    }
    static synchronized String describe(Context c){
        JSONArray list=facts(c);
        StringBuilder b=new StringBuilder("Saved notes: ");
        if(list.length()==0)b.append("none");
        for(int i=0;i<list.length();i++)b.append("\n").append(i+1).append(". ").append(list.optString(i));
        String topics=topTopics(c);
        b.append("\nFrequent question categories: ").append(topics.isEmpty()?"none yet":topics);
        b.append("\nStored only in Quiet's private app data. You can clear it anytime.");
        return b.toString();
    }
    static synchronized String status(Context c){
        if(!enabled(c))return "Off · no learning or saved notes";
        return "On · "+facts(c).length()+" saved notes · topic counts only";
    }
    private static String topTopics(Context c){
        JSONObject scores=counts(c);
        Map<String,Integer> ranked=new LinkedHashMap<>();
        for(String topic:new String[]{"technology","travel","entertainment","food","fitness","productivity","other"}){
            int n=scores.optInt(topic,0);
            if(n>0)ranked.put(topic,n);
        }
        return ranked.entrySet().stream()
                .sorted((x,y)->Integer.compare(y.getValue(),x.getValue()))
                .limit(3)
                .map(Map.Entry::getKey)
                .reduce((x,y)->x+", "+y).orElse("");
    }
    private LocalMemoryStore(){}
}
