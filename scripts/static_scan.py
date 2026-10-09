#!/usr/bin/env python3
"""
static_scan.py - find flakiness anti-patterns in Selenium / Playwright test code.

Findings are SUSPECTS, not proof. Correlate them with flaky_score.py results.

Usage:
  python static_scan.py <repo_root> [--out static.json] [--md static.md]
                        [--only "**/LoginTest*"] [--min-severity medium]

Languages: Java, Kotlin, C#, TypeScript/JavaScript, Python.
Framework is auto-detected per project and per file (imports), so Playwright rules
don't fire on Selenium code and vice versa.
"""
import argparse
import fnmatch
import json
import os
import re
import sys
from collections import Counter, defaultdict

SKIP_DIRS = {"node_modules", ".git", "target", "build", "dist", "out", ".gradle", ".idea",
             "venv", ".venv", "__pycache__", "test-results", "playwright-report",
             "allure-results", "test-output", ".flaky", "bin", "obj"}
EXTS = {".java": "java", ".kt": "java", ".cs": "cs", ".ts": "js", ".tsx": "js",
        ".js": "js", ".mjs": "js", ".cjs": "js", ".py": "py"}
SEV_ORDER = {"info": 0, "low": 1, "medium": 2, "high": 3}

# Each rule: id, frameworks ("selenium"/"playwright"/"any"), langs, severity,
# category, regex, message, fix hint.
RULES = [
    # ---------------- any framework
    ("GEN001", "any", {"java", "cs"}, "high", "timing",
     r"\bThread\.sleep\s*\(|\bTimeUnit\.\w+\.sleep\s*\(|\bThread\.Sleep\s*\(|Task\.Delay\s*\(",
     "Hard-coded sleep.", "Replace with an explicit wait for the condition you actually need."),
    ("GEN002", "any", {"py"}, "high", "timing",
     r"\btime\.sleep\s*\(|^\s*sleep\s*\(",
     "Hard-coded sleep.", "Replace with an explicit wait / auto-waiting assertion."),
    ("GEN003", "any", {"js"}, "high", "timing",
     r"new Promise\s*\(\s*\(?\s*\w*\s*\)?\s*=>\s*setTimeout|\bsleep\s*\(\s*\d+",
     "Hard-coded sleep via setTimeout/sleep helper.", "Wait for a UI/network condition instead."),
    ("GEN010", "any", {"java", "cs", "js", "py"}, "low", "test-data",
     r"Math\.random\(|new Random\(|\brandom\.(randint|choice|random)\(|Faker\(|faker\.",
     "Random test data without a fixed seed.", "Seed the generator or log the value so failures are reproducible."),
    ("GEN011", "any", {"java", "cs", "js", "py"}, "low", "environment",
     r"LocalDate(Time)?\.now\(|new Date\(\s*\)|Date\.now\(|datetime\.now\(|DateTime\.Now|date\.today\(",
     "Test depends on the current date/time.", "Inject a fixed clock (page.clock / mocked time) or compute expectations from the same source."),
    ("GEN020", "any", {"java", "cs"}, "medium", "error-handling",
     r"catch\s*\([^)]*\)\s*\{\s*\}",
     "Empty catch block swallows failures.", "Fail or log; hidden exceptions cause later, confusing failures."),
    ("GEN021", "any", {"py"}, "medium", "error-handling",
     r"except(\s+\w+(\s+as\s+\w+)?)?\s*:\s*pass\b",
     "Exception swallowed with pass.", "Let it fail or assert the expected state."),
    ("GEN022", "any", {"js"}, "medium", "error-handling",
     r"catch\s*(\(\s*\w*\s*\))?\s*\{\s*\}|\.catch\(\s*\(\)\s*=>\s*\{\s*\}\s*\)",
     "Empty catch swallows failures.", "Fail or log; don't hide errors."),
    ("GEN030", "any", {"java"}, "info", "retry-masking",
     r"retryAnalyzer|IRetryAnalyzer|@RepeatedIfExceptionsTest|@RetryingTest|rerunFailingTestsCount",
     "Retry mechanism present.", "Retries hide flakiness; report retried passes as flaky instead of green."),
    ("GEN031", "any", {"py"}, "info", "retry-masking",
     r"@pytest\.mark\.flaky|--reruns|@flaky",
     "Retry mechanism present.", "Track reruns as flaky signals; don't treat them as passes."),

    # ---------------- Selenium
    ("SEL001", "selenium", {"java", "cs", "py", "js"}, "medium", "timing",
     r"implicitlyWait|implicitly_wait|ImplicitWait",
     "Implicit wait configured.", "Don't mix implicit and explicit waits (wait times compound unpredictably). Use explicit waits only."),
    ("SEL002", "selenium", {"java", "cs", "py", "js"}, "medium", "locator",
     r"""["']/html/|["'](//?\w+\[\d+\]){2,}|By\.xpath\(\s*"[^"]*\[\d+\][^"]*\[\d+\]""",
     "Absolute or index-based XPath.", "Use stable attributes (data-testid, id, name, aria labels)."),
    ("SEL003", "selenium", {"java"}, "high", "parallelism",
     r"\bstatic\s+(?!final\b)(?:\w+\s+)*(WebDriver|RemoteWebDriver|ChromeDriver|FirefoxDriver|EdgeDriver|WebDriverWait)\s+\w+",
     "Static (shared) WebDriver/WebDriverWait.", "Use ThreadLocal<WebDriver> or per-test instances for parallel safety."),
    ("SEL004", "selenium", {"cs"}, "high", "parallelism",
     r"\bstatic\s+(?!readonly\b)(?:\w+\s+)*(IWebDriver|WebDriverWait)\s+\w+",
     "Static (shared) IWebDriver.", "Use ThreadLocal<IWebDriver> / per-test driver."),
    ("SEL005", "selenium", {"java"}, "medium", "order-dependency",
     r"dependsOnMethods|dependsOnGroups|@Test\s*\([^)]*priority\s*=|@Order\(|@TestMethodOrder",
     "Test order dependency.", "Make each test set up its own state (API/DB setup) so it can run alone and in parallel."),
    ("SEL006", "selenium", {"java", "cs", "py", "js"}, "medium", "stale-element",
     r"StaleElementReference",
     "StaleElementReference handled manually.", "Re-locate elements after DOM changes; wait for the re-render (stalenessOf) instead of retry loops."),
    ("SEL007", "selenium", {"java", "cs", "py", "js"}, "low", "interaction",
     r"executeScript\(\s*\"[^\"]*\.click\(\)|execute_script\(\s*[\"'][^\"']*\.click\(\)|ExecuteScript\(\s*\"[^\"]*\.click\(\)",
     "JavaScript click bypasses real interaction.", "Wait for element to be clickable / overlay to disappear, then use a native click."),
    ("SEL008", "selenium", {"java", "cs", "py", "js"}, "low", "locator",
     r"findElements?\([^)]*\)\s*\.get\(\s*\d+\s*\)|find_elements\([^)]*\)\[\d+\]|FindElements\([^)]*\)\[\d+\]",
     "Element picked by list index.", "Locate by a unique, meaningful attribute."),
    ("SEL009", "selenium", {"java", "py"}, "medium", "timing",
     r"\.getText\(\)\s*\)\s*\.(isEqualTo|contains)|assert(Equals|True)\([^;]*\.getText\(\)|assert\s+[^\n]*\.text\s*==",
     "Assertion on a one-shot getText().", "Wait for the expected text (textToBePresentInElement / wait.until) before asserting."),
    ("SEL010", "selenium", {"java", "cs", "py", "js"}, "low", "timing",
     r"pageLoadTimeout|set_page_load_timeout|PageLoad\s*=",
     "Page load timeout tuned.", "Timeouts don't fix races; confirm the real wait condition."),

    # ---------------- Playwright
    ("PW001", "playwright", {"js", "py"}, "high", "timing",
     r"\.waitForTimeout\s*\(|\.wait_for_timeout\s*\(",
     "Hard wait (waitForTimeout).", "Use web-first assertions (expect(locator).toBeVisible()) or wait for a response/event."),
    ("PW002", "playwright", {"js"}, "high", "non-retrying-assertion",
     r"expect\(\s*await\s+.*?\.(isVisible|isHidden|isEnabled|isChecked|textContent|innerText|getAttribute|inputValue|count|isDisabled)\(",
     "Non-retrying assertion on a one-shot read.", "Use web-first assertions: toBeVisible(), toHaveText(), toHaveAttribute(), toHaveCount()."),
    ("PW003", "playwright", {"py"}, "high", "non-retrying-assertion",
     r"assert\s+.*?\.(is_visible|is_hidden|is_enabled|text_content|inner_text|get_attribute|input_value|count)\(",
     "Non-retrying assert on a one-shot read.", "Use expect(locator).to_be_visible()/to_have_text()."),
    ("PW004", "playwright", {"js", "py"}, "medium", "interaction",
     r"force\s*:\s*true|force\s*=\s*True",
     "force: true skips actionability checks.", "Find what blocks the element (overlay, animation, disabled state) and wait for it."),
    ("PW005", "playwright", {"js", "py"}, "medium", "timing",
     r"networkidle",
     "networkidle wait (discouraged, unreliable with polling/websockets).", "Wait for a specific element or response (waitForResponse / expect)."),
    ("PW006", "playwright", {"js", "py"}, "medium", "order-dependency",
     r"describe\.serial|mode\s*:\s*['\"]serial['\"]",
     "Serial mode: tests depend on each other.", "Make tests independent; use fixtures/API setup for shared preconditions."),
    ("PW007", "playwright", {"js"}, "medium", "locator",
     r"page\.\$\$?\(|\.\$eval\(|\.\$\$eval\(|elementHandle|waitForSelector\(",
     "ElementHandle / waitForSelector API (no auto-wait, can go stale).", "Use locators: page.getByRole()/getByTestId()."),
    ("PW008", "playwright", {"js", "py"}, "low", "locator",
     r"\.nth\(\s*\d+\s*\)|\.first\(\)|\.last\(\)|\.first\b|\.last\b|nth-child|xpath=",
     "Position-based or XPath locator.", "Prefer getByRole/getByTestId/filter({ hasText }) to target a unique element."),
    ("PW009", "playwright", {"js"}, "high", "missing-await",
     None,  # handled specially
     "Playwright call without await.", "Add await; un-awaited actions race with the next step and end of test."),
    ("PW010", "playwright", {"js"}, "low", "test-data",
     r"test\.use\(\s*\{\s*storageState",
     "Shared storageState.", "Fine for auth, but tests mutating the same account in parallel collide; use per-worker accounts."),
]

