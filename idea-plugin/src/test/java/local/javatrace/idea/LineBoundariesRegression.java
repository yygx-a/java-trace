package local.javatrace.idea;

/** 行边界来自人工标注：保留 CRLF、中文和代理对，最后一行没有换行。 */
public final class LineBoundariesRegression {
    public static void main(String[] args) {
        String text = "甲😀\r\nsecond\n末行";
        check(LineBoundaries.next(text, 0), 5);
        check(LineBoundaries.next(text, 5), 12);
        check(LineBoundaries.next(text, 12), 14);
        check(LineBoundaries.next(text, 14), 14);
        check(LineBoundaries.previous(text, 14), 12);
        check(LineBoundaries.previous(text, 12), 5);
        check(LineBoundaries.previous(text, 5), 0);
        check(LineBoundaries.previous(text, 0), 0);
        String blanks = "a\r\n\r\nb\r";
        check(LineBoundaries.next(blanks, 3), 5);
        check(LineBoundaries.previous(blanks, 5), 3);
        check(LineBoundaries.previous(blanks, 7), 5);
        System.out.println("Line boundary regression passed: 11 assertions.");
    }
    private static void check(int actual, int expected) {
        if (actual != expected) throw new AssertionError("expected " + expected + ", got " + actual);
    }
}
