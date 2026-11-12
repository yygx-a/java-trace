package local.javatrace.core;

import local.javatrace.core.LineClassifier.Classified;
import local.javatrace.core.LineClassifier.Kind;
import local.javatrace.core.LineClassifier.MessageFormat;
import local.javatrace.core.RecognitionResult.Reason;
import local.javatrace.core.RecognitionResult.Warning;
import local.javatrace.core.RecognitionResult.Note;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;

/**
 * 将分类后的连续物理行组合为异常候选块。
 * 类的职责是判断行之间的结构关系，不读取 IDEA 对象，也不改变原文。
 * LineClassifier 已经告诉这里“每行是什么”；这里决定“哪些行属于同一段异常”。
 * 关键位置：readBlock 决定起止；Trace 中的分支栈维护 Caused by / Suppressed 的归属。
 */
final class TraceAssembler {
    private TraceAssembler() {}

    // ===== 区域 1：候选块的数据与整份日志的遍历 =====
    // first、last 和 context 都是行索引。last 包含在主体内，context 为 -1 表示没有上下文。
    // 此处尚未产生字符范围，最后由 JavaExceptionRecognizer 按 LineIndex 转换。
    record Block(int first, int last, int context, EnumSet<Reason> reasons, EnumSet<Warning> warnings, EnumSet<Note> notes) {}

    /** 顺序扫描所有行，发现候选块后从该块末尾的下一行继续，避免重复组合。 */
    static List<Block> assemble(List<Classified> lines) {
        List<Block> blocks = new ArrayList<>();
        for (int index = 0; index < lines.size();) {
            Kind kind = lines.get(index).kind();
            if (!isTitle(kind) && !isContinuation(kind)) {
                index++;
                continue;
            }
            Block block = readBlock(lines, index);
            if (block != null) {
                blocks.add(block);
                index = block.last() + 1;
            } else {
                index++;
            }
        }
        return blocks;
    }

    // ===== 区域 2：组合一段异常，并确定何处停止 =====
    /** 从标题或堆栈片段开始，持续接收结构上可以归属同一异常的行。 */
    private static Block readBlock(List<Classified> lines, int first) {
        Classified start = lines.get(first);
        boolean hasHeader = isTitle(start.kind());
        Trace trace = new Trace(start, hasHeader);
        int cursor = first + 1;
        while (cursor < lines.size()) {
            if (!start.prefix().equals(lines.get(cursor).prefix())) {
                Kind next = lines.get(cursor).kind();
                if (isContinuation(next) || next == Kind.BROKEN_TRACE || next == Kind.OTHER) {
                    trace.warnings.add(Warning.AMBIGUOUS_BOUNDARY);
                }
                break;
            }
            // 2.1 先接收合法调用帧或嵌套分支；不符合当前分支缩进时也会拒绝。
            if (trace.accept(lines.get(cursor))) {
                cursor++;
                continue;
            }
            // 2.2 普通结构未命中，再尝试已支持的多行消息。无法确认时停止，不无限吞行。
            int frame = trace.messageFrameAfter(lines, cursor);
            if (frame == cursor) break;
            // 只有已知消息格式、分支还没有帧、后方存在同分支的合法帧时，
            // 才接纳中间的诊断和空行。新的日志/标题/损坏帧始终阻断前瞻。
            trace.reasons.add(Reason.MULTILINE_MESSAGE);
            cursor = frame;
        }
        // 2.3 检查结构证据：孤立普通类名不生成候选；仅异常标题保留候选并说明未见堆栈。
        if (hasHeader && !trace.reasons.contains(Reason.STACK_FRAME)
                && !trace.reasons.contains(Reason.ELIDED_FRAMES)) {
            if (start.kind() == Kind.TITLE && cursor == first + 1) {
                return null;
            }
            trace.warnings.add(Warning.MISSING_STACK);
        }
        while (!trace.branches.isEmpty()) {
            trace.finishBranch();
            trace.branches.pop();
        }
        // 2.4 区分“部分分支无帧”和“整段缺乏堆栈证据”。前者可合法存在，只记内容观察。
        if (trace.reasons.contains(Reason.STACK_FRAME) || trace.reasons.contains(Reason.ELIDED_FRAMES)
                || trace.reasons.contains(Reason.CIRCULAR_REFERENCE)) {
            if (trace.warnings.remove(Warning.MISSING_STACK)) trace.notes.add(Note.BRANCH_WITHOUT_FRAMES);
        }
        // 2.5 停止后的下一行若仍可能与异常有关，报告归属歧义；明确的新日志是正常边界。
        if (cursor < lines.size()) {
            Kind next = lines.get(cursor).kind();
            boolean looseTitle = next == Kind.TITLE
                    && (cursor + 1 == lines.size() || !isContinuation(lines.get(cursor + 1).kind()));
            if (looseTitle || next == Kind.OTHER || next == Kind.BROKEN_TRACE
                    || next == Kind.MYBATIS_DETAIL || next == Kind.MYSQL_DETAIL || isContinuation(next)) {
                trace.warnings.add(Warning.AMBIGUOUS_BOUNDARY);
            }
        }
        // 2.6 单独寻找前置 ERROR 上下文，不把业务参数或日志等级并入异常主体。
        // 常见 Boot 转换器在 ERROR 和 Throwable 之间留一个空行。
        // 仍只关联一个物理 ERROR 行，不跨过任意消息或更多空行，也不把它并入主体。
        int context = -1;
        if (hasHeader && !start.inlineHeader() && first > 0) {
            int preceding = first - 1;
            if (lines.get(preceding).kind() == Kind.BLANK) preceding--;
            if (preceding >= 0 && lines.get(preceding).kind() == Kind.ERROR_LOG) {
                context = preceding;
                trace.reasons.add(Reason.ADJACENT_ERROR_CONTEXT);
            }
        }
        return new Block(first, cursor - 1, context, trace.reasons, trace.warnings, trace.notes);
    }

