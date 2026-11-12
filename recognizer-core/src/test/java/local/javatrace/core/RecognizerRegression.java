package local.javatrace.core;

import local.javatrace.core.RecognitionResult.Reason;
import local.javatrace.core.RecognitionResult.Status;
import local.javatrace.core.RecognitionResult.Warning;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 可直接由 Java 运行的回归检查。预期状态与范围来自人工标注的合成样本。
 * 入口由 Gradle 的 regressionTest 任务调用，不需要启动 IDEA。
 */
public final class RecognizerRegression {
    private static final JavaExceptionRecognizer RECOGNIZER = new JavaExceptionRecognizer();
    private static int assertions;

    private RecognizerRegression() {}

    public static void main(String[] args) throws IOException {
        verifyFixtures();
        verifyOriginalOffsets();
        verifyBatch();
        verifyTruncationAndArguments();
        verifyBudgets();
        assertions += RealLogRegression.run();
        assertions += PackagingDataRegression.run();
        assertions += RecognitionAssessmentRegression.run();
        assertions += LogFormatRegression.run();
        System.out.println("Recognizer regression passed: " + assertions + " assertions.");
    }

    /** 逐条读取标注，检查不同锚点的状态、主体范围、上下文范围和警告。 */
    private static void verifyFixtures() throws IOException {
        for (String row : fixture("annotations.tsv").lines().toList()) {
            if (row.startsWith("#") || row.isBlank()) {
                continue;
            }
            String[] fields = row.split("\t");
            String text = fixture(fields[0]).replace("\r\n", "\n");
            int anchor = lineStart(text, Integer.parseInt(fields[2]));
            String label = fields[0] + " anchor at line " + fields[2];
            RecognitionResult result = RECOGNIZER.recognize(text, anchor);
            equal(Status.valueOf(fields[3]), result.status(), label + " status");
            equal(parseRange(text, fields[4]), result.exceptionRange(), label + " exception range");
            equal(parseRange(text, fields[5]), result.contextRange(), label + " context range");
            equal(fields[6].equals("-") ? List.of() : List.of(Warning.valueOf(fields[6])),
                    result.warnings(), label + " warnings");
        }
        String text = fixture("nested.log").replace("\r\n", "\n");
        equal(List.of(Reason.THROWABLE_HEADER, Reason.STACK_FRAME, Reason.CAUSE, Reason.SUPPRESSED,
                        Reason.ELIDED_FRAMES, Reason.ADJACENT_ERROR_CONTEXT),
                RECOGNIZER.recognize(text, lineStart(text, 3)).reasons(), "nested evidence");
    }

    private static void verifyOriginalOffsets() {
        // 手工确定主体边界，检查中文、emoji、CRLF 和 ANSI 颜色序列是否影响原文偏移。
        String prefix = "INFO 前置 😀\r\n";
        String body = "\u001b[31mexample.Shutdown: 失败 😀\u001b[0m\r\n"
                + "\tat \u001b[1mexample.App.main\u001b[0m(App.java:5)\r\n";
        String text = prefix + body;
        for (int anchor : new int[] {prefix.length(), text.indexOf("😀", prefix.length()) + 1,
                text.indexOf('\r', prefix.length()), text.indexOf('\n', prefix.length()),
                text.length() - 1, text.length()}) {
            RecognitionResult result = RECOGNIZER.recognize(text, anchor);
            equal(Status.FOUND, result.status(), "CRLF/ANSI status at " + anchor);
            TextRange range = result.exceptionRange().orElseThrow();
            equal(new TextRange(prefix.length(), text.length()), range, "raw offsets at " + anchor);
            equal(body, text.substring(range.startOffset(), range.endOffset()), "unchanged raw body");
        }
        equal(Status.NOT_FOUND, RECOGNIZER.recognize(text, prefix.length() - 1).status(), "prior LF");
        String cr = body.replace("\r\n", "\r");
        equal(Optional.of(new TextRange(0, cr.length())),
                RECOGNIZER.recognize(cr, cr.length()).exceptionRange(), "bare CR");
        equal(Status.NOT_FOUND, RECOGNIZER.recognize(text + "\n", text.length() + 1).status(), "final blank line");
    }

    private static void verifyTruncationAndArguments() {
        // 末行缺少换行符可能表示只读到了部分输出，应返回警告。
        String trace = "java.lang.IllegalStateException: synthetic\n\tat p.A.run(A.java:1)";
        equal(List.of(Warning.UNTERMINATED_LAST_LINE), RECOGNIZER.recognize(trace, trace.length()).warnings(),
                "unterminated last line");
        trace += "\n";
        RecognitionResult cropped = RECOGNIZER.recognize(trace, 0, true, true);
        equal(Status.UNCERTAIN, cropped.status(), "explicit cropping");
        equal(List.of(Warning.SNAPSHOT_START_TRUNCATED, Warning.SNAPSHOT_END_TRUNCATED),
                cropped.warnings(), "cropping warnings");
        equal(Status.NOT_FOUND, RECOGNIZER.recognize("", 0).status(), "empty input");
        expectIllegalArgument(() -> RECOGNIZER.recognize(null, 0));
        expectIllegalArgument(() -> RECOGNIZER.recognize("", -1));
        expectIllegalArgument(() -> RECOGNIZER.recognize("", 1));
    }

