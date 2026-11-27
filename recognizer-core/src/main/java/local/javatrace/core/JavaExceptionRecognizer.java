package local.javatrace.core;

import local.javatrace.core.RecognitionResult.Status;
import local.javatrace.core.RecognitionResult.Warning;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 异常识别的入口。输入是一份固定的日志原文和用户指定的锚点，输出对应的异常范围与诊断。
 * 整个处理过程依次建立行索引、分类、组合异常块，再查找包含锚点的块。
 *
 * 阅读地图：本类负责调度；LineIndex 管原文位置；LineClassifier 管单行规则；
 * TraceAssembler 管整段边界和分支归属；RecognitionResult 管返回状态与解释。
 * 它读取已经打印出来的文本，不监听 JVM 内部 throw，也不会主动捕获业务程序里的异常。
 */
public final class JavaExceptionRecognizer {
    // ===== 区域 1：扫描上限与单段识别入口 =====
    // 限制单次扫描规模。超出限制时明确返回警告，避免把部分扫描当成完整结果。
    public static final int MAX_CHARACTERS = 1_000_000;
    public static final int MAX_LINES = 20_000;

    public RecognitionResult recognize(String originalText, int anchorOffset) {
        return recognize(originalText, anchorOffset, false, false);
    }

    /**
     * 识别指定快照。截断标志由获取快照的调用方提供，表示原始输出可能在前端或末端缺失。
     *
     * @param originalText 不可变的原始文本快照
     * @param anchorOffset 原文中的 UTF-16 偏移，允许等于文本长度
     * @param startTruncated 快照前端是否已被截断
     * @param endTruncated 快照末端是否已被截断
     * @return 包含状态、原文范围、识别依据和警告的结果
     * @throws IllegalArgumentException 文本为 null 或锚点越界时抛出
     *
     * 返回的范围始终指向传入的原文。CRLF 的两个字符都归属于前一行。
     */
    public RecognitionResult recognize(String originalText, int anchorOffset,
                                       boolean startTruncated, boolean endTruncated) {
        if (originalText == null) {
            throw new IllegalArgumentException("originalText must not be null");
        }
        if (anchorOffset < 0 || anchorOffset > originalText.length()) {
            throw new IllegalArgumentException("anchorOffset must be between 0 and the snapshot length");
        }
        // 先扫描整份有界快照得到候选块，再定位点击处；不是只向上下各取固定几行。
        Scan scan = scan(originalText, startTruncated, endTruncated);
        if (scan.lines() == null) {
            return withoutRange(Status.UNCERTAIN, scan.warnings());
        }
        if (!scan.blocks().isEmpty()) {
            // anchorOffset 是 UTF-16 字符位置，转换成行号后检查是否落在主体或可选上下文中。
            int anchorLine = LineIndex.anchorLine(scan.lines(), anchorOffset);
            for (TraceAssembler.Block block : scan.blocks()) {
                if ((block.context() >= 0 && anchorLine >= block.context() && anchorLine < block.first())
                        || (anchorLine >= block.first() && anchorLine <= block.last())) {
                    return resultFor(block, scan.lines(), scan.warnings());
                }
            }
        }
        // 点击普通日志时不会自动跳去选中附近的异常。
        return withoutRange(Status.NOT_FOUND, scan.warnings());
    }

    // ===== 区域 2：批量识别入口 =====
    /** 一次扫描取出全部可识别的异常块，保留顺序、重复异常与每段独立范围。 */
    public BatchRecognitionResult recognizeAll(String originalText, boolean startTruncated, boolean endTruncated) {
        if (originalText == null) {
            throw new IllegalArgumentException("originalText must not be null");
        }
        Scan scan = scan(originalText, startTruncated, endTruncated);
        // 每个候选各自转换结果，不去重、不合并重复事件；快照级警告单独放在批量结果中。
        List<RecognitionResult> exceptions = scan.blocks().stream()
                .map(block -> resultFor(block, scan.lines(), List.of()))
                .toList();
        return new BatchRecognitionResult(exceptions, scan.warnings());
    }

    // ===== 区域 3：公共扫描流水线，单段和批量共用 =====
    private static Scan scan(String originalText, boolean startTruncated, boolean endTruncated) {
        // 3.1 记录调用方告知的裁剪情况，并检查字符上限；不能把没扫描完解释为没有异常。
        List<Warning> snapshotWarnings = new ArrayList<>();
        if (startTruncated) {
            snapshotWarnings.add(Warning.SNAPSHOT_START_TRUNCATED);
        }
        if (endTruncated) {
            snapshotWarnings.add(Warning.SNAPSHOT_END_TRUNCATED);
        }
        if (originalText.length() > MAX_CHARACTERS) {
            snapshotWarnings.add(Warning.CHARACTER_LIMIT);
            return new Scan(null, List.of(), snapshotWarnings);
        }
        // 3.2 按实际换行建立原文行索引，同时检查物理行上限。
        List<LineIndex.Line> lines = LineIndex.scan(originalText, MAX_LINES);
        if (lines == null) {
            snapshotWarnings.add(Warning.LINE_LIMIT);
            return new Scan(null, List.of(), snapshotWarnings);
        }
        // 3.3 每行判断是标题、帧、原因、日志头等。分类结果只描述单行，不决定整段归属。
        List<LineClassifier.Classified> classified = LogFormatAdapter.classify(originalText, lines);
        // 3.4 根据相邻行、缩进和分支状态组合异常，得到以行号表示的候选范围。
        return new Scan(lines, TraceAssembler.assemble(classified), snapshotWarnings);
    }

    private record Scan(List<LineIndex.Line> lines, List<TraceAssembler.Block> blocks, List<Warning> warnings) {}

    // ===== 区域 4：把候选行号转换为可供预览/复制的原文范围 =====
    private static RecognitionResult resultFor(TraceAssembler.Block block, List<LineIndex.Line> lines,
                                               List<Warning> snapshotWarnings) {
        LineIndex.Line first = lines.get(block.first());
        LineIndex.Line last = lines.get(block.last());
        // 主体包含末行原有的换行符。相邻 ERROR 行单独作为可选上下文。
        TextRange exceptionRange = new TextRange(first.start(), last.end());
        Optional<TextRange> contextRange = Optional.empty();
        if (block.context() >= 0) {
            contextRange = Optional.of(new TextRange(lines.get(block.context()).start(), first.start()));
        }
        List<Warning> warnings = new ArrayList<>(snapshotWarnings);
        warnings.addAll(block.warnings());
        // 最后一行没有结束符时，无法排除输出还在写入或快照被截断的可能。
        if (block.last() == lines.size() - 1 && last.contentEnd() == last.end()) {
            warnings.add(Warning.UNTERMINATED_LAST_LINE);
        }
        if (block.inferredMessageLines() > 0) warnings.add(Warning.INFERRED_MESSAGE);
        // FOUND 表示当前规则没有触发警告，不保证程序曾完整输出全部信息；内容观察 notes 另存。
        return new RecognitionResult(warnings.isEmpty() ? Status.FOUND : Status.UNCERTAIN,
                Optional.of(exceptionRange), contextRange, List.copyOf(block.reasons()), warnings, List.copyOf(block.notes()),
                block.inferredMessageLines());
    }

    // ===== 区域 5：未命中或超限时返回无范围的结果 =====
    private static RecognitionResult withoutRange(Status status, List<Warning> warnings) {
        return new RecognitionResult(status, Optional.empty(), Optional.empty(), List.of(), warnings);
    }
}
