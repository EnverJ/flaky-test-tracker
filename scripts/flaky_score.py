#!/usr/bin/env python3
"""
flaky_score.py - score tests for flakiness across multiple runs.

Inputs (any mix, files or directories, each path = one run unless --each-file-is-run):
  * JUnit XML: Maven Surefire, Gradle, TestNG junitreports, pytest --junitxml,
    Playwright junit reporter. Surefire <flakyFailure>/<flakyError> = passed on retry.
  * Playwright JSON reporter output (per-attempt results, "flaky" status, repeat-each).

Usage:
  python flaky_score.py .flaky/runs/run_* --out score.json --md score.md
  python flaky_score.py results.json                       # Playwright --repeat-each run
  python flaky_score.py reports/ --each-file-is-run        # a folder of one-file-per-run XMLs
  python flaky_score.py runs/* --history .flaky/history.jsonl

Classification (per test id):
  FLAKY              mixed pass/fail across runs, or passed only after a retry
  BROKEN             failed every executed attempt (>= min-runs)
  STABLE             passed every executed attempt (>= min-runs)
  INSUFFICIENT_DATA  fewer than --min-runs executed attempts and no flaky signal
"""
import argparse
import datetime as dt
import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict, Counter

PASS, FAIL, SKIP = "pass", "fail", "skip"


# ----------------------------------------------------------------- helpers
def normalize_signature(msg: str) -> str:
    """Reduce an error message to a stable signature so similar failures group."""
    if not msg:
        return "(no message)"
    first = msg.strip().splitlines()[0][:300]
    first = re.sub(r"\x1b\[[0-9;]*m", "", first)                 # ANSI colors
    first = re.sub(r"0x[0-9a-fA-F]+", "<hex>", first)
    first = re.sub(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f-]{27,}", "<uuid>", first)
    first = re.sub(r"\d+(\.\d+)?", "<n>", first)
    first = re.sub(r"\s+", " ", first)
    return first.strip()


class Attempt:
    __slots__ = ("outcome", "message", "retry", "file", "duration")

    def __init__(self, outcome, message="", retry=0, file=None, duration=None):
        self.outcome, self.message, self.retry = outcome, message, retry
        self.file, self.duration = file, duration


# ----------------------------------------------------------------- JUnit XML
def parse_junit(path, run_id, sink):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        print(f"warn: cannot parse {path}: {e}", file=sys.stderr)
        return
    for tc in root.iter("testcase"):
        cls = tc.get("classname") or ""
        name = tc.get("name") or "(unnamed)"
        tid = f"{cls}::{name}" if cls else name
        file_attr = tc.get("file")
        try:
            dur = float(tc.get("time")) if tc.get("time") else None
        except ValueError:
            dur = None

        failure = tc.find("failure")
        if failure is None:
            failure = tc.find("error")
        skipped = tc.find("skipped")
        flaky_fails = tc.findall("flakyFailure") + tc.findall("flakyError")
        rerun_fails = tc.findall("rerunFailure") + tc.findall("rerunError")

        def msg_of(el):
            return (el.get("message") or el.text or el.get("type") or "").strip()

        # Surefire retries inside one run: each earlier failed attempt is recorded.
        for i, ff in enumerate(flaky_fails):
            sink[tid][run_id].append(Attempt(FAIL, msg_of(ff), i, file_attr))
        for i, rf in enumerate(rerun_fails):
            sink[tid][run_id].append(Attempt(FAIL, msg_of(rf), i + 1, file_attr))

        retry_idx = len(flaky_fails) + len(rerun_fails)
        if failure is not None:
            sink[tid][run_id].append(Attempt(FAIL, msg_of(failure), 0 if not rerun_fails else 0, file_attr, dur))
        elif skipped is not None:
            sink[tid][run_id].append(Attempt(SKIP, msg_of(skipped), retry_idx, file_attr, dur))
        else:
            sink[tid][run_id].append(Attempt(PASS, "", retry_idx, file_attr, dur))


# ----------------------------------------------------------------- Playwright JSON
PW_FAIL = {"failed", "timedOut", "interrupted"}


