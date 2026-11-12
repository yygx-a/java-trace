package local.javatrace.idea;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dimension;

/** 单独展示可选证据，避免用户把前置日志误认成异常主体。 */
final class ContextPreview extends JPanel {
    private final JTextArea text = new JTextArea();

    ContextPreview() {
        super(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("已附带的前置 ERROR 日志 · 随询问 AI 发送并保存"));
        text.setEditable(false);
        text.setFont(UIManager.getFont("Label.font"));
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(text);
        scroll.setPreferredSize(new Dimension(100, 75));
        add(scroll, BorderLayout.CENTER);
        setVisible(false);
    }

    void showContext(String value) {
        text.setText(value);
        text.setCaretPosition(0);
        setVisible(!value.isEmpty());
        revalidate();
        repaint();
    }
}
