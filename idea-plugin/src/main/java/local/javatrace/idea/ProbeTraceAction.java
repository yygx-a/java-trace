package local.javatrace.idea;

import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import org.jetbrains.annotations.NotNull;

/** 单段和批量入口共用 Run 控制台的右键位置及快照检查。 */
public class ProbeTraceAction extends AnAction {
    private final boolean all;
    private final boolean manual;

    public ProbeTraceAction() {
        this(false);
    }

    ProbeTraceAction(boolean all) {
        this(all, false);
    }

    ProbeTraceAction(boolean all, boolean manual) {
        this.all = all;
        this.manual = manual;
    }
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        // 菜单状态依赖编辑器及鼠标上下文，在 IDEA 的界面线程上读取。
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        var anchor = popupAnchor(event);
        event.getPresentation().setVisible(event.getProject() != null && anchor != null);
        event.getPresentation().setEnabled(event.getProject() != null && anchor != null && (!manual || anchor.selection() != null));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        ConsolePopupData.Anchor anchor = popupAnchor(event);
        if (project == null || anchor == null || anchor.editor().isDisposed()) {
            return;
        }
        // 菜单打开后控制台可能继续输出或被清空。此时旧偏移可能指向另一段文本。
        if (anchor.editor().getDocument().getModificationStamp() != anchor.stamp()) {
            StatusBar.Info.set("控制台输出已变化，请重新右键选择。", project);
            return;
        }
        if (manual && anchor.selection() == null) return;
        project.getService(ConsoleTraceProbe.class).probe(anchor.editor(), anchor.offset(), all, manual ? anchor.selection() : null);
    }

    /** 只接受编辑器右键菜单中捕获的锚点。 */
    private static ConsolePopupData.Anchor popupAnchor(AnActionEvent event) {
        return ActionPlaces.EDITOR_POPUP.equals(event.getPlace()) ? event.getData(ConsolePopupData.ANCHOR) : null;
    }
}
