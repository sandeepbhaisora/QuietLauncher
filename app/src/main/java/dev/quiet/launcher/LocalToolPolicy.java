package dev.quiet.launcher;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure whitelisted, non-privileged model tool-selection policy. */
final class LocalToolPolicy {
    enum Kind { NONE, BATTERY, DEVICE_TIME, FOCUS_STATUS, APP_REVIEW }
    static final class Call {
        final Kind kind;
        final String argument;
        Call(Kind kind,String argument){this.kind=kind;this.argument=argument;}
    }
    private static final Pattern TOOL=Pattern.compile(
            "(?is)^\\s*(?:\\{\\s*\"tool\"\\s*:\\s*\"([a-z_]+)\"\\s*(?:,\\s*\"app\"\\s*:\\s*\"([\\p{L}0-9 ._-]{1,55})\")?\\s*\\}|"
                    + "TOOL:\\s*([a-z_]+))\\s*$");
    private static String clean(String question){
        return question==null?"":question.trim().toLowerCase(Locale.ROOT)
            .replaceFirst("^(?:hey londe|londe)[,\\s]+","").replaceAll("[.!?।]+$","");
    }
    static Call direct(String question){
        String q=clean(question);
        if(q.length()>200)return new Call(Kind.NONE,"");
        if(q.matches("(?:what(?:'s| is)? (?:my |the )?|show (?:my |the )?|"
                + "check (?:my |the )?|tell me (?:my |the )?)"
                + "(?:battery|battery level|battery percentage|charge level|charging status)"
                + "(?: (?:right now|now))?")
                || q.matches("(?:battery|battery status|battery percentage|battery kitni hai|"
                + "battery kitni bachi hai|बैटरी कितनी है|बैटरी कितनी बची है)"))
            return new Call(Kind.BATTERY,"");
        return new Call(Kind.NONE,"");
    }
    static Call fromModel(String originalQuestion,String output) {
        if(output==null||output.length()>256)return new Call(Kind.NONE,"");
        Matcher m=TOOL.matcher(output.trim());
        if(!m.matches())return new Call(Kind.NONE,"");
        String name=m.group(1)!=null?m.group(1):m.group(3);
        String question=clean(originalQuestion);
        switch(name.toLowerCase(Locale.ROOT)){
            case "get_battery":
                return question.matches("(?s).*(?:battery|charge|बैटरी).*")
                        ?new Call(Kind.BATTERY,""):new Call(Kind.NONE,"");
            case "get_time":
                return question.matches("(?s).*(?:\\btime\\b|\\bclock\\b|\\bbaje\\b|समय|बजे).*")
                        ?new Call(Kind.DEVICE_TIME,""):new Call(Kind.NONE,"");
            case "get_focus_status":
                return question.matches("(?s).*(?:\\bfocus\\b|deep work|फोकस).*")
                        ?new Call(Kind.FOCUS_STATUS,""):new Call(Kind.NONE,"");
            case "open_app":
                String app=m.group(2);
                if(app==null||app.isEmpty()||!question.matches("(?s).*(?:\\bopen\\b|"
                        + "\\blaunch\\b|\\bkhol\\w*\\b|खोलो).*"))
                    return new Call(Kind.NONE,"");
                // Only a named app explicitly present in the original user's command.
                if(!question.contains(app.toLowerCase(Locale.ROOT)))
                    return new Call(Kind.NONE,"");
                return new Call(Kind.APP_REVIEW,app);
            default:return new Call(Kind.NONE,"");
        }
    }
    private LocalToolPolicy(){}
}