    private static boolean isTitle(Kind kind) {
        return kind == Kind.TITLE || kind == Kind.LIKELY_TITLE || kind == Kind.THREAD_TITLE;
    }

    private static boolean isContinuation(Kind kind) {
        return kind == Kind.FRAME || kind == Kind.CAUSE || kind == Kind.SUPPRESSED || kind == Kind.ELISION
                || kind == Kind.CIRCULAR_CAUSE || kind == Kind.CIRCULAR_SUPPRESSED;
    }

    // ===== 区域 3：单个 Throwable 分支的状态 =====
    /**
     * 一个 Throwable 分支的状态。
     * indent 是标题缩进，frameIndent 是该分支的调用帧缩进，-1 表示尚未确定。
     */
    private static final class Branch {
        private final int indent;
        private int frameIndent = -1;
        private boolean elided;
        private boolean hasSuppressed;
        private boolean hasFrames;
        private boolean circular;
        private final MessageFormat messageFormat;

        private Branch(int indent, MessageFormat messageFormat) {
            this.indent = indent;
            this.messageFormat = messageFormat;
        }

        private boolean acceptsFrame(int indent) {
            // 同一分支的帧应比标题更深且保持相同列；省略帧表示该分支的帧已经结束。
            return !elided && indent > this.indent && (frameIndent == -1 || frameIndent == indent);
        }
    }

    // ===== 区域 4：整段异常的分支栈与识别依据 =====
    // branches.peek() 是当前分支；进入 Suppressed 时保留父分支，缩进回退时离开内层。
    /** 使用栈追踪当前异常和嵌套的 Suppressed / Caused by 分支。 */
    private static final class Trace {
        private final EnumSet<Reason> reasons = EnumSet.noneOf(Reason.class);
        private final EnumSet<Warning> warnings = EnumSet.noneOf(Warning.class);
        private final EnumSet<Note> notes = EnumSet.noneOf(Note.class);
        private final Deque<Branch> branches = new ArrayDeque<>();
        private final boolean eventBounded;
        private final String prefix;

        private Trace(Classified start, boolean hasHeader) {
            eventBounded = start.eventBounded();
            prefix = start.prefix();
            if (hasHeader) {
                branches.push(new Branch(start.indent(), start.messageFormat()));
                reasons.add(Reason.THROWABLE_HEADER);
            } else {
                // 有调用帧或原因链但没有标题时，保留片段并标记缺少标题。
                warnings.add(Warning.MISSING_HEADER);
                if (start.kind() == Kind.FRAME || start.kind() == Kind.ELISION) {
                    branches.push(new Branch(-1, MessageFormat.NONE));
                    accept(start);
                } else {
                    branches.push(new Branch(start.indent(), start.messageFormat()));
                    reasons.add(start.kind() == Kind.CAUSE || start.kind() == Kind.CIRCULAR_CAUSE ? Reason.CAUSE : Reason.SUPPRESSED);
                    if(start.kind() == Kind.CIRCULAR_CAUSE || start.kind() == Kind.CIRCULAR_SUPPRESSED) {
                        branches.peek().circular = true;
                        reasons.add(Reason.CIRCULAR_REFERENCE);
                    }
                }
            }
        }