PW_ASYNC_CALL = re.compile(
    r"^\s*(?:page|locator|\w+(?:Page|Locator|Btn|Button|Input|Link|Field)?)\s*"
    r"(?:\.[\w$]+\([^()]*\))*\.(click|fill|goto|press|check|uncheck|type|pressSequentially|"
    r"selectOption|hover|dblclick|setInputFiles|waitForURL|waitForResponse|reload|dragTo|focus|tap)\s*\(")
PW_EXPECT_NOAWAIT = re.compile(
    r"^\s*expect\(.*\)\s*(\.not)?\.(toBeVisible|toHaveText|toContainText|toHaveURL|toHaveTitle|toBeHidden|"
    r"toHaveCount|toHaveValue|toBeEnabled|toBeChecked|toHaveAttribute)\s*\(")


def detect_project_frameworks(root):
    fw = set()
    for name in ("package.json", "pom.xml", "build.gradle", "build.gradle.kts",
                 "requirements.txt", "pyproject.toml", "setup.py", "Pipfile"):
        p = os.path.join(root, name)
        if os.path.exists(p):
            try:
                txt = open(p, encoding="utf-8", errors="ignore").read().lower()
            except OSError:
                continue
            if "playwright" in txt:
                fw.add("playwright")
            if "selenium" in txt:
                fw.add("selenium")
    for f in os.listdir(root):
        if f.startswith("playwright.config"):
            fw.add("playwright")
    for dirpath, _, files in os.walk(root):
        if any(part in SKIP_DIRS for part in dirpath.split(os.sep)):
            continue
        for f in files:
            if f.endswith(".csproj"):
                try:
                    txt = open(os.path.join(dirpath, f), encoding="utf-8", errors="ignore").read().lower()
                    if "selenium" in txt:
                        fw.add("selenium")
                    if "playwright" in txt:
                        fw.add("playwright")
                except OSError:
                    pass
    return fw