def parse_playwright_json(data, run_id, sink):
    def walk(suite, trail):
        title = suite.get("title") or ""
        new_trail = trail + ([title] if title and not title.endswith((".ts", ".js", ".mjs", ".py")) else [])
        file_ = suite.get("file")
        for spec in suite.get("specs", []):
            for t in spec.get("tests", []):
                proj = t.get("projectName") or ""
                tid = " > ".join([spec.get("file") or file_ or ""] + new_trail + [spec.get("title", "")])
                if proj:
                    tid += f" [{proj}]"
                # --repeat-each produces several result groups via repeatEachIndex
                rep = t.get("repeatEachIndex", 0)
                rid = f"{run_id}#r{rep}"
                results = t.get("results", [])
                if not results and t.get("status") == "skipped":
                    sink[tid][rid].append(Attempt(SKIP, "", 0, spec.get("file")))
                for r in results:
                    st = r.get("status")
                    if st == "skipped":
                        oc = SKIP
                    elif st in PW_FAIL:
                        oc = FAIL
                    else:
                        oc = PASS
                    # tests marked test.fail() expect failure: invert
                    if t.get("expectedStatus") == "failed" and oc != SKIP:
                        oc = PASS if oc == FAIL else FAIL
                    err = r.get("error") or {}
                    if not err and r.get("errors"):
                        err = r["errors"][0]
                    msg = err.get("message", "") if isinstance(err, dict) else str(err)
                    if st == "timedOut" and not msg:
                        msg = "Test timeout exceeded"
                    sink[tid][rid].append(Attempt(oc, msg, r.get("retry", 0), spec.get("file"),
                                                  (r.get("duration") or 0) / 1000.0))
        for child in suite.get("suites", []):
            walk(child, new_trail)

    for s in data.get("suites", []):
        walk(s, [])


# ----------------------------------------------------------------- loading
def load_path(path, run_id, sink):
    if path.endswith(".json"):
        try:
            with open(path, encoding="utf-8") as fh:
                data = json.load(fh)
        except (OSError, json.JSONDecodeError) as e:
            print(f"warn: cannot read {path}: {e}", file=sys.stderr)
            return
        if "suites" in data:
            parse_playwright_json(data, run_id, sink)
        return
    if path.endswith(".xml"):
        parse_junit(path, run_id, sink)


def files_under(p):
    if os.path.isfile(p):
        return [p]
    out = []
    for dirpath, _, files in os.walk(p):
        for f in files:
            if f.endswith(".xml") or f.endswith(".json"):
                # skip TestNG's native testng-results.xml (duplicates junitreports) and
                # Surefire summary files
                if f in ("testng-results.xml", "testng-failed.xml") or f.startswith("failsafe-summary"):
                    continue
                out.append(os.path.join(dirpath, f))
    return sorted(out)


# ----------------------------------------------------------------- scoring
def flip_rate(seq):
    seq = [s for s in seq if s != SKIP]
    if len(seq) < 2:
        return 0.0
    flips = sum(1 for a, b in zip(seq, seq[1:]) if a != b)
    return flips / (len(seq) - 1)


def score_tests(sink, min_runs):
    results = []
    for tid, runs in sink.items():
        run_outcomes = []          # final outcome per run
        attempts_all = []
        passed_on_retry_runs = 0
        sigs = Counter()
        durations = []
        file_ = None
        for rid in sorted(runs):
            atts = sorted(runs[rid], key=lambda a: a.retry)
            attempts_all.extend(atts)
            file_ = file_ or next((a.file for a in atts if a.file), None)
            for a in atts:
                if a.outcome == FAIL:
                    sigs[normalize_signature(a.message)] += 1
                if a.duration:
                    durations.append(a.duration)
            executed = [a for a in atts if a.outcome != SKIP]
            if not executed:
                run_outcomes.append(SKIP)
                continue
            final = executed[-1].outcome
            if final == PASS and any(a.outcome == FAIL for a in executed[:-1]):
                passed_on_retry_runs += 1
            run_outcomes.append(final)

        executed_attempts = [a.outcome for a in attempts_all if a.outcome != SKIP]
        n_exec = len(executed_attempts)
        n_fail = executed_attempts.count(FAIL)
        n_pass = n_exec - n_fail
        fail_rate = n_fail / n_exec if n_exec else 0.0
        fr = flip_rate([o for o in run_outcomes])

        if passed_on_retry_runs or (n_pass and n_fail):
            cls = "FLAKY"
        elif n_exec < min_runs:
            cls = "INSUFFICIENT_DATA"
        elif n_fail == n_exec:
            cls = "BROKEN"
        else:
            cls = "STABLE"

        # Score 0-100: how unpredictable the test is.
        # 1 - |2p - 1| peaks at a 50% failure rate; flip rate rewards alternation.
        unpredictability = 1 - abs(2 * fail_rate - 1) if n_exec else 0.0
        score = 0.0
        if cls == "FLAKY":
            score = 100 * (0.6 * unpredictability + 0.4 * fr)
            if passed_on_retry_runs:
                score = max(score, 25.0)
        results.append({
            "test": tid,
            "file": file_,
            "classification": cls,
            "flaky_score": round(score, 1),
            "runs": len(run_outcomes),
            "attempts": n_exec,
            "passes": n_pass,
            "failures": n_fail,
            "failure_rate": round(fail_rate, 3),
            "flip_rate": round(fr, 3),
            "passed_on_retry_runs": passed_on_retry_runs,
            "run_sequence": "".join({"pass": "P", "fail": "F", "skip": "S"}[o] for o in run_outcomes),
            "avg_duration_s": round(sum(durations) / len(durations), 2) if durations else None,
            "max_duration_s": round(max(durations), 2) if durations else None,
            "error_signatures": [{"signature": s, "count": c} for s, c in sigs.most_common(5)],
        })
    order = {"FLAKY": 0, "BROKEN": 1, "INSUFFICIENT_DATA": 2, "STABLE": 3}
    results.sort(key=lambda r: (order[r["classification"]], -r["flaky_score"], r["test"]))
    return results