    private static void verifyBatch() {
        String a = "java.lang.IllegalStateException: 导入失败 😀\r\n"
                + "\tat example.App.load(App.java:5)\r\n"
                + "\tSuppressed: java.io.IOException: close failed\r\n"
                + "\t\tat example.Reader.close(Reader.java:2)\r\n"
                + "Caused by: java.lang.IllegalArgumentException: bad input\r\n"
                + "\t... 1 more\r\n";
        String b = "java.lang.UnsupportedOperationException: audit failed\r\n"
                + "\tat example.Audit.write(Audit.java:8)\r\n";
        String betweenAAndB = "INFO record imported\r\n";
        String betweenBAndA = "INFO retry started\r\n";
        String text = a + betweenAAndB + b + betweenBAndA + a;
        int bStart = a.length() + betweenAAndB.length();
        int lastAStart = bStart + b.length() + betweenBAndA.length();
        BatchRecognitionResult batch = RECOGNIZER.recognizeAll(text, true, false);
        List<RecognitionResult> items = batch.exceptions();
        List<TextRange> ranges = items.stream().map(item -> item.exceptionRange().orElseThrow()).toList();
        equal(List.of(new TextRange(0, a.length()), new TextRange(bStart, bStart + b.length()),
                new TextRange(lastAStart, text.length())), ranges, "batch has three independent UTF-16 ranges");
        equal(List.of(a, b, a), ranges.stream().map(range -> text.substring(range.startOffset(), range.endOffset()))
                .toList(), "batch retains repeats and nested traces and excludes normal output");
        equal(List.of(Warning.SNAPSHOT_START_TRUNCATED), batch.warnings(), "batch snapshot cropping warning");
        equal(List.of(), items.getFirst().warnings(), "batch keeps snapshot warning separate");
        equal(List.of(), RECOGNIZER.recognizeAll(betweenAAndB, false, false).exceptions(), "batch normal output");

        BatchRecognitionResult limited = RECOGNIZER.recognizeAll(
                "x".repeat(JavaExceptionRecognizer.MAX_CHARACTERS + 1), false, false);
        equal(List.of(), limited.exceptions(), "batch limit does not return misleading partial results");
        equal(List.of(Warning.CHARACTER_LIMIT), limited.warnings(), "batch limit warning");
        BatchRecognitionResult fragmented = RECOGNIZER.recognizeAll(b.stripTrailing(), false, false);
        equal(List.of(Warning.UNTERMINATED_LAST_LINE), fragmented.exceptions().getFirst().warnings(),
                "batch preserves the warning of an individual candidate");
    }

    /** 分别检查刚好达到与超过字符、行数上限的情况，并记录一次有界扫描耗时。 */
    private static void verifyBudgets() {
        String trace = "example.Sample: boundary\n\tat example.App.main(App.java:5)\n";
        int prefixLength = JavaExceptionRecognizer.MAX_CHARACTERS - trace.length();
        String nearCharacterLimit = "x".repeat(prefixLength - 1) + "\n" + trace;
        equal(Status.FOUND, RECOGNIZER.recognize(nearCharacterLimit, prefixLength).status(), "exact character limit");
        RecognitionResult overCharacters = RECOGNIZER.recognize(nearCharacterLimit + "x", prefixLength);
        equal(List.of(Warning.CHARACTER_LIMIT), overCharacters.warnings(), "character budget");
        equal(Status.UNCERTAIN, overCharacters.status(), "over character budget status");
        equal(Optional.empty(), overCharacters.exceptionRange(), "over character budget range");

        String prefix = "INFO synthetic padding for bounded scan\n".repeat(JavaExceptionRecognizer.MAX_LINES - 2);
        long started = System.nanoTime();
        RecognitionResult atLineLimit = RECOGNIZER.recognize(prefix + trace, prefix.length());
        long millis = (System.nanoTime() - started) / 1_000_000;
        equal(Status.FOUND, atLineLimit.status(), "exact line limit");
        equal(Optional.of(new TextRange(prefix.length(), prefix.length() + trace.length())),
                atLineLimit.exceptionRange(), "exact line limit range");
        RecognitionResult overLines = RECOGNIZER.recognize(prefix + trace + "\n", prefix.length());
        equal(List.of(Warning.LINE_LIMIT), overLines.warnings(), "line budget");
        equal(Status.UNCERTAIN, overLines.status(), "over line budget status");
        equal(Optional.empty(), overLines.exceptionRange(), "over line budget range");
        System.out.printf("Bounded synthetic scan: %,d lines, %,d UTF-16 units, %d ms (%s, %s)%n",
                JavaExceptionRecognizer.MAX_LINES, prefix.length() + trace.length(),
                millis, System.getProperty("java.runtime.version"), System.getProperty("os.name"));
    }

    private static Optional<TextRange> parseRange(String text, String field) {
        if (field.equals("-")) {
            return Optional.empty();
        }
        String[] endpoints = field.split("-");
        return Optional.of(new TextRange(lineStart(text, Integer.parseInt(endpoints[0])),
                lineStart(text, Integer.parseInt(endpoints[1]) + 1)));
    }

    private static int lineStart(String text, int line) {
        int offset = 0;
        for (int current = 1; current < line; current++) {
            int next = text.indexOf('\n', offset);
            if (next < 0) {
                throw new AssertionError("Fixture does not have the annotated line " + line);
            }
            offset = next + 1;
        }
        return offset;
    }

    private static String fixture(String name) throws IOException {
        try (InputStream stream = RecognizerRegression.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(Objects.requireNonNull(stream, name).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void expectIllegalArgument(Runnable call) {
        try {
            call.run();
        } catch (IllegalArgumentException expected) {
            assertions++;
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
        assertions++;
    }
}
