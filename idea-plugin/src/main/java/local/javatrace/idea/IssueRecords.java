package local.javatrace.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.WindowManager;
import local.javatrace.store.Issue;
import local.javatrace.store.IssueStore;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** 来源项目绑定到服务，网络和磁盘均在后台；不重新读取滚动控制台。 */
@Service(Service.Level.PROJECT)
public final class IssueRecords implements Disposable {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private final Project project;
    private final String projectName;
    private final String basePath;
    private final IssueStore legacyStore = new IssueStore(Path.of(System.getProperty("user.home"), ".java-trace", "issues"));
    private final Set<JDialog> dialogs = new HashSet<>();
    private final Map<String, AnalysisWindow> editors = new HashMap<>();
    private JDialog browser;

    public IssueRecords(Project project) {
        this.project = project; projectName = project.getName(); basePath = project.getBasePath();
    }
    private IssueStore projectStore() throws IOException { return new IssueStore(ProjectIssueLocation.resolve(basePath)); }
    private String projectLocation() { return basePath == null ? "项目目录未确定" : Path.of(basePath, ".java-trace", "issues").toString(); }

    void explainPreview(UUID token, List<Issue.Segment> segments) {
        openPreview(token, segments, true);
    }

    void savePreview(UUID token, List<Issue.Segment> segments) {
        openPreview(token, segments, false);
    }

    private void openPreview(UUID token, List<Issue.Segment> segments, boolean askAI) {
        if (segments.isEmpty() || segments.size() > Issue.MAX_SEGMENTS) {
            JOptionPane.showMessageDialog(WindowManager.getInstance().getFrame(project),
                    "请勾选 1 至 " + Issue.MAX_SEGMENTS + " 段异常。", "询问 AI", JOptionPane.INFORMATION_MESSAGE); return;
        }
        String key = "project:" + token;
        if (editors.containsKey(key)) { editors.get(key).dialog.toFront(); return; }
        AnalysisWindow editor = new AnalysisWindow(key, token, segments, null, false);
        editor.open(); editor.saving = true; editor.refreshButtons();
        editor.status.setText("正在检查此预览是否已有项目记录…");
        background(editor.dialog, () -> {
            IssueStore store = projectStore();
            try { return Optional.of(store.read(token)); }
            catch (NoSuchFileException absent) { return Optional.<Issue>empty(); }
        }, issue -> {
            issue.ifPresent(editor::load); editor.saving = false; editor.refreshButtons();
            if (askAI && (issue.isEmpty() || issue.orElseThrow().analysis().isEmpty())) editor.ask();
            else if (issue.isEmpty()) editor.status.setText("固定报错快照，尚未保存。点击「保存记录」写入当前项目。");
        }, error -> { editor.saving = false; editor.status.setText(error); editor.refreshButtons(); });
    }