def file_frameworks(text, project_fw):
    fw = set()
    if re.search(r"@playwright/test|from playwright|playwright\.(sync|async)_api|Microsoft\.Playwright|"
                 r"\bpage\.(goto|getBy|locator)\(|\bpage\.(get_by_|locator)", text):
        fw.add("playwright")
    if re.search(r"org\.openqa\.selenium|from selenium|OpenQA\.Selenium|selenium-webdriver|WebDriver\b|By\.(id|xpath|css)",
                 text):
        fw.add("selenium")
    if not fw:
        fw = set(project_fw)   # page objects/utilities without imports inherit project fw
    return fw


def strip_comment(line, lang):
    if lang == "py":
        return re.sub(r"(^|\s)#.*$", "", line)
    return re.sub(r"(^|[^:])//.*$", r"\1", line)


def scan_file(path, lang, project_fw, rel):
    try:
        text = open(path, encoding="utf-8", errors="ignore").read()
    except OSError:
        return []
    fws = file_frameworks(text, project_fw)
    findings = []
    lines = text.splitlines()
    in_block = False
    for i, raw in enumerate(lines, 1):
        line = raw
        if lang != "py":
            if in_block:
                if "*/" in line:
                    in_block = False
                    line = line.split("*/", 1)[1]
                else:
                    continue
            if "/*" in line and "*/" not in line.split("/*", 1)[1]:
                in_block = True
                line = line.split("/*", 1)[0]
        line = strip_comment(line, lang)
        if not line.strip():
            continue
        for rid, fw, langs, sev, cat, rx, msg, fix in RULES:
            if lang not in langs:
                continue
            if fw != "any" and fw not in fws:
                continue
            if rid == "PW009":
                s = line.strip()
                if s.startswith(("await ", "return ", "yield ")) or "await " in s.split("=")[0] or "=>" in s:
                    continue
                if PW_ASYNC_CALL.search(line) or PW_EXPECT_NOAWAIT.search(line):
                    findings.append(_f(rid, sev, cat, msg, fix, rel, i, raw))
                continue
            if re.search(rx, line):
                findings.append(_f(rid, sev, cat, msg, fix, rel, i, raw))
    return findings


