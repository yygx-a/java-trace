package local.javatrace.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单行识别规则：回答“这一行看起来是什么”，不决定整段异常到哪里结束。
 * 阅读顺序：区域 1～5 定义分类和匹配规则 → 区域 6 准备单行文本 → 区域 7～8 应用规则。
 * 例如标题、at 调用帧、Caused by 会分别得到标签；是否属于同一异常由 TraceAssembler 判断。
 * 自定义异常不需要逐个登记类名；下面的 MyBatis/MySQL 类型判断只用于已知多行消息格式。
 */
final class LineClassifier {
    // ===== 区域 1：分类结果，供异常块组合器使用 =====
    // TITLE 是普通类名形式，LIKELY_TITLE 带常见后缀，THREAD_TITLE 带线程标题。
    // BROKEN_TRACE 表示开头像堆栈结构但整行不符合当前规则。
    enum Kind {
        TITLE, THREAD_TITLE, LIKELY_TITLE, FRAME, CAUSE, SUPPRESSED, ELISION, CIRCULAR_CAUSE, CIRCULAR_SUPPRESSED,
        ERROR_LOG, LOG, BLANK, SEPARATOR, MYBATIS_DETAIL, MYSQL_DETAIL, BROKEN_TRACE, OTHER
    }

    // 不是异常类型清单，而是“后续消息行是否有已支持的特殊格式”。
    enum MessageFormat { NONE, MYBATIS, MYSQL }

    // kind 是行标签；indent 是展开制表符后的缩进列数；messageFormat 是多行消息提示。
    record Classified(Kind kind, int indent, MessageFormat messageFormat, String prefix, boolean eventBounded,
                      boolean inlineHeader) {
        Classified(Kind kind, int indent, MessageFormat messageFormat) { this(kind, indent, messageFormat, "", false, false); }
        Classified(Kind kind, int indent, MessageFormat messageFormat, String prefix, boolean eventBounded) {
            this(kind, indent, messageFormat, prefix, eventBounded, false);
        }
    }

    // ===== 区域 2：类名语法与颜色控制序列 =====
    // 复用 Java 标识符规则组合包名、类型和方法名。
    // 正则中的 *+、++ 是占有量词，可避免这些重复部分反复回溯。
    private static final String IDENTIFIER = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*+";
    private static final String PACKAGE = "(?:" + IDENTIFIER + "\\.)*+";
    private static final String TYPE = PACKAGE + "(?:[\\p{Lu}\\p{Lt}$_]\\p{javaJavaIdentifierPart}*+)";
    private static final String THROWABLE = TYPE + "(?::.*)?";
    private static final Pattern ANSI_SGR = Pattern.compile("\u001b\\[[0-9;:]*+m");

    // ===== 区域 3：异常标题、原因链、抑制异常和循环引用 =====
    // TITLE：java.lang.IllegalStateException: 消息，也允许没有常见异常后缀的自定义类型。
    // THREAD_TITLE：Exception in thread "main" java.lang.NullPointerException: 消息。
    // CAUSE / SUPPRESSED：Caused by: ... / Suppressed: ...，属于异常内部的分支标题。
    // CIRCULAR_*：JVM 用 [CIRCULAR REFERENCE: ...] 表示再次引用已打印过的 Throwable。
    private static final Pattern TITLE = Pattern.compile("(" + TYPE + ")(?::.*)?");
    private static final Pattern THREAD_TITLE = Pattern.compile("Exception in thread \"[^\"\\r\\n]*+\" " + THROWABLE);
    private static final Pattern CAUSE = Pattern.compile("Caused by: " + THROWABLE);
    private static final Pattern SUPPRESSED = Pattern.compile("Suppressed: " + THROWABLE);
    private static final Pattern CIRCULAR_CAUSE = Pattern.compile("Caused by: \\[CIRCULAR REFERENCE: " + THROWABLE + "\\]");
    private static final Pattern CIRCULAR_SUPPRESSED = Pattern.compile("Suppressed: \\[CIRCULAR REFERENCE: " + THROWABLE + "\\]");

