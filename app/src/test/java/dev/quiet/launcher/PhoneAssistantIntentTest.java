package dev.quiet.launcher;

public final class PhoneAssistantIntentTest {
    private static void kind(String text,PhoneAssistantIntent.Kind expected) {
        PhoneAssistantIntent value=PhoneAssistantIntent.parse(text);
        if(value.kind!=expected)
            throw new AssertionError("Expected "+expected+" for "+text+" got "+value.kind);
    }
    public static void main(String[] args) {
        kind("Achcha WhatsApp mein koi message aaya kya mujhe",
                PhoneAssistantIntent.Kind.WHATSAPP_NOTIFICATIONS);
        kind("Any new WhatsApp messages?",PhoneAssistantIntent.Kind.WHATSAPP_NOTIFICATIONS);
        kind("Read my notifications",PhoneAssistantIntent.Kind.NOTIFICATIONS);
        kind("can you speak in Hindi",PhoneAssistantIntent.Kind.SPEAK_HINDI);
        kind("Please speak in English",PhoneAssistantIntent.Kind.SPEAK_ENGLISH);
        kind("Send WhatsApp to Rahul saying I will be late",
                PhoneAssistantIntent.Kind.WHATSAPP_COMPOSE);
        kind("Send SMS saying I'll be there",
                PhoneAssistantIntent.Kind.SMS_COMPOSE);
        kind("What is WhatsApp?",PhoneAssistantIntent.Kind.NONE);
        kind("Explain the Android notification API",PhoneAssistantIntent.Kind.NONE);
        kind("Open WhatsApp",PhoneAssistantIntent.Kind.NONE);
        PhoneAssistantIntent message=PhoneAssistantIntent.parse(
                "Send WhatsApp to Rahul saying I will be late");
        if(!"I will be late".equals(message.message))
            throw new AssertionError("Wrong message body: "+message.message);
        if(!"Rahul".equals(message.recipient))
            throw new AssertionError("Wrong recipient: "+message.recipient);
        System.out.println("PhoneAssistantIntentTest passed");
    }
}
