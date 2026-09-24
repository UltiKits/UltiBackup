package com.ultikits.plugins.backup.i18n;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads this module's Java source with the JDK's own parser, for the two language guards.
 * <p>
 * Both guards ({@code UltiBackupLanguageCatalogueTest} and {@code UltiBackupCjkLiteralScopeTest})
 * read {@code src/main/java} through this one class. It does not tokenise or guess: it asks
 * {@code javac} ({@link JavacTask#parse()} and the {@code com.sun.source} tree API, part of every
 * JDK the build runs on) for the syntax tree, so comments, Unicode escapes, escape sequences, text
 * blocks, calls versus declarations, and which method a name belongs to are decided exactly as the
 * compiler decides them. A file {@code javac} cannot parse fails the scan.
 * <p>
 * The first version was a hand-written lexer with heuristics for "is this a call or a
 * declaration". Review found one gap after another in those heuristics (a call after a lambda
 * arrow, a call after a comparison, a forwarding call inside an anonymous class), each fix exposing
 * the next, so the lexer was replaced by the compiler's parser instead of being patched again.
 * <p>
 * This file is copied unchanged into every module; only its package line differs.
 */
final class I18nSourceScanner {

    /** The Unicode block both guards detect: CJK Unified Ideographs, U+4E00 through U+9FFF. */
    static final char CJK_FIRST = (char) 0x4E00;
    static final char CJK_LAST = (char) 0x9FFF;

    /** Method names whose argument is a catalogue key. */
    static final Set<String> KEY_METHODS = new HashSet<>(Arrays.asList("i18n", "getLocalizedText"));

    private I18nSourceScanner() {
    }

