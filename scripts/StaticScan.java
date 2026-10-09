import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * StaticScan - find flakiness anti-patterns in Selenium / Playwright test code.
 *
 * Findings are SUSPECTS, not proof. Correlate them with FlakyScore results.
 *
 * Java 11+, no dependencies. Run without compiling:
 *   java scripts/StaticScan.java <repo_root> [--out static.json] [--md static.md]
 *                                [--only "src/test/**"] [--min-severity medium]
 *
 * Languages: Java, Kotlin, C#, TypeScript/JavaScript, Python.
 * The framework is detected per project and per file (imports), so Playwright rules
 * don't fire on Selenium code and vice versa.
 */
public class StaticScan {

    static final Set<String> SKIP_DIRS = Set.of("node_modules", ".git", "target", "build", "dist", "out", ".gradle", ".idea",
            "venv", ".venv", "__pycache__", "test-results", "playwright-report", "allure-results", "test-output", ".flaky", "bin", "obj");
    static final Map<String, String> EXTS = Map.ofEntries(
            Map.entry(".java", "java"), Map.entry(".kt", "java"), Map.entry(".cs", "cs"),
            Map.entry(".ts", "js"), Map.entry(".tsx", "js"), Map.entry(".js", "js"), Map.entry(".mjs", "js"),
            Map.entry(".cjs", "js"), Map.entry(".py", "py"));
    static final Map<String, Integer> SEV = Map.of("info", 0, "low", 1, "medium", 2, "high", 3);

    static final class Rule {
        final String id, framework, severity, category, message, fix; final Set<String> langs; final Pattern rx;
        Rule(String id, String framework, Set<String> langs, String severity, String category, String rx, String message, String fix) {
            this.id = id; this.framework = framework; this.langs = langs; this.severity = severity; this.category = category;
            this.rx = rx == null ? null : Pattern.compile(rx); this.message = message; this.fix = fix;
        }
    }

