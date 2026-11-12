package local.javatrace.idea;

import com.intellij.openapi.actionSystem.*;
import org.jetbrains.annotations.NotNull;

public final class OpenIssuesAction extends AnAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null);
    }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        if (event.getProject() != null) event.getProject().getService(IssueRecords.class).browse();
    }
}
