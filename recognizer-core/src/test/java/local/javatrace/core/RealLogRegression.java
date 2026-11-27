package local.javatrace.core;

import java.util.List;
import java.util.Objects;
import local.javatrace.core.RecognitionResult.Reason;
import local.javatrace.core.RecognitionResult.Status;
import local.javatrace.core.RecognitionResult.Warning;

/** 人工缩减并脱敏的真实格式。范围由拼接片段指定，不使用解析结果反推答案。 */
final class RealLogRegression {
    private static final JavaExceptionRecognizer RECOGNIZER = new JavaExceptionRecognizer();
    private static int assertions;
    private static final String DATABASE = """
            java.util.concurrent.ExecutionException: org.mybatis.spring.MyBatisSystemException: nested exception is org.apache.ibatis.exceptions.PersistenceException:
            ### Error querying database.  Cause: java.sql.SQLTransientConnectionException: synthetic timeout
            ### The error may exist in file [<project>/mapper/sample.xml]
            ### The error may involve example.SampleMapper.select
            ### The error occurred while executing a query
            ### SQL: select count(*) from synthetic_table
            ### Cause: java.sql.SQLTransientConnectionException: synthetic timeout
            \tat example.QueryService.load(QueryService.java:10)
            Caused by: org.mybatis.spring.MyBatisSystemException: nested exception is org.apache.ibatis.exceptions.PersistenceException:
            ### Error querying database.  Cause: java.sql.SQLTransientConnectionException: synthetic timeout
            ### Cause: java.sql.SQLTransientConnectionException: synthetic timeout
            \tat org.mybatis.spring.SqlSessionTemplate.selectList(SqlSessionTemplate.java:224)
            Caused by: org.apache.ibatis.exceptions.PersistenceException:
            ### Error querying database.  Cause: java.sql.SQLTransientConnectionException: synthetic timeout
            ### Cause: java.sql.SQLTransientConnectionException: synthetic timeout
            \tat org.apache.ibatis.session.defaults.DefaultSqlSession.selectList(DefaultSqlSession.java:149)
            \t... 2 common frames omitted
            Caused by: java.sql.SQLTransientConnectionException: synthetic timeout
            \tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)
            \t... 3 common frames omitted
            Caused by: com.mysql.cj.jdbc.exceptions.CommunicationsException: Communications link failure

            The last packet sent successfully to the server was 0 milliseconds ago. The driver has not received any packets from the server.
            \tat com.mysql.cj.jdbc.ConnectionImpl.createNewIO(ConnectionImpl.java:836)
            Caused by: com.mysql.cj.exceptions.CJCommunicationsException: Communications link failure

            The last packet successfully received from the server was 0 milliseconds ago.
            The last packet sent successfully to the server was 0 milliseconds ago.
            \tat com.mysql.cj.NativeSession.connect(NativeSession.java:144)
            Caused by: java.net.ConnectException: synthetic connection refused
            \tat java.base/sun.nio.ch.Net.pollConnect(Native Method)
            \t... 1 common frames omitted
            """;
    private static final String NPE = "java.lang.NullPointerException: 合成通知为空 🧩\n"
            + "\tat example.TradeController.lock(TradeController.java:71)\n";

    static int run() {
        logHeaders();
        springSqlGrammar();
        boundaries();
        return assertions;
    }

