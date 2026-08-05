#!/usr/bin/env bash
#
# The canonical gate invocation: run the live benchmarks with the allocation profiler, then check
# Tiers 1 and 2. Exit 0 = pass, 1 = breach, 2 = usage or I/O error.
#
# The exclusion, the profiler and the JSON format are all load-bearing, which is why this exists as a
# script rather than as three lines in a README that drift apart:
#
#   -prof gc          produces gc.alloc.rate.norm. WITHOUT IT TIER 1 SILENTLY CHECKS NOTHING - the
#                     gate warns per benchmark, but a warning is not a failure.
#   -rf json          the only format GateChecker reads.
#
# THE `-e '\.staged\.'` EXCLUSION IS GONE, AND ITS ABSENCE IS LOAD-BEARING. It excluded
# BulkTransformBenchmark, whose @Setup used to throw because org.locationtech.proj4j
# .BulkCoordinateTransform did not exist. It does now, the benchmark is an ordinary member of
# ...benchmark, and the staged package has been deleted. While the exclusion stood, THE BULK PATH -
# the API the consumer is being pointed at for per-row work - HAD NO ALLOCATION RATCHETS AT ALL, and
# its "zero bytes per point" contract was a sentence in a javadoc. Do not reintroduce an -e that
# matches a benchmark class: an excluded benchmark is an ungated one, and GateChecker can only
# report it if the rule is `required`.
#
# Usage:
#   ./run-gate.sh                       full run, both tiers
#   ./run-gate.sh --quick               reduced iterations; fine for Tier 1, NOT for timing
#   ./run-gate.sh --tier2-only          no JMH run at all; about a second
#   ./run-gate.sh --record              refresh the baselines from this run
#   ./run-gate.sh --require-baseline    a TBD baseline entry is a FAILURE, not a warning
#   ./run-gate.sh --resume              reuse shard result files that already look complete
#   ./run-gate.sh --no-shard            one monolithic JMH invocation (the old, fragile behaviour)
#   ./run-gate.sh -- <extra jmh args>   append anything else to the JMH invocation
#
# GateChecker options are forwarded explicitly: --skip-tier1, --skip-tier2, --require-baseline,
# --alloc-baseline <p>, --op-counts <p>, --baseline-dir <p>, --commit <sha>, --self-test.
#
# EVERY OPTION NEEDS AN EXPLICIT CASE BELOW, AND AN UNKNOWN ONE IS A HARD ERROR. This script used to
# append anything it did not recognise to the JMH command line, which meant `--require-baseline`,
# `--skip-tier1`, `--skip-tier2`, `--alloc-baseline`, `--op-counts`, `--baseline-dir`, `--commit` and
# `--self-test` were all handed to JMH instead of to GateChecker. JMH rejects them, so the gate never
# ran at all - or, worse, they were silently dropped and the gate ran WEAKER than the caller asked
# for. CI passes --require-baseline, because an unpinned threshold that silently warns is exactly the
# shape of gate this repository is trying to stop shipping - see allocation-baseline.json's own note:
# "Pass --require-baseline to turn those warnings into failures once the baseline is real - that is
# the flag CI should use after capture." An instrument that cannot fail is worthless; an instrument
# that silently discards the flag that makes it strict is worse, because it reports success.
#
# TIER 1 GATES EVERYTHING IT MEASURES TODAY, AND THE OUTPUT SAYS SO EITHER WAY.
#
# As of 2026-08-02 NO rule in allocation-baseline.json carries `tier1Gated: false`, and a run prints
# "245 gated, 0 EXCLUDED". The exclusion machinery is intact and self-tested; it simply has no user.
#
# THE HISTORY, BECAUSE THE SHAPE OF IT IS THE REUSABLE PART. The `crs-parse` rule carried
# `tier1Gated: false` from 2026-08-01, so nine CrsParseBenchmark arms were measured, recorded and
# printed but DID NOT BLOCK - a real reduction in coverage, stated in the gate's own output on every
# run (section "Tier 1 coverage exclusions", plus a suffix on the GATE PASSED / GATE FAILED line), in
# the rule's `exclusionReason`, and in README.md under "Coverage - what Tier 1 does not gate".
# Reason: those arms flaked by up to 0.121% against a 0.001 slack because their subject WAS
# data-dependent allocation - the 200x EARLY->LATE spread was Proj4FileReader's per-call re-scan,
# i.e. the finding - so they never satisfied Tier 1's fixed-object-graph premise.
#
# The exclusion was written with its own exit condition: "fix the re-scan and the arms rejoin Tier 1
# on their own terms." io/InitFileCache did exactly that on 2026-08-02, createFromName fell to
# 2480/2872/1136 B/op, and tier1Gated + exclusionArmCount + exclusionReason were deleted together in
# the same change. AN EXCLUSION IS A DATED LOAN AGAINST A NAMED FIX, NOT A TOLERANCE. If a second one
# is ever added, give it a repayment clause too.
#
# SHARDING BY BENCHMARK CLASS, AND WHY IT IS NOT AN OPTIMISATION.
#
# JMH TRUNCATES the -rff file AT STARTUP and writes its content ONLY AT THE END. Both halves were
# measured on JMH 1.37, not assumed, and they give TWO DISTINCT failure modes that a single -rff
# conflates:
#
#   (1) The JMH PARENT process dies - OOM killer, a CI job cancellation, an agent's tool timeout,
#       ^C. The file it truncated at startup is all that is left: ZERO BYTES. `set -euo pipefail`
#       then kills this script before GateChecker ever runs. Ninety minutes of measurement, no
#       measurement. This is exactly the artefact the previous capture attempt left behind.
#
#   (2) A FORKED VM dies - SIGKILL, a JVM crash, an OOM inside the fork. This one does NOT zero the
#       file, and it is the more dangerous of the two: JMH prints
#       `<forked VM failed with exit code 137>`, CONTINUES with the next benchmark, writes a
#       perfectly well-formed result file, AND EXITS 0. The dead benchmark is simply gone. No
#       non-zero exit, no empty file, no warning. `-foe` does not help - it governs whether a
#       benchmark EXCEPTION is fatal and says nothing about the forked VM dying.
#
# So: one JMH invocation per benchmark class, each with its own -rff, concatenated at the end
# (GateChecker reads a plain array, so the merge is a concat). That bounds (1) to a single class,
# with the completed shards still on disk and --resume re-running only what is missing. And because
# (2) is silent, every shard's record count is checked against JMH's own `-lp` arm enumeration - see
# compute_shard_expectations below. Correctness is unaffected by the split: JMH already runs each
# benchmark in its own forked VM, so splitting the invocation changes nothing a measurement can
# observe.
#
set -euo pipefail

