package dev.quiet.launcher;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Device request parser. No model response can trigger one of these actions. */
final class PhoneAssistantIntent {
    enum Kind { NONE, WHATSAPP_NOTIFICATIONS, NOTIFICATIONS, WHATSAPP_COMPOSE, SMS_COMPOSE,
        SPEAK_HINDI, SPEAK_ENGLISH }
    final Kind kind;
    final String message;
    final String recipient;

    private PhoneAssistantIntent(Kind kind, String message, String recipient) {
        this.kind=kind;
        this.message=message==null ? "" : message;
        this.recipient=recipient==null ? "" : recipient;
    }
    private static PhoneAssistantIntent of(Kind kind) {
        return new PhoneAssistantIntent(kind, "", "");
    }
    private static final Pattern BODY = Pattern.compile(
        "(?iu)(?:\\bsaying\\b|\\bthat says\\b|\\bwith text\\b|\\bmessage\\s*[:：]|"
        + "\\bki\\s+|\\bकह(?:कर|ते हुए)\\s+|\\bलिखो\\s*[:：]?)\\s*(.{1,450})$");
    private static final Pattern RECIPIENT = Pattern.compile(
        "(?iu)\\bto\\s+([\\p{L}][\\p{L} .'-]{0,65}?)\\s+(?:on|via|using)\\s+whatsapp\\b");
    private static final Pattern RECIPIENT_AFTER = Pattern.compile(
        "(?iu)\\bwhatsapp\\s+(?:message\\s+)?to\\s+([\\p{L}][\\p{L} .'-]{0,65}?)"
        + "\\s+(?:saying|that says|with text)\\b");

    static PhoneAssistantIntent parse(String input) {
        if(input==null || input.trim().isEmpty()) return of(Kind.NONE);
        String raw=input.trim().replace('’','\'');
        if(raw.length()>650) return of(Kind.NONE);
        String q=raw.toLowerCase(Locale.ROOT).replaceFirst(
            "^(?:hey londe|londe|achcha|accha|acha|okay|ok|अच्छा)[,\\s]+", "").trim();
        if(q.matches("(?su).*(?:speak|talk|reply|answer|baat kar|bolo|bolna|बोलो|बात करो)"
                + ".*(?:in hindi|hindi mein|hindi me|हिंदी|हिन्दी).*")
                || q.matches("(?su)^(?:hindi mein baat karo|हिंदी में बात करो)[.!? ]*$"))
            return of(Kind.SPEAK_HINDI);
        if(q.matches("(?su).*(?:speak|talk|reply|answer|baat kar|बोलो|बात करो)"
                + ".*(?:in english|english mein|इंग्लिश|अंग्रेजी).*"))
            return of(Kind.SPEAK_ENGLISH);

        boolean whatsapp=q.contains("whatsapp") || q.contains("व्हाट्सएप")
                || q.contains("वॉट्सऐप") || q.contains("वाट्सएप");
        boolean message=q.matches("(?su).*(?:\\bmessage\\b|\\bmessages\\b|\\bmsg\\b|"
                + "मैसेज|संदेश|\\bnotification\\b|\\bnotifications\\b).*");
        boolean writing=q.matches("(?su).*(?:\\bsend\\b|\\bcompose\\b|\\bwrite\\b|"
                + "\\bbhej\\w*\\b|भेजो|भेजना|भेज दो|\\btext\\s+\\w+).*");
        if(whatsapp && writing) return compose(Kind.WHATSAPP_COMPOSE,raw);
        if (!whatsapp && writing && q.matches("(?s).*(?:\\bsms\\b|\\btext message\\b).*"))
            return compose(Kind.SMS_COMPOSE,raw);

        if(whatsapp && message &&
                q.matches("(?su).*(?:\\bany\\b|\\bnew\\b|\\blast\\b|\\blatest\\b|"
                        + "\\breceived\\b|\\bcheck\\b|\\bread\\b|\\bshow\\b|\\bkoi\\b|"
                        + "\\baaya\\b|\\baya\\b|\\baayi\\b|\\bnotification\\b|"
                        + "\\bkya\\b|आया|आई|कोई|दिखा|पढ़|क्या).*"))
            return of(Kind.WHATSAPP_NOTIFICATIONS);
        if(!whatsapp && q.matches("(?su).*(?:\\bnotifications?\\b|नोटिफिकेशन).*")
                && !q.matches("(?su)^(?:what is|explain|how do|define|क्या होता).*"))
            return of(Kind.NOTIFICATIONS);
        return of(Kind.NONE);
    }
    private static PhoneAssistantIntent compose(Kind kind, String raw) {
        String body="";
        Matcher content=BODY.matcher(raw);
        if(content.find()) body=content.group(1).trim();
        String recipient="";
        Matcher before=RECIPIENT.matcher(raw);
        if(before.find()) recipient=before.group(1).trim();
        else {
            Matcher after=RECIPIENT_AFTER.matcher(raw);
            if(after.find()) recipient=after.group(1).trim();
        }
        return new PhoneAssistantIntent(kind, body, recipient);
    }
    private PhoneAssistantIntent() {}
}
