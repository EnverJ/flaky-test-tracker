import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * FlakyScore - score tests for flakiness across multiple runs.
 *
 * Java 11+, no dependencies. Run without compiling:
 *   java scripts/FlakyScore.java .flaky/runs --out .flaky/score.json --md .flaky/score.md
 *
 * Inputs (files or directories; each path = one run unless --each-file-is-run):
 *   - JUnit XML: Maven Surefire, Gradle, TestNG junitreports, pytest --junitxml,
 *     Playwright junit reporter. Surefire flakyFailure/flakyError = passed on retry.
 *   - Playwright JSON reporter output (per-attempt results, "flaky" status, repeat-each).
 *
 * A directory whose children are run_* folders (from CollectRuns) is expanded so each
 * child is one run.
 *
 * Classification per test:
 *   FLAKY             mixed pass/fail across runs, or passed only after a retry
 *   BROKEN            failed every executed attempt (>= --min-runs)
 *   STABLE            passed every executed attempt (>= --min-runs)
 *   INSUFFICIENT_DATA fewer than --min-runs executed attempts and no flaky signal
 */
public class FlakyScore {

    enum Outcome { PASS, FAIL, SKIP }

    static final class Attempt {
        final Outcome outcome; final String message; final int retry; final String file; final Double duration;
        Attempt(Outcome o, String m, int r, String f, Double d) { outcome = o; message = m == null ? "" : m; retry = r; file = f; duration = d; }
    }

    /** test id -> run id -> attempts */
    static final Map<String, TreeMap<String, List<Attempt>>> SINK = new LinkedHashMap<>();

    static void add(String testId, String runId, Attempt a) {
        SINK.computeIfAbsent(testId, k -> new TreeMap<>()).computeIfAbsent(runId, k -> new ArrayList<>()).add(a);
    }

