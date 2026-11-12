package local.javatrace.idea;

/** 固定快照与源位置是两个生命周期。仅明确关闭/替换请求才禁止异步结果展示。界面线程使用。 */
final class PreviewLifetime {
    private boolean closed;
    private String sourceStatus = "";

    boolean canUseSnapshot() { return !closed; }
    boolean canHighlight() { return !closed && sourceStatus.isEmpty(); }
    String sourceStatus() { return sourceStatus; }

    boolean detach(String reason) {
        if (closed || sourceStatus.equals(reason)) return false;
        sourceStatus = reason;
        return true;
    }

    void close() { closed = true; }
}
