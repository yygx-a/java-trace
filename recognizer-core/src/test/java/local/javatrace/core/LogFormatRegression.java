package local.javatrace.core;

import java.util.List;
import java.util.Objects;
import static local.javatrace.core.RecognitionResult.Status.*;
import static local.javatrace.core.RecognitionResult.Warning.*;

/** 人工拼接主体/上下文作为预期，不以适配器输出反推标注。 */
final class LogFormatRegression {
    private static final JavaExceptionRecognizer R = new JavaExceptionRecognizer();
    private static int assertions;
    private static final String HEAD = "2026-11-12 10:20:30.123 [worker-1] ERROR example.App - ";
    private static final String TITLE = "java.lang.IllegalStateException: 中文 😀\n";
    private static final String FRAME = "\tat example.App.run(App.java:12)\n";

    static int run() {
        for (String nl : List.of("\n", "\r\n")) {
            exact("INFO before\n", HEAD + TITLE + FRAME, "INFO after\n", nl, "inline");
            exact("INFO before\n", HEAD + "request failed : " + TITLE + FRAME, "INFO after\n", nl, "delimited message");
            exact("", "ERROR example.App - " + TITLE + FRAME, "", nl, "level logger");
            exact("", "10:20:30 ERROR [worker-1] example.App - " + TITLE + FRAME, "", nl, "level before thread");
            exact("", HEAD.replace("ERROR", "[ERROR]") + TITLE + FRAME, "", nl, "bracketed level");
            exact("", "\u001b[31m" + HEAD + TITLE.stripTrailing() + "\u001b[0m\n" + FRAME, "", nl, "colored inline header");
            String prefixed = HEAD + TITLE + HEAD + FRAME + HEAD + "Caused by: java.io.IOException: nested\n"
                    + HEAD + FRAME + HEAD + "\tSuppressed: java.io.IOException: close\n" + HEAD + "\t\tat example.App.close(App.java:13)\n";
            exact("INFO before\n", prefixed, "INFO after\n", nl, "consistent prefix and indentation");
            String multiline = HEAD + "java.sql.SQLException: query failed\nselect name\nfrom synthetic_table\n说明 😀\n\n" + FRAME;
            exact("", multiline, "INFO after\n", nl, "bounded SQL message");
            exact("", HEAD + TITLE + HEAD + "说明 😀\n" + HEAD + FRAME, "INFO after\n", nl, "prefixed message");
            String context = HEAD + "request failed\n\n";
            String body = TITLE + "select name\nfrom synthetic_table\n" + FRAME;
            String text = (context + body + "INFO after\n").replace("\n", nl);
            var item = R.recognize(text, context.replace("\n", nl).length());
            equal(FOUND, item.status(), "event before raw multiline status");
            equal(new TextRange(0, context.replace("\n", nl).length()), item.contextRange().orElseThrow(), "context separate");
            equal(body.replace("\n", nl), slice(text, item), "raw multiline body");
            equal(true, R.recognize(HEAD + "independent event\n" + HEAD + TITLE + FRAME,
                    (HEAD + "independent event\n").length()).contextRange().isEmpty(), "inline root has its own log head, no preceding event context");
            var batch = R.recognizeAll((HEAD + TITLE + HEAD + FRAME + HEAD + TITLE + HEAD + FRAME).replace("\n", nl), false, false);
            equal(2, batch.exceptions().size(), "adjacent identical-prefix roots separate");
            equal((HEAD + TITLE + HEAD + FRAME).replace("\n", nl), slice((HEAD + TITLE + HEAD + FRAME + HEAD + TITLE + HEAD + FRAME).replace("\n", nl), batch.exceptions().get(0)), "first root exact");
        }
        for (String negative : List.of(HEAD + "we mention java.lang.RuntimeException in text\n", HEAD + "java.lang.RuntimeException is expected\n",
                HEAD + "ordinary sentence - java.lang.RuntimeException mentioned\n", "unknown | " + TITLE)) {
            equal(0, R.recognizeAll(negative, false, false).exceptions().size(), "ordinary/unknown prefix rejected");
        }
        fragmented(HEAD + TITLE + HEAD.replace("worker-1", "worker-2") + FRAME, "thread interleave");
        fragmented(HEAD + TITLE + HEAD.replace("example.App", "example.Other") + FRAME, "logger changes");
        fragmented(HEAD + TITLE + HEAD.replace("ERROR", "WARN") + FRAME, "level changes");
        fragmented(HEAD + TITLE + HEAD + FRAME + FRAME, "prefix disappears");
        fragmented(HEAD + TITLE + FRAME + HEAD + FRAME, "prefix appears");
        fragmented(HEAD + TITLE + HEAD.replace("10:20:30.123", "10:20:31.123") + FRAME, "timestamp changes");
        fragmented(HEAD + TITLE + "at broken frame\n" + FRAME, "broken message boundary");
        fragmented(TITLE + "unknown message\n" + FRAME, "no event evidence");
        fragmented(HEAD + TITLE + "unfinished message\n", "no closing frame");
        fragmented(HEAD + TITLE + "INFO independent event\n" + FRAME, "new event before first frame");
        var unrelated = R.recognizeAll(HEAD + TITLE + HEAD + FRAME + TITLE + "unknown message\n" + FRAME, false, false);
        equal(UNCERTAIN, unrelated.exceptions().get(1).status(), "prefixed frame is not evidence of a new raw event");
        String budget = HEAD + TITLE + "message\n".repeat(256) + FRAME;
        fragmented(budget, "message lookahead limit");
        var tail = R.recognizeAll(HEAD + TITLE + FRAME + "trailing ordinary text\n", false, false).exceptions().getFirst();
        equal(HEAD + TITLE + FRAME, slice(HEAD + TITLE + FRAME + "trailing ordinary text\n", tail), "text after frame excluded");
        equal(true, tail.warnings().contains(AMBIGUOUS_BOUNDARY), "unexplained tail reported");
        equal(List.of(SNAPSHOT_START_TRUNCATED, SNAPSHOT_END_TRUNCATED), R.recognizeAll(HEAD + TITLE + FRAME, true, true).warnings(), "snapshot limits retained");
        return assertions;
    }

