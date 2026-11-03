package dev.quiet.launcher;

import java.util.Locale;

/** Pure offline policy for opt-in assistant memory: explicit facts and coarse topics. */
final class LocalMemoryPolicy {
    enum Command { NONE, REMEMBER, SHOW, FORGET_ALL }
    static final class Parsed {
        final Command command;
        final String fact;
        Parsed(Command command, String fact) { this.command=command; this.fact=fact; }
    }

    static Parsed parse(String utterance) {
        if(utterance==null)return new Parsed(Command.NONE,"");
        String q=utterance.trim().replace('’','\'');
        q=q.replaceFirst("(?i)^(?:hey londe|londe)[,\\s]+","");
        String lower=q.toLowerCase(Locale.ROOT);
        if(lower.matches("(?s)^(?:forget (?:everything|all) (?:you |that you )?(?:remember|know)(?: about me)?|"
                + "clear (?:all )?(?:my )?memories|delete (?:all )?(?:my )?memories|"
                + "sab kuch bhool jao|सब कुछ भूल जाओ)[.!? ]*$"))
            return new Parsed(Command.FORGET_ALL,"");
        if(lower.matches("(?s)^(?:what do you remember(?: about me)?|"
                + "what have you remembered|show (?:my |your )?memories|"
                + "what do you know about me|kya yaad hai|क्या याद है)[.!? ]*$"))
            return new Parsed(Command.SHOW,"");
        String fact=q.replaceFirst("(?iu)^(?:remember that|remember this|remember|"
                + "yaad rakh(?:na|o)?(?: ki)?|याद रखो(?: कि)?)\\s*[:,-]?\\s*","");
        if(!fact.equals(q))return new Parsed(Command.REMEMBER,fact.trim());
        return new Parsed(Command.NONE,"");
    }

    static boolean safeFact(String value) {
        if(value==null)return false;
        String s=value.trim();
        if(s.length()<5 || s.length()>140 || s.contains("\n") || s.contains("\r")
                || s.contains("@") || s.matches("(?s).*\\d{6,}.*"))return false;
        String lower=s.toLowerCase(Locale.ROOT);
        // The app cannot verify all sensitive content; these are conservative guards,
        // not a guarantee. Memory is manual/opt-in and reviewable/deletable.
        return !lower.matches("(?s).*(?:password|passcode|\\bpin\\b|\\botp\\b|"
                + "one.time code|security code|cvv|credit card|debit card|"
                + "bank account|aadhaar|aadhar|passport|\\baddress\\b|"
                + "house number|street address|phone number|"
                + "medical|disease|diagnos|medication|prescription|"
                + "therapy|mental health|pregnan|sex life|sexual orientation|"
                + "caste|ethnic|religion|political party|political ideology|"
                + "suicid|self.harm|whatsapp chat|private message|"
                + "ignore previous|ignore instruction|system prompt|you must).*");
    }

    static String topic(String input) {
        if(input==null)return "other";
        String q=input.toLowerCase(Locale.ROOT);
        if(q.matches("(?s).*(?:java|kotlin|code|programming|backend|kafka|spring|"
                + "android|github|api|database|software|develop|computer|model|llm|\\bai\\b).*"))
            return "technology";
        if(q.matches("(?s).*(?:travel|trip|hotel|tour|route|drive|mountain|flight|"
                + "vacation|trek|destination).*"))return "travel";
        if(q.matches("(?s).*(?:movie|series|netflix|game|gaming|music|song|film).*"))
            return "entertainment";
        if(q.matches("(?s).*(?:recipe|cook|food|dish|ingredient|maggi|pasta).*"))
            return "food";
        if(q.matches("(?s).*(?:workout|exercise|train|fitness|dumbbell).*"))
            return "fitness";
        if(q.matches("(?s).*(?:focus|productivity|schedule|task|study|learn).*"))
            return "productivity";
        return "other";
    }

    private LocalMemoryPolicy() {}
}
