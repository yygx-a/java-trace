package local.javatrace.core;

/**
 * 原始快照中的非空半开区间 [startOffset, endOffset)。
 * 单位是 Java String 使用的 UTF-16 代码单元，可直接传给 substring。
 * 范围的上界是否小于等于原文长度由生成范围的调用方保证。
 */
public record TextRange(int startOffset, int endOffset) {
    public TextRange {
        if (startOffset < 0 || endOffset <= startOffset) {
            throw new IllegalArgumentException("Expected a nonempty half-open range");
        }
    }
}
