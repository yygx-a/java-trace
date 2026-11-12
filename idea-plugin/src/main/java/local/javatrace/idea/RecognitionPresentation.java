package local.javatrace.idea;

import local.javatrace.core.RecognitionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** 同一诊断用于单段、批量和记录。范围问题与内容观察分别说明。 */
final class RecognitionPresentation {
    private RecognitionPresentation() {}

    static String summary(RecognitionResult result) {
        if (result.boundary() == RecognitionResult.Boundary.USER_SELECTED) return "手动选择的文本 · 未作异常识别";
        String heading;
        if (result.exceptionRange().isEmpty()) heading = "未定位到可预览范围";
        else if (result.boundary() == RecognitionResult.Boundary.REVIEW_REQUIRED) heading = "范围需核对";
        else if (result.structure() == RecognitionResult.Structure.HEADER_ONLY) heading = "已定位异常标题";
        else heading = "已定位异常";
        List<String> details = new ArrayList<>();
        String warnings = warnings(result.warnings());
        if (!warnings.isEmpty()) details.add(warnings);
        if (result.notes().contains(RecognitionResult.Note.BRANCH_WITHOUT_FRAMES)) {
            details.add("部分分支未见调用帧，可能为无堆栈异常");
        }
        return heading + (details.isEmpty() ? "" : " · " + String.join("；", details));
    }

    static String warnings(List<RecognitionResult.Warning> warnings) {
        return warnings.stream().map(warning -> switch (warning) {
            case SNAPSHOT_START_TRUNCATED -> "控制台开头可能已被裁剪";
            case SNAPSHOT_END_TRUNCATED -> "快照结尾被截断";
            case UNTERMINATED_LAST_LINE -> "末行可能尚未输出完整";
            case AMBIGUOUS_BOUNDARY -> "相邻内容无法归属，可按行调整";
            case MISSING_HEADER -> "仅见堆栈片段，未见异常标题";
            case MISSING_STACK -> "仅见标题，无法确认调用帧是否被省略";
            case CHARACTER_LIMIT -> "超过字符扫描上限";
            case LINE_LIMIT -> "超过行数扫描上限";
        }).collect(Collectors.joining("；"));
    }
}
