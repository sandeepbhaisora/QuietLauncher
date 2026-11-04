package dev.quiet.launcher;

public final class OfflineVoiceCommandsTest {
    private static void check(boolean expected,String input) {
        if(OfflineVoiceCommands.isExit(input)!=expected)
            throw new AssertionError("Exit intent for: "+input);
    }
    public static void main(String[] args) {
        check(true,"exit");
        check(true,"Exit!");
        check(true,"Hey Londe, exit");
        check(true,"Laundey quit");
        check(true,"Londe stop listening");
        check(true,"go to sleep");
        check(true,"बंद हो जा");
        check(false,"How do I exit Vim?");
        check(false,"Explain exit codes in Java");
        check(false,"What's the time?");
        check(false,"exist");
        check(false,"exit gracefully in Spring Boot");
        System.out.println("OfflineVoiceCommandsTest OK");
    }
}
