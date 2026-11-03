package dev.quiet.launcher;

public final class CalendarIntentTest {
    private static void yes(String text) {
        if (!CalendarIntent.isPersonalSchedule(text))
            throw new AssertionError("Missed personal schedule: "+text);
    }
    private static void no(String text) {
        if (CalendarIntent.isPersonalSchedule(text))
            throw new AssertionError("False personal schedule: "+text);
    }
    public static void main(String[] args) {
        yes("do I have any meetings today scheduled");
        yes("do I have to go anywhere today");
        yes("what meetings do I have today");
        yes("What's on my calendar tomorrow");
        yes("Aaj meri koi meeting hai kya?");
        yes("where do I need to go today?");
        no("kya hal chaal ho rahe");
        no("What is a meeting in Java?");
        no("explain Google Calendar API");
        no("what is a race condition");
        no("today is Tuesday");
        if(!CalendarIntent.isTravelQuestion("do I have to go anywhere today"))
            throw new AssertionError("Travel question must use calendar location");
        System.out.println("CalendarIntentTest passed");
    }
}
