package local.javatrace.idea;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.WindowManager;
import local.javatrace.core.RecognitionResult;
import local.javatrace.core.TextRange;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import local.javatrace.store.Issue;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** 勾选同时决定下方原文、控制台高亮和复制范围。 */
final class BatchTracePreview implements ConsolePreview {
    private final JFrame frame;
    private final String snapshot;
    private final List<RecognitionResult> exceptions;
    private final List<RecognitionResult.Warning> snapshotWarnings;
    private final List<String> summaries;
    private final boolean[] checked;
    private final JLabel status = new JLabel();
    private final JTextArea text = new JTextArea();
    private final JTextArea gutter = new JTextArea();
    private final JButton copy = new JButton();
    private final JButton save = new JButton("询问 AI…");
    private final JButton localSave = new JButton("保存报错…");
    private final JCheckBox context = new JCheckBox("附带前置 ERROR 日志");
    private final ContextPreview contextPreview = new ContextPreview();
    private final Map<List<Issue.Segment>, UUID> saveTokens = new HashMap<>();
    private final BatchTableModel model;
    private final JTable table;
    private final BiConsumer<TextRange, Boolean> onCheck;
    private final BooleanSupplier current;
    private int checkedCount;
    private String previewText = "";
    private final JLabel source = new JLabel("固定快照 · 复制与调整不受新输出影响");

