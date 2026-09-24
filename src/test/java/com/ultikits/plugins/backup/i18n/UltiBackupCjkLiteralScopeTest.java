package com.ultikits.plugins.backup.i18n;

import com.ultikits.plugins.backup.i18n.I18nSourceScanner.KeySite;
import com.ultikits.plugins.backup.i18n.I18nSourceScanner.Kind;
import com.ultikits.plugins.backup.i18n.I18nSourceScanner.SourceFile;
import com.ultikits.plugins.backup.i18n.I18nSourceScanner.Token;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Language guard 2: no Chinese text in a {@code src/main/java} literal unless it is a catalogue key
 * or listed, with a written reason, in {@code src/test/resources/i18n/cjk-literal-exemptions.tsv}.
 * <p>
 * Detection contract, the same as the framework's {@code .github/scripts/check-cjk-scope.sh}: the
 * CJK Unified Ideographs block, U+4E00 through U+9FFF, and nothing wider. Unlike that script, this
 * guard is about literals, not comments: comments never count, and every string, character and
 * text-block literal counts after its Unicode and escape sequences are decoded.
 * <p>
 * Exemption file format: one line per literal, {@code path<TAB>exact literal<TAB>reason}. The path
 * is relative to the module root; the literal is the text between the quotes exactly as written in
 * the source; the reason is required. Lines starting with {@code #} and blank lines are ignored. An
 * exemption that no longer matches a literal fails the build, so the file cannot drift.
 * <p>
 * This file is copied unchanged into every module; only its package line and class name differ.
 */
@DisplayName("Language guard 2: Chinese literals")
class UltiBackupCjkLiteralScopeTest {

    static final String EXEMPTIONS = "src/test/resources/i18n/cjk-literal-exemptions.tsv";

    private static List<SourceFile> sources;
    private static List<String> exemptionLines;

    @BeforeAll
    static void scan() throws Exception {
        Path root = I18nSourceScanner.moduleRoot();
        sources = I18nSourceScanner.scanMainSources(root);
        Path tsv = root.resolve(EXEMPTIONS);
        assertThat(tsv).as("the exemption file must exist, even with no entries").isRegularFile();
        exemptionLines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
    }

    // ================================================================== the module itself

    @Test
    @DisplayName("control: the scan reached this module's source")
    void scanReachedTheModule() {
        assertThat(sources).isNotEmpty();
        int literals = 0;
        for (SourceFile f : sources) {
            for (Token t : f.tokens) {
                if (t.kind == Kind.STRING) {
                    literals++;
                }
            }
        }
        assertThat(literals).as("string literals seen").isPositive();
    }

    @Test
    @DisplayName("no Chinese literal outside a catalogue key or a written exemption")
    void noChineseLiteralOutsideI18nOrExemption() {
        assertThat(violations(sources, parseExemptions(exemptionLines))).isEmpty();
    }

    @Test
    @DisplayName("every exemption is well formed, has a reason, and still matches a literal")
    void everyExemptionIsWellFormedAndStillMatches() {
        assertThat(exemptionProblems(exemptionLines, sources)).isEmpty();
    }

    // ================================================================== the checks

    /** A literal the exemption file accepts. */
    static final class Exemption {
        final int lineNumber;
        final String path;
        final String literal;
        final String reason;

        Exemption(int lineNumber, String path, String literal, String reason) {
            this.lineNumber = lineNumber;
            this.path = path;
            this.literal = literal;
            this.reason = reason;
        }

        boolean matches(String filePath, Token token) {
            return path.equals(filePath) && literal.equals(token.raw);
        }
    }

    static List<Exemption> parseExemptions(List<String> lines) {
        List<Exemption> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length == 3 && !fields[0].trim().isEmpty() && !fields[1].isEmpty()
                    && !fields[2].trim().isEmpty()) {
                result.add(new Exemption(i + 1, fields[0], fields[1], fields[2]));
            }
        }
        return result;
    }

    static List<String> exemptionProblems(List<String> lines, List<SourceFile> files) {
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != 3) {
                problems.add(EXEMPTIONS + ":" + (i + 1) + " must have three tab-separated fields "
                        + "(path, exact literal, reason), found " + fields.length);
            } else if (fields[0].trim().isEmpty() || fields[1].isEmpty()) {
                problems.add(EXEMPTIONS + ":" + (i + 1) + " must name a path and a literal");
            } else if (fields[2].trim().isEmpty()) {
                problems.add(EXEMPTIONS + ":" + (i + 1) + " has no reason; every exemption needs one");
            }
        }
        for (Exemption e : parseExemptions(lines)) {
            boolean matched = false;
            for (SourceFile f : files) {
                for (Token t : f.tokens) {
                    if ((t.kind == Kind.STRING || t.kind == Kind.CHAR) && e.matches(f.path, t)) {
                        matched = true;
                    }
                }
            }
            if (!matched) {
                problems.add(EXEMPTIONS + ":" + e.lineNumber + " is stale: no literal \"" + e.literal + "\" in "
                        + e.path);
            }
        }
        return problems;
    }

    static List<String> violations(List<SourceFile> files, List<Exemption> exemptions) {
        List<String> problems = new ArrayList<>();
        for (SourceFile f : files) {
            Set<Integer> keyLiterals = new HashSet<>();
            for (KeySite s : f.sites) {
                if (s.literalTokenIndex >= 0) {
                    keyLiterals.add(s.literalTokenIndex);
                }
            }
            for (int i = 0; i < f.tokens.size(); i++) {
                Token t = f.tokens.get(i);
                if ((t.kind != Kind.STRING && t.kind != Kind.CHAR) || !I18nSourceScanner.containsCjk(t.value)
                        || keyLiterals.contains(i)) {
                    continue;
                }
                boolean exempt = false;
                for (Exemption e : exemptions) {
                    exempt |= e.matches(f.path, t);
                }
                if (!exempt) {
                    problems.add(f.path + ":" + t.line + " has Chinese text outside i18n(): \"" + t.raw + "\"");
                }
            }
        }
        return problems;
    }

    // ================================================================== the guard's own behaviour

    private static List<String> check(String body) {
        return check(body, Collections.<String>emptyList());
    }

    private static List<String> check(String body, List<String> exemptionFileLines) {
        SourceFile f = SourceFile.of("src/main/java/Sample.java", "class Sample {\n" + body + "\n}\n");
        return violations(Collections.singletonList(f), parseExemptions(exemptionFileLines));
    }

    private static List<Token> lex(String source) {
        return I18nSourceScanner.lex(source);
    }

    @Nested
    @DisplayName("lexer")
    class Lexer {

        @Test
        @DisplayName("Chinese in a line comment and a block comment does not count")
        void commentsDoNotCount() {
            assertThat(check("// \u4e2d\u6587\n/* \u4e2d\u6587 */\n/** \u4e2d\u6587 */ int x;")).isEmpty();
        }

        @Test
        @DisplayName("Chinese in a plain string literal is reported with its line")
        void plainLiteralIsReported() {
            assertThat(check("String s = \"\u4e2d\u6587\";"))
                    .containsExactly("src/main/java/Sample.java:2 has Chinese text outside i18n(): \"\u4e2d\u6587\"");
        }

        @Test
        @DisplayName("a quote inside a comment does not open a string")
        void quoteInsideComment() {
            assertThat(check("// he said \"\nString s = \"ok\"; // \u4e2d\"")).isEmpty();
        }

        @Test
        @DisplayName("a comment marker inside a string does not open a comment")
        void commentMarkerInsideString() {
            assertThat(check("String s = \"// \u4e2d\"; String t = \"/* \u6587 */\";")).hasSize(2);
        }

        @Test
        @DisplayName("an escaped quote does not end the literal")
        void escapedQuote() {
            List<Token> tokens = lex("String s = \"a\\\"\u4e2d\\\"b\"; int x;");
            assertThat(tokens).filteredOn(t -> t.kind == Kind.STRING).singleElement()
                    .satisfies(t -> assertThat(t.value).isEqualTo("a\"\u4e2d\"b"));
            assertThat(check("String s = \"a\\\"\u4e2d\\\"b\";")).hasSize(1);
        }

        @Test
        @DisplayName("a char literal holding a quote does not open a string")
        void charLiteralQuote() {
            assertThat(check("char q = '\"'; // \u4e2d\nchar r = '\\''; String s = \"ok\";")).isEmpty();
        }

        @Test
        @DisplayName("a Chinese char literal is reported")
        void chineseCharLiteral() {
            assertThat(check("char c = '\u4e2d';")).hasSize(1);
        }

        @Test
        @DisplayName("a Unicode escape standing for a Chinese character is decoded before detection")
        void unicodeEscapeIsDecoded() {
            assertThat(check("String s = \"\\u4e2d\";"))
                    .containsExactly("src/main/java/Sample.java:2 has Chinese text outside i18n(): \"\\u4e2d\"");
            assertThat(check("String s = \"\\uuuu4e2d\";")).hasSize(1);
        }

        @Test
        @DisplayName("an escaped backslash before u is not a Unicode escape")
        void escapedBackslashIsNotAnEscape() {
            List<Token> tokens = lex("String s = \"\\\\u4e2d\";");
            assertThat(tokens).filteredOn(t -> t.kind == Kind.STRING).singleElement()
                    .satisfies(t -> assertThat(t.value).isEqualTo("\\u4e2d"));
            assertThat(check("String s = \"\\\\u4e2d\";")).isEmpty();
        }

        @Test
        @DisplayName("an escaped line break ends a line comment, so the code after it is scanned")
        void escapedLineBreakEndsComment() {
            assertThat(check("// \\u000a String s = \"\u4e2d\";")).hasSize(1);
        }

        @Test
        @DisplayName("an escaped quote in a comment's text does not hide what follows")
        void escapedQuoteInCommentStaysComment() {
            assertThat(check("/* \\u0022 */ String s = \"\u4e2d\";")).hasSize(1);
        }

        @Test
        @DisplayName("octal and standard escapes decode")
        void octalAndStandardEscapes() {
            List<Token> tokens = lex("String s = \"\\101\\t\\n\\0\\377\";");
            assertThat(tokens).filteredOn(t -> t.kind == Kind.STRING).singleElement()
                    .satisfies(t -> assertThat(t.value).isEqualTo("A\t\n\u0000\u00ff"));
        }

        @Test
        @DisplayName("a text block (not Java 8 syntax, lexed anyway) is scanned")
        void textBlock() {
            assertThat(check("String s = \"\"\"\n    \u4e2d\u6587\n    \"\"\";")).hasSize(1);
        }

        @Test
        @DisplayName("two adjacent empty strings are two literals, not a text block")
        void emptyStrings() {
            List<Token> tokens = lex("String s = \"\" + \"\";");
            assertThat(tokens).filteredOn(t -> t.kind == Kind.STRING).hasSize(2);
        }

        @Test
        @DisplayName("an unterminated literal stops the scan instead of being guessed at")
        void unterminatedLiteralThrows() {
            assertThatThrownBy(() -> lex("String s = \"abc;\nint x;"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("line 1");
        }

        @Test
        @DisplayName("line numbers count from the original source")
        void lineNumbers() {
            List<Token> tokens = lex("a\n\nb /* x\n y */ c\r\nd");
            assertThat(tokens).extracting(t -> t.line).containsExactly(1, 3, 4, 5);
        }
    }

    @Nested
    @DisplayName("what is allowed")
    class Allowed {

        @Test
        @DisplayName("the key argument of i18n( is allowed, even split across lines")
        void i18nKeyIsAllowed() {
            assertThat(check("void m() { plugin.i18n(\n\"\u4e2d\u6587\"\n); }")).isEmpty();
        }

        @Test
        @DisplayName("the two-argument form allows its key, not its first argument")
        void twoArgumentForm() {
            assertThat(check("void m() { plugin.i18n(\"zh\", \"\u4e2d\"); }")).isEmpty();
            assertThat(check("void m() { plugin.i18n(\"\u6587\", \"key\"); }")).hasSize(1);
        }

        @Test
        @DisplayName("a Chinese literal concatenated into a key is not a key literal")
        void concatenatedIsNotAKey() {
            assertThat(check("void m() { plugin.i18n(\"\u4e2d\" + x); }")).hasSize(1);
        }

        @Test
        @DisplayName("a Chinese literal passed to a method that is not i18n is reported")
        void otherMethodIsReported() {
            assertThat(check("void m() { player.sendMessage(\"\u4e2d\"); log(i18n2(\"\u6587\")); }")).hasSize(2);
        }

        @Test
        @DisplayName("an exemption with a reason allows exactly its literal in its file")
        void exemptionAllows() {
            List<String> tsv = Arrays.asList("# comment", "", "src/main/java/Sample.java\t\u4e2d\tfile header");
            assertThat(check("String s = \"\u4e2d\"; String t = \"\u6587\";", tsv))
                    .singleElement().asString().contains("\"\u6587\"");
        }

        @Test
        @DisplayName("an exemption without a reason, or with the wrong shape, is reported and allows nothing")
        void malformedExemptions() {
            SourceFile f = SourceFile.of("src/main/java/Sample.java", "class S { String s = \"\u4e2d\"; }");
            List<String> tsv = Arrays.asList("src/main/java/Sample.java\t\u4e2d\t ", "only-one-field");
            assertThat(exemptionProblems(tsv, Collections.singletonList(f))).hasSize(2);
            assertThat(violations(Collections.singletonList(f), parseExemptions(tsv))).hasSize(1);
        }

        @Test
        @DisplayName("an exemption that matches no literal is stale")
        void staleExemption() {
            SourceFile f = SourceFile.of("src/main/java/Sample.java", "class S { String s = \"ok\"; }");
            List<String> tsv = Collections.singletonList("src/main/java/Sample.java\t\u4e2d\tgone");
            assertThat(exemptionProblems(tsv, Collections.singletonList(f)))
                    .singleElement().asString().contains("is stale");
        }
    }
}