    // ===== 区域 4：调用帧、框架附加信息和省略帧 =====
    // 识别 at example.Service.load(Service.java:42)，不是在句子里找到一个 at 就算调用帧。
    // Logback 的扩展帧在合法来源后追加 [location:version] 或 ~[location:version]。
    // 只识别一个完整、非空的元数据对，不把任意尾部文字当作调用帧；原文不做删改。
    private static final String PACKAGING_DATA = "(?:[ \\t]++~?\\[[^\\[\\]:\\r\\n]++:[^\\[\\]\\r\\n]++\\])?";
    // 调用帧要求有方法名和括号内的来源信息，可带模块或类加载器前缀。
    private static final Pattern FRAME = Pattern.compile(
            "at (?:[\\w.$@+-]++/(?:[\\w.$@+-]*+/)?)?" + IDENTIFIER + "\\." + PACKAGE + "(?:" + IDENTIFIER
                    + "|<init>|<clinit>)\\((?:Native Method|Unknown Source|[^():\\r\\n]++(?::[0-9]++)?)\\)" + PACKAGING_DATA);
    // ... 12 more（JVM）或 ... 12 common frames omitted（日志框架）。
    private static final Pattern ELISION = Pattern.compile("\\.\\.\\. [0-9]++ (?:more|common frames omitted)");

    // ===== 区域 5：普通日志头，帮助识别异常边界和可选前置 ERROR =====
    // 支持已列出的日期/时间、线程、等级组合；匹配的是整行，不在任意消息中搜索 ERROR。
    // ERROR 只是日志等级，不意味着这行一定打印了 Java 异常；其他等级也可结束上一段异常。
    private static final String LOG_THREAD = "\\[[^\\]\\r\\n]++\\][ \\t]++";
    private static final String LOG_TIME = "(?:(?:\\d{2}|\\d{4})-\\d{2}-\\d{2}[ T.])?\\d{2}:\\d{2}:\\d{2}"
            + "(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?[ \\t]++";
    private static final Pattern LOG = Pattern.compile(
            "(?:" + LOG_TIME + "(?:" + LOG_THREAD + ")?|" + LOG_THREAD + ")?"
                    + "(?:\\[(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\]|(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)(?=[ \\t:]))"
                    + "(?:[ \\t:].*)?");

    private LineClassifier() {}

    // ===== 区域 6：提取并准备一行，保留原文偏移 =====
    // 这里只生成用于判断的副本；复制/预览仍从原始快照按 LineIndex 的位置取文本。
    static Classified classify(String original, LineIndex.Line line) {
        // 只在用于分类的单行副本中去除 ANSI SGR 颜色序列，返回范围仍来自原文行索引。
        String content = original.substring(line.start(), line.contentEnd());
        return classifyContent(content);
    }

    static Classified classifyContent(String content) {
        if (content.indexOf('\u001b') >= 0) {
            content = ANSI_SGR.matcher(content).replaceAll("");
        }
        // 将制表符展开到下一个 8 列边界，使空格和制表符混排时也能比较缩进。
        int leading = 0;
        int indent = 0;
        while (leading < content.length()) {
            char ch = content.charAt(leading);
            if (ch != ' ' && ch != '\t') {
                break;
            }
            indent = ch == '\t' ? (indent / 8 + 1) * 8 : indent + 1;
            leading++;
        }
        String stripped = content.substring(leading).stripTrailing();
        Kind kind = kindOf(stripped);
        return new Classified(kind, indent, messageFormat(kind, stripped));
    }