cd "$(dirname "$0")"

JAR=target/benchmarks.jar
RESULT=${GATE_JMH_RESULT:-target/jmh-result.json}
GATE_MAIN=org.locationtech.proj4j.benchmark.gate.GateChecker

QUICK=0
TIER2_ONLY=0
RECORD=0
RESUME=0
SHARD=1
SELF_TEST=0
GATE_OPTS=()
EXTRA=()

die() {
  echo "run-gate.sh: $*" >&2
  exit 2
}

need_value() {
  # $1 = option name, $2 = remaining arg count
  [[ $2 -ge 2 ]] || die "$1 needs a value"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --quick)            QUICK=1; shift ;;
    --tier2-only)       TIER2_ONLY=1; shift ;;
    --record)           RECORD=1; shift ;;
    --resume)           RESUME=1; shift ;;
    --no-shard)         SHARD=0; shift ;;
    # ---- forwarded verbatim to GateChecker -------------------------------------------------
    --require-baseline|--skip-tier1|--skip-tier2)
                        GATE_OPTS+=("$1"); shift ;;
    --alloc-baseline|--op-counts|--baseline-dir|--commit)
                        need_value "$1" $#; GATE_OPTS+=("$1" "$2"); shift 2 ;;
    --self-test)        SELF_TEST=1; shift ;;
    # 3,33 is the header block down to the "--self-test." line. Deleting the staged exclusion
    # lengthened that block; the range has to follow it or --help silently truncates.
    -h|--help)          sed -n '3,33p' "$0" | sed 's/^#\{1,\} \{0,1\}//'; exit 0 ;;
    --)                 shift; EXTRA=("$@"); break ;;
    *)                  die "unknown option '$1'.
  Extra JMH arguments must come after a literal --, e.g. ./run-gate.sh --quick -- -bm avgt.
  GateChecker options need an explicit case in this script; silently forwarding them to JMH
  (or silently dropping them) is how a gate stops gating." ;;
  esac
