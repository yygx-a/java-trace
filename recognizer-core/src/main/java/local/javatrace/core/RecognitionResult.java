package local.javatrace.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次识别的完整结果。主体和上下文分别保存，避免把相邻日志当作异常正文。
 * UNCERTAIN 既可能带有候选范围，也可能没有范围，调用方必须检查 Optional。
 * 本类解释识别结果，不负责匹配日志：status 是汇总，range 是原文位置，reasons 是依据，
 * warnings 是限制/歧义，notes 是“看见了什么”的内容观察。
 */
public record RecognitionResult(
        Status status,
        Optional<TextRange> exceptionRange,
        Optional<TextRange> contextRange,
        List<Reason> reasons,
        List<Warning> warnings,
        List<Note> notes,
        int inferredMessageLines
) {
    public RecognitionResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(exceptionRange, "exceptionRange");
        Objects.requireNonNull(contextRange, "contextRange");
        // 防御性复制，避免调用方随后修改列表，导致已返回的结果发生变化。
        reasons = List.copyOf(reasons);
        warnings = List.copyOf(warnings);
        notes = List.copyOf(notes);
        if (inferredMessageLines < 0) throw new IllegalArgumentException("Negative inferred message line count");
    }

    public RecognitionResult(Status status, Optional<TextRange> exceptionRange, Optional<TextRange> contextRange,
                             List<Reason> reasons, List<Warning> warnings, List<Note> notes) {
        this(status, exceptionRange, contextRange, reasons, warnings, notes, 0);
    }

    /** 兼容 v0 调用方；新增内容观察不会丢失或降级为边界警告。 */
    public RecognitionResult(Status status, Optional<TextRange> exceptionRange, Optional<TextRange> contextRange,
                             List<Reason> reasons, List<Warning> warnings) {
        this(status, exceptionRange, contextRange, reasons, warnings, List.of());
    }

    // ===== 区域 1：范围是否可用，为什么需要核对 =====
    /** 只描述返回范围的限制；不把没有帧直接解释成范围错误。 */
    public Boundary boundary() {
        if (exceptionRange.isEmpty()) return Boundary.UNAVAILABLE;
        if (reasons.contains(Reason.USER_SELECTION)) return Boundary.USER_SELECTED;
        return warnings.stream().anyMatch(w -> w != Warning.MISSING_STACK)
                ? Boundary.REVIEW_REQUIRED : Boundary.LOCATED;
    }

    // ===== 区域 2：已看见完整形状的堆栈、仅标题，还是缺标题的片段 =====
    // TRACE 是结构类别，不保证异常从未被截断；裁剪和边界情况仍需一起看 boundary/warnings。
    /** 只描述原文中看见的结构，不推断未输出的 Throwable 信息。 */
    public Structure structure() {
        if (exceptionRange.isEmpty()) return Structure.NONE;
        if (reasons.contains(Reason.USER_SELECTION)) return Structure.UNCLASSIFIED;
        if (!reasons.contains(Reason.THROWABLE_HEADER)) return Structure.FRAGMENT;
        return reasons.contains(Reason.STACK_FRAME) || reasons.contains(Reason.ELIDED_FRAMES)
                || reasons.contains(Reason.CIRCULAR_REFERENCE) ? Structure.TRACE : Structure.HEADER_ONLY;
    }

    // ===== 区域 3：供界面和记录使用的诊断词汇 =====
    // USER_SELECTED / UNCLASSIFIED 来自手动选区，不冒充自动识别成功。
    public enum Boundary { LOCATED, REVIEW_REQUIRED, UNAVAILABLE, USER_SELECTED }
    public enum Structure { TRACE, HEADER_ONLY, FRAGMENT, NONE, UNCLASSIFIED }
    /** 内容观察不等于截断证据。没有帧可能是合法的 stackless Throwable。 */
    public enum Note { BRANCH_WITHOUT_FRAMES }

    /** FOUND 为规则匹配且无警告，UNCERTAIN 表示存在限制或歧义，NOT_FOUND 表示锚点未命中。 */
    public enum Status { FOUND, UNCERTAIN, NOT_FOUND }

    /** 记录主体与上下文分别依据哪些结构被识别。 */
    public enum Reason {
        THROWABLE_HEADER, STACK_FRAME, CAUSE, SUPPRESSED, ELIDED_FRAMES, ADJACENT_ERROR_CONTEXT,
        MULTILINE_MESSAGE, CIRCULAR_REFERENCE, USER_SELECTION
    }

    /** 记录上限、残缺输入和无法明确判断的边界，供界面提示。 */
    public enum Warning {
        CHARACTER_LIMIT, LINE_LIMIT, MISSING_HEADER, MISSING_STACK,
        AMBIGUOUS_BOUNDARY, SNAPSHOT_START_TRUNCATED, SNAPSHOT_END_TRUNCATED, UNTERMINATED_LAST_LINE,
        INFERRED_MESSAGE, MESSAGE_LOOKAHEAD_LIMIT
    }
}
