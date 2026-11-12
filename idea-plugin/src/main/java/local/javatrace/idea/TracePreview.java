package local.javatrace.idea;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.WindowManager;
import local.javatrace.core.RecognitionResult;
import local.javatrace.core.TextRange;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.List;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import local.javatrace.store.Issue;

/** 非模态预览始终引用识别时的原文，调整操作以物理行为单位。 */
final class TracePreview implements ConsolePreview {
    private final String snapshot;
    private final JDialog dialog;
    private final JTextArea text = new JTextArea();
    private final JLabel status = new JLabel();
    private final JButton copy = new JButton("复制");
    private final JButton save = new JButton("询问 AI…");
    private final JButton localSave = new JButton("保存报错…");
    private final JCheckBox context = new JCheckBox("附带前置 ERROR 日志");
    private final ContextPreview contextPreview = new ContextPreview();
    private final RecognitionResult recognition;
    private final Map<List<Issue.Segment>, UUID> saveTokens = new HashMap<>();
    private final JButton[] adjustments = new JButton[4];
    private final BiConsumer<Integer, Integer> onRange;
    private final BooleanSupplier current;
    private int start;
    private int end;
    private final JLabel source = new JLabel("固定快照 · 复制与调整不受新输出影响");

    TracePreview(Project project, String snapshot, int start, int end, RecognitionResult recognition, String warnings,
                 BiConsumer<Integer, Integer> onRange, Runnable onClose, BooleanSupplier current) {
        String description = RecognitionPresentation.summary(recognition);
        this.snapshot = snapshot;
        this.recognition = recognition;
        this.start = start;
        this.end = end;
        this.onRange = onRange;
        this.current = current;
        dialog = new JDialog(WindowManager.getInstance().getFrame(project), "Java 异常预览", false);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { onClose.run(); }
        });
        status.setText(description);
        text.setEditable(false);
        text.setFont(UIManager.getFont("Label.font"));
        text.setLineWrap(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEADING));
        String[] names = {"向上扩大", "缩小顶部", "缩小底部", "向下扩大"};
        for (int i = 0; i < names.length; i++) {
            final int action = i;
            adjustments[i] = new JButton(names[i]);
            adjustments[i].addActionListener(event -> adjust(action));
            buttons.add(adjustments[i]);
        }
        copy.addActionListener(event -> {
            // 操作只读取当前窗口持有的固定快照；源控制台变化不会改变它。
            if (!current.getAsBoolean()) return;
            CopyPasteManager.getInstance().setContents(new StringSelection(snapshot.substring(this.start, this.end)));
            status.setText("已复制当前预览。" + description);
        });
        buttons.add(copy);
        context.setEnabled(recognition.contextRange().isPresent());
        if (!context.isEnabled()) context.setText("无前置 ERROR 日志");
        context.setToolTipText("勾选后显示前置日志，随询问 AI 发送并保存；复制仅复制报错原文。");
        context.addActionListener(event -> {
            if (!current.getAsBoolean()) return;
            refresh();
        });
        save.setToolTipText("将当前稳定预览发送至 DeepSeek，报错与回答在同一窗口展示。");
        buttons.add(context);
        save.addActionListener(event -> {
            if (!current.getAsBoolean()) return;
            List<Issue.Segment> segments = List.of(PreviewEvidence.segment(snapshot,
                    new TextRange(this.start, this.end), recognition, context.isSelected()));
            project.getService(IssueRecords.class).explainPreview(saveTokens.computeIfAbsent(segments, key -> UUID.randomUUID()), segments);
        });
        buttons.add(save);
        localSave.setToolTipText("打开本地记录草稿，保留当前调整范围和已勾选上下文。");
        localSave.addActionListener(event -> {
            if (!current.getAsBoolean()) return;
            List<Issue.Segment> segments = List.of(PreviewEvidence.segment(snapshot,
                    new TextRange(this.start, this.end), recognition, context.isSelected()));
            project.getService(IssueRecords.class).savePreview(saveTokens.computeIfAbsent(segments, key -> UUID.randomUUID()), segments);
        });
        buttons.add(localSave);
        JPanel notices = new JPanel(new java.awt.GridLayout(0, 1));
        notices.add(status); notices.add(source);
        dialog.add(notices, BorderLayout.NORTH);
        JPanel evidence = new JPanel(new BorderLayout());
        evidence.add(contextPreview, BorderLayout.NORTH);
        evidence.add(new JScrollPane(text), BorderLayout.CENTER);
        dialog.add(evidence, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setSize(1000, 480);
        dialog.setLocationRelativeTo(WindowManager.getInstance().getFrame(project));
        refresh();
    }

    @Override
    public void open() { dialog.setVisible(true); }

    @Override
    public void close() { dialog.dispose(); }

    @Override
    public void sourceDetached(String message) {
        source.setText(message + "；固定快照仍可调整、复制、保存或主动询问 AI。");
    }

    private void adjust(int action) {
        if (!current.getAsBoolean()) return;
        switch (action) {
            case 0 -> start = previousLine(start);
            case 1 -> { int next = nextLine(start); if (next < end) start = next; }
            case 2 -> { int previous = previousLine(end); if (previous > start) end = previous; }
            case 3 -> end = nextLine(end);
            default -> throw new IllegalArgumentException();
        }
        refresh();
        onRange.accept(start, end);
    }

    // 识别器返回的范围位于行边界。以 UTF-16 索引查找换行，不改变原文的 CRLF。
    private int previousLine(int offset) { return LineBoundaries.previous(snapshot, offset); }

    private int nextLine(int offset) { return LineBoundaries.next(snapshot, offset); }

    private void refresh() {
        contextPreview.showContext(PreviewEvidence.segment(snapshot, new TextRange(start, end), recognition,
                context.isSelected()).context());
        text.setText(snapshot.substring(start, end));
        text.setCaretPosition(0);
        refreshButtons();
    }

    private void refreshButtons() {
        copy.setEnabled(true);
        save.setEnabled(true);
        context.setEnabled(!PreviewEvidence.segment(snapshot, new TextRange(start, end), recognition, true).context().isEmpty());
        adjustments[0].setEnabled(start > 0);
        adjustments[1].setEnabled(nextLine(start) < end);
        adjustments[2].setEnabled(previousLine(end) > start);
        adjustments[3].setEnabled(end < snapshot.length());
    }
}

final class LineBoundaries {
    private LineBoundaries() {}

    static int previous(String snapshot, int offset) {
        if (offset <= 0) return 0;
        int pos = offset - 1;
        if (snapshot.charAt(pos) == '\n') pos--;
        if (pos >= 0 && snapshot.charAt(pos) == '\r') pos--;
        while (pos >= 0 && snapshot.charAt(pos) != '\n' && snapshot.charAt(pos) != '\r') pos--;
        return pos + 1;
    }

    static int next(String snapshot, int offset) {
        int pos = offset;
        while (pos < snapshot.length() && snapshot.charAt(pos) != '\n' && snapshot.charAt(pos) != '\r') pos++;
        if (pos < snapshot.length() && snapshot.charAt(pos++) == '\r' && pos < snapshot.length()
                && snapshot.charAt(pos) == '\n') pos++;
        return pos;
    }

}
