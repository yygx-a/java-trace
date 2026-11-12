package local.javatrace.idea;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import local.javatrace.store.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 只连接环回模拟服务，不读取真实凭据，不访问 DeepSeek。 */
public final class DeepSeekRegression {
    private static int checks;
    private static final String KEY = "synthetic-test-token";
    private static final List<Issue.Segment> SEGMENTS = List.of(
            new Issue.Segment("java.lang.Error: 中文😀\r\n\tat Demo.run(Demo.java:1)\r\n", "ERROR 前置日志\r\n", "边界不确定"),
            new Issue.Segment("java.lang.Error: B\n\tat B.run(B.java:2)\n", "", "已识别"));
    public static void main(String[] args) throws Exception {
        check(DeepSeekClient.ENDPOINT.toString().equals("https://api.deepseek.com/chat/completions"), "fixed official HTTPS endpoint");
        String question = DeepSeekClient.question(SEGMENTS);
        check(question.contains("异常 #1") && question.contains("异常 #2") && question.contains("中文😀\r\n"), "numbered exact unicode and CRLF");
        check(question.contains("ERROR 前置日志") && question.contains("边界不确定"), "selected context and boundary warnings included");
        fails(() -> DeepSeekClient.question(List.of()), "empty selection rejected");
        fails(() -> DeepSeekClient.question(List.of(new Issue.Segment("x".repeat(200_001), "", ""))), "oversized input rejected before network");
        fails(() -> DeepSeekClient.validateKey(""), "empty key rejected");
        fails(() -> DeepSeekClient.validateKey("key\nother"), "header injection rejected");
        fails(() -> DeepSeekClient.validateKey("中文"), "invalid key rejected");
        DeepSeekClient.validateKey(KEY);

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(handlers);
        AtomicReference<String> authorization = new AtomicReference<>(), body = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger(), redirected = new AtomicInteger(); CountDownLatch slowStarted = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            String route = exchange.getRequestURI().getPath();
            byte[] request = exchange.getRequestBody().readAllBytes();
            int status = 200; byte[] response;
            switch (route) {
                case "/success" -> {
                    calls.incrementAndGet(); authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    body.set(new String(request, StandardCharsets.UTF_8)); response = json("这段报错表示输入无效。\n请检查 Demo.java:1。", "stop");
                }
                case "/unauthorized" -> { calls.incrementAndGet(); status = 401; response = ("do not expose " + KEY).getBytes(StandardCharsets.UTF_8); }
                case "/quota" -> { status = 402; response = new byte[0]; }
                case "/limited" -> { status = 429; response = new byte[0]; }
                case "/redirect" -> { status = 302; exchange.getResponseHeaders().set("Location", "/redirect-target"); response = new byte[0]; }
                case "/redirect-target" -> { redirected.incrementAndGet(); response = json("unexpected", "stop"); }
                case "/truncated" -> response = json("部分回答", "length");
                case "/malformed" -> response = "{not-json".getBytes(StandardCharsets.UTF_8);
                case "/empty" -> response = json("  ", "stop");
                case "/unfinished" -> response = json("partial", "tool_calls");
                case "/oversized" -> response = new byte[DeepSeekClient.MAX_RESPONSE_BYTES + 1];
                case "/slow" -> {
                    slowStarted.countDown();
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    response = json("late", "stop");
                }
                case "/slow-body" -> {
                    response = json("late body", "stop"); exchange.sendResponseHeaders(200, response.length);
                    try { Thread.sleep(1500); exchange.getResponseBody().write(response); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    catch (IOException disconnected) { /* caller timed out */ }
                    finally { exchange.close(); }
                    return;
                }
                default -> { status = 503; response = new byte[0]; }
            }
            try { exchange.sendResponseHeaders(status, response.length); exchange.getResponseBody().write(response); }
            catch (IOException disconnected) { /* cancellation/size limit deliberately closes the request */ }
            finally { exchange.close(); }
        });
        server.start();
        String base = "http://" + (server.getAddress().getAddress() instanceof Inet6Address ? "[::1]" : "127.0.0.1") + ":" + server.getAddress().getPort();
        try {
            ProxySelector previousProxy = ProxySelector.getDefault();
            AtomicInteger proxySelections = new AtomicInteger();
            Issue.Analysis ai;
            try {
                ProxySelector.setDefault(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) {
                        proxySelections.incrementAndGet(); return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 1)));
                    }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
                });
                ai = client(base, "/success", 3000).explain(KEY, SEGMENTS);
                check(proxySelections.get() == 0, "explicit direct route ignores broken global proxy");
            } finally { ProxySelector.setDefault(previousProxy); }
            check(ai.answer().equals("这段报错表示输入无效。\n请检查 Demo.java:1。") && !ai.truncated(), "answer exact and complete");
            check(ai.provider().equals("DeepSeek") && ai.model().equals("synthetic-model") && ai.question().equals(question), "provider model and question retained");
            check(authorization.get().equals("Bearer " + KEY), "bearer authentication header");
            JsonObject payload = JsonParser.parseString(body.get()).getAsJsonObject();
            check(payload.get("model").getAsString().equals("deepseek-flash") && !payload.get("stream").getAsBoolean(), "current model nonstream contract");
            check(payload.getAsJsonObject("thinking").get("type").getAsString().equals("disabled") && payload.get("max_tokens").getAsInt() == 4096, "bounded nonthinking response");
            check(payload.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString().equals(question), "JSON preserves prompt exactly");
            check(!body.get().contains(KEY) && payload.getAsJsonArray("messages").size() == 2, "key never in prompt and no extra history");
            check(calls.get() == 1, "one click one request");
            IOException unauthorized = fails(() -> client(base, "/unauthorized", 3000).explain(KEY, SEGMENTS), "401 surfaced");
            check(unauthorized.getMessage().contains("Key 无效") && !unauthorized.toString().contains(KEY), "error body and key not echoed");
            check(calls.get() == 2, "no automatic retry after unauthorized");
            check(fails(() -> client(base, "/quota", 3000).explain(KEY, SEGMENTS), "402 surfaced").getMessage().contains("余额"), "quota explanation");
            check(fails(() -> client(base, "/limited", 3000).explain(KEY, SEGMENTS), "429 surfaced").getMessage().contains("请求受限"), "rate limit explanation");
            fails(() -> client(base, "/redirect", 3000).explain(KEY, SEGMENTS), "redirect rejected");
            check(redirected.get() == 0, "authorization never forwarded by redirect");
            check(client(base, "/truncated", 3000).explain(KEY, SEGMENTS).truncated(), "length finish reports partial answer");
            fails(() -> client(base, "/malformed", 3000).explain(KEY, SEGMENTS), "malformed JSON rejected");
            fails(() -> client(base, "/empty", 3000).explain(KEY, SEGMENTS), "empty answer rejected");
            fails(() -> client(base, "/unfinished", 3000).explain(KEY, SEGMENTS), "unexpected completion rejected");
            fails(() -> client(base, "/oversized", 3000).explain(KEY, SEGMENTS), "response size bounded");
            check(fails(() -> client(base, "/slow", 250).explain(KEY, SEGMENTS), "header timeout").getMessage().contains("超时"), "whole request timeout reported");
            check(fails(() -> client(base, "/slow-body", 250).explain(KEY, SEGMENTS), "body timeout").getMessage().contains("超时"), "timeout also bounds body consumption");
            ExecutorService worker = Executors.newSingleThreadExecutor(); CountDownLatch cancelled = new CountDownLatch(1);
            try {
                Future<?> job = worker.submit(() -> {
                    try { client(base, "/slow", 3000).explain(KEY, SEGMENTS); }
                    catch (InterruptedException expected) { cancelled.countDown(); Thread.currentThread().interrupt(); }
                    catch (IOException unexpected) { throw new RuntimeException(unexpected); }
                });
                // 上一个慢请求可能已经释放此 latch；等待短暂调度后取消同样覆盖中断路径。
                check(slowStarted.await(2, TimeUnit.SECONDS), "slow request entered local server");
                Thread.sleep(80); job.cancel(true);
                check(cancelled.await(2, TimeUnit.SECONDS), "cancel interrupts client without replacing answer");
            } finally { worker.shutdownNow(); }

            Path projects = Files.createTempDirectory("java-trace-project-location-");
            try {
                Path first = Files.createDirectory(projects.resolve("中文项目A")); Path second = Files.createDirectory(projects.resolve("项目B"));
                Path storeA = ProjectIssueLocation.resolve(first.toString()), storeB = ProjectIssueLocation.resolve(second.toString());
                check(storeA.startsWith(first) && storeA.endsWith(Path.of(".java-trace", "issues")), "record path inside source project");
                UUID id = UUID.randomUUID(); new IssueStore(storeA).create(id, "项目A", SEGMENTS, Issue.Notes.empty(), Optional.of(ai));
                check(new IssueStore(storeA).read(id).analysis().orElseThrow().equals(ai), "AI response survives project store reopen");
                check(new IssueStore(storeB).search("").issues().isEmpty(), "other project isolated");
                check(new IssueStore(storeA).search("输入无效 Demo.java").issues().size() == 1, "AI answer searchable");
                fails(() -> ProjectIssueLocation.resolve(null), "no source project rejected");
                fails(() -> ProjectIssueLocation.resolve(projects.resolve("missing").toString()), "missing project rejected without fallback");
            } finally { try (var paths = Files.walk(projects)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
        } finally { server.stop(0); handlers.shutdownNow(); }
        System.out.println("DeepSeek local-contract regression: " + checks + " assertions passed; no external requests");
    }
    private static DeepSeekClient client(String base, String path, long millis) { return new DeepSeekClient(URI.create(base + path), Duration.ofMillis(millis)); }
    private static byte[] json(String answer, String finish) {
        JsonObject message = new JsonObject(); message.addProperty("content", answer);
        JsonObject choice = new JsonObject(); choice.addProperty("finish_reason", finish); choice.add("message", message);
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject root = new JsonObject(); root.addProperty("model", "synthetic-model"); root.add("choices", choices);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }
    private interface IO { void run() throws Exception; }
    private static IOException fails(IO action, String label) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return expected; }
        throw new AssertionError(label);
    }
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); checks++; }
}
