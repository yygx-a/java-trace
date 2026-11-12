package local.javatrace.idea;

import local.javatrace.core.RecognitionResult;
import local.javatrace.core.TextRange;
import java.util.List;
import java.util.Optional;

/** 手动选区的来源必须保留，不能冒充自动识别成功。 */
final class ManualSelection {
    private ManualSelection() {}
    static RecognitionResult result(String snapshot, TextRange selected) {
        if (selected.startOffset() >= selected.endOffset() || selected.endOffset() > snapshot.length()) {
            throw new IllegalArgumentException("Selection must be nonempty and within snapshot");
        }
        return new RecognitionResult(RecognitionResult.Status.UNCERTAIN, Optional.of(selected), Optional.empty(),
                List.of(RecognitionResult.Reason.USER_SELECTION), List.of());
    }
}
