package local.javatrace.idea;

import local.javatrace.core.RecognitionResult;
import local.javatrace.core.TextRange;
import local.javatrace.store.Issue;
import java.util.List;

/** 从稳定预览提取证据，不从正在滚动的控制台重新读取。 */
final class PreviewEvidence {
    private PreviewEvidence() {}
    static Issue.Segment segment(String snapshot, TextRange selected, RecognitionResult result, boolean context) {
        return segment(snapshot, selected, result, context, List.of());
    }

    static Issue.Segment segment(String snapshot, TextRange selected, RecognitionResult result, boolean context,
                                 List<RecognitionResult.Warning> snapshotWarnings) {
        String preceding = context ? result.contextRange().map(range -> {
            int end = Math.min(range.endOffset(), selected.startOffset());
            return end > range.startOffset() ? snapshot.substring(range.startOffset(), end) : "";
        }).orElse("") : "";
        String diagnostics = result.status() + " · boundary=" + result.boundary() + " · structure=" + result.structure()
                + " · reasons=" + result.reasons() + " · warnings=" + result.warnings() + " · notes=" + result.notes()
                + (snapshotWarnings.isEmpty() ? "" : " · snapshotWarnings=" + snapshotWarnings)
                + (selected.equals(result.exceptionRange().orElseThrow()) ? "" : " · 用户按行调整范围");
        return new Issue.Segment(snapshot.substring(selected.startOffset(), selected.endOffset()), preceding, diagnostics);
    }
}
