package local.javatrace.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileTypes.FileType;
import javax.swing.Icon;

/** .jti 是带校验的记录协议，不应被文本编辑器解码或编辑。 */
public final class IssueFileType implements FileType {
    public static final IssueFileType INSTANCE = new IssueFileType();
    private IssueFileType() {}
    @Override public String getName() { return "Java Trace Issue"; }
    @Override public String getDescription() { return "Java 报错与 AI 解答记录"; }
    @Override public String getDefaultExtension() { return "jti"; }
    @Override public Icon getIcon() { return AllIcons.FileTypes.Text; }
    @Override public boolean isBinary() { return true; }
    @Override public boolean isReadOnly() { return true; }
}
