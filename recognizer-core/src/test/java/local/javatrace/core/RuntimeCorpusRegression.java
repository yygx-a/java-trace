package local.javatrace.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Fixed producer-annotated corpus. Full-case results are reported, not an accuracy estimate. */
public final class RuntimeCorpusRegression {
    private static final JavaExceptionRecognizer RECOGNIZER = new JavaExceptionRecognizer();
    private static int assertions;
    public static void main(String[] args) throws Exception {
        String split=args.length>0 ? args[0] : "all";
        List<String> failures=new ArrayList<>(); int cases=0, passed=0;
        for(String row:resource("annotations.tsv").lines().toList()) {
            if(row.startsWith("#") || row.isBlank()) continue;
            String[] fields=row.split("\t");
            if(!split.equals("all") && !split.equals(fields[0])) continue;
            cases++;
            try { verify(fields); passed++; }
            catch(AssertionError error) { failures.add(fields[1]+": "+error.getMessage()); }
        }
        System.out.println("Runtime corpus ["+split+"]: "+passed+"/"+cases+" full cases passed; "+assertions+" checks (not independent projects).");
        failures.forEach(System.out::println);
        if(!failures.isEmpty()) throw new AssertionError(failures.size()+" corpus cases failed");
    }
    private static void verify(String[] f) throws Exception {
        String original=resource(f[1]+".log").replace("\r\n","\n");
        for(String newline:List.of("\n","\r\n")) {
            String text=original.replace("\n",newline);
            var batch=RECOGNIZER.recognizeAll(text,false,false);
            String[] ranges=f[2].equals("-") ? new String[0] : parts(f[2]);
            equal(ranges.length,batch.exceptions().size(),"candidate count");
            equal(List.of(),batch.warnings(),"scan warnings");
            for(int i=0;i<ranges.length;i++) {
                var result=batch.exceptions().get(i);
                var range=range(original,newline,ranges[i]);
                equal(range,result.exceptionRange(),"body #"+(i+1));
                equal(parts(f[3])[i],result.status().name(),"status #"+(i+1));
                equal(names(parts(f[4])[i]),result.warnings().stream().map(Enum::name).toList(),"warnings");
                // Reflection permits running this same checker against pre-change core for the baseline.
                List<?> notes;
                try { notes=(List<?>)RecognitionResult.class.getMethod("notes").invoke(result); }
                catch(NoSuchMethodException absent) { notes=List.of(); }
                equal(names(parts(f[5])[i]),notes.stream().map(Object::toString).toList(),"content notes");
                equal(range(original,newline,parts(f[6])[i]),result.contextRange(),"context #"+(i+1));
                var selected=range.orElseThrow();
                for(int anchor=selected.startOffset();anchor<selected.endOffset();anchor=text.indexOf('\n',anchor)+1) {
                    equal(result,RECOGNIZER.recognize(text,anchor),"single/batch at physical line");
                }
            }
        }
    }
    private static String[] parts(String value) { return value.split(";"); }
    private static List<String> names(String value) { return value.equals("-") ? List.of() : Arrays.asList(value.split(",")); }
    private static Optional<TextRange> range(String original,String newline,String pair) {
        if(pair.equals("-")) return Optional.empty();
        var offsets=pair.split(":");
        int start=Integer.parseInt(offsets[0]), end=Integer.parseInt(offsets[1]);
        return Optional.of(new TextRange(original.substring(0,start).replace("\n",newline).length(),original.substring(0,end).replace("\n",newline).length()));
    }
    private static String resource(String name) throws Exception {
        try(var stream=RuntimeCorpusRegression.class.getResourceAsStream("/runtime-corpus/"+name)) {
            if(stream==null) throw new AssertionError("Missing corpus "+name);
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private static void equal(Object expected,Object actual,String label) {
        if(!Objects.equals(expected,actual)) throw new AssertionError(label+": expected "+expected+", got "+actual);
        assertions++;
    }
}
