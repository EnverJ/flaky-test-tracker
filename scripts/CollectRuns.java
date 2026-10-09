import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

/**
 * CollectRuns - run any test command N times and archive each run's reports.
 *
 * Java 11+, no dependencies, works on Windows, macOS and Linux. Run without compiling:
 *   java scripts/CollectRuns.java -n 10 -c "mvn -q test -Dtest=LoginTest" -r target/surefire-reports
 *   java scripts/CollectRuns.java -n 10 -c "gradlew test --rerun-tasks --tests *Checkout*" -r build/test-results/test
 *   java scripts/CollectRuns.java -n 10 -c "pytest tests/ui --junitxml=report.xml" -r report.xml
 *   java scripts/CollectRuns.java -n 10 -c "npx playwright test --retries=0 --reporter=junit" -r results.xml \
 *        -e PLAYWRIGHT_JUNIT_OUTPUT_NAME=results.xml
 *
 * Options:
 *   -n <count>        number of runs (default 10)
 *   -c <command>      test command, run through the system shell (sh -c / cmd /c)
 *   -r <report path>  report file or folder the command produces
 *   -o <out dir>      where runs are archived (default .flaky/runs)
 *   -e KEY=VALUE      extra environment variable (repeatable)
 *   -s <count>        stop after this many failing runs (default 0 = never)
 *
 * Safety: the report path is deleted before each run, so -r and -o must be real
 * (non-symlink) paths strictly inside the repository; anything else is refused.
 *
 * Each run's report is copied to <out>/run_001, run_002, ... with the console log and a
 * run-meta.txt. A failing test command does NOT stop the loop (that's the point). Then:
 *   java scripts/FlakyScore.java .flaky/runs
 */
public class CollectRuns {

    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
        int n = 10, stopAfter = 0;
        String cmd = null, report = null, out = ".flaky/runs";
        Map<String, String> env = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-n": n = Integer.parseInt(args[++i]); break;
                case "-c": cmd = args[++i]; break;
                case "-r": report = args[++i]; break;
                case "-o": out = args[++i]; break;
                case "-s": stopAfter = Integer.parseInt(args[++i]); break;
                case "-e": {
                    String kv = args[++i];
                    int eq = kv.indexOf('=');
                    if (eq <= 0) { System.err.println("bad -e value (need KEY=VALUE): " + kv); System.exit(2); }
                    env.put(kv.substring(0, eq), kv.substring(eq + 1));
                    break;
                }
                case "-h": case "--help": usage(); return;
                default: System.err.println("unknown argument: " + args[i]); usage(); System.exit(2);
            }
        }
        if (cmd == null || report == null) { System.err.println("need -c <command> and -r <report path>"); usage(); System.exit(2); }

        // Safety: the report path is deleted before every run, so both paths must be real
        // (non-symlink) locations strictly inside the repository.
        Path repoRoot = repoRoot();
        Path reportPath = validateUnderRepo(Paths.get(report), "report path (-r)", repoRoot);
        Path outDir = validateUnderRepo(Paths.get(out), "output dir (-o)", repoRoot);
        if (reportPath.equals(outDir) || outDir.startsWith(reportPath)) {
            System.err.println("error: output dir must not be inside the report path (it is deleted each run)");
            System.exit(2);
        }
        Files.createDirectories(outDir);
        int startIdx;
        try (Stream<Path> s = Files.list(outDir)) {
            startIdx = (int) s.filter(Files::isDirectory).filter(p -> p.getFileName().toString().startsWith("run_")).count();
        }
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        String commit = gitCommit();

        int fails = 0;
        for (int i = 1; i <= n; i++) {
            String idx = String.format("%03d", startIdx + i);
            Path dest = outDir.resolve("run_" + idx);
            Path log = outDir.resolve("run_" + idx + ".log");
            if (Files.isSymbolicLink(reportPath)) fail("report path became a symlink; refusing to delete it: " + reportPath);
            deleteRecursively(reportPath);                     // never score a stale report (does not follow symlinks)
            System.out.println("== run " + i + "/" + n + " -> " + dest);

            ProcessBuilder pb = windows ? new ProcessBuilder("cmd", "/c", cmd) : new ProcessBuilder("sh", "-c", cmd);
            pb.environment().putAll(env);
            pb.redirectErrorStream(true);
            pb.redirectOutput(log.toFile());
            long t0 = System.currentTimeMillis();
            int rc = pb.start().waitFor();
            long secs = (System.currentTimeMillis() - t0) / 1000;

            Files.createDirectories(dest);
            if (Files.exists(reportPath)) copyRecursively(reportPath, dest.resolve(reportPath.getFileName()));
            else System.out.println("   warn: no report at " + report + " (see " + log + ")");
            String meta = "run=" + (startIdx + i) + "\nexit_code=" + rc + "\nseconds=" + secs + "\ncommit=" + commit + "\n";
            Files.write(dest.resolve("run-meta.txt"), meta.getBytes(StandardCharsets.UTF_8));
            System.out.println("   exit=" + rc + "  " + secs + "s");

            if (rc != 0) fails++;
            if (stopAfter > 0 && fails >= stopAfter) { System.out.println("stopping: " + fails + " failing runs"); break; }
        }
        System.out.println("done: " + fails + " failing run(s). Score with: java scripts/FlakyScore.java " + out);
    }

    static void usage() {
        System.out.println("Usage: java CollectRuns.java -n 10 -c \"<test command>\" -r <report path> [-o .flaky/runs] [-e KEY=VALUE]... [-s 0]");
    }

    /** Repository root (git top level), or the current directory outside a git repo. Real path, symlinks resolved. */
    static Path repoRoot() throws IOException {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--show-toplevel").redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (p.waitFor() == 0 && !s.isEmpty()) return Paths.get(s).toRealPath();
        } catch (Exception ignored) { }
        return Paths.get("").toAbsolutePath().toRealPath();
    }

    /**
     * Rejects a path that is a symlink, contains a symlinked parent that leads outside the repo,
     * escapes the repo (absolute or via ".."), or IS the repo root. Returns the absolute, normalized path.
     */
    static Path validateUnderRepo(Path path, String label, Path repoRoot) throws IOException {
        Path abs = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(abs)) fail(label + " must not be a symlink: " + path);
        // Resolve symlinks in the deepest existing ancestor so a symlinked parent can't escape the repo.
        Path existing = abs;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        Path real = existing == null ? abs : existing.toRealPath().resolve(existing.relativize(abs)).normalize();
        if (!real.startsWith(repoRoot)) fail(label + " must stay inside the repository (" + repoRoot + "): " + path);
        if (real.equals(repoRoot)) fail(label + " must not be the repository root: " + path);
        return real;
    }

    static void fail(String msg) {
        System.err.println("error: " + msg);
        System.exit(2);
    }

    static String gitCommit() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return p.waitFor() == 0 && !s.isEmpty() ? s : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException { Files.delete(f); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException { Files.delete(d); return FileVisitResult.CONTINUE; }
        });
    }

    static void copyRecursively(Path src, Path dst) throws IOException {
        if (Files.isRegularFile(src)) { Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING); return; }
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(d).toString().replace(File.separatorChar, '/')));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.copy(f, dst.resolve(src.relativize(f).toString().replace(File.separatorChar, '/')), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