        // 4.1 分流：帧/省略帧交给 acceptFrame，原因/抑制/循环引用交给 acceptNested。
        private boolean accept(Classified line) {
            return switch (line.kind()) {
                case FRAME, ELISION -> acceptFrame(line);
                case CAUSE, SUPPRESSED, CIRCULAR_CAUSE, CIRCULAR_SUPPRESSED -> acceptNested(line);
                default -> false;
            };
        }

        // ===== 区域 5：MyBatis / MySQL 已知多行消息的有限前瞻 =====
        // 只在当前分支的首帧之前，查找“已知诊断行/空行 → 同分支合法帧”。
        // 返回后方帧的位置才表示中间消息可接纳；返回 first 表示不能证明归属。
        // 新日志、独立标题、未知消息等都会阻断，不靠单个异常类名就任意扩大范围。
        private int messageFrameAfter(List<Classified> lines, int first) {
            Branch active = branches.peek();
            if (active.hasFrames || active.hasSuppressed || active.circular
                    || (active.messageFormat == MessageFormat.NONE && !eventBounded)) return first;
            Kind detail = active.messageFormat == MessageFormat.MYBATIS ? Kind.MYBATIS_DETAIL : Kind.MYSQL_DETAIL;
            boolean hasDetail = false;
            for (int index = first; index < Math.min(lines.size(), first + 256); index++) {
                Classified line = lines.get(index);
                if (!prefix.equals(line.prefix())) return first;
                if (line.kind() == Kind.FRAME) {
                    return hasDetail && active.acceptsFrame(line.indent()) ? index : first;
                }
                boolean generic = eventBounded && (line.kind() == Kind.OTHER
                        || (line.kind() == Kind.LOG || line.kind() == Kind.ERROR_LOG) && !prefix.isEmpty());
                if (line.kind() != Kind.BLANK && !generic
                        && (line.kind() != detail || line.indent() != active.indent)) return first;
                hasDetail |= line.kind() == detail || generic;
            }
            return first;
        }

        // ===== 区域 6：把调用帧或省略帧接到当前分支 =====
        private boolean acceptFrame(Classified line) {
            Branch active = branches.peek();
            // 省略帧之后不再接收普通调用帧，出现 Suppressed 之后也不再接收父分支的帧。
            if (active.circular || active.hasSuppressed || !active.acceptsFrame(line.indent())) {
                return false;
            }
            active.frameIndent = line.indent();
            active.elided = line.kind() == Kind.ELISION;
            active.hasFrames = true;
            reasons.add(active.elided ? Reason.ELIDED_FRAMES : Reason.STACK_FRAME);
            return true;
        }

        // ===== 区域 7：原因链、抑制异常和循环引用的归属 =====
        // Caused by 与所属标题同级，切换到原因；Suppressed 缩进更深，压栈保留父分支。
        // 循环引用是该分支的终止标记，后面不能继续接该分支的帧。
        private boolean acceptNested(Classified line) {
            boolean cause = line.kind() == Kind.CAUSE || line.kind() == Kind.CIRCULAR_CAUSE;
            int indent = line.indent();
            // 缩进回退代表离开内层分支。Caused by 与所属标题同级，
            // Suppressed 则位于所属标题更深一层，同级 Suppressed 可以连续出现。
            while (branches.size() > 1 && (cause ? branches.peek().indent > indent : branches.peek().indent >= indent)) {
                finishBranch();
                branches.pop();
            }
            Branch parent = branches.peek();
            if (parent.circular) return false;
            if (cause) {
                if (parent.indent != indent) {
                    return false;
                }
                finishBranch();
                branches.pop();
            } else if (indent <= parent.indent || (parent.frameIndent != -1 && indent != parent.frameIndent)) {
                return false;
            } else {
                // 同一层的 Suppressed 与所属异常的调用帧使用相同缩进。
                parent.frameIndent = indent;
                parent.hasSuppressed = true;
            }
            branches.push(new Branch(indent, line.messageFormat()));
            if(line.kind() == Kind.CIRCULAR_CAUSE || line.kind() == Kind.CIRCULAR_SUPPRESSED) {
                branches.peek().circular = true;
                reasons.add(Reason.CIRCULAR_REFERENCE);
            }
            reasons.add(cause ? Reason.CAUSE : Reason.SUPPRESSED);
            return true;
        }

        // ===== 区域 8：收集无帧分支，交由 readBlock 统一区分警告与内容观察 =====
        /** 循环引用本就不重复打印帧，其余分支未见帧时先记录 MISSING_STACK。 */
        private void finishBranch() {
            Branch active = branches.peek();
            if (!active.hasFrames && !active.circular) {
                warnings.add(Warning.MISSING_STACK);
            }
        }
    }
}
