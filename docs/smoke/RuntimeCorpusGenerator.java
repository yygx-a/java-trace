import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;

/** Produces synthetic, shareable output using actual JVM and logger renderers.
 * No recognizer is used. The manifest is annotated from producer-owned parts.
 * Run only to deliberately refresh the corpus, never as part of the tests. */
public final class RuntimeCorpusGenerator {
    record Case(String split, String id, String prefix, String body, String suffix,
                String ranges, String states, String warnings, String notes) {}
    static final List<Case> cases = new ArrayList<>();
    static final String INFO = "INFO corpus - finished\n";
    static final String ERROR = "ERROR corpus - synthetic request\n";
    static final StackTraceElement FRAME = new StackTraceElement("example.Corpus", "run", "Corpus.java", 42);

    public static void main(String[] args) throws Exception {
        String chain = print(chain());
        add("development", "jdk-cause", ERROR, chain, INFO, "FOUND", "-", "-");
        var cycle = throwable("cycle"); var inner = throwable("inner");
        cycle.initCause(inner); inner.initCause(cycle);
        add("development", "jdk-cause-cycle", ERROR, print(cycle), INFO, "FOUND", "-", "-");
        var suppressed = throwable("root"); var child = throwable("suppressed");
        suppressed.addSuppressed(child); child.addSuppressed(suppressed);
        add("development", "jdk-suppressed-cycle", ERROR, print(suppressed), INFO, "FOUND", "-", "-");
        var emptyRoot = new QuietException("quiet wrapper", chain());
        add("development", "jdk-stackless-wrapper", ERROR, print(emptyRoot), INFO, "FOUND", "-", "BRANCH_WITHOUT_FRAMES");
        var emptyCause = throwable("root"); emptyCause.initCause(new QuietException("quiet cause", null));
        add("development", "jdk-stackless-cause", ERROR, print(emptyCause), INFO, "FOUND", "-", "BRANCH_WITHOUT_FRAMES");
        add("development", "jdk-header-only", ERROR, print(new QuietException("quiet", null)), INFO, "UNCERTAIN", "MISSING_STACK", "-");
        String fragment = "\tat example.Corpus.run(Corpus.java:42)\n";
        add("development", "frame-fragment", "", fragment, INFO, "UNCERTAIN", "MISSING_HEADER", "-");
        String full = print(throwable("message\nunknown extra message"));
        int end = full.indexOf('\n') + 1;
        cases.add(new Case("development", "unknown-message", ERROR, full, INFO,
                range(ERROR.length(), ERROR.length()+end)+";"+range(ERROR.length()+full.indexOf("\tat"), ERROR.length()+full.length()),
                "UNCERTAIN;UNCERTAIN", "MISSING_STACK,AMBIGUOUS_BOUNDARY;MISSING_HEADER", "-;-"));
        pair("development", "adjacent-independent", "", chain, print(throwable("second")));
        logger("development", "logback-basic", false, "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger - %msg%n%ex", chain());
        logger("development", "logback-packaging", false, "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %logger - %msg%n%xEx", chain());
        logger("development", "log4j-extended", true, "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5p %c - %m%n%xEx", chain());

        // These acceptance inputs and expectations are fixed before parser changes.
        // They are authored here, so they are a reserved acceptance set, NOT an independent blind evaluation.
        var complex = throwable("root"); var first = throwable("first"); var second = throwable("second");
        first.initCause(new QuietException("quiet nested wrapper", chain()));
        complex.addSuppressed(first); complex.addSuppressed(second); second.initCause(complex);
        complex.initCause(throwable("root cause"));
        add("acceptance", "nested-suppressed-causes", ERROR, print(complex), INFO, "FOUND", "-", "BRANCH_WITHOUT_FRAMES");
        var module = throwable("module");
        module.setStackTrace(new StackTraceElement[]{new StackTraceElement("app", "example.module", "1.0", "example.Corpus", "run", "Corpus.java", 12),
                new StackTraceElement("java.base", "java.base", "21", "java.lang.Thread", "run", null, -1)});
        add("acceptance", "jdk-modules", ERROR, print(module), INFO, "FOUND", "-", "-");
        var quietSuppressed = throwable("root"); quietSuppressed.addSuppressed(new QuietException("quiet suppressed", null));
        quietSuppressed.initCause(chain());
        add("acceptance", "stackless-suppressed", ERROR, print(quietSuppressed), INFO, "FOUND", "-", "BRANCH_WITHOUT_FRAMES");
        logger("acceptance", "logback-time-only", false, "%d{HH:mm:ss.SSS} [%thread] %-5level %logger - %msg%n%ex", complex);
        logger("acceptance", "log4j-level-first", true, "%-5p [%t] %c - %m%n%xEx", complex);
        pair("acceptance", "duplicate-events", ERROR, chain, chain);
        cases.add(new Case("acceptance", "negative-ordinary", "", "INFO corpus - Exception at normal text\nERROR corpus - business refused\n", "", "-", "-", "-", "-"));
        String broken = print(throwable("partial")); int frameAt = broken.indexOf("\tat");
        cases.add(new Case("acceptance", "broken-frame", ERROR,
                broken.substring(0, frameAt)+"\tat example.Corpus.run(Corpus.java:42) extra\n", INFO,
                range(ERROR.length(),ERROR.length()+frameAt), "UNCERTAIN", "MISSING_STACK,AMBIGUOUS_BOUNDARY", "-"));

        Path out = Path.of(args[0]); Files.createDirectories(out);
        StringBuilder manifest = new StringBuilder("# split\tid\tranges UTF-16 half-open\tstatus per range\twarnings per range\tnotes per range\tcontexts per range\n");
        for (Case c : cases) {
            Files.writeString(out.resolve(c.id+".log"), c.prefix+c.body+c.suffix);
            manifest.append(c.split).append('\t').append(c.id).append('\t').append(c.ranges).append('\t').append(c.states)
                    .append('\t').append(c.warnings).append('\t').append(c.notes).append('\t');
            for (int i=0; i<c.ranges.split(";").length; i++) {
                if(i>0) manifest.append(';');
                manifest.append(i==0 && !c.prefix.isEmpty() ? range(0,c.prefix.length()) : "-");
            }
            manifest.append('\n');
        }
        Files.writeString(out.resolve("annotations.tsv"), manifest);
        System.out.println("Generated "+cases.size()+" fixed producer-annotated cases, no parser invoked.");
    }
    static void add(String split, String id, String prefix, String body, String suffix, String status, String warning, String note) {
        cases.add(new Case(split,id,prefix,body,suffix,range(prefix.length(),prefix.length()+body.length()),status,warning,note));
    }
    static String range(int start, int end) { return start+":"+end; }
    static void pair(String split, String id, String prefix, String a, String b) {
        cases.add(new Case(split,id,prefix,a+b,INFO,
                range(prefix.length(),prefix.length()+a.length())+";"+range(prefix.length()+a.length(),prefix.length()+a.length()+b.length()),
                "FOUND;FOUND", "-;-", "-;-"));
    }
    static IllegalStateException throwable(String message) {
        var e = new IllegalStateException(message); e.setStackTrace(new StackTraceElement[]{FRAME}); return e;
    }
    static Throwable chain() { var e=throwable("synthetic 中文 🧩"); e.initCause(throwable("cause")); return e; }
    static String print(Throwable e) { var out=new StringWriter(); e.printStackTrace(new PrintWriter(out)); return out.toString().replace("\r\n","\n"); }
    static class QuietException extends RuntimeException {
        QuietException(String message, Throwable cause) { super(message,cause,true,false); }
    }
    static void logger(String split, String id, boolean log4j, String pattern, Throwable e) {
        String rendered, heading;
        if (log4j) {
            var layout=org.apache.logging.log4j.core.layout.PatternLayout.newBuilder().withPattern(pattern).build();
            var event=Log4jLogEvent.newBuilder().setLoggerName("example.Corpus").setLevel(org.apache.logging.log4j.Level.ERROR)
                    .setThreadName("worker").setTimeMillis(1_700_000_000_000L).setMessage(new SimpleMessage("synthetic request")).setThrown(e).build();
            rendered=layout.toSerializable(event).replace("\r\n","\n");
        } else {
            var context=new LoggerContext(); context.setPackagingDataEnabled(true);
            var layout=new PatternLayout(); layout.setContext(context); layout.setPattern(pattern); layout.start();
            var event=new LoggingEvent(); event.setLoggerName("example.Corpus"); event.setLevel(ch.qos.logback.classic.Level.ERROR);
            event.setThreadName("worker"); event.setTimeStamp(1_700_000_000_000L); event.setMessage("synthetic request");
            var proxy=new ThrowableProxy(e); proxy.calculatePackagingData(); event.setThrowableProxy(proxy);
            rendered=layout.doLayout(event).replace("\r\n","\n"); layout.stop(); context.stop();
        }
        int newline=rendered.indexOf('\n')+1; heading=rendered.substring(0,newline);
        add(split,id,heading,rendered.substring(newline),INFO,"FOUND","-",
                split.equals("acceptance") ? "BRANCH_WITHOUT_FRAMES" : "-");
    }
}
