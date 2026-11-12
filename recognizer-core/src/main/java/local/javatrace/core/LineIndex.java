package local.javatrace.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 位置基础：只切行、保存原文坐标和定位点击行，不判断是否存在异常。
 * 全部位置是 Java String 的 UTF-16 偏移，范围为左闭右开，能够直接用于 substring。
 * IDEA 中一条长日志显示成多行属于软换行；这里只认原文中的 CR/LF 等物理换行。
 */
final class LineIndex {
    private LineIndex() {}

    // ===== 区域 1：一行对应的三个原文位置 =====
    // start 为行首，contentEnd 位于换行符之前，end 位于换行符之后。
    // 最后一行没有换行符时，contentEnd 与 end 相等。
    record Line(int start, int contentEnd, int end) {}

    // ===== 区域 2：按原始换行建立行索引，不修改原文 =====
    /** 空文本返回空列表，物理行超过上限时返回 null。 */
    static List<Line> scan(String text, int maxLines) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        for (int cursor = 0; cursor < text.length(); cursor++) {
            char character = text.charAt(cursor);
            if (character != '\r' && character != '\n') {
                continue;
            }
            int contentEnd = cursor;
            // CRLF 作为一个行结束符处理，两个字符仍留在原文范围中。
            if (character == '\r' && cursor + 1 < text.length() && text.charAt(cursor + 1) == '\n') {
                cursor++;
            }
            if (lines.size() == maxLines) {
                return null;
            }
            lines.add(new Line(start, contentEnd, cursor + 1));
            start = cursor + 1;
        }
        // 只有实际存在内容时才补最后一行，不为结尾换行符虚构一行。
        if (start < text.length()) {
            if (lines.size() == maxLines) {
                return null;
            }
            lines.add(new Line(start, text.length(), text.length()));
        }
        return lines;
    }

    // ===== 区域 3：把右键位置的字符偏移转换为物理行号 =====
    /** 二分查找锚点所在行。调用方保证列表非空，文末锚点归最后一个实际行。 */
    static int anchorLine(List<Line> lines, int offset) {
        int low = 0;
        int high = lines.size() - 1;
        while (low < high) {
            int middle = (low + high) / 2;
            if (offset < lines.get(middle).end()) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }
}
