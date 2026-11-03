package dev.quiet.launcher;

import android.os.SystemClock;
import java.util.ArrayDeque;

/**
 * Ephemeral last-3 Qwen exchanges for quick follow-ups across wake launches.
 * Never written to disk and expires after 8 minutes without a conversation.
 */
final class LocalSessionContext {
    private static final int MAX_PAIRS=3;
    private static final long TTL_MS=8*60*1000;
    private static final ArrayDeque<String> turns=new ArrayDeque<>();
    private static long lastUpdated;

    static synchronized void record(String question, String answer){
        if(question==null || answer==null || question.isEmpty() || answer.isEmpty()
                || question.length()>550)return;
        String q=question.toLowerCase(java.util.Locale.ROOT);
        if(q.matches("(?s).*(?:password|\\botp\\b|\\bpin\\b|whatsapp|messages?|notification|"
                + "calendar|health|medical|address|\\bemail\\b|bank|account).*"))
            return;
        expire();
        turns.addLast("User: "+question.substring(0,Math.min(180,question.length()))+
                "\nAssistant: "+answer.substring(0,Math.min(160,answer.length())));
        while(turns.size()>MAX_PAIRS)turns.removeFirst();
        lastUpdated=SystemClock.elapsedRealtime();
    }
    static synchronized String recent(){
        expire();
        StringBuilder b=new StringBuilder();
        for(String pair:turns) {
            if(b.length()>0)b.append("\n");
            b.append(pair);
        }
        return b.toString();
    }
    static synchronized void clear(){turns.clear();lastUpdated=0;}
    private static void expire(){
        if(lastUpdated>0 && SystemClock.elapsedRealtime()-lastUpdated>TTL_MS)clear();
    }
    private LocalSessionContext(){}
}
