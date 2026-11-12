package local.javatrace.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static local.javatrace.core.RecognitionResult.*;

/** Boundary, observed structure and content notes are independent contracts. */
final class RecognitionAssessmentRegression {
    static int run() {
        int checks=0;
        var recognizer=new JavaExceptionRecognizer();
        String trace="example.QuietException: wrapper\nCaused by: java.lang.IllegalStateException: cause\n"
                +"\tat example.Work.run(Work.java:1)\n";
        var located=recognizer.recognize(trace,0);
        require(located.status()==Status.FOUND && located.boundary()==Boundary.LOCATED && located.structure()==Structure.TRACE,"located stackless chain"); checks++;
        require(located.notes().equals(List.of(Note.BRANCH_WITHOUT_FRAMES)) && located.warnings().isEmpty(),"absence of frames is an observation"); checks++;
        var cropped=recognizer.recognize(trace,0,false,true);
        require(cropped.status()==Status.UNCERTAIN && cropped.boundary()==Boundary.REVIEW_REQUIRED,"crop still needs review"); checks++;
        require(cropped.notes().equals(located.notes()) && cropped.warnings().equals(List.of(Warning.SNAPSHOT_END_TRUNCATED)),"crop and content independently retained"); checks++;
        var header=recognizer.recognize("example.QuietException: quiet\nINFO done\n",0);
        require(header.status()==Status.UNCERTAIN && header.boundary()==Boundary.LOCATED && header.structure()==Structure.HEADER_ONLY,"header evidence is not a boundary ambiguity"); checks++;
        var fragment=recognizer.recognize("\tat example.Work.run(Work.java:1)\n",0);
        require(fragment.structure()==Structure.FRAGMENT && fragment.boundary()==Boundary.REVIEW_REQUIRED,"fragment missing its beginning"); checks++;
        var absent=recognizer.recognize("INFO healthy\n",0);
        require(absent.structure()==Structure.NONE && absent.boundary()==Boundary.UNAVAILABLE,"no invented range"); checks++;
        var notes=new ArrayList<>(List.of(Note.BRANCH_WITHOUT_FRAMES));
        var copy=new RecognitionResult(Status.FOUND,Optional.of(new TextRange(0,1)),Optional.empty(),List.of(Reason.THROWABLE_HEADER),List.of(),notes);
        notes.clear(); require(copy.notes().equals(List.of(Note.BRANCH_WITHOUT_FRAMES)),"immutable notes"); checks++;
        try { copy.notes().clear(); throw new AssertionError("mutable notes"); } catch(UnsupportedOperationException expected) { checks++; }
        for(String invalid:List.of("Caused by: [CIRCULAR REFERENCE: ]", "Suppressed: [CIRCULAR REFERENCE: java.lang.Error", "Caused by: [CIRCULAR REFERENCE: java.lang.Error] trailing")) {
            String body="java.lang.IllegalStateException: root\n\tat example.Work.run(Work.java:1)\n";
            var result=recognizer.recognize(body+invalid+"\n",0);
            require(result.exceptionRange().orElseThrow().endOffset()==body.length() && result.boundary()==Boundary.REVIEW_REQUIRED,"malformed circular marker excluded"); checks++;
        }
        for(String newline:List.of("\n","\r\n")) {
            String context=("2026-11-12 12:00:00.000 ERROR 42 --- [main] example.Log : request\n\n").replace("\n",newline);
            String body=trace.replace("\n",newline);
            String text=context+body;
            var expected=recognizer.recognize(text,context.length());
            require(expected.contextRange().equals(Optional.of(new TextRange(0,context.length()))) && expected.exceptionRange().equals(Optional.of(new TextRange(context.length(),text.length()))),"one blank belongs only to optional context"); checks++;
            require(recognizer.recognize(text,context.length()-newline.length()).equals(expected),"context blank anchor"); checks++;
            for(String separator:List.of(newline, "INFO independent"+newline, "unclassified message"+newline)) {
                require(recognizer.recognize(context+separator+body,context.length()+separator.length()).contextRange().isEmpty(),"context cannot cross more blank lines or messages"); checks++;
            }
        }
        return checks;
    }
    private static void require(boolean value,String label) { if(!value) throw new AssertionError(label); }
}
