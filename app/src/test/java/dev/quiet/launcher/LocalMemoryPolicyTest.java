package dev.quiet.launcher;

public final class LocalMemoryPolicyTest {
    private static void check(boolean ok, String why){
        if(!ok)throw new AssertionError(why);
    }
    public static void main(String[] args){
        check(LocalMemoryPolicy.parse("Remember that I prefer concise answers").command
                ==LocalMemoryPolicy.Command.REMEMBER,"remember parsing");
        check("I prefer concise answers".equals(
                LocalMemoryPolicy.parse("Remember that I prefer concise answers").fact),"fact");
        check(LocalMemoryPolicy.parse("Londe, what do you remember about me?")
                .command==LocalMemoryPolicy.Command.SHOW,"show");
        check(LocalMemoryPolicy.parse("Forget everything you remember")
                .command==LocalMemoryPolicy.Command.FORGET_ALL,"forget all");
        check(LocalMemoryPolicy.parse("What is Kafka?")
                .command==LocalMemoryPolicy.Command.NONE,"ordinary query");
        check(LocalMemoryPolicy.safeFact("I prefer short answers"),"safe preference");
        check(!LocalMemoryPolicy.safeFact("My password is 1234567"),"block secret");
        check(!LocalMemoryPolicy.safeFact("My phone number is 9861234567"),"block number");
        check(!LocalMemoryPolicy.safeFact("I take prescription medication"),"block health");
        check("technology".equals(LocalMemoryPolicy.topic("Explain Kafka partitions")),"tech");
        check("travel".equals(LocalMemoryPolicy.topic("Best mountain trip?")),"travel");
        System.out.println("LocalMemoryPolicyTest passed");
    }
}
