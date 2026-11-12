package local.javatrace.idea;

import local.javatrace.store.Issue;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.DefaultCaret;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.*;
import java.util.List;

/** 文件阅读器和询问窗口共用；仅内部展开/复制链接，无外部资源或脚本。 */
final class IssueReadingPanel extends JPanel {
    private final JEditorPane page = new JEditorPane() {
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
    };
    private final JScrollPane scroll = new JScrollPane(page);
    private final Set<Integer> expanded = new HashSet<>();
    private List<Issue.Segment> segments = List.of();
    private Optional<Issue.Analysis> analysis = Optional.empty();
    private Issue.Notes notes = Issue.Notes.empty();
    private final JComboBox<String> jump = new JComboBox<>();
    private boolean updating;

    IssueReadingPanel() {
        super(new BorderLayout());
        page.setEditable(false); page.setEditorKit(new HTMLEditorKit());
        page.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        Font base = UIManager.getFont("Label.font");
        String family = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()).contains("Microsoft YaHei UI")
                ? "Microsoft YaHei UI" : base.getFamily();
        page.setFont(new Font(family, Font.PLAIN, Math.max(15, base.getSize() + 1)));
        ((DefaultCaret) page.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        page.addHyperlinkListener(event -> {
            if (event.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;
            String action = event.getDescription();
            if (action == null || !action.matches("(?:trace|copy):[0-9]+")) return;
            int index = Integer.parseInt(action.substring(action.indexOf(':') + 1));
            if (index >= segments.size()) return;
            if (action.startsWith("copy:")) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(segments.get(index).exceptionText()), null);
            } else {
                int y = scroll.getVerticalScrollBar().getValue();
                if (!expanded.add(index)) expanded.remove(index);
                render(); SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(y));
            }
        });
        scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) {
                int side = Math.max(0, (scroll.getViewport().getWidth() - 1000) / 2);
                page.setMargin(new Insets(0, side, 0, side));
            }
        });
        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEADING, 12, 6));
        jump.setPrototypeDisplayValue("1000 · IllegalArgumentException");
        navigation.add(new JLabel("跳转问题")); navigation.add(jump);
        jump.addActionListener(event -> {
            if (!updating && jump.getSelectedIndex() >= 0) page.scrollToReference("issue" + jump.getSelectedIndex());
        });
        add(navigation, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }
    void display(List<Issue.Segment> segments, Optional<Issue.Analysis> analysis, Issue.Notes notes) {
        this.segments = List.copyOf(segments); this.analysis = analysis; this.notes = notes;
        updating = true; jump.removeAllItems();
        for (int i = 0; i < segments.size(); i++) jump.addItem((i + 1) + " · " + IssuePresentation.summarize(segments.get(i).exceptionText()).type());
        updating = false; jump.setVisible(segments.size() > 1); jump.getParent().setVisible(segments.size() > 1);
        render(); page.setCaretPosition(0);
    }
    private void render() {
        String foreground = color(UIManager.getColor("Label.foreground"), Color.DARK_GRAY);
        Color background = UIManager.getColor("TextArea.background");
        String bg = color(background, Color.WHITE);
        boolean dark = background != null && (background.getRed() + background.getGreen() + background.getBlue()) < 384;
        String muted = dark ? "#abb3bf" : "#596574", border = dark ? "#49515d" : "#d8dce2";
        String accent = dark ? "#ffafa8" : "#a7352b", errorBg = dark ? "#3c3033" : "#fff0eb";
        StringBuilder html = new StringBuilder("<html><head><style>body{font-family:'")
                .append(IssuePresentation.escape(page.getFont().getFamily())).append("';font-size:").append(page.getFont().getSize())
                .append("pt;color:").append(foreground).append(";background:").append(bg)
                .append(";margin:20px 30px;}h1{font-size:23pt;margin:0 0 12px;}h2{font-size:19pt;margin:0 0 12px;}h3{font-size:16pt;margin:18px 0 9px;}p{margin:7px 0 11px;}li{margin:6px 0 10px;}ul,ol{margin-left:22px;}a{color:")
                .append(dark ? "#a8ceff" : "#245e9a").append(";}code{background:").append(errorBg)
                .append(";font-family:'").append(IssuePresentation.escape(page.getFont().getFamily())).append("';}.muted{color:")
                .append(muted).append(";font-size:12pt;}.error{color:").append(accent).append(";}.codeblock{background:")
                .append(errorBg).append(";padding:12px;margin:10px 0;}.card{border:1px solid ").append(border)
                .append(";padding:20px;margin-bottom:24px;}.cause{background:").append(errorBg)
                .append(";padding:12px;margin:12px 0;}</style></head><body>");
        IssuePresentation.Answers answers = analysis.map(ai -> IssuePresentation.answers(ai.answer(), segments.size()))
                .orElse(new IssuePresentation.Answers(List.of(), "", false));
        for (int i = 0; i < segments.size(); i++) {
            Issue.Segment segment = segments.get(i); IssuePresentation.Summary summary = IssuePresentation.summarize(segment.exceptionText());
            html.append("<div class='card'><p class='muted'><a name='issue").append(i).append("'></a>问题 ").append(i + 1).append(" / ").append(segments.size())
                    .append("</p><h2 class='error'>").append(IssuePresentation.escape(summary.type())).append("</h2>");
            if (!summary.message().isBlank()) html.append("<p><b>").append(IssuePresentation.escape(summary.message())).append("</b></p>");
            if (!summary.cause().isBlank()) html.append("<div class='cause'><span class='muted'>最内层原因</span><br>")
                    .append(IssuePresentation.escape(summary.cause())).append("</div>");
            if (!summary.location().isBlank()) html.append("<p>相关位置　<b>").append(IssuePresentation.escape(summary.location())).append("</b></p>");
            html.append("<p class='muted'><a href='trace:").append(i).append("'>")
                    .append(expanded.contains(i) ? "收起完整报错" : "展开完整报错（" + segment.exceptionText().lines().count() + " 行）")
                    .append("</a>　 · 　<a href='copy:").append(i).append("'>复制报错原文</a></p>");
            if (expanded.contains(i)) {
                html.append("<div class='codeblock'>").append(IssuePresentation.escape(segment.exceptionText()).replace("\t", "    ")
                        .replace("\r\n", "\n").replace("\r", "\n").replace(" ", "&nbsp;").replace("\n", "<br>")).append("</div>");
                if (!segment.context().isEmpty()) html.append("<h3>前置日志</h3>").append(IssuePresentation.markdown("```\n" + segment.context() + "\n```"));
                if (!segment.diagnostics().equals("FOUND · []")) html.append("<p class='muted'>边界说明：")
                        .append(IssuePresentation.escape(segment.diagnostics())).append("</p>");
            }
            html.append("<h3>AI 解答</h3>");
            if (analysis.isEmpty()) html.append("<p class='muted'>还没有解答。点击「询问 AI」，解释会显示在这条报错下方。</p>");
            else if (answers.paired()) html.append(IssuePresentation.markdown(answers.sections().get(i)));
            else html.append("<p class='muted'>此旧回答未按问题编号完整分段，无法可靠对应。请阅读下方的完整解答。</p>");
            html.append("</div>");
        }
        if (analysis.isPresent() && !answers.paired()) html.append("<div class='card'><h2>完整 AI 解答</h2>")
                .append(IssuePresentation.markdown(answers.shared())).append("</div>");
        else if (analysis.isPresent() && !answers.shared().isBlank()) html.append("<div class='muted'><h3>回答补充说明</h3>")
                .append(IssuePresentation.markdown(answers.shared())).append("</div>");
        if (analysis.isPresent() && analysis.orElseThrow().truncated()) html.append("<p class='error'>回答达到长度上限，可能不完整。</p>");
        if (!notes.equals(Issue.Notes.empty())) html.append("<div class='card'><h2>旧版笔记</h2>")
                .append(IssuePresentation.markdown("### 排查过程\n" + notes.investigation() + "\n### 原因\n" + notes.cause() + "\n### 解决办法\n" + notes.solution())).append("</div>");
        page.setText(html.append("</body></html>").toString());
    }
    private static String color(Color value, Color fallback) {
        Color c = value == null ? fallback : value; return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
