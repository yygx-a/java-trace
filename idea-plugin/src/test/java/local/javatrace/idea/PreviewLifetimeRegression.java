package local.javatrace.idea;

import java.util.concurrent.CompletableFuture;

/** 复现解析中追加、旧任务晚到和源释放：快照可用性不能依赖源位置。 */
public final class PreviewLifetimeRegression {
    private static int checks;
    public static void main(String[] args) {
        for (String event : new String[]{"追加", "清空", "裁剪", "运行结束", "Editor释放"}) {
            PreviewLifetime request = new PreviewLifetime();
            CompletableFuture<String> parser = new CompletableFuture<>();
            var displayed = parser.thenApply(text -> request.canUseSnapshot() ? text : "discarded");
            check(request.canHighlight(), "initial highlight");
            check(request.detach(event), "first source transition");
            check(!request.detach(event), "same source event does not repaint");
            check(!request.canHighlight(), "no stale source highlight");
            parser.complete("固定快照😀");
            check(displayed.join().equals("固定快照😀"), "captured parser finishes despite " + event);
            check(request.canUseSnapshot(), "copy/adjust/save independent of " + event);
            request.close();
            check(!request.canUseSnapshot(), "close disables pending callback");
            check(!request.detach("later"), "no callback after disposal");
        }
        PreviewLifetime old = new PreviewLifetime(), newer = new PreviewLifetime();
        var parser = new CompletableFuture<String>();
        var delivered = parser.thenApply(text -> old.canUseSnapshot() ? text : "discarded");
        old.close(); parser.complete("old");
        check(delivered.join().equals("discarded"), "superseded task discarded");
        check(newer.canUseSnapshot() && newer.canHighlight(), "new request remains usable");
        newer.close(); newer.close();
        check(!newer.canHighlight(), "project/preview close idempotent");
        System.out.println("Preview lifetime regression passed: " + checks + " checks.");
    }
    private static void check(boolean okay, String label) { checks++; if (!okay) throw new AssertionError(label); }
}
