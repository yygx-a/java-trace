package local.javatrace.idea;

import local.javatrace.core.*;
import local.javatrace.store.*;
import java.util.*;

public final class PreviewEvidenceRegression {
    private static int checks;
    public static void main(String[] args) {
        String context = "ERROR 合成上下文\r\n";
        String a = "java.lang.IllegalStateException: 中文😀\r\n\tat Demo.run(Demo.java:1)\r\n";
        String ordinary = "INFO 普通日志\r\n";
        String b = "java.lang.Error: B\r\n\tat Demo.run(Demo.java:2)\r\n";
        String snapshot = context + a + ordinary + b + ordinary + a;
        var results = new JavaExceptionRecognizer().recognizeAll(snapshot, false, false).exceptions();
        check(results.size() == 3, "three independent traces");
        var first = results.getFirst();
        Issue.Segment raw = PreviewEvidence.segment(snapshot, first.exceptionRange().orElseThrow(), first, false);
        check(raw.exceptionText().equals(a) && raw.context().isEmpty(), "raw text with optional context excluded");
        Issue.Segment included = PreviewEvidence.segment(snapshot, first.exceptionRange().orElseThrow(), first, true);
        check(included.context().equals(context), "optional context stored separately");
        Issue.Segment expanded = PreviewEvidence.segment(snapshot, new TextRange(0, context.length() + a.length()), first, true);
        check(expanded.exceptionText().equals(context + a) && expanded.context().isEmpty(), "adjusted preview avoids duplicate context");
        check(expanded.diagnostics().contains("用户按行调整"), "manual boundary recorded");
        List<Issue.Segment> selected = List.of(PreviewEvidence.segment(snapshot, first.exceptionRange().orElseThrow(), first, false),
                PreviewEvidence.segment(snapshot, results.get(2).exceptionRange().orElseThrow(), results.get(2), false));
        check(selected.size() == 2 && selected.getFirst().exceptionText().equals(a) && selected.getLast().exceptionText().equals(a), "selected duplicate A preserved without B or ordinary logs");
        var uncertain = new JavaExceptionRecognizer().recognize(snapshot, context.length(), true, false);
        check(PreviewEvidence.segment(snapshot, uncertain.exceptionRange().orElseThrow(), uncertain, false).diagnostics().contains("SNAPSHOT_START_TRUNCATED"), "warnings preserved");
        String stackless="example.QuietException: wrapper\nCaused by: java.lang.IllegalStateException: cause\n\tat Demo.run(Demo.java:1)\n";
        var located=new JavaExceptionRecognizer().recognize(stackless,0);
        check(RecognitionPresentation.summary(located).startsWith("已定位异常") && RecognitionPresentation.summary(located).contains("部分分支未见调用帧"),"stackless note does not claim ambiguous boundary");
        check(PreviewEvidence.segment(stackless,located.exceptionRange().orElseThrow(),located,false).diagnostics().contains("BRANCH_WITHOUT_FRAMES"),"content observation saved");
        var cropped=new JavaExceptionRecognizer().recognize(stackless,0,true,false);
        check(RecognitionPresentation.summary(cropped).startsWith("范围需核对") && RecognitionPresentation.summary(cropped).contains("裁剪")
                && RecognitionPresentation.summary(cropped).contains("部分分支未见调用帧"),"crop and content both visible");
        var header=new JavaExceptionRecognizer().recognize("example.QuietException: quiet\nINFO done\n",0);
        check(RecognitionPresentation.summary(header).startsWith("已定位异常标题") && RecognitionPresentation.summary(header).contains("无法确认"),"header-only evidence labelled honestly");
        String inferredText = "example.ImportException: invalid\r\n第 5 项为空 😃\r\nfield[5]: missing value\r\n\tat Demo.run(Demo.java:3)\r\n";
        var inferred = new JavaExceptionRecognizer().recognize(inferredText, inferredText.indexOf("第"));
        check(RecognitionPresentation.summary(inferred).contains("2 行报错说明按堆栈结构推定")
                && inferred.boundary() == RecognitionResult.Boundary.REVIEW_REQUIRED, "inferred range and count visible in previews");
        var inferredEvidence = PreviewEvidence.segment(inferredText, inferred.exceptionRange().orElseThrow(), inferred, false);
        check(inferredEvidence.exceptionText().equals(inferredText) && inferredEvidence.diagnostics().contains("inferredMessageLines=2"),
                "original inferred message and its count retained in saved evidence");
        var manual=ManualSelection.result(snapshot,new TextRange(1,context.length()+a.length()-2));
        var manualEvidence=PreviewEvidence.segment(snapshot,manual.exceptionRange().orElseThrow(),manual,true);
        check(manualEvidence.exceptionText().equals(snapshot.substring(1,context.length()+a.length()-2)) && manualEvidence.context().isEmpty(),"manual range is exact, context is not inferred");
        check(manual.boundary()==RecognitionResult.Boundary.USER_SELECTED && manual.structure()==RecognitionResult.Structure.UNCLASSIFIED
                && RecognitionPresentation.summary(manual).equals("手动选择的文本 · 未作异常识别"),"manual source never labelled detected");
        check(manualEvidence.diagnostics().contains("USER_SELECTION") && manualEvidence.diagnostics().contains("UNCLASSIFIED"),"manual provenance saved");
        check(PreviewEvidence.segment(snapshot,first.exceptionRange().orElseThrow(),first,false,List.of(RecognitionResult.Warning.SNAPSHOT_START_TRUNCATED))
                .diagnostics().contains("snapshotWarnings=[SNAPSHOT_START_TRUNCATED]"),"batch snapshot limits survive saving evidence");
        for(TextRange invalid:List.of(new TextRange(0,snapshot.length()+1))) {
            try { ManualSelection.result(snapshot,invalid); throw new AssertionError("Invalid manual range accepted"); }
            catch(IllegalArgumentException expected) { checks++; }
        }
        System.out.println("Preview evidence regression: " + checks + " assertions passed");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
}