    static final class Finding {
        final String rule, severity, category, message, fix, file, code; final int line;
        Finding(Rule r, String file, int line, String code) {
            this(r.id, r.severity, r.category, r.message, r.fix, file, line, code);
        }
        Finding(String rule, String severity, String category, String message, String fix, String file, int line, String code) {
            this.rule = rule; this.severity = severity; this.category = category; this.message = message; this.fix = fix;
            this.file = file; this.line = line; this.code = code.length() > 200 ? code.substring(0, 200) : code;
        }
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rule", rule); m.put("severity", severity); m.put("category", category); m.put("message", message);
            m.put("fix", fix); m.put("file", file); m.put("line", line); m.put("code", code);
            return m;
        }
    }

    static final Set<String> JAVA_CS = Set.of("java", "cs"), PY = Set.of("py"), JS = Set.of("js"), JAVA = Set.of("java"),
            CS = Set.of("cs"), ALL = Set.of("java", "cs", "js", "py"), JS_PY = Set.of("js", "py"), JAVA_PY = Set.of("java", "py");

    static final List<Rule> RULES = List.of(
        // ---------------- any framework
        new Rule("GEN001", "any", JAVA_CS, "high", "timing",
            "\\bThread\\.sleep\\s*\\(|\\bTimeUnit\\.\\w+\\.sleep\\s*\\(|\\bThread\\.Sleep\\s*\\(|Task\\.Delay\\s*\\(",
            "Hard-coded sleep.", "Replace with an explicit wait for the condition you actually need."),
        new Rule("GEN002", "any", PY, "high", "timing",
            "\\btime\\.sleep\\s*\\(|^\\s*sleep\\s*\\(",
            "Hard-coded sleep.", "Replace with an explicit wait / auto-waiting assertion."),
        new Rule("GEN003", "any", JS, "high", "timing",
            "new Promise\\s*\\(\\s*\\(?\\s*\\w*\\s*\\)?\\s*=>\\s*setTimeout|\\bsleep\\s*\\(\\s*\\d+",
            "Hard-coded sleep via setTimeout/sleep helper.", "Wait for a UI/network condition instead."),
        new Rule("GEN010", "any", ALL, "low", "test-data",
            "Math\\.random\\(|new Random\\(|\\brandom\\.(randint|choice|random)\\(|Faker\\(|faker\\.",
            "Random test data without a fixed seed.", "Seed the generator or log the value so failures are reproducible."),
        new Rule("GEN011", "any", ALL, "low", "environment",
            "LocalDate(Time)?\\.now\\(|new Date\\(\\s*\\)|Date\\.now\\(|datetime\\.now\\(|DateTime\\.Now|date\\.today\\(",
            "Test depends on the current date/time.", "Inject a fixed clock (page.clock / mocked time) or compute expectations from the same source."),
        new Rule("GEN020", "any", JAVA_CS, "medium", "error-handling",
            "catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}",
            "Empty catch block swallows failures.", "Fail or log; hidden exceptions cause later, confusing failures."),
        new Rule("GEN021", "any", PY, "medium", "error-handling",
            "except(\\s+\\w+(\\s+as\\s+\\w+)?)?\\s*:\\s*pass\\b",
            "Exception swallowed with pass.", "Let it fail or assert the expected state."),
        new Rule("GEN022", "any", JS, "medium", "error-handling",
            "catch\\s*(\\(\\s*\\w*\\s*\\))?\\s*\\{\\s*\\}|\\.catch\\(\\s*\\(\\)\\s*=>\\s*\\{\\s*\\}\\s*\\)",
            "Empty catch swallows failures.", "Fail or log; don't hide errors."),
        new Rule("GEN030", "any", JAVA, "info", "retry-masking",
            "retryAnalyzer|IRetryAnalyzer|@RepeatedIfExceptionsTest|@RetryingTest|rerunFailingTestsCount",
            "Retry mechanism present.", "Retries hide flakiness; report retried passes as flaky instead of green."),
        new Rule("GEN031", "any", PY, "info", "retry-masking",
            "@pytest\\.mark\\.flaky|--reruns|@flaky",
            "Retry mechanism present.", "Track reruns as flaky signals; don't treat them as passes."),

        // ---------------- Selenium
        new Rule("SEL001", "selenium", ALL, "medium", "timing",
            "implicitlyWait|implicitly_wait|ImplicitWait",
            "Implicit wait configured.", "Don't mix implicit and explicit waits (wait times compound unpredictably). Use explicit waits only."),
        new Rule("SEL002", "selenium", ALL, "medium", "locator",
            "[\"']/html/|[\"'](//?\\w+\\[\\d+\\]){2,}|By\\.xpath\\(\\s*\"[^\"]*\\[\\d+\\][^\"]*\\[\\d+\\]",
            "Absolute or index-based XPath.", "Use stable attributes (data-testid, id, name, aria labels)."),
        new Rule("SEL003", "selenium", JAVA, "high", "parallelism",
            "\\bstatic\\s+(?!final\\b)(?:\\w+\\s+)*(WebDriver|RemoteWebDriver|ChromeDriver|FirefoxDriver|EdgeDriver|WebDriverWait)\\s+\\w+",
            "Static (shared) WebDriver/WebDriverWait.", "Use ThreadLocal<WebDriver> or per-test instances for parallel safety."),
        new Rule("SEL004", "selenium", CS, "high", "parallelism",
            "\\bstatic\\s+(?!readonly\\b)(?:\\w+\\s+)*(IWebDriver|WebDriverWait)\\s+\\w+",
            "Static (shared) IWebDriver.", "Use ThreadLocal<IWebDriver> / per-test driver."),
        new Rule("SEL005", "selenium", JAVA, "medium", "order-dependency",
            "dependsOnMethods|dependsOnGroups|@Test\\s*\\([^)]*priority\\s*=|@Order\\(|@TestMethodOrder",
            "Test order dependency.", "Make each test set up its own state (API/DB setup) so it can run alone and in parallel."),
        new Rule("SEL006", "selenium", ALL, "medium", "stale-element",
            "StaleElementReference",
            "StaleElementReference handled manually.", "Re-locate elements after DOM changes; wait for the re-render (stalenessOf) instead of retry loops."),
        new Rule("SEL007", "selenium", ALL, "low", "interaction",
            "executeScript\\(\\s*\"[^\"]*\\.click\\(\\)|execute_script\\(\\s*[\"'][^\"']*\\.click\\(\\)|ExecuteScript\\(\\s*\"[^\"]*\\.click\\(\\)",
            "JavaScript click bypasses real interaction.", "Wait for element to be clickable / overlay to disappear, then use a native click."),
        new Rule("SEL008", "selenium", ALL, "low", "locator",
            "findElements?\\([^)]*\\)\\s*\\.get\\(\\s*\\d+\\s*\\)|find_elements\\([^)]*\\)\\[\\d+\\]|FindElements\\([^)]*\\)\\[\\d+\\]",
            "Element picked by list index.", "Locate by a unique, meaningful attribute."),
        new Rule("SEL009", "selenium", JAVA_PY, "medium", "timing",
            "\\.getText\\(\\)\\s*\\)\\s*\\.(isEqualTo|contains)|assert(Equals|True)\\([^;]*\\.getText\\(\\)|assert\\s+[^\\n]*\\.text\\s*==",
            "Assertion on a one-shot getText().", "Wait for the expected text (textToBePresentInElement / wait.until) before asserting."),
        new Rule("SEL010", "selenium", ALL, "low", "timing",
            "pageLoadTimeout|set_page_load_timeout|PageLoad\\s*=",
            "Page load timeout tuned.", "Timeouts don't fix races; confirm the real wait condition."),

        // ---------------- Playwright
        new Rule("PW001", "playwright", JS_PY, "high", "timing",
            "\\.waitForTimeout\\s*\\(|\\.wait_for_timeout\\s*\\(",
            "Hard wait (waitForTimeout).", "Use web-first assertions (expect(locator).toBeVisible()) or wait for a response/event."),
        new Rule("PW002", "playwright", JS, "high", "non-retrying-assertion",
            "expect\\(\\s*await\\s+.*?\\.(isVisible|isHidden|isEnabled|isChecked|textContent|innerText|getAttribute|inputValue|count|isDisabled)\\(",
            "Non-retrying assertion on a one-shot read.", "Use web-first assertions: toBeVisible(), toHaveText(), toHaveAttribute(), toHaveCount()."),
        new Rule("PW003", "playwright", PY, "high", "non-retrying-assertion",
            "assert\\s+.*?\\.(is_visible|is_hidden|is_enabled|text_content|inner_text|get_attribute|input_value|count)\\(",
            "Non-retrying assert on a one-shot read.", "Use expect(locator).to_be_visible()/to_have_text()."),
        new Rule("PW004", "playwright", JS_PY, "medium", "interaction",
            "force\\s*:\\s*true|force\\s*=\\s*True",
            "force: true skips actionability checks.", "Find what blocks the element (overlay, animation, disabled state) and wait for it."),
        new Rule("PW005", "playwright", JS_PY, "medium", "timing",
            "networkidle",
            "networkidle wait (discouraged, unreliable with polling/websockets).", "Wait for a specific element or response (waitForResponse / expect)."),
        new Rule("PW006", "playwright", JS_PY, "medium", "order-dependency",
            "describe\\.serial|mode\\s*:\\s*['\"]serial['\"]",
            "Serial mode: tests depend on each other.", "Make tests independent; use fixtures/API setup for shared preconditions."),
        new Rule("PW007", "playwright", JS, "medium", "locator",
            "page\\.\\$\\$?\\(|\\.\\$eval\\(|\\.\\$\\$eval\\(|elementHandle|waitForSelector\\(",
            "ElementHandle / waitForSelector API (no auto-wait, can go stale).", "Use locators: page.getByRole()/getByTestId()."),
        new Rule("PW008", "playwright", JS_PY, "low", "locator",
            "\\.nth\\(\\s*\\d+\\s*\\)|\\.first\\(\\)|\\.last\\(\\)|\\.first\\b|\\.last\\b|nth-child|xpath=",
            "Position-based or XPath locator.", "Prefer getByRole/getByTestId/filter({ hasText }) to target a unique element."),
        new Rule("PW009", "playwright", JS, "high", "missing-await", null,   // handled specially
            "Playwright call without await.", "Add await; un-awaited actions race with the next step and end of test."),
        new Rule("PW010", "playwright", JS, "low", "test-data",
            "test\\.use\\(\\s*\\{\\s*storageState",
            "Shared storageState.", "Fine for auth, but tests mutating the same account in parallel collide; use per-worker accounts.")
    );

    static final Pattern PW_ASYNC_CALL = Pattern.compile(
        "^\\s*(?:page|locator|\\w+(?:Page|Locator|Btn|Button|Input|Link|Field)?)\\s*"
        + "(?:\\.[\\w$]+\\([^()]*\\))*\\.(click|fill|goto|press|check|uncheck|type|pressSequentially|"
        + "selectOption|hover|dblclick|setInputFiles|waitForURL|waitForResponse|reload|dragTo|focus|tap)\\s*\\(");
    static final Pattern PW_EXPECT_NOAWAIT = Pattern.compile(
        "^\\s*expect\\(.*\\)\\s*(\\.not)?\\.(toBeVisible|toHaveText|toContainText|toHaveURL|toHaveTitle|toBeHidden|"
        + "toHaveCount|toHaveValue|toBeEnabled|toBeChecked|toHaveAttribute)\\s*\\(");

    static final Pattern PW_IMPORT = Pattern.compile(
        "@playwright/test|from playwright|playwright\\.(sync|async)_api|Microsoft\\.Playwright|"
        + "\\bpage\\.(goto|getBy|locator)\\(|\\bpage\\.(get_by_|locator)");
    static final Pattern SEL_IMPORT = Pattern.compile(
        "org\\.openqa\\.selenium|from selenium|OpenQA\\.Selenium|selenium-webdriver|WebDriver\\b|By\\.(id|xpath|css)");

    // ------------------------------------------------------------- framework detection
    static String readQuiet(Path p) {
        try { return new String(Files.readAllBytes(p), StandardCharsets.UTF_8); } catch (IOException e) { return ""; }
    }

    static Set<String> detectProjectFrameworks(Path root) throws IOException {
        Set<String> fw = new TreeSet<>();
        for (String name : List.of("package.json", "pom.xml", "build.gradle", "build.gradle.kts",
                "requirements.txt", "pyproject.toml", "setup.py", "Pipfile")) {
            Path p = root.resolve(name);
            if (!Files.exists(p)) continue;
            String txt = readQuiet(p).toLowerCase();
            if (txt.contains("playwright")) fw.add("playwright");
            if (txt.contains("selenium")) fw.add("selenium");
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) if (p.getFileName().toString().startsWith("playwright.config")) fw.add("playwright");
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                return !dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                if (f.getFileName().toString().endsWith(".csproj")) {
                    String txt = readQuiet(f).toLowerCase();
                    if (txt.contains("selenium")) fw.add("selenium");
                    if (txt.contains("playwright")) fw.add("playwright");
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return fw;
    }

    static Set<String> fileFrameworks(String text, Set<String> projectFw) {
        Set<String> fw = new HashSet<>();
        if (PW_IMPORT.matcher(text).find()) fw.add("playwright");
        if (SEL_IMPORT.matcher(text).find()) fw.add("selenium");
        if (fw.isEmpty()) fw.addAll(projectFw);   // page objects/utilities without imports inherit project frameworks
        return fw;
    }

    static String stripComment(String line, String lang) {
        if (lang.equals("py")) return line.replaceAll("(^|\\s)#.*$", "");
        return line.replaceAll("(^|[^:])//.*$", "$1");
    }

    // ------------------------------------------------------------- scanning
    static List<Finding> scanFile(Path path, String lang, Set<String> projectFw, String rel) {
        String text = readQuiet(path);
        Set<String> fws = fileFrameworks(text, projectFw);
        List<Finding> findings = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        boolean inBlock = false;
        for (int idx = 0; idx < lines.length; idx++) {
            String raw = lines[idx];
            String line = raw;
            if (!lang.equals("py")) {
                if (inBlock) {
                    int end = line.indexOf("*/");
                    if (end < 0) continue;
                    inBlock = false;
                    line = line.substring(end + 2);
                }
                int start = line.indexOf("/*");
                if (start >= 0 && !line.substring(start + 2).contains("*/")) {
                    inBlock = true;
                    line = line.substring(0, start);
                }
            }
            line = stripComment(line, lang);
            if (line.isBlank()) continue;
            for (Rule r : RULES) {
                if (!r.langs.contains(lang)) continue;
                if (!r.framework.equals("any") && !fws.contains(r.framework)) continue;
                if (r.id.equals("PW009")) {
                    String s = line.strip();
                    if (s.startsWith("await ") || s.startsWith("return ") || s.startsWith("yield ")
                            || s.split("=", 2)[0].contains("await ") || s.contains("=>")) continue;
                    if (PW_ASYNC_CALL.matcher(line).find() || PW_EXPECT_NOAWAIT.matcher(line).find())
                        findings.add(new Finding(r, rel, idx + 1, raw.strip()));
                    continue;
                }
                if (r.rx.matcher(line).find()) findings.add(new Finding(r, rel, idx + 1, raw.strip()));
            }
        }
        return findings;
    }

    /** Cross-file signals that a single line can't show. */
    static List<Finding> projectLevelChecks(List<Finding> findings, Path root) throws IOException {
        List<Finding> extra = new ArrayList<>();
        boolean implicit = findings.stream().anyMatch(f -> f.rule.equals("SEL001"));
        if (implicit) {
            final boolean[] explicit = {false};
            Pattern rx = Pattern.compile("WebDriverWait|ExpectedConditions");
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                    return !dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    if (EXTS.containsKey(ext(f)) && rx.matcher(readQuiet(f)).find()) { explicit[0] = true; return FileVisitResult.TERMINATE; }
                    return FileVisitResult.CONTINUE;
                }
            });
            if (explicit[0])
                extra.add(new Finding("SEL100", "high", "timing", "Implicit and explicit waits are mixed in this project.",
                        "Set implicit wait to 0 and use explicit waits everywhere.", "(project)", 0, ""));
        }
        Pattern retries = Pattern.compile("retries\\s*:\\s*([^,\\n]+)");
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                if (!p.getFileName().toString().startsWith("playwright.config")) continue;
                Matcher m = retries.matcher(readQuiet(p));
                if (m.find() && !m.group(1).strip().equals("0"))
                    extra.add(new Finding("PW100", "info", "retry-masking", "Playwright retries configured (" + m.group(1).strip() + ").",
                            "Keep retries in CI if needed, but read the 'flaky' status in reports and fail on it (--fail-on-flaky-tests) or track it.",
                            p.getFileName().toString(), 0, m.group(0)));
            }
        }
        return extra;
    }

    static String ext(Path p) {
        String n = p.getFileName().toString();
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i);
    }

    // ------------------------------------------------------------- output
    static String toMarkdown(List<Finding> findings, Set<String> projectFw, int nFiles) {
        Map<String, Long> bySev = findings.stream().collect(Collectors.groupingBy(f -> f.severity, Collectors.counting()));
        Map<String, Long> byRule = findings.stream().collect(Collectors.groupingBy(f -> f.rule + "\u0000" + f.message, LinkedHashMap::new, Collectors.counting()));
        StringBuilder sb = new StringBuilder("# Static flakiness scan\n\n");
        sb.append("Frameworks detected: ").append(projectFw.isEmpty() ? "unknown" : String.join(", ", projectFw))
          .append(" | Files scanned: ").append(nFiles).append(" | Findings: ").append(findings.size())
          .append(" (high ").append(bySev.getOrDefault("high", 0L)).append(", medium ").append(bySev.getOrDefault("medium", 0L))
          .append(", low ").append(bySev.getOrDefault("low", 0L)).append(", info ").append(bySev.getOrDefault("info", 0L)).append(")\n\n");
        sb.append("## By rule\n\n| Rule | Count | Issue |\n|---|---:|---|\n");
        byRule.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).forEach(e -> {
            String[] k = e.getKey().split("\u0000", 2);
            sb.append("| ").append(k[0]).append(" | ").append(e.getValue()).append(" | ").append(k[1]).append(" |\n");
        });
        sb.append("\n## By file (high and medium only)\n\n");
        Map<String, List<Finding>> perFile = new LinkedHashMap<>();
        for (Finding f : findings)
            if (f.severity.equals("high") || f.severity.equals("medium")) perFile.computeIfAbsent(f.file, k -> new ArrayList<>()).add(f);
        perFile.entrySet().stream().sorted((a, b) -> b.getValue().size() - a.getValue().size()).forEach(e -> {
            sb.append("### ").append(e.getKey()).append(" (").append(e.getValue().size()).append(")\n");
            e.getValue().stream().sorted(Comparator.comparingInt(f -> f.line)).forEach(f -> {
                String code = f.code.replace("`", "'");
                if (code.length() > 100) code = code.substring(0, 100);
                sb.append("- L").append(f.line).append(" **").append(f.rule).append("** [").append(f.severity).append("] ")
                  .append(f.message).append(" `").append(code).append("`  \n  Fix: ").append(f.fix).append("\n");
            });
            sb.append("\n");
        });
        return sb.toString();
    }

    static void write(String path, String content) throws IOException {
        Path p = Paths.get(path).toAbsolutePath();
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }

    static String json(Object v) {
        StringBuilder sb = new StringBuilder();
        json(v, 0, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static void json(Object v, int depth, StringBuilder sb) {
        String pad = "  ".repeat(depth + 1), padEnd = "  ".repeat(depth);
        if (v == null) sb.append("null");
        else if (v instanceof Number || v instanceof Boolean) sb.append(v);
        else if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int k = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                sb.append(pad); quote(e.getKey(), sb); sb.append(": "); json(e.getValue(), depth + 1, sb);
                sb.append(++k < m.size() ? ",\n" : "\n");
            }
            sb.append(padEnd).append("}");
        } else if (v instanceof Collection) {
            Collection<Object> c = (Collection<Object>) v;
            if (c.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            int k = 0;
            for (Object o : c) { sb.append(pad); json(o, depth + 1, sb); sb.append(++k < c.size() ? ",\n" : "\n"); }
            sb.append(padEnd).append("]");
        } else quote(v.toString(), sb);
    }

    static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------- main
    static void usage() {
        System.out.println("Usage: java StaticScan.java <repo_root> [--out static.json] [--md static.md]\n"
                + "                         [--only <glob>]... [--min-severity info|low|medium|high]\n"
                + "--only takes a glob relative to the repo root, e.g. --only \"**/LoginTest*\"");
    }

    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
        String rootArg = null, out = null, md = null, minSeverity = "info";
        List<PathMatcher> only = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out": out = args[++i]; break;
                case "--md": md = args[++i]; break;
                case "--only": only.add(FileSystems.getDefault().getPathMatcher("glob:" + args[++i])); break;
                case "--min-severity": minSeverity = args[++i]; break;
                case "-h": case "--help": usage(); return;
                default: rootArg = args[i];
            }
        }
        if (rootArg == null) { usage(); System.exit(2); }
        if (!SEV.containsKey(minSeverity)) { System.err.println("bad --min-severity: " + minSeverity); System.exit(2); }
        Path root = Paths.get(rootArg).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) { System.err.println("not a directory: " + root); System.exit(2); }

        Set<String> projectFw = detectProjectFrameworks(root);
        List<Finding> findings = new ArrayList<>();
        int[] nFiles = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                String n = dir.getFileName().toString();
                return SKIP_DIRS.contains(n) || n.startsWith(".") ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                String n = f.getFileName().toString();
                String lang = EXTS.get(ext(f));
                if (lang == null || n.endsWith(".d.ts") || n.endsWith(".min.js")) return FileVisitResult.CONTINUE;
                Path rel = root.relativize(f);
                if (!only.isEmpty() && only.stream().noneMatch(m -> m.matches(rel))) return FileVisitResult.CONTINUE;
                nFiles[0]++;
                findings.addAll(scanFile(f, lang, projectFw, rel.toString().replace('\\', '/')));
                return FileVisitResult.CONTINUE;
            }
        });
        findings.addAll(projectLevelChecks(findings, root));
        int min = SEV.get(minSeverity);
        List<Finding> kept = findings.stream().filter(f -> SEV.get(f.severity) >= min)
                .sorted(Comparator.<Finding>comparingInt(f -> -SEV.get(f.severity)).thenComparing(f -> f.file).thenComparingInt(f -> f.line))
                .collect(Collectors.toList());

        String markdown = toMarkdown(kept, projectFw, nFiles[0]);
        if (out != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("root", root.toString());
            payload.put("frameworks", new ArrayList<>(projectFw));
            payload.put("files_scanned", nFiles[0]);
            payload.put("findings", kept.stream().map(Finding::toMap).collect(Collectors.toList()));
            write(out, json(payload) + "\n");
        }
        if (md != null) write(md, markdown);
        if (out == null && md == null) System.out.print(markdown);
        else System.out.println(nFiles[0] + " files | " + kept.size() + " findings | frameworks: "
                + (projectFw.isEmpty() ? "unknown" : String.join(", ", projectFw)));
    }
}