def _f(rid, sev, cat, msg, fix, rel, line_no, raw):
    return {"rule": rid, "severity": sev, "category": cat, "message": msg, "fix": fix,
            "file": rel, "line": line_no, "code": raw.strip()[:200]}


def project_level_checks(findings, root, project_fw):
    """Cross-file signals that a single line can't show."""
    extra = []
    cats = Counter(f["rule"] for f in findings)
    # implicit + explicit wait mixing
    explicit = False
    for dirpath, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for fn in files:
            if os.path.splitext(fn)[1] in EXTS:
                try:
                    if re.search(r"WebDriverWait|ExpectedConditions",
                                 open(os.path.join(dirpath, fn), encoding="utf-8", errors="ignore").read()):
                        explicit = True
                        break
                except OSError:
                    pass
        if explicit:
            break
    if cats.get("SEL001") and explicit:
        extra.append({"rule": "SEL100", "severity": "high", "category": "timing",
                      "message": "Implicit and explicit waits are mixed in this project.",
                      "fix": "Set implicit wait to 0 and use explicit waits everywhere.",
                      "file": "(project)", "line": 0, "code": ""})
    # Playwright config retries
    for f in os.listdir(root):
        if f.startswith("playwright.config"):
            try:
                txt = open(os.path.join(root, f), encoding="utf-8", errors="ignore").read()
            except OSError:
                continue
            m = re.search(r"retries\s*:\s*([^,\n]+)", txt)
            if m and m.group(1).strip() not in ("0",):
                extra.append({"rule": "PW100", "severity": "info", "category": "retry-masking",
                              "message": f"Playwright retries configured ({m.group(1).strip()}).",
                              "fix": "Keep retries in CI if needed, but read the 'flaky' status in reports and fail on it (--fail-on-flaky-tests) or track it.",
                              "file": f, "line": 0, "code": m.group(0)})
    return extra