    private static void springSqlGrammar() {
        String title = "org.springframework.jdbc.BadSqlGrammarException: \n";
        String details = """
                ### Error querying database. Cause: java.sql.SQLSyntaxErrorException: 合成错误 🧩
                ### The error may involve example.SampleMapper.select
                ### The error occurred while executing a query
                ### SQL: SELECT name FROM example_table
                ### Cause: java.sql.SQLSyntaxErrorException: 合成错误 🧩
                """;
        String suffix = "; bad SQL grammar []; nested exception is java.sql.SQLSyntaxErrorException: 合成错误 🧩\n";
        String frames = """
                \tat org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator.doTranslate(SQLErrorCodeSQLExceptionTranslator.java:239) ~[spring-jdbc-5.3.27.jar:5.3.27]
                \tat org.mybatis.spring.MyBatisExceptionTranslator.translateExceptionIfPossible(MyBatisExceptionTranslator.java:91) ~[mybatis-spring-2.0.6.jar:2.0.6]
                Caused by: java.sql.SQLSyntaxErrorException: 合成错误 🧩
                \tat com.mysql.cj.jdbc.exceptions.SQLError.createSQLException(SQLError.java:120) ~[mysql-connector-java-8.0.22.jar:8.0.22]
                \t... 1 common frames omitted
                """;
        String context = "2026-11-27 10:20:30.123 [worker] ERROR example.App - synthetic query failed\n";
        String unrelated = "INFO after both errors\n";
        for (String newline : List.of("\n", "\r\n")) {
            String before = context.replace("\n", newline);
            String body = (title + details + suffix + frames).replace("\n", newline);
            String independent = NPE.replace("\n", newline);
            String text = before + body + independent + unrelated.replace("\n", newline);
            TextRange range = new TextRange(before.length(), before.length() + body.length());
            var batch = RECOGNIZER.recognizeAll(text, false, false).exceptions();
            equal(2, batch.size(), "one Spring SQL grammar chain and one independent exception");
            var result = batch.getFirst();
            equal(Status.FOUND, result.status(), "complete Spring SQL grammar chain");
            equal(range, result.exceptionRange().orElseThrow(), "all SQL diagnostics, frames and cause in one original range");
            equal(before, slice(text, result.contextRange().orElseThrow()), "previous ERROR remains separate");
            equal(independent, slice(text, batch.getLast().exceptionRange().orElseThrow()), "independent exception preserved");
            equal(true, result.reasons().containsAll(List.of(Reason.MULTILINE_MESSAGE, Reason.CAUSE, Reason.ELIDED_FRAMES)),
                    "diagnostic, cause and elision evidence preserved");
            for (int anchor = range.startOffset(); anchor < range.endOffset(); anchor = text.indexOf('\n', anchor) + 1) {
                equal(result, RECOGNIZER.recognize(text, anchor), "each diagnostic and stack line selects the complete chain");
            }
            equal(result, RECOGNIZER.recognize(text, text.indexOf("🧩") + 1), "original UTF-16 anchor inside the diagnostic");
        }
        String body = title + details + suffix + frames;
        var known = RECOGNIZER.recognize(body, 0);
        equal(new TextRange(0, body.length()), known.exceptionRange().orElseThrow(), "known grammar without timestamp");
        equal(0, known.inferredMessageLines(), "known Spring/MyBatis grammar is explicit evidence");
        String restyled = title + "数据库返回了一段新的诊断文字 🧩\nerror[1]: statement rejected\n" + frames;
        var restyledBatch = RECOGNIZER.recognizeAll(restyled, false, false).exceptions();
        equal(1, restyledBatch.size(), "wording can change without splitting the SQL trace");
        equal(restyled, slice(restyled, restyledBatch.getFirst().exceptionRange().orElseThrow()), "restyled SQL raw range");
        equal(2, restyledBatch.getFirst().inferredMessageLines(), "unfamiliar SQL wording inferred by structure");
        for (String rest : List.of(suffix + frames, details + "unexpected message\n" + suffix + frames,
                details + "  " + suffix + frames, details + suffix + details + frames)) {
            String text = title + rest;
            var result = RECOGNIZER.recognize(text, 0);
            equal(text, slice(text, result.exceptionRange().orElseThrow()), "irregular diagnostic still sealed by the first frame");
            equal(1, result.inferredMessageLines(), "only the unsupported line needs inference");
        }
        for (String rest : List.of(details + suffix + unrelated + frames, details + suffix + NPE,
                details.repeat(53) + suffix + frames)) {
            var result = RECOGNIZER.recognize(title + rest, 0);
            equal(Status.UNCERTAIN, result.status(), "unconfirmed SQL diagnostics stay uncertain");
            equal(new TextRange(0, title.length()), result.exceptionRange().orElseThrow(), "no merge without a frame before the boundary");
        }
        String otherTitle = "java.lang.IllegalStateException: mentioned " + title;
        var other = RECOGNIZER.recognize(otherTitle + details + suffix + frames, 0);
        equal(6, other.inferredMessageLines(), "mentioning the Spring type provides no extra confidence");
    }