    public void browse() {
        if (browser != null) { browser.toFront(); return; }
        JDialog dialog = window("项目问题记录"); browser = dialog;
        JComboBox<String> source = new JComboBox<>(new String[]{"当前项目", "旧版本机记录（只读）"});
        JTextField query = new JTextField(24);
        JButton search = new JButton("搜索 / 刷新"), open = new JButton("打开记录");
        JLabel status = label("关键词用空格分隔，全部匹配；空查询列出全部。"), location = label(projectLocation());
        DefaultListModel<Issue> model = new DefaultListModel<>();
        JList<Issue> list = new JList<>(model);
        boolean[] displayedLegacy = {false};
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> owner, Object value, int index, boolean selected, boolean focus) {
                Issue issue = (Issue) value;
                JLabel label = (JLabel) super.getListCellRendererComponent(owner, "", index, selected, focus);
                var summary = IssuePresentation.summarize(issue.segments().getFirst().exceptionText());
                label.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
                label.setText("<html><b>" + IssuePresentation.escape(summary.type()) + "</b>　"
                        + IssuePresentation.escape(summary.message().length() > 75 ? summary.message().substring(0, 75) + "…" : summary.message())
                        + "<br><font size='-1'>" + TIME.format(issue.updatedAt()) + "　·　" + issue.segments().size() + " 个问题　·　"
                        + (issue.analysis().isPresent() ? "已有 AI 解答" : "仅报错，待解答") + "</font></html>");
                return label;
            }
        });
        Runnable refresh = () -> {
            search.setEnabled(false); source.setEnabled(false); open.setEnabled(false);
            boolean legacy = source.getSelectedIndex() == 1;
            String requested = query.getText(); status.setText("正在本地搜索…");
            background(dialog, () -> (legacy ? legacyStore : projectStore()).search(requested), result -> {
                displayedLegacy[0] = legacy; model.clear(); result.issues().forEach(model::addElement);
                if (!model.isEmpty()) list.setSelectedIndex(0);
                location.setText(legacy ? legacyStore.directory().toString() : projectLocation());
                status.setText("找到 " + model.size() + " 条。" + (result.unreadableFiles().isEmpty() ? ""
                        : "无法读取，原文件保留：" + String.join("、", result.unreadableFiles())));
                search.setEnabled(true); source.setEnabled(true); open.setEnabled(list.getSelectedValue() != null);
            }, error -> { model.clear(); status.setText(error); search.setEnabled(true); source.setEnabled(true); });
        };
        search.addActionListener(e -> refresh.run());
        source.addActionListener(e -> { if (search.isEnabled()) refresh.run(); });
        query.addActionListener(e -> { if (search.isEnabled()) refresh.run(); });
        open.addActionListener(e -> {
            Issue selected = list.getSelectedValue(); if (selected == null) return;
            boolean legacy = displayedLegacy[0];
            background(dialog, () -> (legacy ? legacyStore : projectStore()).read(selected.id()), issue -> {
                String key = (legacy ? "legacy:" : "project:") + issue.id();
                if (editors.containsKey(key)) editors.get(key).dialog.toFront();
                else new AnalysisWindow(key, issue.id(), issue.segments(), issue, legacy).open();
            }, status::setText);
        });
        list.addListSelectionListener(e -> open.setEnabled(search.isEnabled() && list.getSelectedValue() != null)); open.setEnabled(false);
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                int index = list.locationToIndex(event.getPoint());
                if (event.getClickCount() == 2 && index >= 0 && list.getCellBounds(index, index).contains(event.getPoint()) && open.isEnabled()) open.doClick();
            }
        });
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEADING));
        top.add(source); top.add(label("关键词")); top.add(query); top.add(search); top.add(open);
        JPanel bottom = new JPanel(new GridLayout(2, 1)); bottom.add(status); bottom.add(location);
        dialog.add(top, BorderLayout.NORTH); dialog.add(new JScrollPane(list), BorderLayout.CENTER); dialog.add(bottom, BorderLayout.SOUTH);
        show(dialog, 1080, 500); refresh.run();
    }

    private final class AnalysisWindow {
        private final UUID id;
        private final List<Issue.Segment> segments;
        private final boolean legacy;
        private final JDialog dialog;
        private final IssueReadingPanel reading = new IssueReadingPanel();
        private final JLabel status = label("尚未保存。");
        private final JButton ask = new JButton("询问 AI"), cancel = new JButton("取消请求"),
                credentials = new JButton("API 设置"), save = new JButton("保存记录"), reload = new JButton("重新读取");
        private Issue saved;
        private Optional<Issue.Analysis> analysis = Optional.empty();
        private Future<?> request;
        private boolean saving, requesting, dirty;
        private long generation;

        AnalysisWindow(String editorKey, UUID id, List<Issue.Segment> segments, Issue saved, boolean legacy) {
            this.id = id; this.segments = List.copyOf(segments); this.saved = saved; this.legacy = legacy;
            dialog = window(legacy ? "旧版问题记录（只读）" : "报错与 AI 解释"); editors.put(editorKey, this);
            JPanel metadata = new JPanel(new BorderLayout(16, 8));
            metadata.setBorder(BorderFactory.createEmptyBorder(16, 22, 12, 22));
            JPanel heading = new JPanel(new GridLayout(0, 1, 0, 6));
            JLabel title = label((saved == null ? projectName : saved.projectName()) + "  ·  " + segments.size() + " 个问题");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 18f)); heading.add(title); heading.add(status);
            metadata.add(heading, BorderLayout.CENTER);
            JPanel links = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
            JButton browse = new JButton("查看 / 搜索记录"), details = new JButton("详情");
            browse.addActionListener(e -> IssueRecords.this.browse());
            details.addActionListener(e -> {
                JTextArea detail = area(true); detail.setColumns(50); detail.setRows(9);
                detail.setText("保存位置：" + (legacy ? legacyStore.directory() : projectLocation()) + "\n记录编号：" + id
                        + (this.saved == null ? "\n尚未保存" : "\n创建：" + TIME.format(this.saved.createdAt()) + "\n更新：" + TIME.format(this.saved.updatedAt()) + "\n版本：" + this.saved.revision())
                        + analysis.map(ai -> "\nAI：" + ai.provider() + " · " + ai.model() + "\n回答时间：" + TIME.format(ai.answeredAt())).orElse("")
                        + (legacy ? "\n旧版本机记录，只读。" : "\n仅点击询问时，将本次所选报错和已勾选上下文发送给 DeepSeek。"));
                detail.setCaretPosition(0); JOptionPane.showMessageDialog(dialog, new JScrollPane(detail), "记录详情", JOptionPane.PLAIN_MESSAGE);
            });
            links.add(browse); links.add(details); metadata.add(links, BorderLayout.EAST);
            ask.setToolTipText("将这些报错及主动附带的上下文发送给 DeepSeek；不会发送源码或其他记录。");
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 10, 10));
            for (JButton button : List.of(ask, cancel, credentials, save, reload)) buttons.add(button);
            ask.addActionListener(e -> ask()); cancel.addActionListener(e -> cancelRequest()); credentials.addActionListener(e -> configureKey(null));
            save.addActionListener(e -> persist());
            reload.addActionListener(e -> {
                if (dirty && JOptionPane.showConfirmDialog(dialog, "重新读取会替换当前未保存的 AI 回答，继续？", "重新读取", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
                saving = true; refreshButtons();
                background(dialog, () -> (legacy ? legacyStore : projectStore()).read(id), issue -> {
                    load(issue); saving = false; refreshButtons();
                }, error -> { status.setText(error); saving = false; refreshButtons(); });
            });
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            dialog.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) {
                    if (saving) return;
                    if (dirty && JOptionPane.showConfirmDialog(dialog, "AI 回答尚未保存，关闭此窗口？", "关闭记录", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
                    cancelRequest(); dialog.dispose();
                }
                @Override public void windowClosed(WindowEvent event) { cancelRequest(); }
            });
            dialog.add(metadata, BorderLayout.NORTH); dialog.add(reading, BorderLayout.CENTER); dialog.add(buttons, BorderLayout.SOUTH);
            if (saved != null) load(saved); else displayAnalysis(); refreshButtons();
        }
        void open() { show(dialog, 1060, 820); }
        private void load(Issue issue) {
            saved = issue; analysis = issue.analysis(); dirty = false; displayAnalysis();
            status.setText("已保存  ·  " + TIME.format(issue.updatedAt()) + "  ·  版本 " + issue.revision());
        }
        private void displayAnalysis() {
            reading.display(segments, analysis, saved == null ? Issue.Notes.empty() : saved.notes());
        }
        private void refreshButtons() {
            ask.setEnabled(!legacy && !saving && !requesting); ask.setText(analysis.isPresent() ? "重新询问 AI" : "询问 AI");
            cancel.setEnabled(requesting); cancel.setVisible(requesting); credentials.setEnabled(!legacy && !saving && !requesting);
            save.setEnabled(!legacy && !saving && !requesting && (saved == null || dirty));
            reload.setEnabled(saved != null && !saving && !requesting);
        }
        private void ask() {
            if (legacy || saving || requesting) return;
            try { DeepSeekClient.question(segments); } catch (IOException error) { status.setText(error.getMessage()); return; }
            requesting = true; long attempt = ++generation; refreshButtons(); status.setText("正在读取已配置的 API Key…");
            request = background(dialog, () -> {
                try { return Optional.ofNullable(DeepSeekCredentials.load()).filter(key -> !key.isBlank()); }
                catch (RuntimeException error) { throw new IOException("无法读取 IDEA 凭据库，请检查 IDEA 的 Passwords 设置。"); }
            }, key -> {
                if (attempt != generation) return;
                if (key.isEmpty()) { requesting = false; refreshButtons(); configureKey(this::ask); }
                else send(key.orElseThrow(), attempt);
            }, error -> { if (attempt == generation) { requesting = false; status.setText(error); refreshButtons(); } });
        }
        private void send(String key, long attempt) {
            status.setText("正在向 DeepSeek 询问 " + segments.size() + " 段报错，可取消；不会自动重试…");
            request = background(dialog, () -> new DeepSeekClient().explain(key, segments), result -> {
                if (attempt != generation) return;
                analysis = Optional.of(result); dirty = true; requesting = false; displayAnalysis();
                status.setText("AI 解释已返回，尚未保存。请核对内容后点击保存记录。"); refreshButtons();
            }, error -> { if (attempt == generation) { requesting = false; status.setText(error); refreshButtons(); } });
        }
        private void cancelRequest() {
            generation++; if (request != null) request.cancel(true);
            if (requesting) status.setText("已取消等待，保留现有报错和回答。服务端可能已处理本次请求。");
            requesting = false; refreshButtons();
        }
        private void configureKey(Runnable after) {
            if (saving || requesting) return;
            saving = true; refreshButtons(); status.setText("正在检查 IDEA 凭据库是否可持久保存…");
            background(dialog, () -> {
                try { return Optional.ofNullable(DeepSeekCredentials.load()).filter(key -> !key.isBlank()).isPresent(); }
                catch (RuntimeException error) { throw new IOException("无法打开 IDEA 凭据库，请检查 Passwords 中的 KeePass 配置；未发送请求。"); }
            }, configured -> {
                saving = false; refreshButtons();
                if (!configured) { showKeyDialog(after); return; }
                status.setText("API Key 已配置，凭据库处于持久保存模式，无需重新输入。");
                if (after != null) { after.run(); return; }
                int option = JOptionPane.showOptionDialog(dialog,
                        "已从 IDEA 凭据库读到 DeepSeek Key，当前为持久保存模式。\n可以直接询问 AI，无需重新输入。",
                        "API 设置", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                        new Object[]{"完成", "更换 API Key"}, "完成");
                if (option == 1) showKeyDialog(null);
            }, error -> {
                saving = false; status.setText(error); refreshButtons();
                JOptionPane.showMessageDialog(dialog, error, "凭据库尚未就绪", JOptionPane.INFORMATION_MESSAGE);
            });
        }
        private void showKeyDialog(Runnable after) {
            JPasswordField field = new JPasswordField(36); JPanel panel = new JPanel(new GridLayout(0, 1));
            panel.add(label("输入 DeepSeek API Key，仅由 IDEA 凭据库保存，其他项目可复用。"));
            panel.add(field); panel.add(label("只用于 api.deepseek.com；不写入项目、问题记录或日志。"));
            if (JOptionPane.showConfirmDialog(dialog, panel, "配置 DeepSeek API Key", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
                field.setText(""); status.setText("已取消配置，未发送请求。"); return;
            }
            char[] password = field.getPassword(); String key = new String(password).strip(); Arrays.fill(password, '\0'); field.setText("");
            try { DeepSeekClient.validateKey(key); } catch (IOException error) { status.setText(error.getMessage()); return; }
            saving = true; refreshButtons(); status.setText("正在保存 API Key 到 IDEA 凭据库…");
            background(dialog, () -> {
                try { DeepSeekCredentials.save(key); return true; }
                catch (RuntimeException error) { throw new IOException("无法保存到 IDEA 凭据库；请检查 Passwords 设置，未发送请求。"); }
            }, ignored -> { saving = false; status.setText("API Key 已写入凭据库并通过回读检查。"); refreshButtons(); if (after != null) after.run(); },
                    error -> { saving = false; status.setText(error); refreshButtons(); });
        }
        private void persist() {
            if (legacy || saving || requesting) return;
            Issue previous = saved; Optional<Issue.Analysis> captured = analysis;
            saving = true; refreshButtons(); status.setText("正在保存到报错项目…");
            background(dialog, () -> {
                IssueStore store = projectStore();
                if (previous == null) return store.create(id, projectName, segments, Issue.Notes.empty(), captured);
                if (captured.isEmpty()) return store.read(id);
                return store.updateAnalysis(id, previous.revision(), captured.orElseThrow());
            }, issue -> {
                if (!issue.analysis().equals(captured)) {
                    saved = issue; dirty = true; status.setText("已有记录包含不同回答；当前回答保留，可再次保存为新版本。");
                } else load(issue);
                saving = false; refreshButtons();
            }, error -> { saving = false; status.setText("保存失败，当前回答与已有文件保留：" + error); refreshButtons(); });
        }
    }

    private JDialog window(String title) {
        JDialog dialog = new JDialog(WindowManager.getInstance().getFrame(project), title, false); dialogs.add(dialog);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                dialogs.remove(dialog); editors.values().removeIf(editor -> editor.dialog == dialog);
                if (browser == dialog) browser = null;
            }
        }); return dialog;
    }
    private void show(JDialog dialog, int width, int height) {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        dialog.setSize(Math.min(width, screen.width - 80), Math.min(height, screen.height - 100));
        dialog.setLocationRelativeTo(WindowManager.getInstance().getFrame(project)); dialog.setVisible(true);
    }
    private static JLabel label(String text) { JLabel label = new JLabel(); label.putClientProperty("html.disable", true); label.setText(text); return label; }
    private static JTextArea area(boolean wrap) {
        JTextArea text = new JTextArea(); text.setEditable(false); text.setFont(UIManager.getFont("Label.font"));
        text.setLineWrap(wrap); text.setWrapStyleWord(wrap); return text;
    }
    private interface Work<T> { T run() throws Exception; }
    private <T> Future<?> background(JDialog dialog, Work<T> work, Consumer<T> success, Consumer<String> failure) {
        var app = ApplicationManager.getApplication();
        return app.executeOnPooledThread(() -> {
            try {
                T result = work.run();
                app.invokeLater(() -> { if (dialog.isDisplayable()) success.accept(result); }, ModalityState.any(), project.getDisposed());
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            catch (Exception error) {
                String message = error instanceof IOException ? error.getMessage() : "操作未完成，未改写已有记录。";
                app.invokeLater(() -> { if (dialog.isDisplayable()) failure.accept(message); }, ModalityState.any(), project.getDisposed());
            }
        });
    }
    @Override public void dispose() {
        List.copyOf(editors.values()).forEach(AnalysisWindow::cancelRequest);
        List.copyOf(dialogs).forEach(JDialog::dispose); dialogs.clear(); editors.clear();
    }
}
