package local.javatrace.idea;

import java.util.*;
import java.util.regex.*;

/** 只做阅读视图投影，不修改异常原文，不推断不存在的 AI 分段。 */
final class IssuePresentation {
    record Summary(String type, String message, String cause, String location) {}
    record Answers(List<String> sections, String shared, boolean paired) {}
    private static final Pattern FRAME = Pattern.compile("\\(([^()]+\\.java:\\d+)\\)");
    private static final Pattern HEADING = Pattern.compile("(?m)^ {0,3}#{1,4} +异常\\s*#?\\s*(\\d+)\\b([^\\r\\n]*)$");
    private static final Pattern INLINE = Pattern.compile("`([^`\\n]+)`|\\*\\*([^*\\n]+)\\*\\*");
    private IssuePresentation() {}

    static Summary summarize(String trace) {
        List<String> lines = trace.lines().toList();
        String first = lines.isEmpty() ? "未知异常" : lines.getFirst().strip();
        int colon = first.indexOf(':');
        String fullType = colon < 0 ? first : first.substring(0, colon);
        String type = fullType.substring(fullType.lastIndexOf('.') + 1);
        if (type.contains("$")) type = type.substring(type.lastIndexOf('$') + 1);
        String message = colon < 0 ? "" : first.substring(colon + 1).strip();
        String cause = ""; int start = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).stripLeading().startsWith("Caused by:")) {
                cause = lines.get(i).strip().substring("Caused by:".length()).strip(); start = i + 1;
            }
        }
        String location = "";
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i).stripLeading();
            if (line.startsWith("at ") && !line.startsWith("at java.") && !line.startsWith("at java/")
                    && !line.startsWith("at java.base/") && !line.startsWith("at javax.")
                    && !line.startsWith("at jdk.") && !line.startsWith("at sun.")) {
                Matcher matcher = FRAME.matcher(line);
                if (matcher.find()) { location = matcher.group(1); break; }
            }
        }
        return new Summary(type, message, cause, location);
    }

    static Answers answers(String text, int count) {
        if (count == 1) return new Answers(List.of(text.replaceFirst("^\\s*#{1,4} +异常\\s*#?\\s*1(?:\\s*(?:解释|解答))?\\s*\\R", "")), "", true);
        Matcher matcher = HEADING.matcher(text);
        List<Integer> indexes = new ArrayList<>(), starts = new ArrayList<>(), ends = new ArrayList<>();
        while (matcher.find()) {
            int number;
            try { number = Integer.parseInt(matcher.group(1)); } catch (NumberFormatException error) { return unpaired(text); }
            // 合并编号、漏号、重复号一律不猜测对应关系。
            if (number != indexes.size() + 1 || number > count || matcher.group(2).matches(".*(?:#\\s*\\d|[、与和及&]\\s*#?\\d).*$")) return unpaired(text);
            indexes.add(number); starts.add(matcher.start()); ends.add(matcher.end());
        }
        if (indexes.size() != count) return unpaired(text);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String part = text.substring(ends.get(i), i + 1 < count ? starts.get(i + 1) : text.length()).strip();
            if (part.isBlank()) return unpaired(text);
            parts.add(part);
        }
        return new Answers(List.copyOf(parts), text.substring(0, starts.getFirst()).strip(), true);
    }
    private static Answers unpaired(String text) { return new Answers(List.of(), text, false); }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;").replace("\0", "␀");
    }
    static String inline(String text) {
        Matcher matcher = INLINE.matcher(text); StringBuilder result = new StringBuilder(); int end = 0;
        while (matcher.find()) {
            result.append(escape(text.substring(end, matcher.start())));
            result.append(matcher.group(1) != null ? "<code>" : "<b>")
                    .append(escape(matcher.group(1) != null ? matcher.group(1) : matcher.group(2)))
                    .append(matcher.group(1) != null ? "</code>" : "</b>"); end = matcher.end();
        }
        return result.append(escape(text.substring(end))).toString();
    }
    /** 有意只支持本地安全排版：标题、段落、列表、粗体、行内与围栏代码；无 HTML/图片/外链。 */
    static String markdown(String text) {
        StringBuilder html = new StringBuilder(); boolean code = false; String list = "";
        for (String line : text.split("\\R", -1)) {
            String stripped = line.strip();
            if (stripped.startsWith("```")) {
                if (!list.isEmpty()) { html.append("</").append(list).append('>'); list = ""; }
                html.append(code ? "</div>" : "<div class='codeblock'>"); code = !code; continue;
            }
            if (code) { html.append(escape(line).replace(" ", "&nbsp;")).append("<br>"); continue; }
            String nextList = stripped.matches("[-*+] +.*") ? "ul" : stripped.matches("\\d+[.)、] +.*") ? "ol" : "";
            if (!nextList.equals(list)) {
                if (!list.isEmpty()) html.append("</").append(list).append('>');
                if (!nextList.isEmpty()) html.append('<').append(nextList).append('>'); list = nextList;
            }
            if (!list.isEmpty()) html.append("<li>").append(inline(stripped.replaceFirst("^(?:[-*+]|\\d+[.)、]) +", ""))).append("</li>");
            else if (stripped.matches("#{1,6} +.*")) html.append("<h3>").append(inline(stripped.replaceFirst("^#{1,6} +", ""))).append("</h3>");
            else if (!stripped.isEmpty()) html.append("<p>").append(inline(line)).append("</p>");
        }
        if (!list.isEmpty()) html.append("</").append(list).append('>');
        if (code) html.append("</div>");
        return html.toString();
    }
}
