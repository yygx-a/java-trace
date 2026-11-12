package local.javatrace.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import local.javatrace.core.LineClassifier.Classified;
import local.javatrace.core.LineClassifier.Kind;

/** 有限日志布局适配，只改变分类视图；行索引、原文和 UTF-16 坐标完全不变。 */
final class LogFormatAdapter {
    private static final String TIME = "(?:(?:\\d{2}|\\d{4})-\\d{2}-\\d{2}[ T.])?\\d{2}:\\d{2}:\\d{2}"
            + "(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?";
    private static final String LEVEL = "(?:\\[(?:TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\]|(?:TRACE|DEBUG|INFO|WARN|ERROR|FATAL))";
    private static final String LOGGER = "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.]*";
    // 单个分隔空格属于布局；其后的缩进属于 Throwable，不能 stripLeading。
    private static final Pattern HEAD = Pattern.compile("^(?:(" + TIME + ")[ \\t]+)?"
            + "(?:(\\[[^\\]\\r\\n]+\\])[ \\t]+)?(" + LEVEL + ")[ \\t]+"
            + "(?:(\\[[^\\]\\r\\n]+\\])[ \\t]+)?(" + LOGGER + ") - (.*)$");
    private static final Pattern ANSI = Pattern.compile("\u001b\\[[0-9;:]*+m");
    private static final Pattern INLINE = Pattern.compile("(?: : | - )((?:[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*\\.)+"
            + "[\\p{Lu}$_][\\p{javaJavaIdentifierPart}]*(?:Exception|Error|Throwable):.*)$");

    private record Head(String payload, String key, boolean timed) {}

    static List<Classified> classify(String original, List<LineIndex.Line> lines) {
        List<Classified> raw = new ArrayList<>();
        List<Head> heads = new ArrayList<>();
        for (LineIndex.Line line : lines) {
            String content = original.substring(line.start(), line.contentEnd());
            if (content.indexOf('\u001b') >= 0) content = ANSI.matcher(content).replaceAll("");
            raw.add(LineClassifier.classifyContent(content));
            Matcher match = HEAD.matcher(content);
            if (match.matches() && !(match.group(2) != null && match.group(4) != null)) {
                String key = match.group(1) + "|" + match.group(2)
                        + "|" + match.group(3) + "|" + match.group(4) + "|" + match.group(5);
                heads.add(new Head(match.group(6), key, match.group(1) != null));
            } else heads.add(null);
        }
        List<Classified> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Head head = heads.get(i);
            Classified item = raw.get(i);
            if (head != null) {
                Classified payload = LineClassifier.classifyContent(head.payload());
                if (!title(payload.kind())) {
                    Matcher inline = INLINE.matcher(head.payload());
                    if (inline.find()) payload = LineClassifier.classifyContent(inline.group(1));
                }
                if (title(payload.kind()) || continuation(payload.kind()) || payload.kind() == Kind.BROKEN_TRACE) {
                    // 标题后无前缀，表示常见的一行日志头 + 原始 Throwable 布局。
                    String key = title(payload.kind()) && (i + 1 == lines.size() || heads.get(i + 1) == null)
                            ? "" : head.key();
                    item = new Classified(payload.kind(), payload.indent(), payload.messageFormat(), key, head.timed(), title(payload.kind()));
                } else {
                    // 消息候选也携带身份，只有同一个分支的有限前瞻可以接纳它。
                    item = new Classified(item.kind(), payload.indent(), payload.messageFormat(), head.key(), head.timed());
                }
            } else if (title(item.kind())) {
                int previous = i - 1;
                if (previous >= 0 && raw.get(previous).kind() == Kind.BLANK) previous--;
                if (previous >= 0 && heads.get(previous) != null && heads.get(previous).timed()
                        && !title(result.get(previous).kind()) && !continuation(result.get(previous).kind())
                        && result.get(previous).kind() != Kind.BROKEN_TRACE
                        && (raw.get(previous).kind() == Kind.ERROR_LOG || raw.get(previous).kind() == Kind.LOG)) {
                    item = new Classified(item.kind(), item.indent(), item.messageFormat(), "", true);
                }
            }
            result.add(item);
        }
        return result;
    }

    static boolean title(Kind kind) {
        return kind == Kind.TITLE || kind == Kind.LIKELY_TITLE || kind == Kind.THREAD_TITLE;
    }

    private static boolean continuation(Kind kind) {
        return kind == Kind.FRAME || kind == Kind.CAUSE || kind == Kind.SUPPRESSED || kind == Kind.ELISION
                || kind == Kind.CIRCULAR_CAUSE || kind == Kind.CIRCULAR_SUPPRESSED;
    }
}
