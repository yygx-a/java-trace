package local.javatrace.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static java.nio.file.StandardOpenOption.*;

/** 合成样本，使用独立临时目录；预期不由实现反推。 */
public final class IssueStoreRegression {
    private static int checks;
    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            try (FileChannel channel = FileChannel.open(Path.of(args[0]), CREATE, WRITE); var lock = channel.lock()) {
                System.out.println("locked"); System.out.flush(); System.in.read();
            }
            return;
        }
        Path root = Files.createTempDirectory("java-trace-store-regression-");
        try {
            IssueStore store = new IssueStore(root);
            List<Issue.Segment> segments = List.of(
                    new Issue.Segment("java.lang.IllegalStateException: 中文😀\r\n\tat Demo.run(Demo.java:1)\r\n", "ERROR 导入失败\r\n", "边界不确定"),
                    new Issue.Segment("java.lang.Error: B\n\tat B.run(B.java:2)\n", "", ""),
                    new Issue.Segment("java.lang.IllegalStateException: 中文😀\r\n\tat Demo.run(Demo.java:1)\r\n", "", ""));
            UUID id = UUID.randomUUID();
            Issue original = store.create(id, "合成项目", segments, Issue.Notes.empty());
            check(original.revision() == 1, "initial revision");
            check(original.segments().equals(segments), "independent and duplicate segments");
            IssueStore restarted = new IssueStore(root);
            check(restarted.read(id).equals(original), "restart, unicode, exact CRLF, empty notes");
            check(restarted.create(id, "合成项目", segments, Issue.Notes.empty()).equals(original), "idempotent create");
            check(store.search("").issues().size() == 1, "retry does not duplicate");
            fails(() -> store.create(id, "另一项目", segments, Issue.Notes.empty()), "different content same token");
            Issue.Notes notes = new Issue.Notes("检查配置\n复现步骤", "连接池配置", "修复 TIMEOUT\n再次运行");
            Issue updated = restarted.update(id, 1, notes);
            check(updated.revision() == 2 && updated.createdAt().equals(original.createdAt()), "update revision and creation time");
            check(new IssueStore(root).read(id).notes().equals(notes), "notes reopen");
            check(updated.segments().equals(segments), "notes preserve raw evidence");
            fails(() -> store.update(id, 1, Issue.Notes.empty()), "stale update rejected");
            check(store.read(id).equals(updated), "stale update preserves existing");
            check(store.search("中文 timeout").issues().size() == 1, "AND unicode case insensitive search");
            check(store.search("配置 导入失败").issues().size() == 1, "context and notes searchable");
            check(store.search("不存在").issues().isEmpty(), "no matches");
            check(store.search(".*").issues().isEmpty(), "literal query");
            check(store.search("  \n  ").issues().size() == 1, "blank search");

            try (FileChannel lockChannel = FileChannel.open(root.resolve(".write.lock"), CREATE, WRITE);
                 var lock = lockChannel.lock()) {
                fails(() -> store.update(id, 2, Issue.Notes.empty()), "overlapping writer rejected");
                check(store.read(id).equals(updated), "read during write lock");
            }
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) futures.add(pool.submit(() -> {
                    start.await();
                    try { new IssueStore(root).update(id, 2, notes); return true; }
                    catch (IOException e) { return false; }
                }));
                start.countDown();
                int successes = 0;
                for (Future<Boolean> future : futures) if (future.get()) successes++;
                check(successes == 1 && store.read(id).revision() == 3, "only one concurrent update wins");
            } finally { pool.shutdownNow(); }

            Issue beforeFailure = store.read(id);
            IssueStore failing = new IssueStore(root, (source, target) -> {
                check(Files.size(source) > 32, "temporary payload written before failure");
                throw new IOException("simulated commit failure");
            });
            fails(() -> failing.update(id, 3, Issue.Notes.empty()), "commit failure reported");
            check(store.read(id).equals(beforeFailure), "commit failure preserves old record");
            try (var files = Files.list(root)) {
                check(files.noneMatch(file -> file.toString().endsWith(".tmp")), "failed temporary file cleaned");
            }
            Process other = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),
                    "-cp", System.getProperty("java.class.path"), IssueStoreRegression.class.getName(), root.resolve(".write.lock").toString()).start();
            try {
                String line = new java.io.BufferedReader(new java.io.InputStreamReader(other.getInputStream())).readLine();
                check("locked".equals(line), "other process acquired lock");
                fails(() -> store.update(id, 3, notes), "cross-process writer blocked");
                check(store.read(id).equals(beforeFailure), "cross-process lock preserves record");
            } finally { other.getOutputStream().write(1); other.getOutputStream().flush(); other.waitFor(); }
            fails(() -> store.update(id, 3, new Issue.Notes("x".repeat(IssueStore.MAX_FILE_BYTES), "", "")), "oversize update");
            check(store.read(id).equals(beforeFailure), "failed update preserves committed record");
            fails(() -> store.update(id, 3, new Issue.Notes("\uD83D", "", "")), "invalid Unicode rejected without replacement");
            check(store.read(id).equals(beforeFailure), "invalid Unicode preserves original");
            Files.write(root.resolve(".pending-interrupted.tmp"), new byte[]{1, 2});
            check(store.search("").issues().size() == 1, "interrupted temporary file ignored");
            UUID broken = UUID.randomUUID();
            Files.write(root.resolve(broken + ".jti"), new byte[]{1, 2, 3});
            check(store.search("").unreadableFiles().size() == 1 && store.search("").issues().size() == 1, "broken file isolated and reported");
            UUID changed = UUID.randomUUID();
            store.create(changed, "校验样本", segments, notes);
            byte[] damaged = Files.readAllBytes(root.resolve(changed + ".jti"));
            damaged[20] ^= 1;
            Files.write(root.resolve(changed + ".jti"), damaged);
            fails(() -> store.read(changed), "checksum detects corruption");
            fails(() -> store.update(changed, 1, notes), "corrupt record cannot be overwritten");
            check(Arrays.equals(damaged, Files.readAllBytes(root.resolve(changed + ".jti"))), "corrupt evidence retained");
            check(store.search("").unreadableFiles().size() == 2, "all unreadable files reported");
            UUID malformed = UUID.randomUUID();
            Issue dated = store.create(malformed, "坏时间字段", segments, notes);
            byte[] invalidDate = Files.readAllBytes(root.resolve(malformed + ".jti"));
            byte[] time = dated.createdAt().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int dateStart = -1;
            for (int pos = 0; pos < invalidDate.length - time.length; pos++) {
                if (Arrays.equals(time, Arrays.copyOfRange(invalidDate, pos, pos + time.length))) { dateStart = pos; break; }
            }
            check(dateStart >= 0, "synthetic timestamp located");
            invalidDate[dateStart] = 'X';
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(invalidDate, invalidDate.length - 32));
            System.arraycopy(digest, 0, invalidDate, invalidDate.length - 32, 32);
            Files.write(root.resolve(malformed + ".jti"), invalidDate);
            fails(() -> store.read(malformed), "valid checksum with invalid timestamp isolated");
            check(store.search("").unreadableFiles().size() == 3, "malformed structure does not abort search");
            fails(() -> store.create(malformed, "坏时间字段", segments, notes), "create cannot overwrite unreadable evidence");

            Path blocked = root.resolve("not-directory");
            Files.writeString(blocked, "keep");
            fails(() -> new IssueStore(blocked).create(UUID.randomUUID(), "项目", segments, notes), "unwritable location");
            check(Files.readString(blocked).equals("keep"), "storage failure preserves unrelated file");
            check(new IssueStore(root.resolve("absent")).search("").issues().isEmpty(), "empty library");
            UUID aiId = UUID.randomUUID();
            Issue.Analysis ai = new Issue.Analysis("DeepSeek", "synthetic-model", "解释中文报错😀\n第二行", "合成回答：检查字段\r\n建议重试", java.time.Instant.parse("2026-11-11T00:00:00Z"), true);
            Issue explained = store.create(aiId, "AI项目", segments, notes, Optional.of(ai));
            check(new IssueStore(root).read(aiId).equals(explained), "v2 explanation round trip with newlines and metadata");
            check(store.search("合成回答 AI项目").issues().size() == 1, "AI answer searchable");
            check(store.search("解释中文报错").issues().size() == 1, "saved question searchable");
            check(store.create(aiId, "AI项目", segments, Issue.Notes.empty(), Optional.empty()).equals(explained), "duplicate save does not clear answer");
            Issue noteUpdate = store.update(aiId, 1, Issue.Notes.empty());
            check(noteUpdate.analysis().equals(Optional.of(ai)), "legacy update API preserves explanation");
            Issue.Analysis nextAi = new Issue.Analysis("DeepSeek", "synthetic-model", "新提问", "新的完整回答", ai.answeredAt(), false);
            Issue answerUpdate = store.updateAnalysis(aiId, 2, nextAi);
            check(answerUpdate.revision() == 3 && answerUpdate.segments().equals(segments) && answerUpdate.createdAt().equals(explained.createdAt()), "AI update preserves evidence and creation time");
            fails(() -> store.updateAnalysis(aiId, 2, ai), "stale AI update rejected");
            check(store.read(aiId).equals(answerUpdate), "failed stale explanation does not overwrite answer");
            fails(() -> store.updateAnalysis(aiId, 3, new Issue.Analysis("DeepSeek", "model", "question", "x".repeat(IssueStore.MAX_FILE_BYTES), ai.answeredAt(), false)), "oversized AI answer cannot corrupt record");
            check(store.read(aiId).equals(answerUpdate), "failed AI write preserves committed record");

            // 手工构造固定的旧协议样本，避免把当前 writer 输出当作 v1 验证依据。
            UUID legacyId = UUID.randomUUID();
            var legacyPayload = new java.io.ByteArrayOutputStream();
            try (var data = new java.io.DataOutputStream(legacyPayload)) {
                data.writeInt(0x4a544931); legacyString(data, legacyId.toString()); data.writeLong(7);
                legacyString(data, "2026-11-10T01:00:00Z"); legacyString(data, "2026-11-10T02:00:00Z");
                legacyString(data, "旧版合成项目"); data.writeInt(1);
                legacyString(data, "java.lang.Error: 旧版\r\n\tat Old.run(Old.java:1)\r\n"); legacyString(data, ""); legacyString(data, "FOUND");
                legacyString(data, "旧排查\n第二行"); legacyString(data, "旧原因"); legacyString(data, "旧办法");
            }
            var legacyFile = new java.io.ByteArrayOutputStream(); legacyFile.writeBytes(legacyPayload.toByteArray());
            legacyFile.writeBytes(java.security.MessageDigest.getInstance("SHA-256").digest(legacyPayload.toByteArray()));
            Files.write(root.resolve(legacyId + ".jti"), legacyFile.toByteArray());
            Issue legacyIssue = store.read(legacyId);
            check(legacyIssue.revision() == 7 && legacyIssue.analysis().isEmpty(), "v1 schema reads without AI field");
            check(legacyIssue.notes().equals(new Issue.Notes("旧排查\n第二行", "旧原因", "旧办法")), "v1 notes retained exactly");
            Issue migrated = store.updateAnalysis(legacyId, 7, nextAi);
            check(migrated.notes().equals(legacyIssue.notes()) && migrated.segments().equals(legacyIssue.segments()), "v1 to v2 preserves original notes and raw trace");
            check(new IssueStore(root).read(legacyId).equals(migrated), "converted v2 survives reopen");
            System.out.println("Issue store regression: " + checks + " assertions passed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
    private static void legacyString(java.io.DataOutputStream data, String text) throws IOException {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8); data.writeInt(bytes.length); data.write(bytes);
    }
    private interface IO { void run() throws Exception; }
    private static void fails(IO action, String label) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError(label);
    }
}
