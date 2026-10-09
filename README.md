# Flaky Test Tracker agent

Finds flaky tests in any Selenium or Playwright project, proves *why* they're flaky,
and proposes (and verifies) fixes.

## Design: evidence first, then diagnosis, then fix

Most "flaky test detectors" only read code, which produces long lists of guesses.
This agent splits the job into deterministic tools (fast, repeatable, no LLM) and
AI reasoning (diagnosis and fixes), so every conclusion is backed by numbers.

```
            ┌───────────────────┐
  repo ───► │ 0. Discover       │  framework, runner, report paths, retries, parallelism
            └─────────┬─────────┘
                      ▼
            ┌───────────────────┐   CI artifacts, or collect_runs.sh / --repeat-each
            │ 1. Collect runs   │──────────────────────────────────────────────┐
            └─────────┬─────────┘                                              │
                      ▼                                                        ▼
            ┌───────────────────┐                                   ┌───────────────────┐
            │ 2. flaky_score.py │  PROOF: FLAKY / BROKEN / STABLE   │ 3. static_scan.py │  SUSPECTS:
            └─────────┬─────────┘  + error signatures               └─────────┬─────────┘  anti-patterns
                      └──────────────────────┬──────────────────────────────────┘
                                             ▼
                                   ┌───────────────────┐  read test + page objects + fixtures + traces,
                                   │ 4. Diagnose (AI)  │  classify root cause, confidence
                                   └─────────┬─────────┘
                                             ▼
                                   ┌───────────────────┐  minimal diff from fix-patterns.md,
                                   │ 5. Fix (AI)       │  fix shared helpers first
                                   └─────────┬─────────┘
                                             ▼
                                   ┌───────────────────┐  rerun 20x under CI parallelism
                                   │ 6. Verify         │  → Verified / Improved / Not fixed
                                   └─────────┬─────────┘
                                             ▼
                                   .flaky/flaky-report.md  (+ history.jsonl for trends)
```

Why it works on any project: everything goes through **JUnit XML or Playwright JSON**,
which every runner can emit (Surefire, Gradle, TestNG `junitreports`, pytest
`--junitxml`, Playwright `junit`/`json`), and the scanner auto-detects framework and
language per file.

## What's in the package

| File | Purpose |
|---|---|
| `SKILL.md` | The agent's instructions: rules, 7-phase workflow, modes |
| `scripts/flaky_score.py` | Scores tests across runs; reads retries (`<flakyFailure>`, Playwright `flaky`); groups failure signatures; optional trend history |
| `scripts/static_scan.py` | ~30 anti-pattern rules for Java, C#, TS/JS, Python (sleeps, non-retrying assertions, missing `await`, static drivers, order dependency, brittle locators, `force: true`, retries masking flakes…) |
| `scripts/collect_runs.sh` | Runs any test command N times and archives each run's reports |
| `references/fix-patterns.md` | 11 root-cause categories → evidence → fix, with Selenium and Playwright code, plus quarantine and prevention guidance |

Python 3.8+ standard library only — no installs.

## Using it

**Claude Code (recommended)** — copy the folder into your project or user skills:
```
<your-repo>/.claude/skills/flaky-test-tracker/     # this project only
~/.claude/skills/flaky-test-tracker/               # every project
```
Then ask: *"Find flaky tests in this project"*, *"Investigate LoginTest.validLogin"*,
*"Scan only, using the reports in ci-artifacts/"*.

**GitHub Copilot / other agents** — put the folder in your repo and point the agent's
custom instructions at `SKILL.md` (it's plain Markdown; the scripts are ordinary CLIs).

**Without an agent** — the scripts work on their own:
```bash
# Playwright: 10 repeats, no retries
PLAYWRIGHT_JSON_OUTPUT_NAME=.flaky/pw.json npx playwright test --repeat-each=10 --retries=0 --reporter=json
python scripts/flaky_score.py .flaky/pw.json --md .flaky/score.md

# Maven + TestNG/JUnit: 10 separate runs
scripts/collect_runs.sh -n 10 -c "mvn -q test" -r target/surefire-reports
python scripts/flaky_score.py ".flaky/runs/run_*" --md .flaky/score.md --history .flaky/history.jsonl

# Anti-pattern scan
python scripts/static_scan.py . --md .flaky/static.md --min-severity medium
```

## Ideas to extend it (roughly in value order)

1. **CI gate** — a GitHub Actions/Jenkins step that runs `flaky_score.py` on archived
   reports from the last N runs of `main` and posts the report on a schedule.
2. **Lint rules** — turn the high-severity scanner rules into eslint-plugin-playwright /
   Checkstyle rules so new flakes can't merge.
3. **Pre-merge burn-in** — run only changed/new tests 10× in the PR.
4. **Trend dashboard** — chart `history.jsonl` (flake rate per test over time).
5. **Auto-PR mode** — once you trust it, let the agent open one PR per verified fix.