def to_markdown(findings, project_fw, n_files):
    by_sev = Counter(f["severity"] for f in findings)
    by_rule = Counter((f["rule"], f["message"]) for f in findings)
    lines = ["# Static flakiness scan", "",
             f"Frameworks detected: {', '.join(sorted(project_fw)) or 'unknown'} | Files scanned: {n_files} | "
             f"Findings: {len(findings)} (high {by_sev.get('high', 0)}, medium {by_sev.get('medium', 0)}, "
             f"low {by_sev.get('low', 0)}, info {by_sev.get('info', 0)})", "",
             "## By rule", "", "| Rule | Count | Issue |", "|---|---:|---|"]
    for (rid, msg), c in by_rule.most_common():
        lines.append(f"| {rid} | {c} | {msg} |")
    lines += ["", "## By file (high and medium only)", ""]
    per_file = defaultdict(list)
    for f in findings:
        if f["severity"] in ("high", "medium"):
            per_file[f["file"]].append(f)
    for file, fs in sorted(per_file.items(), key=lambda kv: -len(kv[1])):
        lines.append(f"### {file} ({len(fs)})")
        for f in sorted(fs, key=lambda x: x["line"]):
            code = f["code"].replace("`", "'")
            lines.append(f"- L{f['line']} **{f['rule']}** [{f['severity']}] {f['message']} `{code[:100]}`  \n  Fix: {f['fix']}")
        lines.append("")
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("root")
    ap.add_argument("--out")
    ap.add_argument("--md")
    ap.add_argument("--only", action="append", default=[], help="glob(s) of files to include (relative paths)")
    ap.add_argument("--min-severity", default="info", choices=list(SEV_ORDER))
    args = ap.parse_args()

    root = os.path.abspath(args.root)
    if not os.path.isdir(root):
        sys.exit(f"not a directory: {root}")
    project_fw = detect_project_frameworks(root)
    findings, n_files = [], 0
    for dirpath, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.startswith(".")]
        for fn in files:
            ext = os.path.splitext(fn)[1]
            if ext not in EXTS or fn.endswith(".d.ts") or fn.endswith(".min.js"):
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, root)
            if args.only and not any(fnmatch.fnmatch(rel, g) for g in args.only):
                continue
            n_files += 1
            findings.extend(scan_file(path, EXTS[ext], project_fw, rel))
    findings.extend(project_level_checks(findings, root, project_fw))
    minsev = SEV_ORDER[args.min_severity]
    findings = [f for f in findings if SEV_ORDER[f["severity"]] >= minsev]
    findings.sort(key=lambda f: (-SEV_ORDER[f["severity"]], f["file"], f["line"]))

    payload = {"root": root, "frameworks": sorted(project_fw), "files_scanned": n_files, "findings": findings}
    md = to_markdown(findings, project_fw, n_files)
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        json.dump(payload, open(args.out, "w", encoding="utf-8"), indent=2)
    if args.md:
        os.makedirs(os.path.dirname(os.path.abspath(args.md)), exist_ok=True)
        open(args.md, "w", encoding="utf-8").write(md)
    if not args.out and not args.md:
        print(md)
    else:
        print(f"{n_files} files | {len(findings)} findings | frameworks: {', '.join(sorted(project_fw)) or 'unknown'}")


if __name__ == "__main__":
    main()