    // ===== 区域 7：给已知数据库异常分支标记多行消息格式 =====
    // MyBatis 的 ### 诊断、Connector/J 的通信说明会换行，不能简单把每一行当成新异常。
    // 这里只标记格式；真正接纳哪些行，还要由 TraceAssembler.messageFrameAfter 校验归属。
    private static MessageFormat messageFormat(Kind kind, String content) {
        if (kind != Kind.TITLE && kind != Kind.LIKELY_TITLE && kind != Kind.THREAD_TITLE
                && kind != Kind.CAUSE && kind != Kind.SUPPRESSED) return MessageFormat.NONE;
        // 包装异常（如 ExecutionException）会把被包装类型放进同一标题行。
        if (content.contains("org.mybatis.spring.MyBatisSystemException:")
                || content.contains("org.apache.ibatis.exceptions.PersistenceException:")) return MessageFormat.MYBATIS;
        if (content.contains("com.mysql.cj.jdbc.exceptions.CommunicationsException:")
                || content.contains("com.mysql.cj.exceptions.CJCommunicationsException:")) return MessageFormat.MYSQL;
        return MessageFormat.NONE;
    }

    // ===== 区域 8：按优先级应用规则，输出一个行标签 =====
    // 顺序很重要：日志/明确的堆栈结构优先，宽松的类名标题最后尝试，减少普通文本误判。
    private static Kind kindOf(String content) {
        if (content.isEmpty()) return Kind.BLANK;
        if (content.startsWith("Process finished with exit code ")) return Kind.SEPARATOR;
        // 先识别日志头和结构明确的堆栈行，最后才尝试较宽松的异常标题。
        Matcher log = LOG.matcher(content);
        if (log.matches()) {
            return "ERROR".equals(log.group(1)) || "ERROR".equals(log.group(2)) ? Kind.ERROR_LOG : Kind.LOG;
        }
        if (FRAME.matcher(content).matches()) {
            return Kind.FRAME;
        }
        if (THREAD_TITLE.matcher(content).matches()) {
            return Kind.THREAD_TITLE;
        }
        if (CAUSE.matcher(content).matches()) {
            return Kind.CAUSE;
        }
        if (CIRCULAR_CAUSE.matcher(content).matches()) return Kind.CIRCULAR_CAUSE;
        if (CIRCULAR_SUPPRESSED.matcher(content).matches()) return Kind.CIRCULAR_SUPPRESSED;
        if (SUPPRESSED.matcher(content).matches()) {
            return Kind.SUPPRESSED;
        }
        if (ELISION.matcher(content).matches()) {
            return Kind.ELISION;
        }
        // 8.1 已支持的数据库消息行：只赋予诊断标签，不在这里把它们并入异常主体。
        if (content.startsWith("### Error ") || content.startsWith("### The error ")
                || content.startsWith("### SQL:") || content.startsWith("### Cause:")) return Kind.MYBATIS_DETAIL;
        if (content.startsWith("The last packet sent successfully to the server was ")
                || content.startsWith("The last packet successfully received from the server was ")) return Kind.MYSQL_DETAIL;
        // 8.2 像堆栈但未通过完整语法校验：让组合器停下来提示，避免吞掉损坏内容。
        if (content.startsWith("at ") || content.startsWith("Caused by:")
                || content.startsWith("Suppressed:") || content.startsWith("... ")
                || content.startsWith("Exception in thread ")) {
            return Kind.BROKEN_TRACE;
        }
        // 8.3 类名形状的候选标题：后缀增强线索，最终是否构成异常仍需结合相邻结构。
        Matcher title = TITLE.matcher(content);
        if (title.matches()) {
            String type = title.group(1);
            // 后缀仅增强标题线索。没有这些后缀的自定义类型仍可作为 TITLE 参与组合。
            if (type.endsWith("Exception") || type.endsWith("Error") || type.endsWith("Throwable")) {
                return Kind.LIKELY_TITLE;
            }
            return Kind.TITLE;
        }
        // 8.4 当前规则无法解释的行，不猜测其异常归属。
        return Kind.OTHER;
    }
}
