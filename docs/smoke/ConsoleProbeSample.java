import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Synthetic input for the IDEA Java Application run-console smoke test. */
public final class ConsoleProbeSample {
    public static void main(String[] args) throws Exception {
        System.out.println("INFO synthetic run-console sample");
        printFailure(true);
        printFailure(false);
        if (args.length == 0) {
            return;
        }
        Path signals = Path.of(args[0]);
        long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
        while (System.nanoTime() < deadline && !Files.exists(signals.resolve("exit"))) {
            if (Files.deleteIfExists(signals.resolve("append"))) {
                System.out.println("INFO appended by the running sample");
            }
            if (Files.deleteIfExists(signals.resolve("crop"))) {
                System.out.print("INFO synthetic padding to roll the console buffer\n".repeat(512));
                printFailure(false);
            }
            if (Files.deleteIfExists(signals.resolve("trace"))) {
                printFailure(true);
            }
            if (Files.deleteIfExists(signals.resolve("batch"))) {
                printBatch();
            }
            Thread.sleep(200);
        }
    }

    private static void printFailure(boolean first) {
        try {
            if (first) {
                firstFailure();
            } else {
                secondFailure();
            }
        } catch (RuntimeException failure) {
            failure.printStackTrace(System.out);
        }
    }

    private static void firstFailure() {
        throw new IllegalStateException("synthetic first failure", new IllegalArgumentException("sample cause"));
    }

    private static void secondFailure() {
        throw new UnsupportedOperationException("synthetic second failure");
    }

    private static void printBatch() {
        for (boolean first : new boolean[] {true, false, true}) {
            printFailure(first);
            System.out.println("INFO ordinary output between exceptions");
        }
    }
}