    BatchTracePreview(Project project, String snapshot, List<RecognitionResult> exceptions, List<RecognitionResult.Warning> snapshotWarnings,
                      BiConsumer<TextRange, Boolean> onCheck, Runnable onClose, BooleanSupplier current) {
        this.snapshot = snapshot;
        this.exceptions = exceptions;
        this.snapshotWarnings = List.copyOf(snapshotWarnings);
        String warnings = RecognitionPresentation.warnings(snapshotWarnings);
        this.onCheck = onCheck;
        this.current = current;
        checked = new boolean[exceptions.size()];
        Arrays.fill(checked, true);
        checkedCount = checked.length;
        summaries = exceptions.stream().map(this::summary).toList();
        model = new BatchTableModel();
        table = new JTable(model);

        Frame owner = WindowManager.getInstance().getFrame(project);
        frame = new JFrame("全部 Java 异常预览 · " + project.getName());
        if (owner != null) frame.setIconImages(owner.getIconImages());
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                onClose.run();
            }
        });

        status.setText(warnings.isEmpty() ? "当前控制台快照 · 按输出顺序，跳过段间日志"
                : "仅包含当前控制台快照。提示：" + warnings);
        // 表格只用于勾选，点击摘要不产生额外的行选中状态。
        table.setCellSelectionEnabled(false);
        table.getColumnModel().getColumn(0).setMaxWidth(65);
        table.getColumnModel().getColumn(1).setMaxWidth(55);
        text.setEditable(false);
        text.setFont(UIManager.getFont("Label.font"));
        gutter.setFont(text.getFont());
        gutter.setEditable(false);
        gutter.setFocusable(false);
        gutter.setColumns(Integer.toString(exceptions.size()).length() + 2);
        gutter.setBackground(UIManager.getColor("Panel.background"));
        JScrollPane preview = new JScrollPane(text);
        // 序号与正文同步滚动，并且不进入正文选区或剪贴板。
        preview.setRowHeaderView(gutter);
        JPanel evidence = new JPanel(new BorderLayout());
        evidence.add(contextPreview, BorderLayout.NORTH);
        evidence.add(preview, BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), evidence);
        split.setResizeWeight(0.4);

        copy.addActionListener(event -> copySelection());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEADING));
        buttons.add(copy);
        context.setEnabled(exceptions.stream().anyMatch(item -> item.contextRange().isPresent()));
        if (!context.isEnabled()) context.setText("无前置 ERROR 日志");
        context.setToolTipText("勾选后显示已选异常的前置日志，随询问 AI 发送并保存；复制所选仅复制报错。");
        context.addActionListener(event -> {
            if (!current.getAsBoolean()) return;
            refreshPreview();
        });
        save.setToolTipText("仅将已勾选的稳定报错发送给 DeepSeek，保留各段顺序及重复段。");
        buttons.add(context);
        save.addActionListener(event -> {
            if (!current.getAsBoolean()) return;
            List<Issue.Segment> segments = selectedSegments();
            if (!segments.isEmpty()) {
                project.getService(IssueRecords.class)
                        .explainPreview(saveTokens.computeIfAbsent(segments, key -> UUID.randomUUID()), segments, frame);
            }
        });
        buttons.add(save);
        localSave.setToolTipText("打开本地记录草稿，保留勾选的报错及上下文。");
        localSave.addActionListener(event -> {
            if (!current.getAsBoolean()) return;
            List<Issue.Segment> stable = selectedSegments();
            if (!stable.isEmpty()) project.getService(IssueRecords.class)
                    .savePreview(saveTokens.computeIfAbsent(stable, key -> UUID.randomUUID()), stable, frame);
        });
        buttons.add(localSave);
        JPanel notices = new JPanel(new java.awt.GridLayout(0, 1));
        notices.add(status); notices.add(source);
        frame.add(notices, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);
        frame.add(buttons, BorderLayout.SOUTH);
        frame.setSize(850, 540);
        frame.setLocationRelativeTo(owner);
        refreshPreview();
    }

    @Override
    public void open() { frame.setVisible(true); }

    @Override
    public void close() { frame.dispose(); }

    @Override
    public void sourceDetached(String message) {
        source.setText(message + "；固定快照仍可调整、复制、保存或主动询问 AI。");
    }

    private void refreshPreview() {
        boolean hasContext = false;
        StringBuilder preceding = new StringBuilder();
        for (int i = 0; i < checked.length; i++) if (checked[i]) {
            RecognitionResult item = exceptions.get(i);
            String value = PreviewEvidence.segment(snapshot, item.exceptionRange().orElseThrow(), item, true).context();
            if (!value.isEmpty()) {
                hasContext = true;
                if (context.isSelected()) {
                    if (!preceding.isEmpty()) preceding.append('\n');
                    preceding.append("异常 #").append(i + 1).append(" 的前置日志\n").append(value);
                }
            }
        }
        context.setEnabled(hasContext);
        contextPreview.showContext(preceding.toString());
        StringBuilder numbering = new StringBuilder();
        previewText = collectCheckedText(numbering);
        gutter.setText(numbering.toString());
        text.setText(previewText);
        text.setCaretPosition(0);
        copy.setEnabled(checkedCount > 0);
        save.setEnabled(checkedCount > 0);
        localSave.setEnabled(checkedCount > 0);
        copy.setText("复制所选（" + checkedCount + "/" + checked.length + "）");
    }

    private List<Issue.Segment> selectedSegments() {
        List<Issue.Segment> segments = new ArrayList<>();
        for (int i = 0; i < checked.length; i++) if (checked[i]) {
            RecognitionResult item = exceptions.get(i);
            segments.add(PreviewEvidence.segment(snapshot, item.exceptionRange().orElseThrow(), item,
                    context.isSelected(), snapshotWarnings));
        }
        return List.copyOf(segments);
    }

    private String collectCheckedText(StringBuilder numbering) {
        StringBuilder selectedText = new StringBuilder();
        for (int row = 0; row < checked.length; row++) {
            if (checked[row]) {
                TextRange range = exceptions.get(row).exceptionRange().orElseThrow();
                selectedText.append(snapshot, range.startOffset(), range.endOffset());
                numbering.append('#').append(row + 1);
                // 两个文本区共用相同的行高，序号只出现在每段的首行。
                for (int offset = range.startOffset(); offset < range.endOffset(); offset++) {
                    if (snapshot.charAt(offset) == '\n') numbering.append('\n');
                }
            }
        }
        return selectedText.toString();
    }

    private void copySelection() {
        if (!current.getAsBoolean()) return;
        if (checkedCount == 0) return;
        CopyPasteManager.getInstance().setContents(new StringSelection(previewText));
        copy.setText("已复制 " + checkedCount + " 段");
    }

    private String summary(RecognitionResult item) {
        TextRange range = item.exceptionRange().orElseThrow();
        int start = range.startOffset();
        int end = Math.min(range.endOffset(), LineBoundaries.next(snapshot, start));
        int limit = Math.min(end, start + 160);
        if (limit < end && Character.isHighSurrogate(snapshot.charAt(limit - 1))) limit--;
        return RecognitionPresentation.summary(item) + " · "
                + snapshot.substring(start, limit).strip() + (limit < end ? "…" : "");
    }

    private final class BatchTableModel extends AbstractTableModel {
        @Override
        public int getRowCount() { return checked.length; }

        @Override
        public int getColumnCount() { return 3; }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "复制";
                case 1 -> "顺序";
                default -> "异常";
            };
        }

        @Override
        public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }

        @Override
        public Object getValueAt(int row, int column) {
            return switch (column) {
                case 0 -> checked[row];
                case 1 -> Integer.toString(row + 1);
                default -> summaries.get(row);
            };
        }

        @Override
        public boolean isCellEditable(int row, int column) { return column == 0; }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (!current.getAsBoolean()) return;
            boolean selected = Boolean.TRUE.equals(value);
            if (checked[row] == selected) return;
            checked[row] = selected;
            checkedCount += selected ? 1 : -1;
            onCheck.accept(exceptions.get(row).exceptionRange().orElseThrow(), selected);
            fireTableCellUpdated(row, column);
            refreshPreview();
        }
    }
}
