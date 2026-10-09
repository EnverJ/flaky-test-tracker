#!/usr/bin/env bash
# collect_runs.sh - run any test command N times and archive each run's reports.
#
# Usage:
#   collect_runs.sh -n 10 -c "mvn -q test -Dtest=LoginTest" -r target/surefire-reports [-o .flaky/runs]
#   collect_runs.sh -n 10 -c "./gradlew test --rerun-tasks --tests '*Checkout*'" -r build/test-results/test
#   collect_runs.sh -n 10 -c "pytest tests/ui --junitxml=report.xml" -r report.xml
#   collect_runs.sh -n 10 -c "npx playwright test --retries=0 --reporter=junit" -r results.xml \
#                   -e "PLAYWRIGHT_JUNIT_OUTPUT_NAME=results.xml"
#
# Each run's report(s) are copied to <out>/run_001, run_002, ... A failing test
# command does NOT stop the loop (that's the point). Then:
#   python scripts/flaky_score.py <out>/run_*
set -Eeuo pipefail

N=10; CMD=""; REPORT=""; OUT=".flaky/runs"; ENVSET=""; STOP_AFTER=0
while getopts "n:c:r:o:e:s:h" opt; do
  case $opt in
    n) N=$OPTARG ;;
    c) CMD=$OPTARG ;;
    r) REPORT=$OPTARG ;;
    o) OUT=$OPTARG ;;
    e) ENVSET=$OPTARG ;;
    s) STOP_AFTER=$OPTARG ;;   # optional: stop after this many failing runs (0 = never)
    h|*) sed -n '2,16p' "$0"; exit 0 ;;
  esac
done
[ -z "$CMD" ] || [ -z "$REPORT" ] && { echo "need -c <command> and -r <report path>"; exit 2; }

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
validate_under_repo() {
  local path="$1"
  local label="$2"

  if [[ -L "$path" ]]; then
    echo "error: ${label} must not be a symlink: $path" >&2
    exit 2
  fi

  if [[ "$path" = /* ]]; then
    if [[ "$path" != "$repo_root" && "$path" != "$repo_root"/* ]]; then
      echo "error: ${label} must stay inside the repository: $path" >&2
      exit 2
    fi
  else
    local full_path
    full_path="$(realpath -m -- "$repo_root/$path" 2>/dev/null || printf '%s' "$repo_root/$path")"
    if [[ "$full_path" != "$repo_root" && "$full_path" != "$repo_root"/* ]]; then
      echo "error: ${label} escapes the repository: $path" >&2
      exit 2
    fi
  fi
}

validate_under_repo "$REPORT" "REPORT"
validate_under_repo "$OUT" "OUT"

mkdir -p -- "$OUT"
start_idx=$(ls -d "$OUT"/run_* 2>/dev/null | wc -l | tr -d ' ')
fails=0
for i in $(seq 1 "$N"); do
  idx=$(printf "%03d" $((start_idx + i)))
  dest="$OUT/run_$idx"
  rm -rf -- "$REPORT"                                   # never score a stale report
  echo "== run $i/$N -> $dest"
  t0=$(date +%s)
  if [ -n "$ENVSET" ]; then env $ENVSET bash -c "$CMD" > "$OUT/run_$idx.log" 2>&1
  else bash -c "$CMD" > "$OUT/run_$idx.log" 2>&1; fi
  rc=$?
  t1=$(date +%s)
  mkdir -p -- "$dest"
  if [ -e "$REPORT" ]; then cp -R -- "$REPORT" "$dest/"; else echo "   warn: no report at $REPORT"; fi
  echo "{\"run\": $((start_idx + i)), \"exit_code\": $rc, \"seconds\": $((t1 - t0)), \"commit\": \"$(git rev-parse --short HEAD 2>/dev/null || echo unknown)\"}" > "$dest/run-meta.txt"
  echo "   exit=$rc  $((t1 - t0))s"
  [ $rc -ne 0 ] && fails=$((fails + 1))
  if [ "$STOP_AFTER" -gt 0 ] && [ "$fails" -ge "$STOP_AFTER" ]; then echo "stopping: $fails failing runs"; break; fi
done
echo "done: $fails failing run(s). Score with: python scripts/flaky_score.py $OUT/run_*"