    private static void exact(String before, String body, String after, String nl, String label) {
        before = before.replace("\n", nl); body = body.replace("\n", nl); after = after.replace("\n", nl);
        String text = before + body + after;
        var batch = R.recognizeAll(text, false, false);
        equal(1, batch.exceptions().size(), label + " count");
        var expected = new TextRange(before.length(), before.length() + body.length());
        var item = batch.exceptions().getFirst();
        equal(FOUND, item.status(), label + " status");
        equal(expected, item.exceptionRange().orElseThrow(), label + " UTF16 range");
        equal(body, slice(text, item), label + " unchanged original");
        for (int anchor = expected.startOffset(); anchor < expected.endOffset(); anchor++) {
            equal(item, R.recognize(text, anchor), label + " all anchors " + anchor);
        }
    }
    private static void fragmented(String text, String label) {
        var batch = R.recognizeAll(text, false, false);
        equal(true, !batch.exceptions().isEmpty(), label + " keeps evidence");
        equal(UNCERTAIN, batch.exceptions().getFirst().status(), label + " conservative status");
        equal(true, batch.exceptions().getFirst().exceptionRange().orElseThrow().endOffset() < text.length(), label + " does not swallow");
    }
    private static String slice(String text, RecognitionResult item) {
        var range = item.exceptionRange().orElseThrow(); return text.substring(range.startOffset(), range.endOffset());
    }
    private static void equal(Object expected, Object actual, String label) {
        assertions++; if (!Objects.equals(expected, actual)) throw new AssertionError(label + ": " + actual + ", expected " + expected);
    }
}
