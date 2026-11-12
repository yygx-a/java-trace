package local.javatrace.idea;

import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.DataSnapshot;
import com.intellij.openapi.actionSystem.UiDataRule;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.EditorMouseEventArea;
import org.jetbrains.annotations.NotNull;
import local.javatrace.core.TextRange;

import javax.swing.SwingUtilities;
import java.awt.EventQueue;
import java.awt.event.MouseEvent;

/**
 * 在右键菜单创建时保存鼠标位置。
 * 菜单项被点击时鼠标已经移动到菜单上，所以需要提前捕获原始右键位置。
 */
public final class ConsolePopupData implements UiDataRule {
    // IDEA 的 DataKey 用于把捕获的数据放进当前菜单的数据上下文。
    static final DataKey<Anchor> ANCHOR = DataKey.create("local.javatrace.consolePopupAnchor");

    // stamp 是点击时的文档修改戳，后续用于判断该偏移是否已经过期。
    record Anchor(Editor editor, int offset, long stamp, TextRange selection) {}

    @Override
    public void uiDataSnapshot(@NotNull DataSink sink, @NotNull DataSnapshot snapshot) {
        // 目标版本的经典编辑器在原始右键弹出事件期间创建菜单数据上下文。
        if (!(EventQueue.getCurrentEvent() instanceof MouseEvent mouse)
                || !mouse.isPopupTrigger() || !SwingUtilities.isRightMouseButton(mouse)) {
            return;
        }
        Editor editor = snapshot.get(CommonDataKeys.EDITOR);
        if (editor == null || editor.isDisposed() || mouse.getComponent() != editor.getContentComponent()) {
            return;
        }
        // 限制到经典控制台的文本区域，避免把普通源码编辑器当作运行控制台。
        ConsoleViewImpl console = editor.getUserData(ConsoleViewImpl.CONSOLE_VIEW_IN_EDITOR_VIEW);
        if (console == null || console.getEditor() != editor
                || editor.getMouseEventArea(mouse) != EditorMouseEventArea.EDITING_AREA) {
            return;
        }
        Document document = editor.getDocument();
        // 一行中的任意位置都可作为锚点，因此转换为该逻辑行的行首偏移。
        int line = editor.xyToLogicalPosition(mouse.getPoint()).line;
        if (line >= 0 && line < document.getLineCount()) {
            int offset = document.getLineStartOffset(line);
            if (offset < document.getTextLength()) {
                var selection = editor.getSelectionModel();
                sink.set(ANCHOR, new Anchor(editor, offset, document.getModificationStamp(), selection.hasSelection()
                        ? new TextRange(selection.getSelectionStart(), selection.getSelectionEnd()) : null));
            }
        }
    }
}