    private static void logHeaders() {
        for (String date : List.of("26-11-11.20:40:06.502", "2026-11-11 20:40:06.502", "2026-11-11T20:40:06.502+08:00")) {
            for (String thread : List.of("", "[http-nio-18091-exec-5] ", "[scheduling-1    ] ")) {
                String info = date + " " + thread + "INFO  Sample - ordinary Exception / at text\n";
                String context = date + " " + thread + "ERROR Sample - 合成参数 🧩\n";
                // 第一条数据库异常紧接独立 NPE，再经 INFO 分隔后重复数据库异常。
                String raw = info + context + DATABASE + NPE + info + context + DATABASE + info;
                for (String newline : List.of("\n", "\r\n")) {
                    String text = raw.replace("\n", newline);
                    String i = info.replace("\n", newline), c = context.replace("\n", newline);
                    String db = DATABASE.replace("\n", newline), npe = NPE.replace("\n", newline);
                    var batch = RECOGNIZER.recognizeAll(text, false, false);
                    equal(3, batch.exceptions().size(), "one database chain, adjacent NPE, repeated database chain");
                    equal(List.of(), batch.warnings(), "no scan warning");
                    int dbStart = i.length() + c.length();
                    int npeStart = dbStart + db.length();
                    int repeatedStart = npeStart + npe.length() + i.length() + c.length();
                    var ranges = List.of(new TextRange(dbStart, npeStart), new TextRange(npeStart, npeStart + npe.length()),
                            new TextRange(repeatedStart, repeatedStart + db.length()));
                    for (int j = 0; j < 3; j++) {
                        var result = batch.exceptions().get(j);
                        equal(Status.FOUND, result.status(), "complete branch " + j);
                        equal(ranges.get(j), result.exceptionRange().orElseThrow(), "hand specified original range " + j);
                        equal(j != 1, result.contextRange().isPresent(), "separate ERROR ownership " + j);
                    }
                    equal(c, slice(text, batch.exceptions().getFirst().contextRange().orElseThrow()), "unchanged context");
                    equal(db, slice(text, ranges.getFirst()), "database diagnostic/blank/raw content retained");
                    // 每个物理行，包括 ###、空行和原因消息，必须定位到同一个完整主体。
                    for (int anchor = dbStart; anchor < npeStart;) {
                        var single = RECOGNIZER.recognize(text, anchor);
                        equal(Status.FOUND, single.status(), "single anchor status");
                        equal(ranges.getFirst(), single.exceptionRange().orElseThrow(), "single/batch identical");
                        anchor = text.indexOf('\n', anchor) + 1;
                    }
                    equal(Status.FOUND, RECOGNIZER.recognize(text, i.length()).status(), "ERROR anchor");
                    equal(Status.NOT_FOUND, RECOGNIZER.recognize(text, 0).status(), "INFO anchor");
                }
            }
        }
        String color = "\u001b[31m26-11-11.20:40:06.502 [worker] ERROR Sample - synthetic\u001b[0m\n";
        equal(color, slice(color + NPE, RECOGNIZER.recognize(color + NPE, 0).contextRange().orElseThrow()), "ANSI original context");
        for (String prose : List.of("普通说明在中间包含 ERROR 和 Exception", "INFO ordinary ### Cause: not an exception")) {
            equal(0, RECOGNIZER.recognizeAll(prose + "\n", false, false).exceptions().size(), "prose not a trace");
        }
    }

    private static void boundaries() {
        String title = "org.apache.ibatis.exceptions.PersistenceException: synthetic\n";
        String diagnostic = "### Error querying database. Cause: synthetic\n";
        String frame = "\tat example.Mapper.select(Mapper.java:8)\n";
        String info = "26-11-11.20:40:06.502 [worker] INFO Sample - independent log\n";
        String error = info.replace(" INFO ", " ERROR ");
        String broken = title + diagnostic + error + NPE;
        var split = RECOGNIZER.recognizeAll(broken, false, false).exceptions();
        equal(2, split.size(), "incomplete MyBatis and independent NPE");
        equal(Status.UNCERTAIN, split.getFirst().status(), "missing frames not complete");
        equal(new TextRange(0, title.length()), split.getFirst().exceptionRange().orElseThrow(), "unconfirmed diagnostic not swallowed");
        equal(NPE, slice(broken, split.getLast().exceptionRange().orElseThrow()), "independent exception intact");
        equal(error, slice(broken, split.getLast().contextRange().orElseThrow()), "new ERROR owns NPE");

        for (String interruption : List.of(info, error, "Process finished with exit code 1\n",
                "java.lang.IllegalArgumentException: independent\n", "at malformed frame\n")) {
            String text = title + diagnostic + interruption + frame;
            equal(new TextRange(0, title.length()), RECOGNIZER.recognize(text, 0).exceptionRange().orElseThrow(),
                    "do not bridge new log/title/broken frame/process boundary");
            equal(Status.UNCERTAIN, RECOGNIZER.recognize(text, 0).status(), "unconfirmed remains uncertain");
        }
        for (String text : List.of(diagnostic + frame,
                "com.mysql.cj.jdbc.exceptions.CommunicationsException: failed\n\n" + info
                        + "The last packet sent successfully to the server was 0 milliseconds ago.\n" + frame)) {
            var result = RECOGNIZER.recognize(text, 0);
            equal(false, result.exceptionRange().map(r -> slice(text, r).contains(frame)).orElse(false),
                    "do not infer unrelated diagnostic/message ownership");
        }
        String tail = title + frame + diagnostic + frame;
        equal(new TextRange(0, title.length() + frame.length()), RECOGNIZER.recognize(tail, 0).exceptionRange().orElseThrow(),
                "message after frames is unsupported, not silently attached");
        String noFrames = title + diagnostic.repeat(2000);
        equal(true, RECOGNIZER.recognize(noFrames, 0).warnings().contains(Warning.MISSING_STACK), "long incomplete diagnostic remains incomplete");
        equal(1, RECOGNIZER.recognizeAll(noFrames, false, false).exceptions().size(), "diagnostic text cannot create thousands of phantom errors");
        equal(List.of(Warning.SNAPSHOT_START_TRUNCATED, Warning.SNAPSHOT_END_TRUNCATED),
                RECOGNIZER.recognize(DATABASE, 0, true, true).warnings(), "explicit truncation still reported");
        equal(true, RECOGNIZER.recognize(DATABASE.stripTrailing(), 0).warnings().contains(Warning.UNTERMINATED_LAST_LINE),
                "unterminated last frame still uncertain");
    }

    private static String slice(String text, TextRange range) { return text.substring(range.startOffset(), range.endOffset()); }
    private static void equal(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        assertions++;
    }
}
