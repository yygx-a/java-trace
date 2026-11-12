package local.javatrace.core;

import java.util.List;

/** 异常按原文顺序排列。扫描上限和截断等快照级警告单独返回。 */
public record BatchRecognitionResult(List<RecognitionResult> exceptions,
                                     List<RecognitionResult.Warning> warnings) {
    public BatchRecognitionResult {
        exceptions = List.copyOf(exceptions);
        warnings = List.copyOf(warnings);
    }
}
