package local.javatrace.core;

import java.util.List;
import java.util.Objects;
import local.javatrace.core.RecognitionResult.Status;

/** Manually annotated Logback extended frames, including the reported Boot startup shape. */
final class PackagingDataRegression {
    private static final JavaExceptionRecognizer RECOGNIZER = new JavaExceptionRecognizer();
    private static int assertions;

    static int run() {
        for (String suffix : List.of(" ~[redisson-3.17.7.jar:3.17.7]", " ~[na:na]", " [classes/:na]",
                " [demo service.jar:1.0-SNAPSHOT]", "\t~[library.jar:1.0]")) {
            for (String newline : List.of("\n", "\r\n")) {
                String context = "2026-11-11 10:20:30.123 ERROR 1234 --- [main] example.Boot : Startup failed 🧩" + newline;
                String info = "2026-11-11 10:20:30.124  INFO 1234 --- [main] example.Boot : ordinary output" + newline;
                String trace = ("org.springframework.beans.factory.BeanCreationException: synthetic Redis failure\n"
                        + "\tat example.RedisConfiguration.create(RedisConfiguration.java:20)" + suffix + "\n"
                        + "\tSuppressed: java.lang.IllegalStateException: cleanup\n"
                        + "\t\tat example.Connection.close(Connection.java:9)" + suffix + "\n"
                        + "\t\t... 1 common frames omitted\n"
                        + "Caused by: java.util.concurrent.CompletionException: org.redisson.client.RedisException: WRONGPASS synthetic\n"
                        + "\tat java.base/java.util.concurrent.CompletableFuture.encodeRelay(CompletableFuture.java:368)" + suffix + "\n"
                        + "\t... 2 common frames omitted\n"
                        + "Caused by: org.redisson.client.RedisException: WRONGPASS synthetic\n"
                        + "\tat org.redisson.client.handler.CommandDecoder.decode(CommandDecoder.java:370)" + suffix + "\n"
                        + "\t... 3 common frames omitted\n").replace("\n", newline);
                String text=context+trace+info+trace+info;
                var batch=RECOGNIZER.recognizeAll(text,false,false);
                equal(2,batch.exceptions().size(),"independent repeated traces");
                equal(List.of(),batch.warnings(),"no scan warning");
                int start=context.length(), repeatedStart=start+trace.length()+info.length();
                var ranges=List.of(new TextRange(start,start+trace.length()),new TextRange(repeatedStart,repeatedStart+trace.length()));
                for(int i=0;i<2;i++) {
                    var result=batch.exceptions().get(i);
                    equal(Status.FOUND,result.status(),"complete chain");
                    equal(ranges.get(i),result.exceptionRange().orElseThrow(),"original extended frames retained");
                    equal(i==0,result.contextRange().isPresent(),"Boot ERROR ownership");
                }
                equal(new TextRange(0,start),batch.exceptions().getFirst().contextRange().orElseThrow(),"exact original ERROR");
                for(int anchor=start;anchor<start+trace.length();) {
                    var result=RECOGNIZER.recognize(text,anchor);
                    equal(Status.FOUND,result.status(),"inner line complete");
                    equal(ranges.getFirst(),result.exceptionRange().orElseThrow(),"frame/cause/suppressed anchor");
                    anchor=text.indexOf('\n',anchor)+1;
                }
                equal(Status.NOT_FOUND,RECOGNIZER.recognize(text,start+trace.length()).status(),"Boot INFO excluded");
            }
        }
        for(String suffix:List.of(" [na]"," ~[jar:]"," ~[:1]"," ~[jar:1", " [jar:1] extra",
                " ~[jar:1] [other:2]", " [jar:[version]]", " ordinary message")) {
            String title="java.lang.IllegalStateException: boundary\n";
            String frame="\tat example.Sample.run(Sample.java:10)\n";
            String broken="\tat example.Sample.run(Sample.java:11)"+suffix+"\n";
            String text=title+frame+broken;
            var result=RECOGNIZER.recognize(text,0);
            equal(Status.UNCERTAIN,result.status(),"invalid tail remains conservative");
            equal(new TextRange(0,title.length()+frame.length()),result.exceptionRange().orElseThrow(),"invalid tail excluded");
        }
        return assertions;
    }

    private static void equal(Object expected,Object actual,String label) {
        assertions++;
        if(!Objects.equals(expected,actual)) throw new AssertionError(label+": expected "+expected+", got "+actual);
    }
}