done

if [[ ! -f "$JAR" ]]; then
  echo "run-gate.sh: $JAR not found. Build it first:" >&2
  echo "  mvn -B -Dmaven.repo.local=/tmp/m2-bench -f pom.xml package" >&2
  echo "or, from the repository root, with the bench profile:" >&2
  echo "  mvn -B -Pbench -pl benchmark -am package" >&2
  exit 2
fi

if [[ $SELF_TEST -eq 1 ]]; then
  exec java -cp "$JAR" "$GATE_MAIN" --self-test
fi

# THE GATE READS THE BASELINE FROM INSIDE THE JAR, NOT FROM src/main/resources.
# GateChecker.readText falls back to the classpath resource /baseline/*.json, which is the copy
# maven-resources-plugin put in benchmarks.jar at build time. `--record` writes to
# src/main/resources/baseline/. Those are two different files, and after a --record they disagree
# until the module is rebuilt.
#
# This is not hypothetical: the first attempt at the negative control for this capture recorded a
# full baseline, immediately re-ran the gate, and got 298 "baseline is TBD" failures - the gate had
# silently read the pre-capture TBD copy still sitting in the jar. Sixteen minutes of measurement
# checked against the wrong file, and the output looked like a legitimate result. In CI the two
# always agree because the jar is built from source in the same job, so this can only ever bite
# locally, which is exactly where it is hardest to notice.
#
# Hard error rather than a warning: a gate that reports a verdict against a baseline it did not
# read is worse than no gate. Passing --alloc-baseline / --op-counts explicitly is the escape hatch,
# because then the on-disk file IS what gets read.
if [[ $RECORD -eq 0 ]]; then
  baseline_override=0
  for o in ${GATE_OPTS+"${GATE_OPTS[@]}"}; do
    case "$o" in --alloc-baseline|--op-counts) baseline_override=1 ;; esac
  done
  if [[ $baseline_override -eq 0 ]]; then
    for src in src/main/resources/baseline/allocation-baseline.json \
               src/main/resources/baseline/op-counts.json; do
      if [[ -f "$src" && "$src" -nt "$JAR" ]]; then
        die "$src is newer than $JAR.
  The gate reads the baseline COMPILED INTO the jar, so this run would check against a stale copy
  and report a verdict you did not ask for. Rebuild first:
      mvn -B -Pbench -pl benchmark -am -DskipTests package
  or point the gate at the files on disk explicitly:
      ./run-gate.sh --alloc-baseline $src --op-counts src/main/resources/baseline/op-counts.json"
      fi
    done
  fi
fi

if [[ $TIER2_ONLY -eq 1 ]]; then
  if [[ $RECORD -eq 1 ]]; then
    # --record used to take this branch WITHOUT ${GATE_OPTS[@]}, so `--record --tier2-only
    # --baseline-dir /tmp/x` silently wrote to the default directory. Forward them here too.
    exec java -cp "$JAR" "$GATE_MAIN" --record --skip-tier1 ${GATE_OPTS+"${GATE_OPTS[@]}"}
  fi
  exec java -cp "$JAR" "$GATE_MAIN" --skip-tier1 ${GATE_OPTS+"${GATE_OPTS[@]}"}
fi

