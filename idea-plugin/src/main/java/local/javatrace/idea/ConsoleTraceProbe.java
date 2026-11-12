package local.javatrace.idea;

import com.intellij.execution.impl.ConsoleBuffer;
import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.ExecutionListener;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseEventArea;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.ui.JBColor;
import local.javatrace.core.BatchRecognitionResult;
import local.javatrace.core.JavaExceptionRecognizer;
import local.javatrace.core.RecognitionResult;
import local.javatrace.core.TextRange;
import org.jetbrains.annotations.NotNull;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.concurrent.Future;

/**
 * IDEA 项目级服务，负责获取控制台快照、调度识别、展示和清理高亮。
 * 一个项目可有多个控制台，每个编辑器各自维护当前请求。
 */
@Service(Service.Level.PROJECT)
public final class ConsoleTraceProbe implements Disposable {
    private static final Logger LOG = Logger.getInstance(ConsoleTraceProbe.class);
    private static final JavaExceptionRecognizer RECOGNIZER = new JavaExceptionRecognizer();
    private static final JBColor HIGHLIGHT = new JBColor(new Color(0xFFF0A6), new Color(0x514322));

    private final Project project;
    // 仅在界面线程访问。同一编辑器发起新请求时，先清除旧请求和高亮。
    private final Map<Editor, Probe> active = new HashMap<>();
    // Editor 释放后从 active 解绑，窗口仍由项目服务持有，直到窗口/项目关闭。
    private final Set<Probe> owned = new HashSet<>();

