package com.ultikits.plugins.backup.i18n;

import com.ultikits.plugins.backup.UltiBackup;
import com.ultikits.plugins.backup.i18n.I18nSourceScanner.KeySite;
import com.ultikits.plugins.backup.i18n.I18nSourceScanner.SiteKind;
import com.ultikits.plugins.backup.i18n.I18nSourceScanner.SourceFile;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSuggest;
import com.ultikits.ultitools.entities.Language;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Language guard 1: every key this module hands to the catalogue exists in every catalogue the
 * framework would load, and every catalogue entry is reachable from the source.
 * <p>
 * A missing key renders as the key itself and logs nothing ({@code Language#getLocalizedText}), so
 * without this test the defect is silent. A catalogue entry no code can produce is the other half
 * of the same class: text that was written to be shown and never is (UltiKits/UltiBackup#15).
 * <p>
 * Fidelity: catalogues are parsed by the framework's own {@link Language} ({@code fromYaml}
 * flattens nested YAML with {@code getKeys(true)} and keeps only string leaves; JSON goes through
 * its Gson constructor), located where {@code UltiToolsPlugin#loadLanguageFromJar} looks (the
 * module's own code source, {@code lang/<code><ext>}), in the framework's own extension order
 * (read from {@code UltiToolsPlugin.LANGUAGE_EXTENSIONS}). Nothing about the loader is copied.
 * <p>
 * Module-specific parts are the three constants below; the rest of this file is copied unchanged.
 */
@DisplayName("Language guard 1: catalogue keys")
class UltiBackupLanguageCatalogueTest {

    /** The module's main class; its code source is where the framework reads the catalogues. */
    private static final Class<?> MAIN_CLASS = UltiBackup.class;

    /** Language codes every module must ship. Any other code found in {@code lang/} is checked too. */
    private static final List<String> REQUIRED_CODES = Arrays.asList("en", "zh");

    /**
     * Every key site whose key is not one string literal, with every key it can produce.
     * <p>
     * A wrapper method named {@code i18n} that forwards its own key parameter unchanged needs no
     * entry: its callers are themselves {@code i18n(} calls and are checked one by one.
     */
    static final List<DynamicSite> DYNAMIC_KEY_SITES = Collections.unmodifiableList(Arrays.asList(
            new DynamicSite("src/main/java/com/ultikits/plugins/backup/entity/BackupMetadata.java",
                    "getReasonKey()",
                    "BackupMetadata#getReasonKey maps the five stored reason codes to their keys and "
                            + "everything else to backup.reason.unknown",
                    "backup.reason.manual", "backup.reason.auto", "backup.reason.death",
                    "backup.reason.quit", "backup.reason.admin", "backup.reason.unknown")
    ));

    private static List<SourceFile> sources;
    private static I18nSourceScanner.SuggestScan suggestScan;
    private static List<Catalogue> catalogues;

    @BeforeAll
    static void scan() throws Exception {
        sources = new ArrayList<>(I18nSourceScanner.scanMainSources(I18nSourceScanner.moduleRoot()));
        suggestScan = I18nSourceScanner.scanCompiledSuggestValues(I18nSourceScanner.moduleRoot());
        sources.addAll(suggestScan.hints);
        catalogues = loadModuleCatalogues();
    }

    // ================================================================== the module itself

    @Test
    @DisplayName("control: the scan reached this module's source and catalogues")
    void scanReachedTheModule() {
        String mainPath = "src/main/java/" + MAIN_CLASS.getName().replace('.', '/') + ".java";
        assertThat(sources).extracting(f -> f.path).contains(mainPath);
        int sites = 0;
        for (SourceFile f : sources) {
            sites += f.sites.size();
        }
        assertThat(sites).as("key sites found").isPositive();
        assertThat(catalogues).extracting(c -> c.code).containsAll(REQUIRED_CODES);
        for (Catalogue c : catalogues) {
            assertThat(c.entries).as(c.fileName + " entries").isNotEmpty();
        }
    }

    @Test
    @DisplayName("control: the compiled scan saw every @CmdParam(suggest = ...) the source declares")
    void suggestScanSawEverySuggestValue() {
        int declared = 0;
        for (SourceFile f : sources) {
            declared += f.suggestAttributes;
        }
        assertThat(suggestScan.seen).as("suggest values on the compiled classes").hasSize(declared);
    }

    @Test
    @DisplayName("each language code has exactly one catalogue, and en and zh exist")
    void eachLanguageHasExactlyOneCatalogue() throws Exception {
        assertThat(shadowedOrMissingCatalogues(catalogueDirectory(), languageExtensions(), REQUIRED_CODES))
                .isEmpty();
    }

    @Test
    @DisplayName("every literal key exists in every catalogue")
    void everyLiteralKeyExistsInEveryCatalogue() {
        assertThat(missingLiteralKeys(sources, catalogues)).isEmpty();
    }

    @Test
    @DisplayName("every non-literal key site is enumerated, and no enumeration is stale")
    void everyNonLiteralKeySiteIsEnumerated() {
        assertThat(unlistedOrStaleDynamicSites(sources, DYNAMIC_KEY_SITES)).isEmpty();
    }

    @Test
    @DisplayName("every enumerated dynamic key exists in every catalogue")
    void everyEnumeratedDynamicKeyExistsInEveryCatalogue() {
        assertThat(missingDynamicKeys(DYNAMIC_KEY_SITES, catalogues)).isEmpty();
    }

    @Test
    @DisplayName("every catalogue key is reachable from the source")
    void everyCatalogueKeyIsReachableFromSource() {
        assertThat(unreachableCatalogueKeys(sources, DYNAMIC_KEY_SITES, catalogues)).isEmpty();
    }

    @Test
    @DisplayName("no key is a Chinese sentence")
    void noKeyIsAChineseSentence() {
        assertThat(chineseKeys(sources, catalogues)).isEmpty();
    }

    @Test
    @DisplayName("the English catalogue holds no Chinese text")
    void englishCatalogueHoldsNoChineseText() {
        assertThat(chineseTextInEnglish(catalogues)).isEmpty();
    }

    @Test
    @DisplayName("every key has the same placeholders in every language")
    void placeholdersMatchAcrossLanguages() {
        assertThat(placeholderMismatches(catalogues)).isEmpty();
    }

    // ================================================================== the checks

    static List<String> missingLiteralKeys(List<SourceFile> files, List<Catalogue> cats) {
        List<String> problems = new ArrayList<>();
        for (SourceFile f : files) {
            for (KeySite s : f.sites) {
                if (!s.isLiteral()) {
                    continue;
                }
                for (Catalogue c : cats) {
                    if (!c.entries.containsKey(s.literalKey)) {
                        problems.add("key \"" + s.literalKey + "\" (" + f.path + ":" + s.line + ") is missing from "
                                + c.fileName);
                    }
                }
            }
        }
        return problems;
    }

    static List<String> unlistedOrStaleDynamicSites(List<SourceFile> files, List<DynamicSite> table) {
        List<String> problems = new ArrayList<>();
        // Sites and entries pair up by (file, expression), one entry per site: two sites printing the
        // same expression in one file need two entries, so an entry written for one site cannot
        // silently cover a site added later whose keys nobody enumerated.
        Map<String, List<Integer>> siteLines = new LinkedHashMap<>();
        for (SourceFile f : files) {
            for (KeySite s : f.sites) {
                if (s.isLiteral() || s.passThrough) {
                    continue;
                }
                if (findAll(table, f.path, s.expression).isEmpty()) {
                    problems.add(f.path + ":" + s.line + " passes a non-literal key (" + s.kind + " "
                            + s.expression + ") that DYNAMIC_KEY_SITES does not enumerate");
                } else {
                    siteLines.computeIfAbsent(f.path + SEP + s.expression, k -> new ArrayList<>()).add(s.line);
                }
            }
        }
        for (Map.Entry<String, List<Integer>> e : siteLines.entrySet()) {
            String[] fileAndExpression = e.getKey().split(SEP, 2);
            int listed = findAll(table, fileAndExpression[0], fileAndExpression[1]).size();
            if (listed != e.getValue().size()) {
                problems.add(fileAndExpression[0] + " has " + e.getValue().size() + " non-literal key sites printing "
                        + fileAndExpression[1] + " (lines " + e.getValue() + ") but DYNAMIC_KEY_SITES lists " + listed
                        + "; list each site once, with its own keys");
            }
        }
        for (DynamicSite d : table) {
            if (!siteLines.containsKey(d.file + SEP + d.expression)) {
                problems.add("stale DYNAMIC_KEY_SITES entry: " + d.file + " " + d.expression
                        + " matches no key site");
            }
            if (d.keys.isEmpty() || d.reason.trim().isEmpty()) {
                problems.add("DYNAMIC_KEY_SITES entry " + d.file + " " + d.expression
                        + " must list its keys and say why they are the complete set");
            }
        }
        return problems;
    }

    static List<String> missingDynamicKeys(List<DynamicSite> table, List<Catalogue> cats) {
        List<String> problems = new ArrayList<>();
        for (DynamicSite d : table) {
            for (String key : d.keys) {
                for (Catalogue c : cats) {
                    if (!c.entries.containsKey(key)) {
                        problems.add("key \"" + key + "\" (enumerated for " + d.file + " " + d.expression
                                + ") is missing from " + c.fileName);
                    }
                }
            }
        }
        return problems;
    }

    static List<String> unreachableCatalogueKeys(List<SourceFile> files, List<DynamicSite> table,
                                                 List<Catalogue> cats) {
        Set<String> reachable = new TreeSet<>();
        for (SourceFile f : files) {
            for (KeySite s : f.sites) {
                if (s.isLiteral()) {
                    reachable.add(s.literalKey);
                }
            }
        }
        for (DynamicSite d : matchedDynamicSites(files, table)) {
            reachable.addAll(d.keys);
        }
        List<String> problems = new ArrayList<>();
        for (Catalogue c : cats) {
            for (String key : new TreeSet<>(c.entries.keySet())) {
                if (!reachable.contains(key)) {
                    problems.add(c.fileName + " declares \"" + key + "\", which no code can produce");
                }
            }
        }
        return problems;
    }

    static List<String> chineseKeys(List<SourceFile> files, List<Catalogue> cats) {
        List<String> problems = new ArrayList<>();
        for (SourceFile f : files) {
            for (KeySite s : f.sites) {
                if (s.isLiteral() && I18nSourceScanner.containsCjk(s.literalKey)) {
                    problems.add(f.path + ":" + s.line + " uses a Chinese key \"" + s.literalKey + "\"");
                }
            }
        }
        for (Catalogue c : cats) {
            for (String key : c.entries.keySet()) {
                if (I18nSourceScanner.containsCjk(key)) {
                    problems.add(c.fileName + " declares a Chinese key \"" + key + "\"");
                }
            }
        }
        return problems;
    }

    static List<String> chineseTextInEnglish(List<Catalogue> cats) {
        List<String> problems = new ArrayList<>();
        for (Catalogue c : cats) {
            if (!c.code.equals("en")) {
                continue;
            }
            for (Map.Entry<String, String> e : c.entries.entrySet()) {
                if (e.getValue() != null && I18nSourceScanner.containsCjk(e.getValue())) {
                    problems.add(c.fileName + " gives \"" + e.getKey() + "\" Chinese text: \"" + e.getValue() + "\"");
                }
            }
        }
        return problems;
    }

    /**
     * The placeholders of {@code text}, sorted, each {@code java.util.Formatter} specifier written as
     * the argument it formats and how: {@code arg2:%s}, {@code arg1:%.2f}.
     * <p>
     * The argument follows {@code Formatter}'s own indexing rules. An explicit index ({@code %2$s})
     * names it. An ordinary specifier takes the next one in a count of ordinary specifiers only, which
     * is independent of explicit and relative ones. A relative specifier ({@code %<s}) re-uses the
     * argument of the specifier before it; with none before it, it is written {@code arg?}, which no
     * valid translation matches. Two languages agree exactly when every argument is formatted the same
     * way the same number of times, wherever the translation puts it: {@code "Took %2$s from %1$s"} and
     * {@code "%1$s ... %2$s"} agree, and {@code "%s has %d"} and {@code "%d ... %s"} do not, because the
     * second binds the first argument to {@code %d}.
     * <p>
     * The bare {@code {}} marker SLF4J and {@code PluginLogger} fill in order is all one kind, so only
     * their number counts. Named tokens ({@code {PLAYER}}, {@code {0}}, {@code %player_name%}) are kept
     * as written.
     * <p>
     * Every {@code %} in the text belongs to exactly one token: a named token, a specifier, {@code %%},
     * {@code %n}, or, when it starts none of these, a lone {@code %} that counts as itself. So no
     * percent sign can be dropped or added without changing the list. That settles the cases no single
     * reading can: a one-character {@code %x%} is read as {@code Formatter} reads it, {@code %x} then a
     * lone {@code %}, because a one-character named token cannot be told apart from two adjacent
     * specifiers such as {@code %s%s}. A dropped closing {@code %} still changes the count.
     */
    static List<String> placeholders(String text) {
        List<String> found = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(text == null ? "" : text);
        int ordinary = 0;
        String previous = null;
        while (m.find()) {
            if (m.group("named") != null) {
                found.add(m.group("named"));
                continue;
            }
            if (m.group("conversion") == null) {
                found.add(m.group()); // %%, %n, or a lone %
                continue;
            }
            String flags = m.group("flags");
            String argument;
            if (flags.indexOf('<') >= 0) {
                argument = previous == null ? "?" : previous;
            } else if (m.group("index") != null) {
                argument = m.group("index");
            } else {
                argument = String.valueOf(++ordinary);
            }
            previous = "?".equals(argument) ? previous : argument;
            found.add("arg" + argument + ":%" + flags.replace("<", "") + m.group("conversion"));
        }
        Collections.sort(found);
        return found;
    }

    /**
     * {@code {NAME}}/{@code {0}} tokens, the bare {@code {}} argument marker SLF4J and the framework's
     * {@code PluginLogger} fill in order, {@code %NAME%} tokens a caller replaces by name
     * ({@code %player_name%}), {@code %%}, {@code %n}, and every {@code java.util.Formatter} specifier
     * that takes an argument: {@code %[index$][flags][width][.precision]conversion} with any general,
     * character, integral or floating-point conversion, or the {@code t}/{@code T} date/time prefix
     * and its suffix letter. A {@code %NAME%} token is tried first, so {@code %online%} is one named
     * token and not {@code %o} followed by prose. A letter right after the conversion does not end it
     * early: {@code Formatter} reads {@code "%dh"} as {@code %d} then {@code h}, so the pattern does too.
     * <p>
     * The space flag is not matched, so prose such as "100% of" is not read as the specifier
     * {@code "% o"}; its {@code %} is a lone {@code %} instead. A dropped space-flagged specifier such as
     * {@code "% d"} therefore still changes the count of lone {@code %}. What is not reported is one
     * space-flagged conversion changed into another. No module catalogue uses a space-flagged specifier
     * (measured 2026-09-24: 0 of 3,064 entries across the fifteen module repositories).
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("(?<named>\\{[A-Za-z0-9_]*}|%[A-Za-z_][A-Za-z0-9_]+%)"
            + "|%%|%n|%(?:(?<index>\\d+)\\$)?(?<flags>[-#+0,(<]*)"
            + "(?<conversion>\\d*(?:\\.\\d+)?(?:[tT][a-zA-Z]|[bBhHsScCdoxXeEfgGaA]))|%");

    static List<String> placeholderMismatches(List<Catalogue> cats) {
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < cats.size(); i++) {
            for (int j = i + 1; j < cats.size(); j++) {
                Catalogue a = cats.get(i);
                Catalogue b = cats.get(j);
                for (String key : new TreeSet<>(a.entries.keySet())) {
                    if (!b.entries.containsKey(key)) {
                        continue;
                    }
                    List<String> pa = placeholders(a.entries.get(key));
                    List<String> pb = placeholders(b.entries.get(key));
                    if (!pa.equals(pb)) {
                        problems.add("\"" + key + "\" has placeholders " + pa + " in " + a.fileName + " but " + pb
                                + " in " + b.fileName);
                    }
                }
            }
        }
        return problems;
    }

    static List<String> shadowedOrMissingCatalogues(File langDir, String[] extensions, List<String> required) {
        Map<String, List<File>> byCode = catalogueFilesByCode(langDir, extensions);
        List<String> problems = new ArrayList<>();
        for (String code : required) {
            if (!byCode.containsKey(code)) {
                problems.add("lang/" + code + " has no catalogue (looked for " + Arrays.toString(extensions) + ")");
            }
        }
        for (Map.Entry<String, List<File>> e : byCode.entrySet()) {
            List<String> names = new ArrayList<>();
            for (File f : e.getValue()) {
                names.add(f.getName());
            }
            if (names.size() > 1) {
                problems.add("lang/" + e.getKey() + " has " + names + "; the framework loads only "
                        + names.get(0) + " and never reads " + names.subList(1, names.size()));
            }
        }
        return problems;
    }

    /** Catalogue files in {@code langDir} by language code, each list in the framework's extension order. */
    static Map<String, List<File>> catalogueFilesByCode(File langDir, String[] extensions) {
        Map<String, List<File>> byCode = new TreeMap<>();
        for (String ext : extensions) {
            File[] files = langDir.listFiles();
            if (files == null) {
                continue;
            }
            Arrays.sort(files);
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(ext)) {
                    String code = f.getName().substring(0, f.getName().length() - ext.length());
                    byCode.computeIfAbsent(code, k -> new ArrayList<>()).add(f);
                }
            }
        }
        return byCode;
    }

    /** The enumerations that match a real key site; a stale one makes no key reachable. */
    static Set<DynamicSite> matchedDynamicSites(List<SourceFile> files, List<DynamicSite> table) {
        Set<DynamicSite> matched = new LinkedHashSet<>();
        for (SourceFile f : files) {
            for (KeySite s : f.sites) {
                if (!s.isLiteral() && !s.passThrough) {
                    matched.addAll(findAll(table, f.path, s.expression));
                }
            }
        }
        return matched;
    }

    /** Separates file and expression in a pairing key; neither a path nor printed Java contains it. */
    private static final String SEP = "\u0000";

    private static List<DynamicSite> findAll(List<DynamicSite> table, String file, String expression) {
        List<DynamicSite> found = new ArrayList<>();
        for (DynamicSite d : table) {
            if (d.file.equals(file) && d.expression.equals(expression)) {
                found.add(d);
            }
        }
        return found;
    }

    // ================================================================== catalogue loading

    /** One parsed catalogue: the dictionary the framework's {@link Language} holds for it. */
    static final class Catalogue {
        final String code;
        final String fileName;
        final Map<String, String> entries;

        Catalogue(String code, String fileName, Map<String, String> entries) {
            this.code = code;
            this.fileName = fileName;
            this.entries = entries;
        }
    }

    /** A key site whose key is computed, and every key it can produce. */
    static final class DynamicSite {
        final String file;
        final String expression;
        final String reason;
        final List<String> keys;

        DynamicSite(String file, String expression, String reason, String... keys) {
            this.file = file;
            this.expression = expression;
            this.reason = reason;
            this.keys = Collections.unmodifiableList(Arrays.asList(keys));
        }
    }

    /** {@code lang/} inside the module's code source -- where {@code loadLanguageFromJar} looks. */
    static File catalogueDirectory() throws Exception {
        File location = new File(MAIN_CLASS.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertThat(location).as("module code source (Maven's target/classes)").isDirectory();
        return new File(location, "lang");
    }

    /** The framework's own extension order, read from the framework rather than restated. */
    static String[] languageExtensions() throws Exception {
        Field f = UltiToolsPlugin.class.getDeclaredField("LANGUAGE_EXTENSIONS");
        f.setAccessible(true);
        return ((String[]) f.get(null)).clone();
    }

    /**
     * Resolves each language code the way {@code UltiToolsPlugin#resolveJarLanguage} does: the first
     * extension, in the framework's order, that exists.
     */
    static synchronized List<Catalogue> loadModuleCatalogues() throws Exception {
        if (loaded == null) {
            File langDir = catalogueDirectory();
            String[] extensions = languageExtensions();
            Map<String, List<File>> byCode = catalogueFilesByCode(langDir, extensions);
            Set<String> codes = new TreeSet<>(REQUIRED_CODES);
            codes.addAll(byCode.keySet());
            List<Catalogue> result = new ArrayList<>();
            for (String code : codes) {
                List<File> files = byCode.get(code);
                if (files != null) {
                    File f = files.get(0);
                    String ext = f.getName().substring(code.length());
                    result.add(new Catalogue(code, "lang/" + f.getName(), parse(f.toPath(), ext)));
                }
            }
            loaded = Collections.unmodifiableList(result);
        }
        return loaded;
    }

    private static List<Catalogue> loaded;

    /** Parses exactly as {@code UltiToolsPlugin#parseLanguageResource} does. */
    static Map<String, String> parse(Path file, String extension) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader, extension);
        }
    }

    static Map<String, String> parse(BufferedReader reader, String extension) throws IOException {
        Language language;
        if (".json".equals(extension)) {
            StringBuilder joined = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                joined.append(line);
            }
            language = new Language(joined.toString());
        } else {
            language = Language.fromYaml((Reader) reader);
        }
        return dictionaryOf(language);
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> dictionaryOf(Language language) {
        try {
            Field f = Language.class.getDeclaredField("dictionary");
            f.setAccessible(true);
            return Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, String>) f.get(language)));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the framework's Language no longer has a 'dictionary' field; "
                    + "update this guard to read its entries another way", e);
        }
    }

    // ================================================================== the guard's own behaviour

    private static SourceFile source(String body) {
        return SourceFile.of("src/main/java/Sample.java", "class Sample {\n" + body + "\n}\n");
    }

    private static Catalogue yaml(String code, String text) throws IOException {
        return new Catalogue(code, "lang/" + code + ".yml",
                parse(new BufferedReader(new StringReader(text)), ".yml"));
    }

    @Nested
    @DisplayName("catalogue parsing follows the framework")
    class Parsing {

        @Test
        @DisplayName("a nested YAML key counts as its dotted path")
        void nestedYamlIsFlattened() throws IOException {
            Catalogue c = yaml("en", "a:\n  b:\n    c: \"text\"\n");
            assertThat(c.entries).containsEntry("a.b.c", "text").doesNotContainKey("a.b").doesNotContainKey("a");
        }

        @Test
        @DisplayName("a flat dotted YAML key and a nested one are the same key")
        void flatDottedYamlIsTheSameKey() throws IOException {
            assertThat(yaml("en", "a.b.c: \"text\"\n").entries).containsEntry("a.b.c", "text");
        }

        @Test
        @DisplayName("a non-string YAML node is not a key")
        void nonStringNodeIsNotAKey() throws IOException {
            Catalogue c = yaml("en", "number: 5\nlist:\n  - x\nflag: true\ntext: \"ok\"\n");
            assertThat(c.entries).containsOnlyKeys("text");
        }

        @Test
        @DisplayName("JSON goes through the framework's Gson constructor")
        void jsonIsParsedByTheFramework() throws IOException {
            Map<String, String> entries = parse(new BufferedReader(new StringReader("{\n\"k\": \"v\"\n}")), ".json");
            assertThat(entries).containsExactly(org.assertj.core.api.Assertions.entry("k", "v"));
        }
    }

    /** Compiled executors for the {@code @CmdParam(suggest = ...)} tests; the framework's lookup runs on these. */
    static final class SuggestFixtures {
        static final String HINT = "backup.hint." + "constant";

        private SuggestFixtures() {
        }

        abstract static class Base {
            public List<String> inherited() {
                return Collections.emptyList();
            }
        }

        static final class Provider {
            public static List<String> fromSuggestClass() {
                return Collections.emptyList();
            }
        }

        static final class Unrelated {
            public List<String> elsewhere() {
                return Collections.emptyList();
            }
        }

        @CmdSuggest(Provider.class)
        static class Executor extends Base {
            public void run(@CmdParam(value = "a", suggest = "backup.hint.literal") String a,
                            @CmdParam(value = "b", suggest = HINT) String b,
                            @CmdParam(value = "c", suggest = "own") String c,
                            @CmdParam(value = "d", suggest = "inherited()") String d,
                            @CmdParam(value = "e", suggest = "fromSuggestClass") String e,
                            @CmdParam(value = "f", suggest = "elsewhere") String f,
                            @CmdParam("g") String g) {
            }

            public List<String> own() {
                return Collections.emptyList();
            }
        }

        static class Exploding {
            static final Object STATE = explode();

            static Object explode() {
                throw new IllegalStateException("static initialiser failed");
            }

            public void run(@CmdParam(value = "p", suggest = "anything") String p) {
            }
        }
    }

    @Nested
    @DisplayName("key sites")
    class Sites {

        @Test
        @DisplayName("a key present in en but absent from zh fails, naming key, file and language")
        void missingFromOneLanguage() throws IOException {
            List<String> problems = missingLiteralKeys(
                    Collections.singletonList(source("void m() { plugin.i18n(\"only.en\"); }")),
                    Arrays.asList(yaml("en", "only.en: \"x\"\n"), yaml("zh", "other: \"y\"\n")));
            assertThat(problems).containsExactly(
                    "key \"only.en\" (src/main/java/Sample.java:2) is missing from lang/zh.yml");
        }

        @Test
        @DisplayName("a call split across lines is one literal site")
        void callSplitAcrossLines() {
            SourceFile f = source("void m() { plugin.i18n(\n    \"split.key\"\n); }");
            assertThat(f.sites).hasSize(1);
            assertThat(f.sites.get(0).literalKey).isEqualTo("split.key");
        }

        @Test
        @DisplayName("the two-argument form's key is its second argument")
        void twoArgumentForm() {
            SourceFile f = source("void m() { plugin.i18n(\"zh\", \"the.key\"); }");
            assertThat(f.sites).extracting(s -> s.literalKey).containsExactly("the.key");
        }

        @Test
        @DisplayName("a declaration is not a site; a wrapper forwarding its parameter is a pass-through")
        void declarationAndPassThrough() {
            SourceFile f = source("private String i18n(String key) { return plugin.i18n(key); }");
            assertThat(f.sites).hasSize(1);
            assertThat(f.sites.get(0).passThrough).isTrue();
        }

        @Test
        @DisplayName("a wrapper that changes the key is not a pass-through")
        void transformingWrapperIsDynamic() {
            SourceFile f = source("private String i18n(String key) { return plugin.i18n(\"p.\" + key); }");
            assertThat(f.sites).hasSize(1);
            assertThat(f.sites.get(0).passThrough).isFalse();
            assertThat(f.sites.get(0).expression).isEqualTo("\"p.\" + key");
        }

        @Test
        @DisplayName("forwarding a variable outside an i18n wrapper is not a pass-through")
        void forwardingOutsideWrapperIsDynamic() {
            SourceFile f = source("String show(String key) { return plugin.i18n(key); }");
            assertThat(f.sites.get(0).passThrough).isFalse();
        }

        @Test
        @DisplayName("a call right after a lambda arrow is a call, even with a less-than sign earlier")
        void callAfterLambdaArrow() {
            SourceFile f = source("void m(int x) { if (x < 3) { Runnable r = () -> i18n(\"after.arrow\"); } }\n"
                    + "String i18n(String key) { return plugin.i18n(key); }");
            assertThat(f.sites).extracting(s -> s.literalKey).contains("after.arrow");
        }

        @Test
        @DisplayName("a wrapper that reassigns its key parameter before forwarding it is not a pass-through")
        void reassigningWrapperIsDynamic() {
            SourceFile f = source("String i18n(String key) { key = \"p.\" + key; return plugin.i18n(key); }");
            assertThat(f.sites).hasSize(1);
            assertThat(f.sites.get(0).passThrough).isFalse();
        }

        @Test
        @DisplayName("a call right after a greater-than comparison is a call")
        void callAfterComparison() {
            SourceFile f = source("boolean m(int a, int b, int c) { return a < b && c > i18n(\"after.gt\").length(); }");
            assertThat(f.sites).extracting(s -> s.literalKey).containsExactly("after.gt");
        }

        @Test
        @DisplayName("a forwarding call inside an anonymous class in an i18n wrapper is not a pass-through")
        void forwardingInsideAnonymousClassIsDynamic() {
            SourceFile f = source("String i18n(String key) { return new java.util.function.Function<String, String>() {"
                    + " public String apply(String key) { return plugin.i18n(key); } }.apply(\"x.\" + suffix); }");
            assertThat(f.sites).hasSize(1);
            assertThat(f.sites.get(0).passThrough).isFalse();
        }

        @Test
        @DisplayName("@CmdParam(suggest = ...) is resolved on the compiled executor by the framework's own lookup")
        void suggestResolvedByTheFrameworkLookup() {
            I18nSourceScanner.SuggestScan scan = I18nSourceScanner.suggestHintSites(Arrays.<Class<?>>asList(
                    SuggestFixtures.Executor.class, SuggestFixtures.Base.class, SuggestFixtures.Provider.class,
                    SuggestFixtures.Unrelated.class));
            List<String> keys = new ArrayList<>();
            for (SourceFile f : scan.hints) {
                for (KeySite s : f.sites) {
                    assertThat(s.kind).isEqualTo(SiteKind.SUGGEST_HINT);
                    keys.add(s.literalKey);
                }
            }
            // A literal, a compile-time constant, and a name only an unrelated class declares are all shown
            // as hints; a method on the executor, on its superclass or on its @CmdSuggest class is not.
            assertThat(keys).containsExactlyInAnyOrder("backup.hint.literal", "backup.hint.constant", "elsewhere");
            assertThat(scan.seen).hasSize(6);
        }

        @Test
        @DisplayName("an executor the framework's lookup cannot run on fails the scan instead of passing it")
        void unresolvableExecutorFailsClosed() {
            I18nSourceScanner.SuggestScan scan = I18nSourceScanner.suggestHintSites(
                    Collections.<Class<?>>singletonList(SuggestFixtures.Exploding.class));
            assertThat(scan.hints).singleElement().satisfies(f -> assertThat(f.sites).singleElement().satisfies(s -> {
                assertThat(s.literalKey).isNull();
                assertThat(s.expression).contains("anything").contains("cannot resolve");
            }));
        }

        @Test
        @DisplayName("the parser counts each @CmdParam(suggest = ...) that is not \"\", literal or constant")
        void suggestAttributesAreCounted() {
            SourceFile f = source("void run(@CmdParam(value = \"p\", suggest = \"x\") String p,\n"
                    + "        @CmdParam(value = \"q\", suggest = K) String q, @CmdParam(value = \"r\", suggest = \"\") String r) { }");
            assertThat(f.suggestAttributes).isEqualTo(2);
            assertThat(f.sites).as("the parser no longer decides what a suggest value is").isEmpty();
        }

        @Test
        @DisplayName("a method name written with a Unicode escape is still i18n")
        void unicodeEscapedMethodName() {
            SourceFile f = source("void m() { plugin.i1\\u0038n(\"escaped.name\"); }");
            assertThat(f.sites).extracting(s -> s.literalKey).containsExactly("escaped.name");
        }

        @Test
        @DisplayName("an unlisted computed key fails; a listed one passes; a stale listing fails")
        void dynamicSitesMustBeEnumerated() {
            List<SourceFile> files = Collections.singletonList(
                    source("void m() { plugin.i18n(\"a.\" + kind); }"));
            assertThat(unlistedOrStaleDynamicSites(files, Collections.<DynamicSite>emptyList()))
                    .singleElement().asString().contains("Sample.java:2").contains("\"a.\" + kind");
            DynamicSite listed = new DynamicSite("src/main/java/Sample.java", "\"a.\" + kind", "kind is x or y",
                    "a.x", "a.y");
            assertThat(unlistedOrStaleDynamicSites(files, Collections.singletonList(listed))).isEmpty();
            DynamicSite stale = new DynamicSite("src/main/java/Sample.java", "gone()", "r", "a.z");
            assertThat(unlistedOrStaleDynamicSites(files, Arrays.asList(listed, stale)))
                    .singleElement().asString().startsWith("stale DYNAMIC_KEY_SITES entry");
        }

        @Test
        @DisplayName("two sites printing the same expression in one file need one entry each")
        void sameExpressionTwiceNeedsTwoEntries() throws IOException {
            List<SourceFile> files = Collections.singletonList(source(
                    "String a(String key) { return plugin.i18n(key); }\n"
                            + "String b(String key) { return plugin.i18n(key); }"));
            DynamicSite one = new DynamicSite("src/main/java/Sample.java", "key", "a's keys", "a.one");
            assertThat(unlistedOrStaleDynamicSites(files, Collections.singletonList(one)))
                    .as("one entry must not silently cover a second site").singleElement().asString()
                    .contains("2 non-literal key sites").contains("lists 1");
            DynamicSite two = new DynamicSite("src/main/java/Sample.java", "key", "b's keys", "b.one");
            assertThat(unlistedOrStaleDynamicSites(files, Arrays.asList(one, two))).isEmpty();
            assertThat(unreachableCatalogueKeys(files, Arrays.asList(one, two),
                    Collections.singletonList(yaml("en", "a.one: \"x\"\nb.one: \"y\"\n")))).isEmpty();
        }

        @Test
        @DisplayName("a method reference to i18n is a site that must be enumerated")
        void methodReferenceIsASite() {
            SourceFile f = source("void m() { keys.stream().map(plugin::i18n); }");
            assertThat(f.sites).extracting(s -> s.kind).containsExactly(SiteKind.METHOD_REFERENCE);
            assertThat(unlistedOrStaleDynamicSites(Collections.singletonList(f), Collections.<DynamicSite>emptyList()))
                    .hasSize(1);
        }

        @Test
        @DisplayName("getLocalizedText is a key site like i18n")
        void getLocalizedTextIsASite() {
            SourceFile f = source("void m() { plugin.getLanguage().getLocalizedText(\"via.language\"); }");
            assertThat(f.sites).extracting(s -> s.literalKey).containsExactly("via.language");
        }

        @Test
        @DisplayName("@CmdExecutor's description is a key the framework translates")
        void commandDescriptionIsAKey() {
            SourceFile f = SourceFile.of("src/main/java/Sample.java",
                    "@CmdExecutor(alias = {\"x\"}, description = \"cmd.description\", permission = \"p\")\n"
                            + "class Sample {}\n");
            assertThat(f.sites).extracting(s -> s.kind, s -> s.literalKey)
                    .containsExactly(org.assertj.core.api.Assertions.tuple(SiteKind.COMMAND_DESCRIPTION,
                            "cmd.description"));
        }

        @Test
        @DisplayName("a catalogue key nothing produces is reported; one a matched dynamic site produces is not")
        void unreachableKeys() throws IOException {
            List<SourceFile> files = Collections.singletonList(
                    source("void m() { plugin.i18n(\"used\"); plugin.i18n(k()); }"));
            Catalogue en = yaml("en", "used: \"u\"\ndead: \"d\"\nlisted: \"l\"\n");
            DynamicSite d = new DynamicSite("src/main/java/Sample.java", "k()", "r", "listed");
            assertThat(unreachableCatalogueKeys(files, Collections.singletonList(d), Collections.singletonList(en)))
                    .containsExactly("lang/en.yml declares \"dead\", which no code can produce");
            DynamicSite stale = new DynamicSite("src/main/java/Other.java", "k()", "r", "listed");
            assertThat(unreachableCatalogueKeys(files, Collections.singletonList(stale),
                    Collections.singletonList(en)))
                    .as("a stale enumeration makes nothing reachable").hasSize(2);
        }

        @Test
        @DisplayName("a Chinese key is reported in source and in a catalogue")
        void chineseKeysAreReported() throws IOException {
            List<SourceFile> files = Collections.singletonList(source("void m() { plugin.i18n(\"\\u4e2d\\u6587\"); }"));
            Catalogue zh = yaml("zh", "\"\u4e2d\u6587\": \"x\"\n");
            assertThat(chineseKeys(files, Collections.singletonList(zh))).hasSize(2);
        }

        @Test
        @DisplayName("Chinese text in the English catalogue is reported; in the Chinese one it is not")
        void chineseTextInEnglishIsReported() throws IOException {
            List<String> problems = chineseTextInEnglish(Arrays.asList(
                    yaml("en", "a: \"ok\"\nb: \"\u4e2d\"\n"), yaml("zh", "a: \"\u4e2d\"\n")));
            assertThat(problems).singleElement().asString().contains("lang/en.yml").contains("\"b\"");
        }

        @Test
        @DisplayName("a placeholder a translation drops or renames is reported")
        void placeholderMismatchIsReported() throws IOException {
            List<String> problems = placeholderMismatches(Arrays.asList(
                    yaml("en", "a: \"{PLAYER} has %d of %s, 100%%\"\nb: \"{X}\"\nc: \"{0}\"\n"),
                    yaml("zh", "a: \"%s %d {PLAYER} 100%%\"\nb: \"{Y}\"\nc: \"none\"\n")));
            // "a" too: String.format fills %d and %s in order, so zh's "%s %d" would throw at run time.
            assertThat(problems).hasSize(3);
            assertThat(problems.get(0)).startsWith("\"a\"");
            assertThat(problems.get(1)).startsWith("\"b\"");
            assertThat(problems.get(2)).startsWith("\"c\"");
        }

        @Test
        @DisplayName("ordinary specifiers and {} must keep their order; indexed specifiers and named tokens may move")
        void placeholderOrder() throws IOException {
            List<String> problems = placeholderMismatches(Arrays.asList(
                    yaml("en", "swap: \"%s has %d\"\nindexed: \"Took %2$s from %1$s\"\n"
                            + "named: \"{PLAYER} in {WORLD}\"\nlog: \"{} of {}\"\n"),
                    yaml("zh", "swap: \"%d \u4e2a %s\"\nindexed: \"\u6263\u9664 %1$s %2$s\"\n"
                            + "named: \"{WORLD} \u91cc\u7684 {PLAYER}\"\nlog: \"{} / {}\"\n")));
            assertThat(problems).singleElement().asString().startsWith("\"swap\"");
        }

        @Test
        @DisplayName("a literal percent sign in prose is not a format placeholder")
        void percentInProseIsNotAPlaceholder() throws IOException {
            assertThat(placeholderMismatches(Arrays.asList(
                    yaml("en", "a: \"Saved 100% of items\"\n"), yaml("zh", "a: \"\u5df2\u4fdd\u5b58100%\"\n"))))
                    .isEmpty();
        }

        @Test
        @DisplayName("a specifier followed by a letter is still a specifier, as java.util.Formatter reads it (%dh)")
        void specifierFollowedByALetter() throws IOException {
            assertThat(placeholderMismatches(Arrays.asList(
                    yaml("en", "h: \"%dh\"\n"), yaml("zh", "h: \"%d\u5c0f\u65f6\"\n"))))
                    .as("the same %d in both languages").isEmpty();
            assertThat(placeholderMismatches(Arrays.asList(
                    yaml("en", "h: \"%dh\"\n"), yaml("zh", "h: \"\u5c0f\u65f6\"\n"))))
                    .as("a translation that dropped the %d before a letter").singleElement().asString().startsWith("\"h\"");
        }

        @Test
        @DisplayName("every java.util.Formatter specifier that takes an argument is a placeholder")
        void everyFormatterConversionIsAPlaceholder() throws IOException {
            for (String spec : new String[]{"%b", "%c", "%o", "%e", "%g", "%S", "%X", "%h", "%a", "%tY", "%1$tY", "%<s"}) {
                assertThat(placeholderMismatches(Arrays.asList(
                        yaml("en", "k: \"at " + spec + " here\"\n"), yaml("zh", "k: \"\u5728\u6b64\"\n"))))
                        .as("a translation that dropped " + spec).singleElement().asString().startsWith("\"k\"");
            }
        }

        @Test
        @DisplayName("a %NAME% token is a placeholder, read whole rather than as a specifier")
        void percentNameTokenIsAPlaceholder() throws IOException {
            assertThat(placeholders("&e%online%&7/&e%max%")).containsExactly("%max%", "%online%");
            assertThat(placeholderMismatches(Arrays.asList(
                    yaml("en", "k: \"Welcome %player_name%\"\n"), yaml("zh", "k: \"\u6b22\u8fce %name%\"\n"))))
                    .singleElement().asString().startsWith("\"k\"");
        }

        @Test
        @DisplayName("every percent sign belongs to one placeholder, so none can be dropped or added unnoticed")
        void everyPercentSignIsCounted() throws IOException {
            List<String> problems = placeholderMismatches(Arrays.asList(
                    yaml("en", "one: \"Hi %x%\"\npair: \"100%% sure\"\nspace: \"Got % d items\"\nprose: \"Saved 100% of it\"\n"),
                    yaml("zh", "one: \"\u4f60\u597d %x\"\npair: \"\u786e\u5b9a\"\nspace: \"\u5f97\u5230\u7269\u54c1\"\n"
                            + "prose: \"\u5df2\u4fdd\u5b58100%\"\n")));
            // one: a one-character %x% lost its closing %; pair: a %% was dropped; space: a space-flagged
            // specifier was dropped. prose: one literal % in each language, so nothing is reported.
            assertThat(problems).extracting(p -> p.substring(0, p.indexOf(' ')))
                    .containsExactly("\"one\"", "\"pair\"", "\"space\"");
            // Two adjacent specifiers stay two specifiers: %s% is not read as a named token.
            assertThat(placeholders("%s%s")).containsExactly("arg1:%s", "arg2:%s");
        }

        @Test
        @DisplayName("a relative specifier %<s stays bound to the argument before it")
        void relativeSpecifierKeepsItsArgument() throws IOException {
            List<String> problems = placeholderMismatches(Arrays.asList(
                    yaml("en", "lead: \"%s and %<s\"\nmoved: \"%s of %d, %<d\"\nsame: \"%s then %<s\"\n"),
                    yaml("zh", "lead: \"%<s \u548c %s\"\nmoved: \"%s \u7684 %<d\uff0c%d\"\n"
                            + "same: \"%1$s \u7136\u540e %1$s\"\n")));
            // lead: zh's %<s has no argument before it (MissingFormatArgumentException at run time).
            // moved: zh's %<d re-uses %s's argument, so it formats a String with %d and throws.
            // same: %1$s twice binds the same argument as %s then %<s, so it is a correct translation.
            assertThat(problems).hasSize(2);
            assertThat(problems.get(0)).startsWith("\"lead\"");
            assertThat(problems.get(1)).startsWith("\"moved\"");
        }

        @Test
        @DisplayName("a logger argument marker {} that a translation drops is reported")
        void loggerMarkerDroppedIsReported() throws IOException {
            assertThat(placeholderMismatches(Arrays.asList(
                    yaml("en", "a: \"Loaded {} locks in {}s\"\nb: \"{} of {}\"\n"),
                    yaml("zh", "a: \"\u5df2\u52a0\u8f7d {} \u4e2a\u9501\"\nb: \"{} / {}\"\n"))))
                    .singleElement().asString().startsWith("\"a\"");
        }

        @Test
        @DisplayName("placeholders are compared between every pair of languages, not only against the first")
        void placeholdersComparedPairwise() throws IOException {
            List<String> problems = placeholderMismatches(Arrays.asList(
                    yaml("en", "other: \"x\"\n"), yaml("fr", "k: \"{PLAYER}\"\n"), yaml("zh", "k: \"none\"\n")));
            assertThat(problems).singleElement().asString().startsWith("\"k\"");
        }

        @Test
        @DisplayName("two catalogues for one code: the shadowed one is named")
        void shadowedCatalogue() throws Exception {
            Path dir = Files.createTempDirectory("lang");
            try {
                for (String name : new String[]{"en.json", "en.yml", "zh.yml"}) {
                    Files.write(dir.resolve(name), new byte[0]);
                }
                assertThat(shadowedOrMissingCatalogues(dir.toFile(), new String[]{".json", ".yml", ".yaml"},
                        REQUIRED_CODES))
                        .containsExactly("lang/en has [en.json, en.yml]; the framework loads only en.json "
                                + "and never reads [en.yml]");
            } finally {
                for (File f : dir.toFile().listFiles()) {
                    Files.delete(f.toPath());
                }
                Files.delete(dir);
            }
        }
    }
}
