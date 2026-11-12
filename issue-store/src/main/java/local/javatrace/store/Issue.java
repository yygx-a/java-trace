package local.javatrace.store;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;

/** 保存时的不可变记录；多个段始终有各自的边界，原文不作换行规范化。 */
public record Issue(UUID id, long revision, Instant createdAt, Instant updatedAt,
                    String projectName, List<Segment> segments, Notes notes, Optional<Analysis> analysis) {
    public static final int MAX_SEGMENTS = 1000;
    /** v1 调用方继续可用；旧笔记只为兼容保留。 */
    public Issue(UUID id, long revision, Instant createdAt, Instant updatedAt,
                 String projectName, List<Segment> segments, Notes notes) {
        this(id, revision, createdAt, updatedAt, projectName, segments, notes, Optional.empty());
    }
    public Issue {
        Objects.requireNonNull(id);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
        Objects.requireNonNull(projectName);
        Objects.requireNonNull(notes);
        Objects.requireNonNull(analysis);
        segments = List.copyOf(segments);
        if (revision < 1 || segments.isEmpty() || segments.size() > MAX_SEGMENTS) {
            throw new IllegalArgumentException("记录版本或异常段数量无效");
        }
    }

    public record Analysis(String provider, String model, String question, String answer,
                           Instant answeredAt, boolean truncated) {
        public Analysis {
            Objects.requireNonNull(provider); Objects.requireNonNull(model); Objects.requireNonNull(question);
            Objects.requireNonNull(answer); Objects.requireNonNull(answeredAt);
            if (provider.isBlank() || model.isBlank() || question.isBlank() || answer.isBlank()) {
                throw new IllegalArgumentException("AI 解释字段不能为空");
            }
        }
    }

    public record Segment(String exceptionText, String context, String diagnostics) {
        public Segment {
            Objects.requireNonNull(exceptionText);
            Objects.requireNonNull(context);
            Objects.requireNonNull(diagnostics);
            if (exceptionText.isEmpty()) throw new IllegalArgumentException("异常原文不能为空");
        }
    }

    public record Notes(String investigation, String cause, String solution) {
        public Notes {
            Objects.requireNonNull(investigation);
            Objects.requireNonNull(cause);
            Objects.requireNonNull(solution);
        }
        public static Notes empty() { return new Notes("", "", ""); }
    }
}