    // ------------------------------------------------------------- signatures
    static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");
    static final Pattern HEX = Pattern.compile("0x[0-9a-fA-F]+");
    static final Pattern UUID_RX = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f-]{27,}");
    static final Pattern NUM = Pattern.compile("\\d+(\\.\\d+)?");
    static final Pattern WS = Pattern.compile("\\s+");

    static String normalizeSignature(String msg) {
        if (msg == null || msg.isBlank()) return "(no message)";
        String first = msg.strip().split("\\R", 2)[0];
        if (first.length() > 300) first = first.substring(0, 300);
        first = ANSI.matcher(first).replaceAll("");
        first = HEX.matcher(first).replaceAll("<hex>");
        first = UUID_RX.matcher(first).replaceAll("<uuid>");
        first = NUM.matcher(first).replaceAll("<n>");
        first = WS.matcher(first).replaceAll(" ");
        return first.strip();
    }

    // ------------------------------------------------------------- JUnit XML
    static void parseJUnit(Path path, String runId) {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            doc = b.parse(path.toFile());
        } catch (Exception e) {
            System.err.println("warn: cannot parse " + path + ": " + e.getMessage());
            return;
        }
        NodeList cases = doc.getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength(); i++) {
            Element tc = (Element) cases.item(i);
            String cls = tc.getAttribute("classname");
            String name = tc.getAttribute("name").isEmpty() ? "(unnamed)" : tc.getAttribute("name");
            String tid = cls.isEmpty() ? name : cls + "::" + name;
            String file = tc.getAttribute("file").isEmpty() ? null : tc.getAttribute("file");
            Double dur = null;
            try { if (!tc.getAttribute("time").isEmpty()) dur = Double.parseDouble(tc.getAttribute("time").replace(",", "")); }
            catch (NumberFormatException ignored) { }

            Element failure = child(tc, "failure");
            if (failure == null) failure = child(tc, "error");
            Element skipped = child(tc, "skipped");
            List<Element> flaky = children(tc, "flakyFailure", "flakyError");
            List<Element> rerun = children(tc, "rerunFailure", "rerunError");

            // Surefire retries inside one run: each earlier failed attempt is recorded.
            int r = 0;
            for (Element ff : flaky) add(tid, runId, new Attempt(Outcome.FAIL, msgOf(ff), r++, file, null));
            for (Element rf : rerun) add(tid, runId, new Attempt(Outcome.FAIL, msgOf(rf), r++, file, null));

            if (failure != null) add(tid, runId, new Attempt(Outcome.FAIL, msgOf(failure), r, file, dur));
            else if (skipped != null) add(tid, runId, new Attempt(Outcome.SKIP, msgOf(skipped), r, file, dur));
            else add(tid, runId, new Attempt(Outcome.PASS, "", r, file, dur));
        }
    }

    static Element child(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && ((Element) n).getTagName().equals(tag)) return (Element) n;
        return null;
    }

    static List<Element> children(Element parent, String... tags) {
        Set<String> want = new HashSet<>(Arrays.asList(tags));
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && want.contains(((Element) n).getTagName())) out.add((Element) n);
        return out;
    }

    static String msgOf(Element e) {
        String m = e.getAttribute("message");
        if (m.isEmpty()) m = e.getTextContent() == null ? "" : e.getTextContent();
        if (m.isBlank()) m = e.getAttribute("type");
        return m.strip();
    }

    // ------------------------------------------------------------- Playwright JSON
    static final Set<String> PW_FAIL = Set.of("failed", "timedOut", "interrupted");

    @SuppressWarnings("unchecked")
    static void parsePlaywright(Map<String, Object> data, String runId) {
        for (Object s : list(data.get("suites"))) walkSuite((Map<String, Object>) s, new ArrayList<>(), runId);
    }

    @SuppressWarnings("unchecked")
    static void walkSuite(Map<String, Object> suite, List<String> trail, String runId) {
        String title = str(suite.get("title"));
        List<String> newTrail = new ArrayList<>(trail);
        if (!title.isEmpty() && !title.matches(".*\\.(ts|js|mjs|py)$")) newTrail.add(title);
        String suiteFile = str(suite.get("file"));
        for (Object so : list(suite.get("specs"))) {
            Map<String, Object> spec = (Map<String, Object>) so;
            String specFile = str(spec.get("file")).isEmpty() ? suiteFile : str(spec.get("file"));
            for (Object to : list(spec.get("tests"))) {
                Map<String, Object> t = (Map<String, Object>) to;
                List<String> parts = new ArrayList<>();
                parts.add(specFile); parts.addAll(newTrail); parts.add(str(spec.get("title")));
                String tid = String.join(" > ", parts);
                String proj = str(t.get("projectName"));
                if (!proj.isEmpty()) tid += " [" + proj + "]";
                int rep = num(t.get("repeatEachIndex")).intValue();
                String rid = runId + "#r" + String.format("%04d", rep);
                List<Object> results = list(t.get("results"));
                if (results.isEmpty() && "skipped".equals(str(t.get("status"))))
                    add(tid, rid, new Attempt(Outcome.SKIP, "", 0, specFile, null));
                boolean expectFail = "failed".equals(str(t.get("expectedStatus")));
                for (Object ro : results) {
                    Map<String, Object> r = (Map<String, Object>) ro;
                    String st = str(r.get("status"));
                    Outcome oc = "skipped".equals(st) ? Outcome.SKIP : PW_FAIL.contains(st) ? Outcome.FAIL : Outcome.PASS;
                    if (expectFail && oc != Outcome.SKIP) oc = oc == Outcome.FAIL ? Outcome.PASS : Outcome.FAIL; // test.fail()
                    Object err = r.get("error");
                    if (err == null && !list(r.get("errors")).isEmpty()) err = list(r.get("errors")).get(0);
                    String msg = err instanceof Map ? str(((Map<String, Object>) err).get("message")) : err == null ? "" : err.toString();
                    if ("timedOut".equals(st) && msg.isEmpty()) msg = "Test timeout exceeded";
                    add(tid, rid, new Attempt(oc, msg, num(r.get("retry")).intValue(), specFile, num(r.get("duration")).doubleValue() / 1000.0));
                }
            }
        }
        for (Object c : list(suite.get("suites"))) walkSuite((Map<String, Object>) c, newTrail, runId);
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : Collections.emptyList(); }
    static String str(Object o) { return o == null ? "" : o.toString(); }
    static Number num(Object o) { return o instanceof Number ? (Number) o : 0; }

    // ------------------------------------------------------------- loading
    @SuppressWarnings("unchecked")
    static void load(Path p, String runId) {
        String n = p.getFileName().toString();
        if (n.endsWith(".json")) {
            try {
                Object data = Json.parse(Files.readString(p, StandardCharsets.UTF_8));
                if (data instanceof Map && ((Map<String, Object>) data).containsKey("suites"))
                    parsePlaywright((Map<String, Object>) data, runId);
            } catch (Exception e) {
                System.err.println("warn: cannot read " + p + ": " + e.getMessage());
            }
        } else if (n.endsWith(".xml")) {
            parseJUnit(p, runId);
        }
    }

    static List<Path> reportFiles(Path p) throws IOException {
        if (Files.isRegularFile(p)) return List.of(p);
        try (Stream<Path> s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).filter(f -> {
                String n = f.getFileName().toString();
                if (!(n.endsWith(".xml") || n.endsWith(".json"))) return false;
                // TestNG native results duplicate junitreports; skip Surefire summaries
                return !(n.equals("testng-results.xml") || n.equals("testng-failed.xml") || n.startsWith("failsafe-summary"));
            }).sorted().collect(Collectors.toList());
        }
    }

    /** A folder that only holds run_* subfolders (CollectRuns output) expands to one run per subfolder. */
    static List<Path> expandRuns(Path p) throws IOException {
        if (!Files.isDirectory(p)) return List.of(p);
        List<Path> runs;
        try (Stream<Path> s = Files.list(p)) {
            runs = s.filter(Files::isDirectory).filter(d -> d.getFileName().toString().startsWith("run_")).sorted().collect(Collectors.toList());
        }
        return runs.isEmpty() ? List.of(p) : runs;
    }

    // ------------------------------------------------------------- scoring
    static double flipRate(List<Outcome> seq) {
        List<Outcome> s = seq.stream().filter(o -> o != Outcome.SKIP).collect(Collectors.toList());
        if (s.size() < 2) return 0.0;
        int flips = 0;
        for (int i = 1; i < s.size(); i++) if (s.get(i) != s.get(i - 1)) flips++;
        return (double) flips / (s.size() - 1);
    }

    static double round(double v, int places) { double f = Math.pow(10, places); return Math.round(v * f) / f; }

    static List<Map<String, Object>> score(int minRuns) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<String, TreeMap<String, List<Attempt>>> e : SINK.entrySet()) {
            List<Outcome> runOutcomes = new ArrayList<>();
            List<Attempt> all = new ArrayList<>();
            int passedOnRetry = 0;
            Map<String, Integer> sigs = new LinkedHashMap<>();
            List<Double> durations = new ArrayList<>();
            String file = null;
            for (List<Attempt> atts0 : e.getValue().values()) {
                List<Attempt> atts = new ArrayList<>(atts0);
                atts.sort(Comparator.comparingInt(a -> a.retry));
                all.addAll(atts);
                for (Attempt a : atts) {
                    if (file == null && a.file != null) file = a.file;
                    if (a.outcome == Outcome.FAIL) sigs.merge(normalizeSignature(a.message), 1, Integer::sum);
                    if (a.duration != null && a.duration > 0) durations.add(a.duration);
                }
                List<Attempt> executed = atts.stream().filter(a -> a.outcome != Outcome.SKIP).collect(Collectors.toList());
                if (executed.isEmpty()) { runOutcomes.add(Outcome.SKIP); continue; }
                Outcome fin = executed.get(executed.size() - 1).outcome;
                if (fin == Outcome.PASS && executed.subList(0, executed.size() - 1).stream().anyMatch(a -> a.outcome == Outcome.FAIL))
                    passedOnRetry++;
                runOutcomes.add(fin);
            }
            long nExec = all.stream().filter(a -> a.outcome != Outcome.SKIP).count();
            long nFail = all.stream().filter(a -> a.outcome == Outcome.FAIL).count();
            long nPass = nExec - nFail;
            double failRate = nExec == 0 ? 0 : (double) nFail / nExec;
            double fr = flipRate(runOutcomes);

            String cls;
            if (passedOnRetry > 0 || (nPass > 0 && nFail > 0)) cls = "FLAKY";
            else if (nExec < minRuns) cls = "INSUFFICIENT_DATA";
            else if (nFail == nExec) cls = "BROKEN";
            else cls = "STABLE";

            // Score 0-100: how unpredictable the test is.
            // 1 - |2p - 1| peaks at a 50% failure rate; flip rate rewards alternation.
            double unpredictability = nExec == 0 ? 0 : 1 - Math.abs(2 * failRate - 1);
            double score = 0;
            if (cls.equals("FLAKY")) {
                score = 100 * (0.6 * unpredictability + 0.4 * fr);
                if (passedOnRetry > 0) score = Math.max(score, 25.0);
            }

            StringBuilder seq = new StringBuilder();
            for (Outcome o : runOutcomes) seq.append(o == Outcome.PASS ? 'P' : o == Outcome.FAIL ? 'F' : 'S');

            List<Map<String, Object>> sigList = sigs.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue()).limit(5)
                    .map(s -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("signature", s.getKey()); m.put("count", s.getValue()); return m; })
                    .collect(Collectors.toList());

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("test", e.getKey());
            r.put("file", file);
            r.put("classification", cls);
            r.put("flaky_score", round(score, 1));
            r.put("runs", runOutcomes.size());
            r.put("attempts", nExec);
            r.put("passes", nPass);
            r.put("failures", nFail);
            r.put("failure_rate", round(failRate, 3));
            r.put("flip_rate", round(fr, 3));
            r.put("passed_on_retry_runs", passedOnRetry);
            r.put("run_sequence", seq.toString());
            r.put("avg_duration_s", durations.isEmpty() ? null : round(durations.stream().mapToDouble(d -> d).average().orElse(0), 2));
            r.put("max_duration_s", durations.isEmpty() ? null : round(durations.stream().mapToDouble(d -> d).max().orElse(0), 2));
            r.put("error_signatures", sigList);
            results.add(r);
        }
        Map<String, Integer> order = Map.of("FLAKY", 0, "BROKEN", 1, "INSUFFICIENT_DATA", 2, "STABLE", 3);
        results.sort(Comparator.<Map<String, Object>>comparingInt(r -> order.get((String) r.get("classification")))
                .thenComparing(r -> -(Double) r.get("flaky_score"))
                .thenComparing(r -> (String) r.get("test")));
        return results;
    }

    // ------------------------------------------------------------- output
    @SuppressWarnings("unchecked")
    static String topSignature(Map<String, Object> r) {
        List<Object> s = (List<Object>) r.get("error_signatures");
        return s.isEmpty() ? "" : str(((Map<String, Object>) s.get(0)).get("signature"));
    }

    static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    static String toMarkdown(List<Map<String, Object>> results, int nRuns) {
        Map<String, Long> counts = results.stream().collect(Collectors.groupingBy(r -> (String) r.get("classification"), Collectors.counting()));
        StringBuilder sb = new StringBuilder("# Flaky score report\n\n");
        sb.append("Runs analyzed: ").append(nRuns).append(" | Tests: ").append(results.size());
        for (String k : List.of("FLAKY", "BROKEN", "STABLE", "INSUFFICIENT_DATA"))
            sb.append(" | ").append(k).append(": ").append(counts.getOrDefault(k, 0L));
        sb.append("\n\n");
        List<Map<String, Object>> flaky = results.stream().filter(r -> "FLAKY".equals(r.get("classification"))).collect(Collectors.toList());
        if (!flaky.isEmpty()) {
            sb.append("## Flaky tests\n\n| Score | Test | Pass/Fail | Retry-passes | Sequence | Top error signature |\n|---:|---|---|---:|---|---|\n");
            for (Map<String, Object> r : flaky)
                sb.append("| ").append(r.get("flaky_score")).append(" | `").append(r.get("test")).append("` | ")
                  .append(r.get("passes")).append("/").append(r.get("failures")).append(" | ")
                  .append(r.get("passed_on_retry_runs")).append(" | `").append(r.get("run_sequence")).append("` | ")
                  .append(cut(topSignature(r).replace("|", "\\|"), 120)).append(" |\n");
            sb.append("\n");
        }
        List<Map<String, Object>> broken = results.stream().filter(r -> "BROKEN".equals(r.get("classification"))).collect(Collectors.toList());
        if (!broken.isEmpty()) {
            sb.append("## Broken (always failing \u2014 not flaky)\n\n");
            for (Map<String, Object> r : broken)
                sb.append("- `").append(r.get("test")).append("` \u2014 ").append(r.get("failures")).append(" failures \u2014 ").append(cut(topSignature(r), 120)).append("\n");
            sb.append("\n");
        }
        long insufficient = counts.getOrDefault("INSUFFICIENT_DATA", 0L);
        if (insufficient > 0) sb.append("_").append(insufficient).append(" test(s) had too few runs to judge; collect more runs._\n");
        return sb.toString();
    }

    static void write(String path, String content, boolean append) throws IOException {
        Path p = Paths.get(path).toAbsolutePath();
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        if (append) Files.writeString(p, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        else Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------- main
    static void usage() {
        System.out.println("Usage: java FlakyScore.java <report paths...> [--each-file-is-run] [--min-runs 3]\n"
                + "                         [--out score.json] [--md score.md] [--history history.jsonl]\n"
                + "Each path is one run (a JUnit XML file, a folder of XML files, or a Playwright JSON report).\n"
                + "A folder of run_* subfolders (CollectRuns output) counts each subfolder as one run.");
    }

    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
        List<String> paths = new ArrayList<>();
        boolean eachFileIsRun = false;
        int minRuns = 3;
        String out = null, md = null, history = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--each-file-is-run": eachFileIsRun = true; break;
                case "--min-runs": minRuns = Integer.parseInt(args[++i]); break;
                case "--out": out = args[++i]; break;
                case "--md": md = args[++i]; break;
                case "--history": history = args[++i]; break;
                case "-h": case "--help": usage(); return;
                default: paths.add(args[i]);
            }
        }
        if (paths.isEmpty()) { usage(); System.exit(2); }

        List<Path> runPaths = new ArrayList<>();
        for (String p : paths) {
            Path path = Paths.get(p);
            if (!Files.exists(path)) { System.err.println("warn: " + p + " not found"); continue; }
            runPaths.addAll(expandRuns(path));
        }
        int runCount = 0;
        for (int i = 0; i < runPaths.size(); i++) {
            List<Path> files = reportFiles(runPaths.get(i));
            if (eachFileIsRun) {
                for (int j = 0; j < files.size(); j++) { load(files.get(j), String.format("run%03d.%03d", i, j)); runCount++; }
            } else {
                String rid = String.format("run%03d", i);
                for (Path f : files) load(f, rid);
                runCount++;
            }
        }
        if (SINK.isEmpty()) { System.err.println("No test results found. Check the report paths/format."); System.exit(2); }

        List<Map<String, Object>> results = score(minRuns);
        String generated = Instant.now().toString();
        String markdown = toMarkdown(results, runCount);

        if (out != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("generated_at", generated);
            payload.put("inputs", paths);
            payload.put("tests", results);
            write(out, Json.write(payload, 0) + "\n", false);
        }
        if (md != null) write(md, markdown, false);
        if (history != null) {
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> r : results) {
                String c = (String) r.get("classification");
                if (!c.equals("FLAKY") && !c.equals("BROKEN")) continue;
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("ts", generated); h.put("test", r.get("test")); h.put("class", c);
                h.put("score", r.get("flaky_score")); h.put("passes", r.get("passes")); h.put("failures", r.get("failures"));
                sb.append(Json.write(h, -1)).append("\n");
            }
            write(history, sb.toString(), true);
        }
        if (out == null && md == null) System.out.print(markdown);
        else {
            Map<String, Long> counts = results.stream().collect(Collectors.groupingBy(r -> (String) r.get("classification"), LinkedHashMap::new, Collectors.counting()));
            System.out.println(results.size() + " tests | " + counts.entrySet().stream().map(x -> x.getKey() + "=" + x.getValue()).collect(Collectors.joining(", ")));
        }
    }

    // ------------------------------------------------------------- minimal JSON
    /** Small JSON reader/writer so the tool has no dependencies. */
    static final class Json {
        private final String s; private int i;
        private Json(String s) { this.s = s; }

        static Object parse(String text) {
            Json j = new Json(text);
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i != j.s.length()) throw new IllegalArgumentException("trailing data at " + j.i);
            return v;
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Object value() {
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws(); String k = string(); ws();
                if (s.charAt(i++) != ':') throw new IllegalArgumentException("expected : at " + i);
                ws(); m.put(k, value()); ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected , at " + i);
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws(); l.add(value()); ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected , at " + i);
            }
        }

        private String string() {
            if (s.charAt(i) != '"') throw new IllegalArgumentException("expected string at " + i);
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: sb.append(e);
                }
            }
        }

        private Number number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(st, i);
            if (n.isEmpty()) throw new IllegalArgumentException("bad value at " + st);
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            try { return Long.parseLong(n); } catch (NumberFormatException ex) { return Double.parseDouble(n); }
        }

        /** indent < 0 writes compact one-line JSON. */
        @SuppressWarnings("unchecked")
        static String write(Object v, int indent) {
            StringBuilder sb = new StringBuilder();
            write(v, indent, 0, sb);
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private static void write(Object v, int indent, int depth, StringBuilder sb) {
            boolean pretty = indent >= 0;
            String nl = pretty ? "\n" : "";
            String pad = pretty ? "  ".repeat(depth + 1) : "";
            String padEnd = pretty ? "  ".repeat(depth) : "";
            if (v == null) sb.append("null");
            else if (v instanceof String) quote((String) v, sb);
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else if (v instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) v;
                if (m.isEmpty()) { sb.append("{}"); return; }
                sb.append("{").append(nl);
                int k = 0;
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    sb.append(pad); quote(e.getKey(), sb); sb.append(pretty ? ": " : ":");
                    write(e.getValue(), indent, depth + 1, sb);
                    if (++k < m.size()) sb.append(",");
                    sb.append(pretty ? nl : (k < m.size() ? " " : ""));
                }
                sb.append(padEnd).append("}");
            } else if (v instanceof Collection) {
                Collection<Object> c = (Collection<Object>) v;
                if (c.isEmpty()) { sb.append("[]"); return; }
                sb.append("[").append(nl);
                int k = 0;
                for (Object o : c) {
                    sb.append(pad); write(o, indent, depth + 1, sb);
                    if (++k < c.size()) sb.append(",");
                    sb.append(pretty ? nl : (k < c.size() ? " " : ""));
                }
                sb.append(padEnd).append("]");
            } else quote(v.toString(), sb);
        }

        private static void quote(String s, StringBuilder sb) {
            sb.append('"');
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
                }
            }
            sb.append('"');
        }
    }
}