    public ConsoleTraceProbe(Project project) {
        this.project = project;
        // 编辑器关闭时释放对应请求。监听器的生命周期绑定到此项目服务。
        EditorFactory.getInstance().addEditorFactoryListener(new EditorFactoryListener() {
            @Override
            public void editorReleased(@NotNull EditorFactoryEvent event) {
                Probe probe = active.remove(event.getEditor());
                if (probe != null) {
                    probe.detachSource("源控制台已关闭");
                    probe.editor = null;
                    probe.process = null;
                }
            }
        }, this);
        project.getMessageBus().connect(this).subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
            @Override public void processTerminated(@NotNull String executorId, @NotNull ExecutionEnvironment environment,
                                                    @NotNull ProcessHandler handler, int exitCode) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    for (Probe probe : new ArrayList<>(owned)) if (probe.process == handler) {
                        probe.detachSource("源运行已结束");
                        probe.process = null;
                    }
                }, ModalityState.any(), project.getDisposed());
            }
        });
    }

    /** 界面线程上获取快照，再把可能耗时的识别交给后台线程。 */
    void probe(Editor editor, int anchor, boolean all) {
        probe(editor, anchor, all, null);
    }

    void probe(Editor editor, int anchor, boolean all, TextRange selection) {
        remove(editor);
        Document document = editor.getDocument();
        int length = document.getTextLength();
        if (length > JavaExceptionRecognizer.MAX_CHARACTERS) {
            StatusBar.Info.set("Java 异常：超过字符扫描上限，未扫描控制台。", project);
            return;
        }
        // 后台只读取不可变快照。达到循环缓冲区容量时，保守提示前端可能已裁剪。
        CharSequence snapshot = document.getImmutableCharSequence();
        boolean mayHaveCroppedPrefix = ConsoleBuffer.useCycleBuffer() && length >= ConsoleBuffer.getCycleBufferSize();
        Probe probe = new Probe(editor, document.getModificationStamp());
        active.put(editor, probe);
        owned.add(probe);
        // 文档变化只解绑源位置，后台解析仍可完成已捕获的快照。
        if (probe.lifetime.canHighlight()) document.addDocumentListener(probe, probe.listenerLifetime);

        Application app = ApplicationManager.getApplication();
        probe.task = app.executeOnPooledThread(() -> {
            String original = snapshot.toString();
            // 高亮属于界面操作，需要回到界面线程。项目已关闭时不再执行回调。
            if (all) {
                BatchRecognitionResult result = RECOGNIZER.recognizeAll(original, mayHaveCroppedPrefix, false);
                app.invokeLater(() -> showBatch(probe, original, result), ModalityState.nonModal(), project.getDisposed());
            } else {
                RecognitionResult result = selection == null
                        ? RECOGNIZER.recognize(original, anchor, mayHaveCroppedPrefix, false)
                        : ManualSelection.result(original, selection);
                app.invokeLater(() -> show(probe, anchor, length, original, result), ModalityState.nonModal(), project.getDisposed());
            }
        });
    }

    private void show(Probe probe, int anchor, int length, String snapshot, RecognitionResult result) {
        Editor editor = probe.editor;
        // 用户可能在后台解析期间再次触发，也可能关闭编辑器或改变输出。
        if (!probe.canShow()) {
            return;
        }
        LOG.info("Console probe: length=" + length + ", anchor=" + anchor + ", status=" + result.status()
                + ", range=" + result.exceptionRange() + ", warnings=" + result.warnings());
        StatusBar.Info.set("Java Trace: " + RecognitionPresentation.summary(result), project);
        result.exceptionRange().ifPresentOrElse(range -> {
            probe.setHighlighted(range, true);
            if (probe.canHighlight()) editor.addEditorMouseListener(probe, probe.listenerLifetime);
            probe.preview = new TracePreview(project, snapshot, range.startOffset(), range.endOffset(),
                    result, describeWarnings(result.warnings()),
                    (start, end) -> {
                        // 预览始终可用；只有仍绑定原位置时才更新源高亮。
                        if (probe.canHighlight()) {
                            probe.removeHighlights();
                            probe.setHighlighted(new TextRange(start, end), true);
                        }
                    }, probe::closeIfActive, probe::isCurrent);
            probe.notifySource();
            probe.preview.open();
        }, () -> {
            StatusBar.Info.set("未定位到异常结构；可先选中文本，再右键「手动预览所选文本」。", project);
            probe.closeIfActive();
        });
    }

    private void showBatch(Probe probe, String snapshot, BatchRecognitionResult result) {
        if (!probe.canShow()) return;

        String warnings = describeWarnings(result.warnings());
        LOG.info("Console batch preview: length=" + snapshot.length() + ", count=" + result.exceptions().size()
                + ", warnings=" + result.warnings());
        if (result.exceptions().isEmpty()) {
            StatusBar.Info.set(warnings.isEmpty() ? "当前控制台未识别到 Java 异常。"
                    : "没有可预览的异常。提示：" + warnings, project);
            probe.closeIfActive();
            return;
        }

        for (RecognitionResult item : result.exceptions()) {
            probe.setHighlighted(item.exceptionRange().orElseThrow(), true);
        }
        if (probe.canHighlight()) probe.editor.addEditorMouseListener(probe, probe.listenerLifetime);
        probe.preview = new BatchTracePreview(project, snapshot, result.exceptions(), result.warnings(),
                probe::setHighlighted, probe::closeIfActive, probe::isCurrent);
        probe.notifySource();
        probe.preview.open();
    }

    static String describeWarnings(List<RecognitionResult.Warning> warnings) {
        return RecognitionPresentation.warnings(warnings);
    }

    private void remove(Editor editor) {
        Probe probe = active.remove(editor);
        if (probe != null) {
            probe.close();
        }
    }

    @Override
    public void dispose() {
        for (Probe probe : new ArrayList<>(owned)) probe.close();
        active.clear();
    }

    /** 保存一次请求和它创建的资源，完成、替换或失效时统一释放。 */
    private final class Probe implements DocumentListener, EditorMouseListener {
        private Editor editor;
        private ProcessHandler process;
        private final long stamp;
        private final Disposable listenerLifetime = Disposer.newDisposable();
        private Future<?> task;
        private final Map<TextRange, RangeHighlighter> highlights = new HashMap<>();
        private ConsolePreview preview;
        private final PreviewLifetime lifetime = new PreviewLifetime();

        private Probe(Editor editor, long stamp) {
            this.editor = editor;
            this.stamp = stamp;
            ConsoleViewImpl console = editor.getUserData(ConsoleViewImpl.CONSOLE_VIEW_IN_EDITOR_VIEW);
            for (var descriptor : ExecutionManager.getInstance(project).getContentManager().getAllDescriptors()) {
                if (descriptor.getExecutionConsole() == console) {
                    process = descriptor.getProcessHandler();
                    if (process != null && process.isProcessTerminated()) {
                        lifetime.detach("源运行已结束");
                        process = null;
                    }
                    break;
                }
            }
        }

        private boolean isCurrent() {
            return lifetime.canUseSnapshot() && owned.contains(this) && !project.isDisposed();
        }

        private boolean canHighlight() {
            return isCurrent() && lifetime.canHighlight() && editor != null && !editor.isDisposed()
                    && active.get(editor) == this && editor.getDocument().getModificationStamp() == stamp;
        }

        private boolean canShow() { return isCurrent(); }

        private void closeIfActive() {
            // 比较所有权后才移除，旧窗口的延迟 windowClosed 不能关闭新请求。
            if (editor != null) active.remove(editor, this);
            close();
        }

        private void notifySource() {
            if (preview != null && !lifetime.sourceStatus().isEmpty()) preview.sourceDetached(lifetime.sourceStatus());
        }

        private void detachSource(String message) {
            if (!lifetime.detach(message)) return;
            removeHighlights();
            if (!Disposer.isDisposed(listenerLifetime)) Disposer.dispose(listenerLifetime);
            notifySource();
        }

        @Override
        public void mouseClicked(@NotNull EditorMouseEvent event) {
            MouseEvent mouse = event.getMouseEvent();
            if (event.getArea() != EditorMouseEventArea.EDITING_AREA || event.isOverText()
                    || event.getInlay() != null || event.getCollapsedFoldRegion() != null
                    || !SwingUtilities.isLeftMouseButton(mouse) || mouse.getClickCount() != 1
                    || mouse.getModifiersEx() != 0 || highlights.isEmpty()) {
                return;
            }
            // 仅处理高亮区域内的普通左键单击。文字、链接、内嵌元素和其他鼠标操作继续交给 IDEA。
            // 点击视觉行末尾的空白时，IDEA 返回该行末尾对应的偏移。
            int offset = event.getOffset();
            for (RangeHighlighter highlight : highlights.values()) {
                if (highlight.isValid() && offset >= highlight.getStartOffset() && offset < highlight.getEndOffset()) {
                    remove(editor);
                    return;
                }
            }
        }

        @Override
        public void beforeDocumentChange(@NotNull DocumentEvent event) {
            // 第一次变化后解除文档/鼠标监听。后续每条日志不再刷新窗口或重绘。
            detachSource(event.getOffset() == event.getDocument().getTextLength() && event.getOldLength() == 0
                    ? "控制台已有新输出，已取消原位置高亮" : "控制台已清空、裁剪或修改，已取消原位置高亮");
        }

        private void close() {
            if (!lifetime.canUseSnapshot()) return;
            lifetime.close();
            owned.remove(this);
            if (editor != null) active.remove(editor, this);
            if (preview != null) {
                ConsolePreview old = preview;
                preview = null;
                old.close();
            }
            if (!Disposer.isDisposed(listenerLifetime)) Disposer.dispose(listenerLifetime);
            if (task != null) task.cancel(false);
            removeHighlights();
            editor = null;
            process = null;
        }

        private void setHighlighted(TextRange range, boolean selected) {
            if (!canHighlight()) return;
            if (selected) {
                // 每段只添加自己的背景层，源码链接仍交给 IDEA。
                highlights.computeIfAbsent(range, key -> {
                    TextAttributes attributes = new TextAttributes();
                    attributes.setBackgroundColor(HIGHLIGHT);
                    return editor.getMarkupModel().addRangeHighlighter(key.startOffset(), key.endOffset(),
                            HighlighterLayer.CONSOLE_FILTER - 1, attributes, HighlighterTargetArea.EXACT_RANGE);
                });
            } else {
                removeHighlight(highlights.remove(range));
            }
        }

        private void removeHighlights() {
            highlights.values().forEach(this::removeHighlight);
            highlights.clear();
        }

        private void removeHighlight(RangeHighlighter highlight) {
            if (highlight != null && highlight.isValid() && editor != null && !editor.isDisposed()) {
                editor.getMarkupModel().removeHighlighter(highlight);
            }
        }
    }
}
