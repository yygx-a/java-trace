package local.javatrace.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import local.javatrace.store.IssueStore;
import javax.swing.*;
import java.awt.*;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.Future;

public final class IssueFileEditorProvider implements FileEditorProvider, DumbAware {
    @Override public boolean accept(Project project, VirtualFile file) {
        return !file.isDirectory() && file.isInLocalFileSystem() && "jti".equalsIgnoreCase(file.getExtension());
    }
    @Override public FileEditor createEditor(Project project, VirtualFile file) { return new Reader(project, file); }
    @Override public String getEditorTypeId() { return "java-trace-issue-reader"; }
    @Override public FileEditorPolicy getPolicy() { return FileEditorPolicy.HIDE_DEFAULT_EDITOR; }

    private static final class Reader extends UserDataHolderBase implements FileEditor {
        private final Project project;
        private final VirtualFile file;
        private final JPanel root = new JPanel(new BorderLayout(0, 12));
        private final JLabel info = new JLabel("正在读取记录…");
        private final IssueReadingPanel reading = new IssueReadingPanel();
        private final PropertyChangeSupport properties = new PropertyChangeSupport(this);
        private Future<?> work;
        private volatile boolean disposed;
        private long generation;
        Reader(Project project, VirtualFile file) {
            this.project = project; this.file = file;
            root.setBorder(BorderFactory.createEmptyBorder(16, 18, 8, 18));
            JPanel top = new JPanel(new BorderLayout(16, 0)); top.add(info, BorderLayout.CENTER);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
            JButton refresh = new JButton("刷新"), browse = new JButton("查看 / 搜索项目记录");
            refresh.addActionListener(e -> load()); browse.addActionListener(e -> project.getService(IssueRecords.class).browse());
            actions.add(refresh); actions.add(browse); top.add(actions, BorderLayout.EAST);
            root.add(top, BorderLayout.NORTH); root.add(reading, BorderLayout.CENTER); load();
        }
        private void load() {
            if (disposed) return;
            long attempt = ++generation;
            if (work != null) work.cancel(true);
            info.setText("正在读取记录…");
            var app = ApplicationManager.getApplication();
            work = app.executeOnPooledThread(() -> {
                try {
                    Path path = file.toNioPath();
                    var issue = new IssueStore(path.getParent()).read(UUID.fromString(file.getNameWithoutExtension()));
                    app.invokeLater(() -> {
                        if (disposed || attempt != generation) return;
                        String updated = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(issue.updatedAt());
                        info.setText(issue.projectName() + "  ·  " + issue.segments().size() + " 个问题  ·  " + updated + "  ·  只读");
                        root.add(reading, BorderLayout.CENTER); root.revalidate();
                        reading.display(issue.segments(), issue.analysis(), issue.notes());
                    }, ModalityState.any(), project.getDisposed());
                } catch (Exception error) {
                    app.invokeLater(() -> {
                        if (disposed || attempt != generation) return;
                        info.setText("无法读取这份问题记录。文件可能损坏、格式不受支持或名称已更改；原文件未改动。");
                        root.remove(reading); root.revalidate(); root.repaint();
                    }, ModalityState.any(), project.getDisposed());
                }
            });
        }
        @Override public JComponent getComponent() { return root; }
        @Override public JComponent getPreferredFocusedComponent() { return reading; }
        @Override public String getName() { return "问题阅读"; }
        @Override public void setState(FileEditorState state) {}
        @Override public boolean isModified() { return false; }
        @Override public boolean isValid() { return !disposed && file.isValid(); }
        @Override public VirtualFile getFile() { return file; }
        @Override public void addPropertyChangeListener(PropertyChangeListener listener) { properties.addPropertyChangeListener(listener); }
        @Override public void removePropertyChangeListener(PropertyChangeListener listener) { properties.removePropertyChangeListener(listener); }
        @Override public void dispose() { disposed = true; generation++; if (work != null) work.cancel(true); }
    }
}