    static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= CJK_FIRST && c <= CJK_LAST) {
                return true;
            }
        }
        return false;
    }

    /** A string, text-block or character literal. */
    static final class Literal {
        final boolean character;
        /** The literal's value, every escape decoded. */
        final String value;
        /** The text between the delimiters exactly as written in the file. */
        final String raw;
        final int line;
        /** True when the framework looks this literal up as a catalogue key. */
        boolean key;
        /**
         * True when the literal is part of the value of a {@code @ConfigEntry} annotation's
         * {@code comment} element -- and of nothing else. Guard 2 skips these; the reason is written
         * next to the skip in {@code UltiBackupCjkLiteralScopeTest#reportable}.
         */
        boolean configComment;

        Literal(boolean character, String value, String raw, int line) {
            this.character = character;
            this.value = value;
            this.raw = raw;
            this.line = line;
        }
    }

    /** Where a catalogue key reaches the framework. */
    enum SiteKind {
        /** {@code i18n(...)} or {@code getLocalizedText(...)}. */
        CALL,
        /** {@code ::i18n} -- the keys come from wherever the function is applied. */
        METHOD_REFERENCE,
        /** {@code @CmdExecutor(description = ...)} -- {@code CommandManager} passes it through {@code i18n}. */
        COMMAND_DESCRIPTION,
        /**
         * {@code @CmdParam(suggest = ...)} naming no method in the module -- the framework's
         * {@code MethodInvocationCompleter} then shows {@code plugin.i18n(suggest)} as the hint.
         */
        SUGGEST_HINT
    }

    /** One place a key reaches the framework's catalogue lookup. */
    static final class KeySite {
        final SiteKind kind;
        final int line;
        /** The key when it is written as one string literal, otherwise {@code null}. */
        final String literalKey;
        /** The key argument as {@code javac} prints it; matches a {@code DYNAMIC_KEY_SITES} entry. */
        final String expression;
        /** A wrapper named {@code i18n} forwarding its own, never-reassigned key parameter. */
        final boolean passThrough;

        KeySite(SiteKind kind, int line, String literalKey, String expression, boolean passThrough) {
            this.kind = kind;
            this.line = line;
            this.literalKey = literalKey;
            this.expression = expression;
            this.passThrough = passThrough;
        }

        boolean isLiteral() {
            return literalKey != null;
        }
    }

    /** A {@code @CmdParam(suggest = ...)} literal, resolved against the module's methods later. */
    static final class SuggestCandidate {
        final Literal literal;

        SuggestCandidate(Literal literal) {
            this.literal = literal;
        }

        String methodName() {
            String v = literal.value;
            return v.endsWith("()") ? v.substring(0, v.length() - 2) : v;
        }
    }

    /** One parsed {@code .java} file. {@code path} is relative to the module root, with {@code /}. */
    static final class SourceFile {
        final String path;
        final List<Literal> literals = new ArrayList<>();
        final List<KeySite> sites = new ArrayList<>();
        final Set<String> declaredMethods = new HashSet<>();
        final List<SuggestCandidate> suggestCandidates = new ArrayList<>();

        private SourceFile(String path) {
            this.path = path;
        }

        /** Parses one file on its own; its suggest values are resolved against its own methods. */
        static SourceFile of(String path, String source) {
            SourceFile f = parse(path, source);
            resolveSuggestHints(Collections.singletonList(f));
            return f;
        }
    }

    /** The directory Maven runs the tests from (the module root). */
    static Path moduleRoot() {
        return Paths.get(System.getProperty("basedir", System.getProperty("user.dir"))).toAbsolutePath();
    }

    /**
     * Every {@code .java} file under {@code <moduleRoot>/src/main/java}, sorted by path.
     * <p>
     * The directory is walked rather than {@code git ls-files}: {@code javac} compiles every file in
     * it, tracked or not, so the directory is exactly what ships.
     */
    static List<SourceFile> scanMainSources(Path moduleRoot) throws IOException {
        Path sourceRoot = moduleRoot.resolve("src/main/java");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            walk.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p)).forEach(files::add);
        }
        Collections.sort(files);
        List<SourceFile> result = new ArrayList<>();
        for (Path p : files) {
            String rel = moduleRoot.relativize(p).toString().replace('\\', '/');
            result.add(parse(rel, new String(Files.readAllBytes(p), StandardCharsets.UTF_8)));
        }
        resolveSuggestHints(result);
        return result;
    }

    /**
     * The framework resolves {@code suggest = "x"} to a method named {@code x} (a trailing {@code ()}
     * removed) on the executor's class hierarchy or its {@code @CmdSuggest} classes, and only when
     * none exists shows {@code plugin.i18n("x")}. Without types, the nearest faithful reading is: a
     * value that names a method declared anywhere in the module is a method; anything else is a key.
     */
    static void resolveSuggestHints(List<SourceFile> files) {
        Set<String> methods = new HashSet<>();
        for (SourceFile f : files) {
            methods.addAll(f.declaredMethods);
        }
        for (SourceFile f : files) {
            for (SuggestCandidate c : f.suggestCandidates) {
                if (!methods.contains(c.methodName())) {
                    c.literal.key = true;
                    f.sites.add(new KeySite(SiteKind.SUGGEST_HINT, c.literal.line, c.literal.value,
                            "\"" + c.literal.raw + "\"", false));
                }
            }
            f.suggestCandidates.clear();
        }
    }

    static SourceFile parse(final String path, final String source) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IllegalStateException("no system Java compiler: the build must run on a JDK, not a JRE");
        }
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///" + path), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        // -XDallowStringFolding=false: javac's parser otherwise folds "a" + "b" into one literal
        // spanning both, and each literal must stay the one written in the file.
        JavacTask task = (JavacTask) javac.getTask(new StringWriter(), null, diagnostics,
                Arrays.asList("-proc:none", "-XDallowStringFolding=false"), null, Collections.singletonList(file));
        Iterable<? extends CompilationUnitTree> units;
        try {
            units = task.parse();
        } catch (IOException e) {
            throw new IllegalStateException(path + ": " + e.getMessage(), e);
        }
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                throw new IllegalStateException(path + ":" + d.getLineNumber() + ": " + d.getMessage(null));
            }
        }
        SourceFile result = new SourceFile(path);
        SourcePositions positions = Trees.instance(task).getSourcePositions();
        for (CompilationUnitTree unit : units) {
            new Visitor(result, unit, positions, source).scan(unit, null);
        }
        return result;
    }

    /** Collects literals and key sites from one compilation unit. */
    private static final class Visitor extends TreeScanner<Void, Void> {
        private final SourceFile out;
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final String source;
        private final LineMap lines;
        /** Innermost method first; cleared on entering a class, so a nested class's methods stand alone. */
        private Deque<MethodTree> methods = new ArrayDeque<>();
        /** Literals already known to be keys by the time the literal itself is visited. */
        private final Set<Tree> keyLiterals = Collections.newSetFromMap(new IdentityHashMap<Tree, Boolean>());
        private final Set<Tree> suggestLiterals = Collections.newSetFromMap(new IdentityHashMap<Tree, Boolean>());
        /** Literals inside the value of {@code @ConfigEntry(comment = ...)}. */
        private final Set<Tree> configCommentLiterals =
                Collections.newSetFromMap(new IdentityHashMap<Tree, Boolean>());

        Visitor(SourceFile out, CompilationUnitTree unit, SourcePositions positions, String source) {
            this.out = out;
            this.unit = unit;
            this.positions = positions;
            this.source = source;
            this.lines = unit.getLineMap();
        }

        private int line(Tree t) {
            return (int) lines.getLineNumber(positions.getStartPosition(unit, t));
        }

        @Override
        public Void visitClass(ClassTree node, Void p) {
            Deque<MethodTree> saved = methods;
            methods = new ArrayDeque<>();
            try {
                return super.visitClass(node, p);
            } finally {
                methods = saved;
            }
        }

        @Override
        public Void visitMethod(MethodTree node, Void p) {
            out.declaredMethods.add(node.getName().toString());
            methods.push(node);
            try {
                return super.visitMethod(node, p);
            } finally {
                methods.pop();
            }
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
            String name = simpleName(node.getMethodSelect());
            if (name != null && KEY_METHODS.contains(name)) {
                List<? extends ExpressionTree> args = node.getArguments();
                if (args.size() == 1 || args.size() == 2) {
                    ExpressionTree key = args.get(args.size() - 1);
                    boolean passThrough = key instanceof IdentifierTree
                            && forwardsWrapperParameter(((IdentifierTree) key).getName().toString());
                    addSite(SiteKind.CALL, line(node), key, passThrough);
                } else {
                    out.sites.add(new KeySite(SiteKind.CALL, line(node), null,
                            args.size() + " arguments: " + node, false));
                }
            }
            return super.visitMethodInvocation(node, p);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree node, Void p) {
            String name = node.getName().toString();
            if (KEY_METHODS.contains(name)) {
                out.sites.add(new KeySite(SiteKind.METHOD_REFERENCE, line(node), null, "::" + name, false));
            }
            return super.visitMemberReference(node, p);
        }

        @Override
        public Void visitAnnotation(AnnotationTree node, Void p) {
            String type = simpleName(node.getAnnotationType());
            for (ExpressionTree arg : node.getArguments()) {
                if (!(arg instanceof AssignmentTree)) {
                    continue;
                }
                AssignmentTree a = (AssignmentTree) arg;
                String element = simpleName(a.getVariable());
                ExpressionTree value = a.getExpression();
                if ("CmdExecutor".equals(type) && "description".equals(element)) {
                    boolean empty = value instanceof LiteralTree && "".equals(((LiteralTree) value).getValue());
                    if (!empty) {
                        addSite(SiteKind.COMMAND_DESCRIPTION, line(a), value, false);
                    }
                } else if ("ConfigEntry".equals(type) && "comment".equals(element)) {
                    new TreeScanner<Void, Void>() {
                        @Override
                        public Void visitLiteral(LiteralTree literal, Void q) {
                            configCommentLiterals.add(literal);
                            return null;
                        }
                    }.scan(value, null);
                } else if ("CmdParam".equals(type) && "suggest".equals(element)
                        && value.getKind() == Tree.Kind.STRING_LITERAL
                        && !"".equals(((LiteralTree) value).getValue())) {
                    suggestLiterals.add(value);
                }
            }
            return super.visitAnnotation(node, p);
        }

        @Override
        public Void visitLiteral(LiteralTree node, Void p) {
            Tree.Kind k = node.getKind();
            if (k == Tree.Kind.STRING_LITERAL || k == Tree.Kind.CHAR_LITERAL) {
                int start = (int) positions.getStartPosition(unit, node);
                int end = (int) positions.getEndPosition(unit, node);
                String written = source.substring(start, end);
                int delimiter = written.startsWith("\"\"\"") ? 3 : 1;
                Literal literal = new Literal(k == Tree.Kind.CHAR_LITERAL, String.valueOf(node.getValue()),
                        written.substring(delimiter, written.length() - delimiter), line(node));
                literal.key = keyLiterals.contains(node);
                literal.configComment = configCommentLiterals.contains(node);
                out.literals.add(literal);
                if (suggestLiterals.contains(node)) {
                    out.suggestCandidates.add(new SuggestCandidate(literal));
                }
            }
            return super.visitLiteral(node, p);
        }

        private void addSite(SiteKind kind, int line, ExpressionTree key, boolean passThrough) {
            boolean literal = key.getKind() == Tree.Kind.STRING_LITERAL;
            if (literal) {
                keyLiterals.add(key);
            }
            out.sites.add(new KeySite(kind, line, literal ? (String) ((LiteralTree) key).getValue() : null,
                    key.toString(), passThrough));
        }

        /**
         * True when the innermost enclosing method is named {@code i18n}, {@code argument} is its
         * last parameter, and its body never assigns that parameter.
         */
        private boolean forwardsWrapperParameter(String argument) {
            MethodTree m = methods.peek();
            if (m == null || !m.getName().contentEquals("i18n") || m.getParameters().isEmpty()
                    || m.getBody() == null) {
                return false;
            }
            String parameter = m.getParameters().get(m.getParameters().size() - 1).getName().toString();
            return parameter.equals(argument) && !assigns(m.getBody(), parameter);
        }
    }

    /** Whether {@code body} assigns {@code name} anywhere outside a nested class. */
    static boolean assigns(Tree body, final String name) {
        final boolean[] found = {false};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree node, Void p) {
                return null;
            }

            @Override
            public Void visitAssignment(AssignmentTree node, Void p) {
                found[0] |= isName(node.getVariable(), name);
                return super.visitAssignment(node, p);
            }

            @Override
            public Void visitCompoundAssignment(CompoundAssignmentTree node, Void p) {
                found[0] |= isName(node.getVariable(), name);
                return super.visitCompoundAssignment(node, p);
            }

            @Override
            public Void visitUnary(UnaryTree node, Void p) {
                Tree.Kind k = node.getKind();
                if (k == Tree.Kind.PREFIX_INCREMENT || k == Tree.Kind.PREFIX_DECREMENT
                        || k == Tree.Kind.POSTFIX_INCREMENT || k == Tree.Kind.POSTFIX_DECREMENT) {
                    found[0] |= isName(node.getExpression(), name);
                }
                return super.visitUnary(node, p);
            }
        }.scan(body, null);
        return found[0];
    }

    private static boolean isName(ExpressionTree t, String name) {
        return t instanceof IdentifierTree && ((IdentifierTree) t).getName().contentEquals(name);
    }

    /** The simple name of a method select or annotation type: {@code a.b.c} gives {@code c}. */
    static String simpleName(Tree t) {
        if (t instanceof IdentifierTree) {
            return ((IdentifierTree) t).getName().toString();
        }
        if (t instanceof MemberSelectTree) {
            return ((MemberSelectTree) t).getIdentifier().toString();
        }
        return null;
    }
}
