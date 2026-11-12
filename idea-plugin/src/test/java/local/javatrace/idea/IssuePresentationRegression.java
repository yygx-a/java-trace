package local.javatrace.idea;

/** 人工标注的阅读投影与非可信内容回归，无网络、无真实凭据。 */
public final class IssuePresentationRegression {
    private static int assertions;
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        String trace = "org.example.OrderImportFailure: 无法导入订单 A-1042\n"
                + "\tat org.example.Import.run(Import.java:40)\n"
                + "Caused by: java.io.IOException: CSV quantity 无效\n"
                + "\tat org.example.Reader.read(Reader.java:59)\n"
                + "\tSuppressed: java.io.IOException: 关闭失败\n"
                + "\t\tat org.example.Reader.close(Reader.java:65)\n"
                + "Caused by: java.lang.NumberFormatException: For input string: \"two\"\n"
                + "\tat java.base/java.lang.Integer.parseInt(Integer.java:786)\n"
                + "\tat org.example.Reader.read(Reader.java:57)\n";
        var summary = IssuePresentation.summarize(trace);
        check(summary.type().equals("OrderImportFailure"), "Short outer exception type");
        check(IssuePresentation.summarize("org.example.Demo$OrderImportFailure: 失败").type().equals("OrderImportFailure"), "Inner exception type readable");
        check(summary.message().equals("无法导入订单 A-1042"), "Outer message retained");
        check(summary.cause().equals("java.lang.NumberFormatException: For input string: \"two\""), "Innermost cause instead of suppressed");
        check(summary.location().equals("Reader.java:57"), "Related non-JDK frame in innermost cause");
        check(IssuePresentation.summarize("java.lang.IllegalStateException: 审计队列已满\n\tat demo.Main.main(Main.java:15)").location().equals("Main.java:15"), "No-cause frame");
        check(IssuePresentation.summarize("未知输入").type().equals("未知输入"), "Conservative unsupported summary");
        String answer = "说明\n\n## 异常 #1\n第一段：two 不是数字。\n### 怎么改\n- 校验输入\n\n## 异常 #2\n第二段：队列已满。\n\n## 异常 #3\n第三段：重复输入。";
        var paired = IssuePresentation.answers(answer, 3);
        check(paired.paired(), "Three numbered sections pair");
        check(paired.shared().equals("说明"), "Preamble separate");
        check(paired.sections().get(0).contains("two") && !paired.sections().get(0).contains("队列"), "First section exact boundary");
        check(paired.sections().get(1).equals("第二段：队列已满。"), "Second section exact boundary");
        check(paired.sections().get(2).equals("第三段：重复输入。"), "Repeated third issue remains independent");
        check(IssuePresentation.answers("单条未编号的旧回答", 1).sections().getFirst().equals("单条未编号的旧回答"), "Single old response safe");
        check(IssuePresentation.answers("## 异常 #1 解释\n直接原因", 1).sections().getFirst().equals("直接原因"), "Single redundant heading hidden");
        check(!IssuePresentation.answers("## 异常 #1 与 #3\n合并\n## 异常 #2\n队列", 3).paired(), "Merged heading not guessed");
        check(!IssuePresentation.answers("## 异常 #1\nA\n## 异常 #3\nC", 3).paired(), "Missing heading not guessed");
        check(!IssuePresentation.answers("## 异常 #1\nA\n## 异常 #1\nB", 2).paired(), "Duplicate heading not guessed");
        check(!IssuePresentation.answers("## 异常 #1\nA\n## 异常 #2", 2).paired(), "Empty answer not paired");
        check(!IssuePresentation.answers("## 异常 #999999999999999999999\nX", 2).paired(), "Invalid heading bounded");
        String html = IssuePresentation.markdown("## 为什么\n**原因**：`two` 不是数字。\n1. 校验输入\n2. 保留原因\n- 下一项\n```java\nif (a < b) {\n```\n<img src='https://example.invalid/pixel'><script>alert(1)</script>\n[外链](https://example.invalid)");
        check(html.contains("<h3>为什么</h3>") && html.contains("<b>原因</b>") && html.contains("<code>two</code>"), "Markdown visible hierarchy");
        check(html.contains("<ol>") && html.contains("<ul>") && html.contains("<li>校验输入</li>"), "List rendering");
        check(html.contains("a&nbsp;&lt;&nbsp;b"), "Code escaped");
        check(!html.contains("<img") && !html.contains("<script") && !html.contains("<a "), "No active HTML or remote resources");
        check(html.contains("&lt;img") && html.contains("[外链]"), "Untrusted content displayed as data");
        check(IssuePresentation.markdown("```\n未闭合代码").endsWith("</div>"), "Unclosed code fence readable");
        check(IssuePresentation.escape("<>&\"'\0").equals("&lt;&gt;&amp;&quot;&#39;␀"), "HTML attributes and controls escaped");
        System.out.println("Issue reading regression: " + assertions + " assertions passed");
    }
}