JMH_ARGS=(-prof gc -rf json)
if [[ $QUICK -eq 1 ]]; then
  # Reduced iterations are legitimate for Tier 1 because gc.alloc.rate.norm is a property of the
  # bytecode and converges almost immediately. They are NOT legitimate for a Tier 3 timing run;
  # short runs are a large part of why timing on shared hardware is unreliable.
  JMH_ARGS+=(-f 1 -wi 2 -i 3 -w 1s -r 1s)
fi
if [[ ${#EXTRA[@]} -gt 0 ]]; then
  JMH_ARGS+=("${EXTRA[@]}")
fi

# Emits the inner body of a JSON array file, outer brackets removed, so several can be concatenated.
strip_brackets() {
  awk '{ buf = buf $0 "\n" }
       END { sub(/^[ \t\r\n]*\[/, "", buf); sub(/\][ \t\r\n]*$/, "", buf); printf "%s", buf }' "$1"
}

# Expected benchmark-arm count per class, from JMH's own `-lp` listing: one line per @Benchmark
# method, followed by one line per @Param naming its values, so the arm count for a method is the
# product of its parameters' cardinalities.
#
# THIS IS NOT BELT-AND-BRACES, IT IS THE ONLY THING THAT SEES THE FAILURE. Measured, not assumed:
# SIGKILL a JMH forked VM mid-run and JMH 1.37 prints `<forked VM failed with exit code 137>`,
# CARRIES ON, writes its -rff file at the end, AND EXITS 0. The dead benchmark is simply absent from
# the results. Nothing downstream notices: an exit-code check passes, a "file is non-empty" check
# passes, and GateChecker's own `rule matched no benchmark` guard only fires when an ENTIRE CLASS is
# missing - it iterates the measurements it was given, so one vanished arm is silently ungated. The
# demonstration run lost GridShiftBenchmark.forwardShift exactly this way: 3 records where 4 were
# expected, exit 0, no warning anywhere. So a shard is complete only when its record count MATCHES.
SHARD_EXPECT=target/.shard-arm-counts

compute_shard_expectations() {
  java -jar "$JAR" -lp 2>/dev/null | awk '
    function flush() { if (cls != "") counts[cls] += n }
    /^org\./ { flush(); line = $0; sub(/\.[^.]*$/, "", line); cls = line; n = 1; next }
    /param .*=/ { s = $0; sub(/^[^{]*\{/, "", s); sub(/\}.*$/, "", s); n *= split(s, a, ","); next }
    END { flush(); for (c in counts) print c "\t" counts[c] }
  ' | sort > "$SHARD_EXPECT"
  [[ -s "$SHARD_EXPECT" ]] || die "could not enumerate benchmark arms with '$JAR -lp'."
}

expected_arms() {
  awk -F'\t' -v c="$1" '$1 == c { print $2 }' "$SHARD_EXPECT"
}

records_in() {
  [[ -s "$1" ]] || { echo 0; return; }
  grep -c '"benchmark"' "$1" || true
}

# $1 = shard result file, $2 = fully qualified benchmark class.
shard_complete() {
  local want
  want=$(expected_arms "$2")
  [[ -n "$want" ]] || return 1
  [[ "$(records_in "$1")" == "$want" ]]
}

if [[ $SHARD -eq 0 ]]; then
  echo "==> java -jar $JAR ${JMH_ARGS[*]} -rff $RESULT   (unsharded: a fork death loses everything)"
  java -jar "$JAR" "${JMH_ARGS[@]}" -rff "$RESULT"
else
  # No `mapfile`: macOS ships bash 3.2 and this script has to run there as well as on CI.
  CLASSES=()
  while IFS= read -r line; do
    [[ -n "$line" ]] && CLASSES+=("$line")
  done < <(java -jar "$JAR" -l | tail -n +2 | sed 's/\.[^.]*$//' | sort -u)
  [[ ${#CLASSES[@]} -gt 0 ]] || die "no benchmarks listed by '$JAR -l'. Is the jar built?"

  compute_shard_expectations
  TOTAL_ARMS=$(awk -F'\t' '{s += $2} END {print s + 0}' "$SHARD_EXPECT")
  echo "==> ${#CLASSES[@]} shards, one JMH invocation per benchmark class, $TOTAL_ARMS arms expected"

  SHARD_FILES=()
  FAILED_SHARDS=()
  SKIPPED_SHARDS=()
  i=0
  for fqcn in "${CLASSES[@]}"; do
    i=$((i + 1))
    simple=${fqcn##*.}
    out="target/jmh-${simple}.json"
    SHARD_FILES+=("$out")
    want=$(expected_arms "$fqcn")
    if [[ $RESUME -eq 1 ]] && shard_complete "$out" "$fqcn"; then
      echo "--> [$i/${#CLASSES[@]}] $simple: reusing $out, $want/$want arms (--resume)"
      SKIPPED_SHARDS+=("$simple")
      continue
    fi
    rm -f "$out"
    selector="^$(printf '%s' "$fqcn" | sed 's/\./\\./g')\\."
    echo "--> [$i/${#CLASSES[@]}] $simple -> $out  ($want arms)"
    set +e
    java -jar "$JAR" "${JMH_ARGS[@]}" -rff "$out" "$selector"
    rc=$?
    set -e
    if [[ $rc -ne 0 ]] || ! shard_complete "$out" "$fqcn"; then
      echo "!!! shard $simple FAILED: exit $rc, $(records_in "$out")/$want arms in $out" >&2
      echo "    Continuing; the other shards are unaffected - that is the point of sharding." >&2
      FAILED_SHARDS+=("$simple")
    fi
  done

  : > "$RESULT"
  {
    printf '[\n'
    first=1
    for f in "${SHARD_FILES[@]}"; do
      # Stream each shard straight through; do NOT capture it in a shell variable. macOS ships
      # bash 3.2, whose ${var//pattern/} is quadratic - running it over one 331 KB shard hung the
      # merge for over five minutes with no output at all.
      #
      # A shard that failed its arm-count check is still merged if it holds anything, so the gate
      # sees every measurement that DID survive; the failure is reported separately and blocks
      # --record. Dropping it here would turn a partial loss into a whole-class loss.
      [[ -s "$f" ]] || continue
      grep -q '"benchmark"' "$f" || continue
      [[ $first -eq 1 ]] || printf ',\n'
      first=0
      strip_brackets "$f"
    done
    printf '\n]\n'
  } > "$RESULT"
  echo "==> merged $(records_in "$RESULT")/$TOTAL_ARMS benchmark records into $RESULT"

  if [[ ${#FAILED_SHARDS[@]} -gt 0 ]]; then
    echo >&2
    echo "run-gate.sh: ${#FAILED_SHARDS[@]} incomplete shard(s): ${FAILED_SHARDS[*]}" >&2
    echo "  Re-run with --resume to retry only those; the completed shards are on disk and will" >&2
    echo "  be reused, so the cost of the failure is one class, not the whole run." >&2
    if [[ $RECORD -eq 1 ]]; then
      # Recording from a partial run would write a baseline with arms silently missing, and an
      # absent ratchet reads as "not measured yet" rather than as "the run was broken". That is
      # precisely how a fabricated-looking baseline gets committed. Refuse.
      die "refusing to --record from a partial run. Re-run with --resume until every shard is complete."
    fi
    echo "  The gate will now run against what DID survive. A wholly missing class fails as" >&2
    echo "  'rule matched no benchmark'; a missing ARM would otherwise be invisible, which is why" >&2
    echo "  the arm-count check above exists." >&2
  fi
fi

if [[ $RECORD -eq 1 ]]; then
  exec java -cp "$JAR" "$GATE_MAIN" --record "$RESULT" ${GATE_OPTS+"${GATE_OPTS[@]}"}
fi
exec java -cp "$JAR" "$GATE_MAIN" "$RESULT" ${GATE_OPTS+"${GATE_OPTS[@]}"}
