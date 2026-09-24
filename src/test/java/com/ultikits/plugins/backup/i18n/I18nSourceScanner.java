package com.ultikits.plugins.backup.i18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads Java source the way {@code javac} does, for the two language guards.
 * <p>
 * Both guards ({@code UltiBackupLanguageCatalogueTest} and {@code UltiBackupCjkLiteralScopeTest})
 * read {@code src/main/java} through this one class, so they can never disagree about what is a
 * comment, what is a string literal, or which literal is the key of an {@code i18n(...)} call.
 * <p>
 * Fidelity points, each pinned by a unit test:
 * <ul>
 *   <li>Unicode escapes (a backslash, one or more u, four hex digits) are translated before anything else, as JLS 3.3
 *       requires. A Chinese character written as an escape is therefore still a Chinese character,
 *       and an escaped line terminator inside a {@code //} comment ends that comment.</li>
 *   <li>Comments are skipped; a quote inside a comment does not open a string, and a comment marker
 *       inside a string does not open a comment.</li>
 *   <li>String, character and text-block literals are decoded (escape sequences included) before
 *       Chinese detection; the raw text as written is kept for exemption matching.</li>
 *   <li>A malformed literal (an unterminated string, an unknown escape) throws rather than being
 *       guessed at: a guard that silently mis-lexes is worse than one that stops.</li>
 * </ul>
 * <p>
 * This file is copied unchanged into every module; only its package line differs.
 */
final class I18nSourceScanner {

    /** The Unicode block both guards detect: CJK Unified Ideographs, U+4E00 through U+9FFF. */
    static final char CJK_FIRST = (char) 0x4E00;
    static final char CJK_LAST = (char) 0x9FFF;

    /** Method names whose argument is a catalogue key. */
    static final Set<String> KEY_METHODS = new HashSet<>(Arrays.asList("i18n", "getLocalizedText"));

    /** Keywords that can precede a call but never a method's return type. */
    private static final Set<String> KEYWORDS_BEFORE_CALL = new HashSet<>(Arrays.asList(
            "return", "throw", "else", "case", "yield", "assert", "new", "do", "instanceof"));

    private I18nSourceScanner() {
    }

    enum Kind { IDENT, STRING, CHAR, NUMBER, PUNCT }

    /** One token. {@code value} is decoded for literals; {@code raw} is the text as written. */
    static final class Token {
        final Kind kind;
        final String value;
        final String raw;
        final int line;

        Token(Kind kind, String value, String raw, int line) {
            this.kind = kind;
            this.value = value;
            this.raw = raw;
            this.line = line;
        }

        boolean is(Kind k, String v) {
            return kind == k && value.equals(v);
        }

        boolean isPunct(String v) {
            return is(Kind.PUNCT, v);
        }

        @Override
        public String toString() {
            return kind + "(" + raw + ")@" + line;
        }
    }

    /** Where a catalogue key reaches the framework. */
    enum SiteKind {
        /** {@code i18n(...)} or {@code getLocalizedText(...)}. */
        CALL,
        /** {@code ::i18n} -- the keys come from wherever the function is applied. */
        METHOD_REFERENCE,
        /** {@code @CmdExecutor(description = ...)} -- {@code CommandManager} passes it through {@code i18n}. */
        COMMAND_DESCRIPTION
    }

    /** One place a key reaches the framework's catalogue lookup. */
    static final class KeySite {
        final SiteKind kind;
        final int line;
        /** The key when it is written as one string literal, otherwise {@code null}. */
        final String literalKey;
        /** The key argument's source, normalised, used to match an enumerated dynamic site. */
        final String expression;
        /** Token index of the literal key, or -1. */
        final int literalTokenIndex;
        /** A wrapper named {@code i18n} forwarding its own key parameter unchanged. */
        final boolean passThrough;

        KeySite(SiteKind kind, int line, String literalKey, String expression, int literalTokenIndex,
                boolean passThrough) {
            this.kind = kind;
            this.line = line;
            this.literalKey = literalKey;
            this.expression = expression;
            this.literalTokenIndex = literalTokenIndex;
            this.passThrough = passThrough;
        }

        boolean isLiteral() {
            return literalKey != null;
        }
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

    // ------------------------------------------------------------------ lexing

    /** Tokenises Java source. Comments produce no tokens. */
    static List<Token> lex(String source) {
        Translated t = translateUnicodeEscapes(source);
        int[] lineStarts = lineStarts(source);
        List<Token> tokens = new ArrayList<>();
        String s = t.text;
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n' && s.charAt(i) != '\r') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                if (end < 0) {
                    throw error(source, lineStarts, t.origStart[i], "unterminated block comment");
                }
                i = end + 2;
            } else if (c == '"' && s.startsWith("\"\"\"", i)) {
                i = lexTextBlock(source, lineStarts, t, i, tokens);
            } else if (c == '"' || c == '\'') {
                i = lexQuoted(source, lineStarts, t, i, c, tokens);
            } else if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < n && Character.isJavaIdentifierPart(s.charAt(i))) {
                    i++;
                }
                String word = s.substring(start, i);
                tokens.add(new Token(Kind.IDENT, word, word, line(lineStarts, t.origStart[start])));
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_'
                        || s.charAt(i) == '.')) {
                    i++;
                }
                String num = s.substring(start, i);
                tokens.add(new Token(Kind.NUMBER, num, num, line(lineStarts, t.origStart[start])));
            } else {
                String p = String.valueOf(c);
                tokens.add(new Token(Kind.PUNCT, p, p, line(lineStarts, t.origStart[i])));
                i++;
            }
        }
        return tokens;
    }

    private static int lexQuoted(String source, int[] lineStarts, Translated t, int open, char quote,
                                 List<Token> tokens) {
        String s = t.text;
        StringBuilder value = new StringBuilder();
        int i = open + 1;
        while (true) {
            if (i >= s.length() || s.charAt(i) == '\n' || s.charAt(i) == '\r') {
                throw error(source, lineStarts, t.origStart[open], "unterminated literal");
            }
            char c = s.charAt(i);
            if (c == quote) {
                break;
            }
            if (c == '\\') {
                i = decodeEscape(source, lineStarts, t, i, value, false);
            } else {
                value.append(c);
                i++;
            }
        }
        String raw = source.substring(t.origEnd[open], t.origStart[i]);
        tokens.add(new Token(quote == '"' ? Kind.STRING : Kind.CHAR, value.toString(), raw,
                line(lineStarts, t.origStart[open])));
        return i + 1;
    }

    private static int lexTextBlock(String source, int[] lineStarts, Translated t, int open,
                                    List<Token> tokens) {
        String s = t.text;
        int i = open + 3;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\f')) {
            i++;
        }
        if (i >= s.length() || (s.charAt(i) != '\n' && s.charAt(i) != '\r')) {
            throw error(source, lineStarts, t.origStart[open], "text block must start with a line break");
        }
        int contentStart = i;
        StringBuilder value = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw error(source, lineStarts, t.origStart[open], "unterminated text block");
            }
            if (s.startsWith("\"\"\"", i)) {
                break;
            }
            char c = s.charAt(i);
            if (c == '\\') {
                i = decodeEscape(source, lineStarts, t, i, value, true);
            } else {
                value.append(c);
                i++;
            }
        }
        String raw = source.substring(t.origStart[contentStart], t.origStart[i]);
        tokens.add(new Token(Kind.STRING, value.toString(), raw, line(lineStarts, t.origStart[open])));
        return i + 3;
    }

    /** Decodes the escape starting at {@code i} (a backslash); returns the index after it. */
    private static int decodeEscape(String source, int[] lineStarts, Translated t, int i,
                                    StringBuilder value, boolean textBlock) {
        String s = t.text;
        if (i + 1 >= s.length()) {
            throw error(source, lineStarts, t.origStart[i], "dangling backslash");
        }
        char e = s.charAt(i + 1);
        switch (e) {
            case 'b': value.append('\b'); return i + 2;
            case 't': value.append('\t'); return i + 2;
            case 'n': value.append('\n'); return i + 2;
            case 'f': value.append('\f'); return i + 2;
            case 'r': value.append('\r'); return i + 2;
            case 's': value.append(' '); return i + 2;
            case '"': value.append('"'); return i + 2;
            case '\'': value.append('\''); return i + 2;
            case '\\': value.append('\\'); return i + 2;
            default:
                break;
        }
        if (textBlock && (e == '\n' || e == '\r')) {
            int j = i + 2;
            if (e == '\r' && j < s.length() && s.charAt(j) == '\n') {
                j++;
            }
            return j;
        }
        if (e >= '0' && e <= '7') {
            int j = i + 1;
            int max = e <= '3' ? 3 : 2;
            int code = 0;
            int digits = 0;
            while (j < s.length() && digits < max && s.charAt(j) >= '0' && s.charAt(j) <= '7') {
                code = code * 8 + (s.charAt(j) - '0');
                j++;
                digits++;
            }
            value.append((char) code);
            return j;
        }
        throw error(source, lineStarts, t.origStart[i], "unknown escape \\" + e);
    }

    /** JLS 3.3 translation, keeping each output character's source span. */
    static final class Translated {
        final String text;
        final int[] origStart;
        final int[] origEnd;

        Translated(String text, int[] origStart, int[] origEnd) {
            this.text = text;
            this.origStart = origStart;
            this.origEnd = origEnd;
        }
    }

    static Translated translateUnicodeEscapes(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int[] start = new int[source.length() + 1];
        int[] end = new int[source.length() + 1];
        int backslashRun = 0;
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\' && backslashRun % 2 == 0 && i + 1 < source.length() && source.charAt(i + 1) == 'u') {
                int j = i + 1;
                while (j < source.length() && source.charAt(j) == 'u') {
                    j++;
                }
                if (j + 4 > source.length()) {
                    throw new IllegalStateException("illegal unicode escape at offset " + i);
                }
                String hex = source.substring(j, j + 4);
                int code;
                try {
                    code = Integer.parseInt(hex, 16);
                } catch (NumberFormatException ex) {
                    throw new IllegalStateException("illegal unicode escape at offset " + i, ex);
                }
                start[out.length()] = i;
                end[out.length()] = j + 4;
                out.append((char) code);
                i = j + 4;
                backslashRun = 0;
            } else {
                start[out.length()] = i;
                end[out.length()] = i + 1;
                out.append(c);
                backslashRun = c == '\\' ? backslashRun + 1 : 0;
                i++;
            }
        }
        start[out.length()] = source.length();
        end[out.length()] = source.length();
        return new Translated(out.toString(), start, end);
    }

    private static int[] lineStarts(String source) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 >= source.length() || source.charAt(i + 1) != '\n'))) {
                starts.add(i + 1);
            }
        }
        int[] result = new int[starts.size()];
        for (int k = 0; k < result.length; k++) {
            result[k] = starts.get(k);
        }
        return result;
    }

    private static int line(int[] lineStarts, int offset) {
        int idx = Arrays.binarySearch(lineStarts, offset);
        return idx >= 0 ? idx + 1 : -idx - 1;
    }

    private static IllegalStateException error(String source, int[] lineStarts, int offset, String what) {
        return new IllegalStateException(what + " at line " + line(lineStarts, offset));
    }

    // ------------------------------------------------------------------ key sites

    /** Every place a key reaches the catalogue lookup, in token order. */
    static List<KeySite> keySites(List<Token> tokens) {
        List<int[]> wrapperBodies = new ArrayList<>();
        List<String> wrapperParams = new ArrayList<>();
        for (int i = 0; i + 1 < tokens.size(); i++) {
            Token tok = tokens.get(i);
            if (tok.kind == Kind.IDENT && tok.value.equals("i18n") && tokens.get(i + 1).isPunct("(")
                    && isDeclaration(tokens, i)) {
                int close = matching(tokens, i + 1);
                if (close + 1 < tokens.size() && tokens.get(close + 1).isPunct("{")
                        && tokens.get(close - 1).kind == Kind.IDENT) {
                    wrapperBodies.add(new int[]{close + 1, matching(tokens, close + 1)});
                    wrapperParams.add(tokens.get(close - 1).value);
                }
            }
        }

        List<KeySite> sites = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token tok = tokens.get(i);
            if (tok.kind == Kind.IDENT && KEY_METHODS.contains(tok.value)) {
                if (i >= 2 && tokens.get(i - 1).isPunct(":") && tokens.get(i - 2).isPunct(":")) {
                    sites.add(new KeySite(SiteKind.METHOD_REFERENCE, tok.line, null, "::" + tok.value, -1, false));
                } else if (i + 1 < tokens.size() && tokens.get(i + 1).isPunct("(") && !isDeclaration(tokens, i)) {
                    int close = matching(tokens, i + 1);
                    List<int[]> args = splitArguments(tokens, i + 1, close);
                    int[] key = args.isEmpty() ? new int[]{i + 2, i + 2} : args.get(args.size() - 1);
                    boolean recognisedShape = args.size() == 1 || args.size() == 2;
                    boolean passThrough = recognisedShape && key[1] - key[0] == 1
                            && tokens.get(key[0]).kind == Kind.IDENT
                            && insideWrapperForwarding(i, tokens.get(key[0]).value, wrapperBodies, wrapperParams);
                    sites.add(site(SiteKind.CALL, tok.line, tokens,
                            recognisedShape ? key : new int[]{i + 2, close}, passThrough));
                }
            } else if (tok.isPunct("@") && i + 1 < tokens.size()) {
                int nameEnd = i + 1;
                while (nameEnd + 2 < tokens.size() && tokens.get(nameEnd + 1).isPunct(".")
                        && tokens.get(nameEnd + 2).kind == Kind.IDENT) {
                    nameEnd += 2;
                }
                if (tokens.get(nameEnd).is(Kind.IDENT, "CmdExecutor") && nameEnd + 1 < tokens.size()
                        && tokens.get(nameEnd + 1).isPunct("(")) {
                    int close = matching(tokens, nameEnd + 1);
                    for (int[] arg : splitArguments(tokens, nameEnd + 1, close)) {
                        if (arg[1] - arg[0] >= 3 && tokens.get(arg[0]).is(Kind.IDENT, "description")
                                && tokens.get(arg[0] + 1).isPunct("=")) {
                            int[] value = new int[]{arg[0] + 2, arg[1]};
                            boolean emptyLiteral = value[1] - value[0] == 1
                                    && tokens.get(value[0]).kind == Kind.STRING
                                    && tokens.get(value[0]).value.isEmpty();
                            if (!emptyLiteral) {
                                sites.add(site(SiteKind.COMMAND_DESCRIPTION, tokens.get(arg[0]).line, tokens,
                                        value, false));
                            }
                        }
                    }
                }
            }
        }
        return sites;
    }

    private static KeySite site(SiteKind kind, int line, List<Token> tokens, int[] range, boolean passThrough) {
        boolean literal = range[1] - range[0] == 1 && tokens.get(range[0]).kind == Kind.STRING;
        return new KeySite(kind, line, literal ? tokens.get(range[0]).value : null,
                normalise(tokens, range[0], range[1]), literal ? range[0] : -1, passThrough);
    }

    private static boolean insideWrapperForwarding(int callIndex, String argument, List<int[]> bodies,
                                                   List<String> params) {
        for (int k = 0; k < bodies.size(); k++) {
            int[] body = bodies.get(k);
            if (callIndex > body[0] && callIndex < body[1] && params.get(k).equals(argument)) {
                return true;
            }
        }
        return false;
    }

    /** True when the name at {@code i} is being declared (a return type precedes it), not called. */
    static boolean isDeclaration(List<Token> tokens, int i) {
        if (i == 0) {
            return false;
        }
        Token prev = tokens.get(i - 1);
        if (prev.kind == Kind.IDENT) {
            return !KEYWORDS_BEFORE_CALL.contains(prev.value);
        }
        if (prev.isPunct("]")) {
            return true;
        }
        if (prev.isPunct(">")) {
            int depth = 0;
            for (int j = i - 1; j >= 0; j--) {
                if (tokens.get(j).isPunct(">")) {
                    depth++;
                } else if (tokens.get(j).isPunct("<")) {
                    depth--;
                    if (depth == 0) {
                        return j == 0 || !tokens.get(j - 1).isPunct(".");
                    }
                }
            }
        }
        return false;
    }

    /** Index of the bracket closing the one at {@code open}. */
    static int matching(List<Token> tokens, int open) {
        int depth = 0;
        for (int j = open; j < tokens.size(); j++) {
            Token t = tokens.get(j);
            if (t.isPunct("(") || t.isPunct("[") || t.isPunct("{")) {
                depth++;
            } else if (t.isPunct(")") || t.isPunct("]") || t.isPunct("}")) {
                depth--;
                if (depth == 0) {
                    return j;
                }
            }
        }
        throw new IllegalStateException("unbalanced bracket opened at line " + tokens.get(open).line);
    }

    /** Top-level argument ranges {@code [from, to)} between {@code open} and {@code close}. */
    static List<int[]> splitArguments(List<Token> tokens, int open, int close) {
        if (close == open + 1) {
            return Collections.emptyList();
        }
        List<int[]> args = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        for (int j = open + 1; j < close; j++) {
            Token t = tokens.get(j);
            if (t.isPunct("(") || t.isPunct("[") || t.isPunct("{")) {
                depth++;
            } else if (t.isPunct(")") || t.isPunct("]") || t.isPunct("}")) {
                depth--;
            } else if (depth == 0 && t.isPunct(",")) {
                args.add(new int[]{start, j});
                start = j + 1;
            }
        }
        args.add(new int[]{start, close});
        return args;
    }

    // ------------------------------------------------------------------ the module tree

    /** One scanned {@code .java} file. {@code path} is relative to the module root, with {@code /}. */
    static final class SourceFile {
        final String path;
        final List<Token> tokens;
        final List<KeySite> sites;

        SourceFile(String path, List<Token> tokens) {
            this.path = path;
            this.tokens = tokens;
            this.sites = keySites(tokens);
        }

        static SourceFile of(String path, String source) {
            try {
                return new SourceFile(path, lex(source));
            } catch (IllegalStateException e) {
                throw new IllegalStateException(path + ": " + e.getMessage(), e);
            }
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
            result.add(SourceFile.of(rel, new String(Files.readAllBytes(p), StandardCharsets.UTF_8)));
        }
        return result;
    }

    /** Source of {@code tokens[from, to)} with insignificant whitespace removed. */
    static String normalise(List<Token> tokens, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int j = from; j < to; j++) {
            Token t = tokens.get(j);
            boolean word = t.kind == Kind.IDENT || t.kind == Kind.NUMBER;
            if (word && j > from) {
                Token p = tokens.get(j - 1);
                if (p.kind == Kind.IDENT || p.kind == Kind.NUMBER) {
                    sb.append(' ');
                }
            }
            if (t.kind == Kind.STRING) {
                sb.append('"').append(t.raw).append('"');
            } else if (t.kind == Kind.CHAR) {
                sb.append('\'').append(t.raw).append('\'');
            } else {
                sb.append(t.raw);
            }
        }
        return sb.toString();
    }
}
