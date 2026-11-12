package local.javatrace.store;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.*;

import static java.nio.file.StandardOpenOption.*;

/** 纯 JDK 文件存储。读者只看已提交文件；写者持跨进程锁并比较版本。 */
public final class IssueStore {
    private static final int MAGIC = 0x4a544931; // JTI1
    private static final int MAGIC_V2 = 0x4a544932; // JTI2：追加 AI 解释；不修改 v1 文件布局
    public static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private final Path directory;
    private final Committer committer;

    public IssueStore(Path directory) {
        this(directory, (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }
    // 包内故障注入：测试可在已写完临时文件、尚未提交时模拟磁盘错误。
    interface Committer { void commit(Path source, Path target) throws IOException; }
    IssueStore(Path directory, Committer committer) {
        this.directory = directory.toAbsolutePath().normalize();
        this.committer = Objects.requireNonNull(committer);
    }
    public Path directory() { return directory; }

    /** token 由一次保存流程生成；重试同一 token 不会创建第二条记录。 */
    public Issue create(UUID token, String project, List<Issue.Segment> segments, Issue.Notes notes) throws IOException {
        return create(token, project, segments, notes, Optional.empty());
    }

    public Issue create(UUID token, String project, List<Issue.Segment> segments, Issue.Notes notes,
                        Optional<Issue.Analysis> analysis) throws IOException {
        return locked(() -> {
            try {
                Issue existing = read(token);
                if (!existing.projectName().equals(project) || !existing.segments().equals(segments)) {
                    throw new ConflictException("此保存流程已创建另一份内容，请从记录列表打开已有记录");
                }
                return existing;
            } catch (NoSuchFileException absent) { /* 仅文件不存在时创建；其他读取错误不能当作空库。 */ }
            Instant now = Instant.now();
            Issue issue = new Issue(token, 1, now, now, project, segments, notes, analysis);
            write(issue);
            return issue;
        });
    }

    /** 只修改笔记；原始异常、上下文、创建时间固定。 */
    public Issue update(UUID id, long expectedRevision, Issue.Notes notes) throws IOException {
        return locked(() -> {
            Issue old = read(id);
            if (old.revision() != expectedRevision) throw new ConflictException("记录已被其他窗口修改；请保留笔记并重新打开最新记录");
            Issue updated = new Issue(id, Math.addExact(old.revision(), 1), old.createdAt(), Instant.now(),
                    old.projectName(), old.segments(), notes, old.analysis());
            write(updated);
            return updated;
        });
    }

    /** 只替换 AI 解释，保留证据与旧版笔记；仍核对磁盘版本。 */
    public Issue updateAnalysis(UUID id, long expectedRevision, Issue.Analysis analysis) throws IOException {
        return locked(() -> {
            Issue old = read(id);
            if (old.revision() != expectedRevision) throw new ConflictException("记录已被其他窗口更新，请重新打开；当前回答仍保留");
            Issue updated = new Issue(id, Math.addExact(old.revision(), 1), old.createdAt(), Instant.now(),
                    old.projectName(), old.segments(), old.notes(), Optional.of(analysis));
            write(updated);
            return updated;
        });
    }

    public Issue read(UUID id) throws IOException {
        Path file = path(id);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES || bytes.length < 40) throw new IOException("记录大小无效");
            byte[] payload = Arrays.copyOf(bytes, bytes.length - 32);
            if (!MessageDigest.isEqual(hash(payload), Arrays.copyOfRange(bytes, payload.length, bytes.length))) {
                throw new IOException("记录校验失败，文件保持原状");
            }
            try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(payload))) {
                int magic = data.readInt();
                if (magic != MAGIC && magic != MAGIC_V2) throw new IOException("不支持的记录格式");
                UUID stored = UUID.fromString(string(data));
                if (!id.equals(stored)) throw new IOException("记录编号不匹配");
                long revision = data.readLong();
                Instant created = Instant.parse(string(data));
                Instant updated = Instant.parse(string(data));
                String project = string(data);
                int count = data.readInt();
                if (count < 1 || count > Issue.MAX_SEGMENTS) throw new IOException("异常段数量无效");
                List<Issue.Segment> segments = new ArrayList<>();
                for (int i = 0; i < count; i++) segments.add(new Issue.Segment(string(data), string(data), string(data)));
                Issue.Notes notes = new Issue.Notes(string(data), string(data), string(data));
                Optional<Issue.Analysis> analysis = Optional.empty();
                if (magic == MAGIC_V2 && data.readBoolean()) {
                    analysis = Optional.of(new Issue.Analysis(string(data), string(data), string(data), string(data),
                            Instant.parse(string(data)), data.readBoolean()));
                }
                Issue issue = new Issue(stored, revision, created, updated, project, segments, notes, analysis);
                if (data.available() != 0) throw new IOException("记录含未识别内容");
                return issue;
            }
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new IOException("记录结构无效，文件保持原状", e);
        }
    }

    public record SearchResult(List<Issue> issues, List<String> unreadableFiles) {
        public SearchResult { issues = List.copyOf(issues); unreadableFiles = List.copyOf(unreadableFiles); }
    }

    /** 空查询列出全部；空白分词 AND，大小写不敏感的字面子串，不解释正则。 */
    public SearchResult search(String query) throws IOException {
        Objects.requireNonNull(query);
        if (!Files.exists(directory)) return new SearchResult(List.of(), List.of());
        List<String> words = Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(word -> !word.isEmpty()).toList();
        List<Issue> issues = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.jti")) {
            for (Path file : files) {
                try {
                    String name = file.getFileName().toString();
                    Issue issue = read(UUID.fromString(name.substring(0, name.length() - 4)));
                    StringBuilder text = new StringBuilder(issue.projectName()).append('\n').append(issue.createdAt())
                            .append('\n').append(issue.updatedAt()).append('\n').append(issue.id());
                    for (Issue.Segment segment : issue.segments()) text.append('\n').append(segment.exceptionText())
                            .append('\n').append(segment.context()).append('\n').append(segment.diagnostics());
                    text.append('\n').append(issue.notes().investigation()).append('\n').append(issue.notes().cause())
                            .append('\n').append(issue.notes().solution());
                    issue.analysis().ifPresent(ai -> text.append('\n').append(ai.provider()).append('\n').append(ai.model())
                            .append('\n').append(ai.question()).append('\n').append(ai.answer()));
                    String searchable = text.toString().toLowerCase(Locale.ROOT);
                    if (words.stream().allMatch(searchable::contains)) issues.add(issue);
                } catch (IOException | IllegalArgumentException e) { unreadable.add(file.getFileName().toString()); }
            }
        }
        issues.sort(Comparator.comparing(Issue::updatedAt).reversed().thenComparing(Issue::id));
        return new SearchResult(issues, unreadable);
    }

    public static final class ConflictException extends IOException {
        public ConflictException(String message) { super(message); }
    }

    private Path path(UUID id) { return directory.resolve(Objects.requireNonNull(id) + ".jti"); }

    private interface Operation<T> { T run() throws IOException; }

    private <T> T locked(Operation<T> operation) throws IOException {
        Files.createDirectories(directory);
        try (FileChannel channel = FileChannel.open(directory.resolve(".write.lock"), CREATE, WRITE)) {
            try (FileLock lock = channel.tryLock()) {
                if (lock == null) throw new IOException("记录库正在写入，请稍后重试");
                return operation.run();
            } catch (OverlappingFileLockException e) { throw new IOException("记录库正在写入，请稍后重试", e); }
        }
    }

    private void write(Issue issue) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(buffer)) {
            data.writeInt(MAGIC_V2);
            string(data, issue.id().toString());
            data.writeLong(issue.revision());
            string(data, issue.createdAt().toString());
            string(data, issue.updatedAt().toString());
            string(data, issue.projectName());
            data.writeInt(issue.segments().size());
            for (Issue.Segment segment : issue.segments()) {
                string(data, segment.exceptionText()); string(data, segment.context()); string(data, segment.diagnostics());
            }
            string(data, issue.notes().investigation()); string(data, issue.notes().cause()); string(data, issue.notes().solution());
            data.writeBoolean(issue.analysis().isPresent());
            if (issue.analysis().isPresent()) {
                Issue.Analysis ai = issue.analysis().orElseThrow();
                string(data, ai.provider()); string(data, ai.model()); string(data, ai.question()); string(data, ai.answer());
                string(data, ai.answeredAt().toString()); data.writeBoolean(ai.truncated());
            }
        }
        byte[] payload = buffer.toByteArray();
        if (payload.length > MAX_FILE_BYTES - 32) throw new IOException("单条记录超过 16 MiB，未保存");
        Path temporary = Files.createTempFile(directory, ".pending-", ".tmp");
        try {
            try (FileChannel file = FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING)) {
                writeFully(file, ByteBuffer.wrap(payload));
                writeFully(file, ByteBuffer.wrap(hash(payload)));
                file.force(true);
            }
            // 不降级到非原子覆盖：文件系统不支持时报告失败，旧记录仍可读取。
            committer.commit(temporary, path(issue.id()));
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void writeFully(FileChannel file, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) file.write(bytes);
    }

    private static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static void string(DataOutputStream data, String value) throws IOException {
        if (value.length() > MAX_FILE_BYTES - 36 - data.size()) throw new IOException("单条记录超过 16 MiB，未保存");
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        if (bytes.length > MAX_FILE_BYTES - 36 - data.size()) throw new IOException("单条记录超过 16 MiB，未保存");
        data.writeInt(bytes.length);
        data.write(bytes);
    }

    private static String string(DataInputStream data) throws IOException {
        int length = data.readInt();
        if (length < 0 || length > data.available()) throw new IOException("字段长度无效");
        byte[] bytes = data.readNBytes(length);
        // 解码器报告非法 UTF-8，避免静默替换损坏的中文。
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }
}