def to_markdown(results, n_runs):
    counts = Counter(r["classification"] for r in results)
    lines = ["# Flaky score report", "",
             f"Runs analyzed: {n_runs} | Tests: {len(results)} | "
             + " | ".join(f"{k}: {counts.get(k, 0)}" for k in ("FLAKY", "BROKEN", "STABLE", "INSUFFICIENT_DATA")),
             ""]
    flaky = [r for r in results if r["classification"] == "FLAKY"]
    if flaky:
        lines += ["## Flaky tests", "",
                  "| Score | Test | Pass/Fail | Retry-passes | Sequence | Top error signature |",
                  "|---:|---|---|---:|---|---|"]
        for r in flaky:
            sig = r["error_signatures"][0]["signature"] if r["error_signatures"] else ""
            sig = sig.replace("|", "\\|")[:120]
            lines.append(f"| {r['flaky_score']} | `{r['test']}` | {r['passes']}/{r['failures']} | "
                         f"{r['passed_on_retry_runs']} | `{r['run_sequence']}` | {sig} |")
        lines.append("")
    broken = [r for r in results if r["classification"] == "BROKEN"]
    if broken:
        lines += ["## Broken (always failing — not flaky)", ""]
        for r in broken:
            sig = r["error_signatures"][0]["signature"] if r["error_signatures"] else ""
            lines.append(f"- `{r['test']}` — {r['failures']} failures — {sig[:120]}")
        lines.append("")
    insufficient = counts.get("INSUFFICIENT_DATA", 0)
    if insufficient:
        lines.append(f"_{insufficient} test(s) had too few runs to judge; collect more runs._")
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="+", help="report files/dirs; each path is one run (globs allowed)")
    ap.add_argument("--each-file-is-run", action="store_true",
                    help="treat every report file as a separate run")
    ap.add_argument("--min-runs", type=int, default=3, help="min executed attempts to call a test STABLE/BROKEN")
    ap.add_argument("--out", help="write JSON results here")
    ap.add_argument("--md", help="write a Markdown summary here")
    ap.add_argument("--history", help="append a summary line per flaky/broken test to this JSONL file")
    args = ap.parse_args()

    paths = []
    for p in args.paths:
        paths.extend(sorted(glob.glob(p)) or [p])

    sink = defaultdict(lambda: defaultdict(list))
    run_ids = []
    for i, p in enumerate(paths):
        if not os.path.exists(p):
            print(f"warn: {p} not found", file=sys.stderr)
            continue
        files = files_under(p)
        if args.each_file_is_run:
            for j, f in enumerate(files):
                rid = f"run{i:03d}.{j:03d}"
                run_ids.append(rid)
                load_path(f, rid, sink)
        else:
            rid = f"run{i:03d}"
            run_ids.append(rid)
            for f in files:
                load_path(f, rid, sink)

    if not sink:
        print("No test results found. Check the report paths/format.", file=sys.stderr)
        sys.exit(2)

    results = score_tests(sink, args.min_runs)
    payload = {"generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
               "inputs": paths, "tests": results}

    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, indent=2)
    md = to_markdown(results, len(paths))
    if args.md:
        os.makedirs(os.path.dirname(os.path.abspath(args.md)), exist_ok=True)
        with open(args.md, "w", encoding="utf-8") as fh:
            fh.write(md)
    if args.history:
        os.makedirs(os.path.dirname(os.path.abspath(args.history)), exist_ok=True)
        with open(args.history, "a", encoding="utf-8") as fh:
            for r in results:
                if r["classification"] in ("FLAKY", "BROKEN"):
                    fh.write(json.dumps({"ts": payload["generated_at"], "test": r["test"],
                                         "class": r["classification"], "score": r["flaky_score"],
                                         "passes": r["passes"], "failures": r["failures"]}) + "\n")
    if not args.out and not args.md:
        print(md)
    else:
        counts = Counter(r["classification"] for r in results)
        print(f"{len(results)} tests | " + ", ".join(f"{k}={v}" for k, v in counts.items()))


if __name__ == "__main__":
    main()
