package local.javatrace.idea;

/** 控制台请求统一管理单段和批量预览的生命周期。 */
interface ConsolePreview {
    void open();
    void close();
    void sourceDetached(String message);
}
