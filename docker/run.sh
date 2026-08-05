#!/usr/bin/env bash
#
# neoproj4j check runner - one script, two modes.
#
#   ON THE HOST   it builds docker/Dockerfile, then re-executes ITSELF inside the container.
#   IN CONTAINER  ($PROJ4J_IN_CONTAINER is set by the image) it copies the working tree out of the
#                 read-only /src mount into /work and runs the checks there.
#
# WHY IT MIRRORS .github/workflows RATHER THAN INVENTING COMMANDS. Every Maven invocation below is
# copied from the committed workflow that owns that check, flag for flag, and every guard step is a
# translation of that workflow's own non-vacuity step. If a workflow changes and this does not, the
# two disagree and the disagreement is the bug - there is deliberately no second, parallel account
# of how the suites are run.
#
#     check         mirrors                              expected today
#     ------------  -----------------------------------  ------------------------------------------
#     ci            ci.yaml   job build-and-test          FAILS on MetaCRSTest only (see below)
#     conformance   conformance.yaml  job corpus          passes, 7441/7900, regressed 0
#     golden        golden.yaml       job golden          FAILS on ~2,291 UNEXPLAINED rows
#     determinism   determinism.yaml  job bits (one leg)  passes, 22 tests
#     bench         bench.yaml        job gate            passes; OPT-IN, ~16 min, needs a quiet box
#
# TWO OF THESE ARE EXPECTED TO FAIL, AND THE SUMMARY SAYS WHICH AND WHY. They are reported as
# EXPECTED-FAIL, not as PASS: the check really did fail, and the run prints its measured numbers.
# The expectation is not hardcoded to a verdict either - each is re-derived from the run:
#
#   * ci is EXPECTED-FAIL only while the ONLY failing class is MetaCRSTest, whose input
#     (epsg/src/main/resources/proj4/proj4-epsg.csv) is stale and is being regenerated. If ci goes
#     green the summary says so. If anything ELSE fails, it is an unexpected failure and the run
#     exits non-zero. Neither outcome is baked in.
#   * golden is EXPECTED-FAIL only while its failure is UNEXPLAINED rows and nothing else. A
#     COUNT_MISMATCH, DEAD_RULE, EXPIRED_RULE or PENDING_RULE_FIRED is a real failure and is
#     reported as one, because those mean the rule set has stopped describing the tree.
#
# TRAPS THIS SCRIPT IS WRITTEN AGAINST. Each of these has produced a false green on this project:
#
#   1. -Dmaven.test.failure.ignore=true forces exit 0. It appears nowhere here. When the exit code
#      IS the measurement it cannot be used, and getting past core's expected MetaCRSTest failure
#      is done with -Dtest= narrowing instead, exactly as the workflows do.
#   2. Surefire's single `*` does not cross a package separator. The conformance tests live in
#      sub-packages (bridge/ manifest/ parse/ report/ runner/) and need `**`; golden's and
#      determinism's are flat and use `*`. A pattern that matches nothing prints BUILD SUCCESS.
#   3. EVERY CHECK ASSERTS A FLOOR ON ITS TEST COUNT. A container that runs nothing cannot report
#      success. This is the specific failure the project has hit repeatedly.
#   4. `bc` is not installed in the image, on purpose. All arithmetic here is shell or awk.
#   5. grep is line-oriented and a single NUL byte makes it skip a whole file silently. Nothing
#      here greps sources, but if that changes, use `grep -a`.
#   6. -Djgitver.skip=true is required whenever -Dmaven.repo.local is non-default. It is NOT passed
#      here and must not be: the container's Maven repository is the default ~/.m2, which is what
#      makes these commands byte-identical to the workflows'.
#   7. Every guard reads a report file this invocation produced. The report directories are deleted
#      before each check runs, so a stale artefact from an earlier check cannot satisfy a guard.
#
# EXIT CODE: 0 if every requested check either PASSED or failed in its expected, explained way;
# 1 if any check failed for any other reason (including a floor assertion). Pass --strict to make
# an expected failure exit non-zero too.

set -uo pipefail

IMAGE_DEFAULT='neoproj4j-checks:temurin-21.0.11'
VOLUME_DEFAULT='neoproj4j-m2'
OUT_DEFAULT='/tmp/neoproj4j-docker-out'
ALL_CHECKS='ci conformance golden determinism'   # bench is opt-in; see README

usage() {
    cat <<'EOF'
Usage: ./docker/run.sh [checks...] [options]

Checks (default: all)
  all             ci + conformance + golden + determinism  (bench is NOT included)
  ci              mvn clean install                        - mirrors ci.yaml/build-and-test
  conformance     the PROJ 9.8.1 gie/GIGS corpus sweep     - mirrors conformance.yaml/corpus
  golden          the 53,430-row behavioural sweep         - mirrors golden.yaml/golden
  determinism     the raw-bit StrictMath golden, one leg   - mirrors determinism.yaml/bits
  bench           the Tier 1/2 performance gate            - mirrors bench.yaml/gate  [~16 min]

Options
  --strict              an EXPECTED failure also exits non-zero
  --break-conformance   POSITIVE CONTROL. Corrupt one row of the conformance manifest in the
                        container's throwaway copy of the tree and prove the check goes red and
                        names the assertion. Never touches the host tree.
  --reset-cache         delete the named ~/.m2 volume first, then run cold
  --rebuild             docker build --no-cache
  --out DIR             host directory for logs and reports  (default /tmp/neoproj4j-docker-out)
  --image NAME          image tag to build/run             (default neoproj4j-checks:temurin-21.0.11)
  --volume NAME         named volume for ~/.m2                (default neoproj4j-m2)
  --shell               build the image, then drop into a shell in the prepared /work
  -h, --help            this

Examples
  ./docker/run.sh                            # the four default checks
  ./docker/run.sh conformance                # one check
  ./docker/run.sh bench                      # the opt-in 16-minute gate
  ./docker/run.sh conformance --break-conformance   # positive control: must exit 1
  ./docker/run.sh --reset-cache              # prove the result does not depend on a warm cache
EOF
}

# =====================================================================================
# HOST DRIVER
# =====================================================================================
if [ -z "${PROJ4J_IN_CONTAINER:-}" ]; then
    HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    REPO_ROOT="$(cd "$HERE/.." && pwd)"

    IMAGE="$IMAGE_DEFAULT"
    VOLUME="$VOLUME_DEFAULT"
    OUT="$OUT_DEFAULT"
    RESET_CACHE=0
    SHELL_MODE=0
    BUILD_FLAGS=()
    PASSTHRU=()

    while [ $# -gt 0 ]; do
        case "$1" in
            --reset-cache) RESET_CACHE=1 ;;
            --rebuild)     BUILD_FLAGS+=(--no-cache) ;;
            --shell)       SHELL_MODE=1 ;;
            --out)         OUT="${2:?--out needs a directory}"; shift ;;
            --image)       IMAGE="${2:?--image needs a name}"; shift ;;
            --volume)      VOLUME="${2:?--volume needs a name}"; shift ;;
            -h|--help)     usage; exit 0 ;;
            *)             PASSTHRU+=("$1") ;;
        esac
        shift
    done

    # Refuse to run against something that is not this repository, rather than producing a
    # confusing Maven error three minutes later.
    if ! grep -q '<artifactId>neoproj4j-modules</artifactId>' "$REPO_ROOT/pom.xml" 2>/dev/null; then
        echo "run.sh: $REPO_ROOT does not look like the neoproj4j repository" >&2
        exit 2
    fi
    command -v docker >/dev/null || { echo "run.sh: docker is not on PATH" >&2; exit 2; }

    mkdir -p "$OUT"

    if [ "$RESET_CACHE" = 1 ]; then
        echo "==> removing the Maven cache volume '$VOLUME' (the next run is cold)"
        docker volume rm "$VOLUME" >/dev/null 2>&1 || true
    fi

    # ---- build context -------------------------------------------------------------------
    # A throwaway context in /tmp holding the Dockerfile and exactly one other file: the CA bundle,
    # written EMPTY when the host has none. The source tree is bind-mounted at run time rather than
    # COPYed in, so editing a check does not invalidate an image layer and the image carries no
    # claim about which commit it was built from.
    CTX="$(mktemp -d /tmp/neoproj4j-docker-ctx.XXXXXX)"
    trap 'rm -rf "$CTX"' EXIT
    cp "$HERE/Dockerfile" "$CTX/Dockerfile"

    # This host is behind a TLS-intercepting firewall, so both curl and the JVM need its root CA or
    # nothing downloads. On a machine without one - or on a real runner - the file stays empty and
    # the Dockerfile's CA stage is a no-op, which is the case worth keeping working.
    CA=""
    for cand in "${PROJ4J_DOCKER_CA:-}" "${SSL_CERT_FILE:-}" "${REQUESTS_CA_BUNDLE:-}" \
                /etc/ssl/certs/ca-bundle.crt; do
        [ -n "$cand" ] && [ -f "$cand" ] && { CA="$cand"; break; }
    done
    if [ -n "$CA" ]; then
        cat "$CA" > "$CTX/extra-ca.crt"
        echo "==> injecting CA bundle $CA ($(grep -c 'BEGIN CERTIFICATE' "$CA") certificate(s))"
    else
        : > "$CTX/extra-ca.crt"
    fi

    echo "==> building $IMAGE"
    # `${arr[@]+"${arr[@]}"}` rather than `"${arr[@]}"`, throughout the host half of this script:
    # macOS ships bash 3.2, where expanding an EMPTY array under `set -u` is an "unbound variable"
    # error. bash 4.4 fixed it; the host this was written for has not.
    docker build ${BUILD_FLAGS[@]+"${BUILD_FLAGS[@]}"} -t "$IMAGE" -f "$CTX/Dockerfile" "$CTX" || exit 2

    RUN_FLAGS=(--rm -v "$REPO_ROOT:/src:ro" -v "$VOLUME:/root/.m2" -v "$OUT:/out")
    [ -t 0 ] && RUN_FLAGS+=(-i)

    if [ "$SHELL_MODE" = 1 ]; then
        echo "==> interactive shell; /work is prepared on entry"
        exec docker run "${RUN_FLAGS[@]}" -t "$IMAGE" -c \
            '/src/docker/run.sh --prepare-only; cd /work; exec bash'
    fi
    # No -t for a check run, deliberately: a pseudo-terminal turns every newline in the tee'd logs
    # into CRLF and lets Maven emit colour, so the saved log stops being greppable.

    echo "==> host:   $REPO_ROOT"
    echo "==> output: $OUT"
    docker run "${RUN_FLAGS[@]}" "$IMAGE" /src/docker/run.sh ${PASSTHRU[@]+"${PASSTHRU[@]}"}
    exit $?
fi

# =====================================================================================
# IN-CONTAINER RUNNER
# =====================================================================================
SRC=/src
WORK=/work
OUT=/out

STRICT=0
BREAK_CONFORMANCE=0
PREPARE_ONLY=0
REQUESTED=()

while [ $# -gt 0 ]; do
    case "$1" in
        --strict)             STRICT=1 ;;
        --break-conformance)  BREAK_CONFORMANCE=1 ;;
        --prepare-only)       PREPARE_ONLY=1 ;;
        -h|--help)            usage; exit 0 ;;
        all)                  REQUESTED+=($ALL_CHECKS) ;;
        ci|conformance|golden|determinism|bench) REQUESTED+=("$1") ;;
        *) echo "run.sh: unknown argument '$1' (try --help)" >&2; exit 2 ;;
    esac
    shift
done
[ ${#REQUESTED[@]} -eq 0 ] && REQUESTED=($ALL_CHECKS)

# ------------------------------------------------------------------ small helpers
hdr()  { printf '\n========== %s ==========\n' "$*"; }
say()  { printf '%s\n' "$*"; }
bad()  { printf '!! %s\n' "$*"; }

# Both taken from the workflows, which use exactly this shape. `grep -o` on the attribute rather
# than an XML parser, deliberately: no dependency is acquired to keep the guard true.
attr()  { grep -o "$1=\"[0-9]*\"" "$2" 2>/dev/null | head -1 | sed 's/[^0-9]//g'; }
sumattr() {                                   # sumattr <attr> <file...>
    local a="$1"; shift
    grep -ho "$a=\"[0-9]*\"" "$@" 2>/dev/null | sed 's/[^0-9]//g' \
        | awk '{n += $1} END {print n + 0}'
}
tsv()   { awk -v k="$1" -F'\t' '$1 == k {print $2; f = 1} END {if (!f) print "MISSING"}' "$2"; }
rows()  { grep -cv '^#' "$1"; }

# Results table. Status is one of PASS / XFAIL / FAIL.
#
# Written to a FILE, not to a bash array, and the reason is a bug this script had for one draft:
# each check runs inside a `| tee` pipeline, which bash executes in a subshell, so anything a check
# appends to an array is discarded the moment it returns. The summary would then have been empty -
# or worse, silently short - while every individual check printed correctly. A file crosses the
# subshell boundary; an array does not.
RESULTS="$OUT/results.tsv"
record() { printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "${4:-}" >> "$RESULTS"; }

# Mirrors every workflow's last step. A locally built neoproj4j snapshot left in the local repository
# could silently satisfy a later check's inter-module dependencies, so a run could "pass" against
# jars from an earlier state of the tree. The cached volume makes that a standing hazard here,
# not just a CI one.
scrub_m2() { rm -rf /root/.m2/repository/io/github/emilevictor/neoproj4j; }

# ------------------------------------------------------------------ prepare /work
prepare() {
    hdr "preparing /work from $SRC (read-only bind mount)"
    mkdir -p "$WORK" "$OUT"
    # The tree is COPIED, not built in place. Two reasons, both load-bearing:
    #   * `ci` runs `mvn clean install`, which would wipe the host's target/ directories out from
    #     under whatever else is running on this machine.
    #   * a check must not be able to modify a committed input; /src is mounted read-only and the
    #     positive control below edits only this copy.
    # target/ is excluded so the container starts from a genuinely clean tree rather than
    # inheriting the host's build output. .git is NOT excluded: jgitver reads it to compute the
    # version, exactly as it does on a runner after actions/checkout.
    rsync -a --delete \
        --exclude='target/' \
        --exclude='.claude/' \
        --exclude='.DS_Store' \
        "$SRC/" "$WORK/"
    chown -R root:root "$WORK"
    cd "$WORK" || exit 2

    {
        echo "container:   $(uname -s) $(uname -m)"
        echo "java:        $(java -version 2>&1 | head -1)"
        echo "maven:       $(mvn -v 2>/dev/null | head -1)"
        echo "locale:      LANG=$LANG TZ=$TZ  default=$(locale 2>/dev/null | head -1)"
        echo "git HEAD:    $(git -C "$WORK" rev-parse --short HEAD 2>/dev/null || echo '(none)')"
        echo "git branch:  $(git -C "$WORK" rev-parse --abbrev-ref HEAD 2>/dev/null || echo '(none)')"
        echo "git dirty:   $(git -C "$WORK" status --porcelain 2>/dev/null | wc -l | tr -d ' ') file(s)"
        echo "m2 cache:    $(du -sh /root/.m2/repository 2>/dev/null | cut -f1 || echo empty)"
    } | tee "$OUT/environment.txt"
}

# ------------------------------------------------------------------ positive control
break_conformance() {
    local m="$WORK/conformance/src/test/resources/gie-expected-failures.tsv"
    # The manifest lists only assertions that are NOT expected to pass; a key absent from it is
    # expected to PASS. So deleting one row asserts that a known-failing assertion now passes, and
    # the sweep must report it REGRESSED. This is the same control conformance.yaml's header
    # records having been run by hand ("one manifest row removed -> exit 1, naming the assertion").
    local key
    key=$(grep -v '^#' "$m" | awk -F'\t' 'NR > 1 && $2 == "FAIL" {print $1; exit}')
    hdr "POSITIVE CONTROL: removing one manifest row from the container's copy"
    say "  file: conformance/src/test/resources/gie-expected-failures.tsv  (in /work only)"
    say "  key:  $key"
    say "  This asserts that a known-failing assertion now passes. The sweep must call it"
    say "  REGRESSED and the check must go red naming it. If it does not, this runner cannot"
    say "  detect a conformance regression and nothing else it reports can be trusted."
    grep -v "^$key	" "$m" > "$m.tmp" && mv "$m.tmp" "$m"
    echo "$key" > "$OUT/broken-manifest-key.txt"
}

# =====================================================================================
# CHECK: ci   -- mirrors .github/workflows/ci.yaml, job build-and-test
# =====================================================================================
CI_MIN_TESTS=1700          # measured 1,735 in `core` alone on 2026-08-01
check_ci() {
    hdr "ci  -- mvn -B -ntp clean install   (ci.yaml / build-and-test)"
    cd "$WORK" || return 2
    mvn -B -ntp clean install 2>&1 | tee /tmp/ci-mvn.out
    local rc=${PIPESTATUS[0]}
    scrub_m2

    # ---- non-vacuity ------------------------------------------------------------------
    local xmls
    xmls=$(find . -path '*/target/surefire-reports/TEST-*.xml' | sort)
    if [ -z "$xmls" ]; then
        bad "no surefire reports anywhere. Nothing was tested, so exit code $rc means nothing."
        record ci FAIL "no surefire reports" "the build never reached a test phase"
        return
    fi
    # shellcheck disable=SC2086
    local ran fails errs skips
    ran=$(sumattr tests    $xmls)
    fails=$(sumattr failures $xmls)
    errs=$(sumattr errors   $xmls)
    skips=$(sumattr skipped  $xmls)
    say ""
    say "tests=$ran failures=$fails errors=$errs skipped=$skips  across $(printf '%s\n' "$xmls" | wc -l | tr -d ' ') report files"

    if [ "$ran" -lt "$CI_MIN_TESTS" ]; then
        bad "only $ran tests ran; the floor is $CI_MIN_TESTS."
        bad "A run that executes nothing cannot report success. Tests were dropped, a module"
        bad "failed to compile, or the reactor stopped before core's test phase."
        record ci FAIL "$ran tests (floor $CI_MIN_TESTS)" "floor assertion failed - too few tests ran"
        return
    fi

    # ---- which classes failed ---------------------------------------------------------
    local offenders=""
    local f
    for f in $xmls; do
        local nf ne cls
        nf=$(attr failures "$f"); ne=$(attr errors "$f")
        if [ "${nf:-0}" -gt 0 ] || [ "${ne:-0}" -gt 0 ]; then
            cls=$(basename "$f" .xml); cls=${cls#TEST-}
            offenders="$offenders $cls"
            say "  failing: $cls  (failures=${nf:-0} errors=${ne:-0})"
        fi
    done
    offenders=$(echo "$offenders" | tr -s ' ' | sed 's/^ //;s/ $//')

    if [ "$rc" -eq 0 ]; then
        say ""
        say "ci is GREEN. It was expected to fail on MetaCRSTest, whose input"
        say "epsg/src/main/resources/proj4/proj4-epsg.csv was stale and was being regenerated."
        say "If that regeneration has landed, this is the intended new state - not a fluke."
        record ci PASS "$ran tests, 0 failures" \
            "green; the MetaCRSTest expectation no longer applies (proj4-epsg.csv looks regenerated)"
        return
    fi

    if [ "$offenders" = "org.locationtech.proj4j.MetaCRSTest" ]; then
        record ci XFAIL "$ran tests, $((fails + errs)) failure(s) - MetaCRSTest only" \
            "EXPECTED: MetaCRSTest reads epsg/src/main/resources/proj4/proj4-epsg.csv, which is stale and is being regenerated. It is the only failing class, so nothing else regressed."
    elif [ -z "$offenders" ]; then
        # A reactor failure with every test green is the interesting case: something in the build
        # itself broke, and while `core` was red nobody could see it because the reactor stopped
        # there. Name the goal and the module rather than making the reader open the log.
        local goal
        goal=$(grep -m1 -oE 'Failed to execute goal [^ ]+ \([^)]*\) on project [A-Za-z0-9_.-]+' /tmp/ci-mvn.out)
        say ""
        bad "the reactor failed with ZERO failing tests. ${goal:-(no 'Failed to execute goal' line found)}"
        grep -E '^\[INFO\] (neoProj4J|[A-Za-z].*\.\.\.\.).*(SUCCESS|FAILURE|SKIPPED)' /tmp/ci-mvn.out | tail -10
        record ci FAIL "$ran tests, 0 test failures, exit $rc; ${goal:-build error}" \
            "the build failed with no failing test - a compile, plugin or packaging error. All $ran tests passed, so this is a build problem, not a behavioural one."
    else
        record ci FAIL "$ran tests, $((fails + errs)) failure(s) in: $offenders" \
            "classes other than MetaCRSTest failed; only MetaCRSTest is expected to"
    fi
}

# =====================================================================================
# CHECK: conformance -- mirrors .github/workflows/conformance.yaml, job corpus
# =====================================================================================
check_conformance() {
    hdr "conformance  -- the PROJ 9.8.1 gie/GIGS corpus sweep   (conformance.yaml / corpus)"
    cd "$WORK" || return 2
    rm -rf conformance/target/conformance conformance/target/surefire-reports

    # VERBATIM from conformance.yaml. Every flag is load-bearing and two of them are here to stop
    # this passing without measuring anything - see that file's step comment. The ** is required:
    # every conformance test lives in a sub-package and surefire's single * does not cross a
    # package separator, so the single-star form prints "No tests to run." then BUILD SUCCESS.
    mvn -B -ntp -Pconformance -pl conformance -am verify \
        -Dtest='org.locationtech.proj4j.conformance.**.*Test' \
        -Dsurefire.failIfNoSpecifiedTests=false \
        -Dmaven.javadoc.skip=true
    local rc=$?
    scrub_m2

    # ---- the workflow's own "Assert the sweep actually swept" step, translated ---------
    local reports=conformance/target/surefire-reports
    local xml="$reports/TEST-org.locationtech.proj4j.conformance.runner.GieConformanceTest.xml"
    local summary=conformance/target/conformance/summary.tsv
    local index=conformance/src/test/resources/gie-corpus-index.tsv
    local manifest=conformance/src/test/resources/gie-expected-failures.tsv
    local why=""
    say ""

    if [ ! -f "$manifest" ] || [ ! -f "$index" ]; then
        bad "ABSENT BASELINE: the committed manifest or corpus index is missing. The gate cannot"
        bad "run without it and its absence is NOT a regression - restore the file, do not"
        bad "regenerate it."
        record conformance FAIL "absent baseline" "the committed baseline is missing, which is a different failure from a regression"
        return
    fi
    if [ ! -f "$xml" ]; then
        bad "no surefire report for GieConformanceTest at $xml"
        bad "The sweep did not run: either -Pconformance was ineffective, or the -Dtest pattern"
        bad "matched nothing (the ** is required)."
        ls -R "$reports" 2>/dev/null || true
        record conformance FAIL "sweep produced no report" "the sweep did not execute"
        return
    fi

    local ran skipped
    ran=$(attr tests "$xml"); skipped=$(attr skipped "$xml")
    say "GieConformanceTest: tests=$ran skipped=$skipped"
    if [ "${ran:-0}" -lt 7900 ]; then
        bad "GieConformanceTest reported only ${ran:-0} tests. The corpus is 7,923 assertions plus"
        bad "per-file leak checks and the gate container; a count of 1 means the sweep was skipped"
        bad "by gie.corpus.skip and NOTHING was measured."
        record conformance FAIL "${ran:-0} assertions (floor 7900)" "floor assertion failed - the sweep did not sweep"
        return
    fi

    # Nothing OUTSIDE the sweep may be skipped. GieConformanceTest legitimately aborts
    # STILL_FAILING assertions; an Assume firing anywhere else hides a real loss of coverage.
    local others otherskips classes
    others=$(ls "$reports"/TEST-*.xml 2>/dev/null | grep -v GieConformanceTest)
    classes=$(printf '%s\n' "$others" | grep -c .)
    if [ "$classes" -eq 0 ]; then
        # Guarded, not merely reported: `sumattr` with no file arguments would read stdin and the
        # whole run would hang instead of failing.
        bad "no conformance test classes besides the sweep produced a report. The -Dtest pattern"
        bad "matched only the sweep, or the other tests were not compiled."
        record conformance FAIL "0 test classes besides the sweep" "floor assertion failed - the surrounding conformance tests did not run"
        return
    fi
    # shellcheck disable=SC2086
    otherskips=$(sumattr skipped $others)
    say "other conformance test classes: $classes with $otherskips skips"
    if [ "$otherskips" -ne 0 ]; then
        bad "$otherskips conformance tests outside the sweep were SKIPPED. Skips are never passes."
        record conformance FAIL "$otherskips skips outside the sweep" "an Assume fired where it must not"
        return
    fi
    if [ "$classes" -lt 25 ]; then
        bad "only $classes conformance test classes ran (expected 26 or more besides the sweep)."
        record conformance FAIL "$classes test classes (floor 25)" "floor assertion failed - test classes were dropped or not matched"
        return
    fi

    if [ ! -f "$summary" ]; then
        bad "no $summary; the sweep produced no machine summary."
        record conformance FAIL "no summary.tsv" "the sweep produced no machine-readable summary"
        return
    fi
    local evaluated notrun keys pass denom
    evaluated=$(tsv conformance.evaluated "$summary")
    notrun=$(tsv conformance.notrun "$summary")
    pass=$(tsv conformance.pass "$summary")
    denom=$(tsv conformance.measured_denominator "$summary")
    keys=$(rows "$index")
    say "assertions evaluated=$evaluated  baseline index keys=$keys  notrun=$notrun"
    if [ "$evaluated" != "$keys" ]; then
        bad "the sweep evaluated $evaluated assertions but the committed index has $keys keys."
        bad "The run did not cover the same corpus, so the diff below it is not comparable."
        bad "Do NOT regenerate the baseline to absorb this."
        record conformance FAIL "$evaluated evaluated vs $keys indexed" "the sweep covered a different corpus than the baseline"
        return
    fi
    if [ "$notrun" != "0" ]; then
        bad "$notrun assertions were never run."
        record conformance FAIL "$notrun assertions not run" "part of the corpus never executed"
        return
    fi

    local stillfailing manifestrows
    stillfailing=$(tsv diff.still_failing "$summary")
    manifestrows=$(($(rows "$manifest") - 1))     # minus the column header
    say "sweep skips=$skipped  still_failing=$stillfailing  manifest rows=$manifestrows"
    if [ "$skipped" != "$stillfailing" ]; then
        bad "the sweep aborted $skipped assertions but the diff calls only $stillfailing of them STILL_FAILING."
        record conformance FAIL "skips $skipped != still_failing $stillfailing" "the abort mechanism is absorbing assertions nobody wrote down"
        return
    fi

    local k v hard=0
    for k in diff.regressed diff.unexpected_pass diff.disappeared; do
        v=$(tsv "$k" "$summary")
        say "$k = $v"
        if [ "$v" != "0" ]; then hard=1; why="$why $k=$v"; fi
    done
    say "Non-vacuity satisfied: $evaluated assertions swept against a $keys-key baseline."
    [ -f conformance/target/conformance/report.txt ] && head -1 conformance/target/conformance/report.txt

    if [ "$hard" = 1 ]; then
        bad "REGRESSION:$why  -- see conformance/target/conformance/differences.txt"
        head -20 conformance/target/conformance/differences.txt 2>/dev/null
        record conformance FAIL "$pass/$denom genuine passes;$why" \
            "an assertion that used to pass no longer does (or the baseline no longer describes the corpus)"
    elif [ "$rc" -ne 0 ]; then
        record conformance FAIL "$pass/$denom genuine passes, regressed 0, but mvn exited $rc" \
            "the sweep is clean but the build failed for another reason - read the log"
    else
        record conformance PASS "$pass/$denom genuine passes, regressed 0, $evaluated assertions evaluated" ""
    fi
}

# =====================================================================================
# CHECK: golden -- mirrors .github/workflows/golden.yaml, job golden
# =====================================================================================
check_golden() {
    hdr "golden  -- the 53,430-row behavioural sweep   (golden.yaml / golden)"
    cd "$WORK" || return 2
    rm -rf golden/target/golden golden/target/surefire-reports

    # VERBATIM from golden.yaml. `*` not `**` here is correct: every golden test sits directly in
    # org.locationtech.proj4j.golden. -Dmaven.test.failure.ignore=true is deliberately absent - it
    # would force exit 0 and ignore THE GATE'S OWN FAILURE.
    mvn -B -ntp -Pgolden -pl golden -am verify \
        -Dtest='org.locationtech.proj4j.golden.*Test' \
        -Dsurefire.failIfNoSpecifiedTests=false \
        -Dmaven.javadoc.skip=true
    local rc=$?
    scrub_m2

    local xml=golden/target/surefire-reports/TEST-org.locationtech.proj4j.golden.GoldenMasterTest.xml
    say ""
    if [ ! -f "$xml" ]; then
        bad "no surefire report for GoldenMasterTest at $xml"
        bad "The gate did not run. -Pgolden is what adds the module AND flips golden.skip;"
        bad "a job that runs no assertions is not a pass."
        ls -R golden/target/surefire-reports 2>/dev/null || true
        record golden FAIL "gate produced no report" "the gate did not execute"
        return
    fi

    local ran skipped
    ran=$(attr tests "$xml"); skipped=$(attr skipped "$xml")
    say "GoldenMasterTest: tests=$ran skipped=$skipped"
    if [ "${ran:-0}" -ne 1 ]; then
        bad "expected exactly 1 GoldenMasterTest, found ${ran:-0}"
        record golden FAIL "GoldenMasterTest ran ${ran:-0} times" "floor assertion failed"
        return
    fi
    if [ "${skipped:-0}" -ne 0 ]; then
        bad "GoldenMasterTest was SKIPPED - golden.skip was still true, so no table was generated"
        bad "and nothing was compared. Skips are never passes."
        record golden FAIL "GoldenMasterTest skipped" "the sweep was skipped, which surefire reports as success"
        return
    fi

    local got want
    got=$(wc -l < golden/target/golden/golden.tsv 2>/dev/null || echo 0)
    want=$(wc -l < golden/baseline/1.4.3/golden.tsv)
    say "golden.tsv rows (incl. header): generated=$got baseline=$want"
    if [ "$got" -ne "$want" ]; then
        bad "the generated table has $got lines but the baseline has $want. A row-count mismatch"
        bad "means the sweep did not cover the same input set, so the diff is not comparable."
        record golden FAIL "$got rows generated vs $want baseline" "the sweep covered a different input set"
        return
    fi

    local total totalskip
    total=$(sumattr tests   golden/target/surefire-reports/TEST-org.locationtech.proj4j.golden.*.xml)
    totalskip=$(sumattr skipped golden/target/surefire-reports/TEST-org.locationtech.proj4j.golden.*.xml)
    say "golden module: tests=$total skipped=$totalskip"
    if [ "$total" -lt 40 ]; then
        bad "only $total tests ran in the golden module; expected at least 40."
        record golden FAIL "$total tests (floor 40)" "floor assertion failed - tests were dropped or not matched"
        return
    fi
    if [ "$totalskip" -ne 0 ]; then
        bad "$totalskip golden tests were skipped. Skips are never passes."
        record golden FAIL "$totalskip skips" "an Assume fired inside the golden module"
        return
    fi
    say "Non-vacuity satisfied: the gate ran, over the full $want-line table, with no skips."

    # ---- classify the failure ----------------------------------------------------------
    # 41 rules, all with a pinned expected_rows, is the state the backlog is being worked against.
    local rules pinned
    rules=$(grep -cE '^  - id:' golden/rules.yaml)
    pinned=$(grep -cE '^    expected_rows: [0-9]+' golden/rules.yaml)
    say "rules.yaml: $pinned/$rules rules carry a pinned expected_rows"

    local unexplained diffline hardkinds="" kind n
    diffline=$(grep -ho 'golden diff: [^<"]*' "$xml" | head -1)
    [ -n "$diffline" ] && say "$diffline"
    unexplained=$(grep -o 'UNEXPLAINED [0-9]* row(s)' "$xml" | head -1 | tr -dc '0-9')
    [ -z "$unexplained" ] && unexplained=0
    for kind in COUNT_MISMATCH DEAD_RULE EXPIRED_RULE PENDING_RULE_FIRED; do
        n=$(grep -c "$kind " "$xml")
        [ "$n" -gt 0 ] && hardkinds="$hardkinds $kind($n)"
    done

    if [ "$rc" -eq 0 ]; then
        record golden PASS "0 UNEXPLAINED, $pinned/$rules rules pinned" ""
    elif [ -n "$hardkinds" ]; then
        bad "golden failed on rule-level problems:$hardkinds"
        bad "These are NOT the triage backlog. A COUNT_MISMATCH, DEAD_RULE, EXPIRED_RULE or"
        bad "PENDING_RULE_FIRED means the rule set has stopped describing the tree."
        grep -o "\(COUNT_MISMATCH\|DEAD_RULE\|EXPIRED_RULE\|PENDING_RULE_FIRED\) [a-z0-9-]*" "$xml" | sort -u
        record golden FAIL "$unexplained UNEXPLAINED plus$hardkinds" \
            "a rule-level failure, which is a defect in the rule set - not the triage backlog"
    elif [ "$unexplained" -gt 0 ]; then
        record golden XFAIL "$unexplained UNEXPLAINED rows, $pinned/$rules rules pinned, no COUNT_MISMATCH/DEAD_RULE/EXPIRED_RULE/PENDING_RULE_FIRED" \
            "EXPECTED: $unexplained changed rows have not yet been attributed to a specific fix. That is a triage backlog owned by the streams that caused it, not a defect in this gate. It goes green one rule at a time."
    else
        record golden FAIL "mvn exited $rc with 0 UNEXPLAINED and no rule failure" \
            "the gate failed for a reason the summary cannot name - read the log"
    fi
}

# =====================================================================================
# CHECK: determinism -- mirrors .github/workflows/determinism.yaml, job bits (ONE leg)
# =====================================================================================
# UPDATED 2026-08-02. This block used to say determinism.yaml pinned EXPECTED_TESTS as an EXACT
# count and was stale at 15 against a true 22, so this runner deliberately held a lower floor and
# shouted about the drift. That is no longer the situation in either direction: determinism.yaml
# now asserts DET_FLOOR_TESTS=22, a FLOOR, and reports an upward drift as a ::notice:: instead of
# failing. Its own header records the two staleness incidents (9 -> 15 -> 22) as the reason. So the
# two files now agree on both the constant and its shape, and this runner's job is to keep them in
# step rather than to compensate for one of them.
#
# The value is re-derived here rather than copied from that file: `mvn -pl core -am test
# -Dtest='org.locationtech.proj4j.determinism.*Test'` on Temurin 21 / aarch64 reports "Tests run:
# 22, Failures: 0, Errors: 0, Skipped: 0" across four surefire XMLs (StrictMathGoldenTableTest 6,
# NanBitPatternTest 3, NoAmbientLocaleInCoreTest 7, NoJdkAngleConversionTest 6), cross-checked
# against 22 `@Test` methods in the package's sources. Two independent counts that agree.
#
# A floor still makes "a container that ran nothing" impossible, which is the property that
# matters. What it gives up is catching a DROP that stays above the floor, and that is why the
# drift line below prints on every run rather than only on failure.
#
# RAISE BOTH when tests are added on purpose. It is a ratchet, not a ceiling, and the two constants
# must move together.
DET_FLOOR_TESTS=22
DET_WORKFLOW_FLOOR=22
check_determinism() {
    hdr "determinism  -- the raw-bit StrictMath golden   (determinism.yaml / bits, one leg)"
    cd "$WORK" || return 2
    rm -rf core/target/determinism core/target/surefire-reports

    say "This container is ONE leg of a six-leg matrix (x86-64 x aarch64, JDK 11/17/21). It"
    say "reproduces the per-leg assertion only. determinism.yaml's cross-arch job - in particular"
    say "its non-vacuity check that Math must diverge from the golden on at least one leg - is"
    say "a property of the MATRIX and cannot be asserted from inside a single container."
    say ""

    mvn -B -ntp -pl core -am test \
        -Dtest='org.locationtech.proj4j.determinism.*Test' \
        -Dsurefire.failIfNoSpecifiedTests=false \
        -Dmaven.javadoc.skip=true \
        -DargLine="-Dproj4j.determinism.outDir=$WORK/core/target/determinism"
    local rc=$?
    scrub_m2

    say ""
    local xmls nxml
    xmls=$(ls core/target/surefire-reports/TEST-org.locationtech.proj4j.determinism.*.xml 2>/dev/null)
    nxml=$(printf '%s\n' "$xmls" | grep -c .)
    if [ "$nxml" -lt 2 ]; then
        bad "expected at least 2 determinism surefire reports, found $nxml."
        bad "The tests did not run. A run that makes no assertions is not a pass."
        record determinism FAIL "$nxml report files" "the determinism tests did not execute"
        return
    fi
    # shellcheck disable=SC2086
    local ran fails errs skips
    ran=$(sumattr tests    $xmls)
    fails=$(sumattr failures $xmls)
    errs=$(sumattr errors   $xmls)
    skips=$(sumattr skipped  $xmls)
    say "determinism tests: ran=$ran failures=$fails errors=$errs skipped=$skips  across $nxml classes"
    if [ "$ran" -lt "$DET_FLOOR_TESTS" ]; then
        bad "only $ran determinism tests ran; the floor is $DET_FLOOR_TESTS. The tests did not run,"
        bad "or were renamed out of the -Dtest pattern. A run that makes no assertions is not a pass."
        record determinism FAIL "$ran tests (floor $DET_FLOOR_TESTS)" "floor assertion failed - too few determinism tests ran"
        return
    fi
    local drift="" driftwhy=""
    if [ "$ran" -gt "$DET_WORKFLOW_FLOOR" ]; then
        drift=" [drifted UP: determinism.yaml's floor is $DET_WORKFLOW_FLOOR, +$((ran - DET_WORKFLOW_FLOOR))]"
        driftwhy="$ran determinism tests match the pattern today against a floor of $DET_WORKFLOW_FLOOR in both .github/workflows/determinism.yaml and this script. Not a failure - a floor is a ratchet - but raise both so the floor keeps tracking reality."
        say ""
        say "DRIFT, not a failure: $ran tests matched the pattern and the floor in both"
        say ".github/workflows/determinism.yaml and this script is $DET_WORKFLOW_FLOOR. Raise"
        say "DET_FLOOR_TESTS in both files in the same commit so the floor keeps tracking reality."
        ls core/target/surefire-reports/TEST-org.locationtech.proj4j.determinism.*.xml \
            | sed 's|.*TEST-|    |; s|\.xml$||'
        say ""
    fi
    if [ "$skips" -ne 0 ]; then
        bad "$skips determinism tests were skipped. Skips are never passes."
        record determinism FAIL "$skips skips" "an Assume fired"
        return
    fi

    # Informational: this leg's Math-vs-golden divergence. NOT asserted - see the header above and
    # determinism.yaml's own comment: on Temurin 11 / aarch64 the true value is 0, so a per-leg
    # assertion here would ship a permanently red leg for a non-defect.
    local mdt=core/target/determinism/math-divergence.tsv
    if [ -f "$mdt" ]; then
        local d t
        d=$(awk -F'\t' '$1 == "TOTAL" {print $2}' "$mdt")
        t=$(awk -F'\t' '$1 == "TOTAL" {print $3}' "$mdt")
        say "this leg ($(uname -m), $(java -version 2>&1 | head -1 | tr -d '\n')):"
        say "  Math differs from the StrictMath golden on $d of $t probes (reported, not asserted)"
    else
        say "note: $mdt was not produced, so the cross-arch payload is missing from this leg."
    fi

    if [ "$rc" -ne 0 ] || [ "$fails" -ne 0 ] || [ "$errs" -ne 0 ]; then
        record determinism FAIL "$ran tests, $fails failures, $errs errors" \
            "a leg of the bit-identity proof failed - this is the headline guarantee"
    else
        record determinism PASS "$ran tests, 0 failures (one leg: $(uname -m) / Temurin 21)$drift" "$driftwhy"
    fi
}

# =====================================================================================
# CHECK: bench -- mirrors .github/workflows/bench.yaml, job gate.  OPT-IN.
# =====================================================================================
check_bench() {
    hdr "bench  -- Tier 1 allocation + Tier 2 op-count gate   (bench.yaml / gate)"
    cd "$WORK" || return 2
    rm -f benchmark/target/jmh-result.json

    say "This is the slow one: ~16 minutes, and it wants a quiet machine. It is deliberately NOT"
    say "part of the default run. JMH measures bytes/op, which is a bytecode property and is"
    say "robust to a busy box - but the run still takes real wall-clock, and a container competing"
    say "for CPU with a browser will take longer, not lie."
    say ""

    mvn -B -ntp -Pbench -pl benchmark -am package \
        -DskipTests \
        -Dmaven.javadoc.skip=true \
        -Dmaven.source.skip=true
    local rc=$?
    if [ "$rc" -ne 0 ]; then
        bad "the shaded benchmarks jar did not build."
        scrub_m2
        record bench FAIL "build failed (exit $rc)" "benchmark/target/benchmarks.jar was never produced"
        return
    fi

    # The gate's own plumbing, before it is trusted to judge anything else.
    java -cp benchmark/target/benchmarks.jar \
        org.locationtech.proj4j.benchmark.gate.GateChecker --self-test
    local selftest=$?
    say "GateChecker --self-test exit $selftest"
    if [ "$selftest" -ne 0 ]; then
        scrub_m2
        record bench FAIL "GateChecker --self-test exited $selftest" "the gate's own plumbing is broken, so its verdict on anything else is worthless"
        return
    fi

    ( cd benchmark && ./run-gate.sh --quick --require-baseline ) 2>&1 | tee /tmp/bench-gate.out
    local grc=${PIPESTATUS[0]}
    scrub_m2

    # ---- non-vacuity ------------------------------------------------------------------
    say ""
    local json=benchmark/target/jmh-result.json
    if [ ! -f "$json" ]; then
        bad "no $json - JMH produced no result file, so Tier 1 examined nothing."
        record bench FAIL "no jmh-result.json" "JMH produced no measurements"
        return
    fi
    local arms alloc
    arms=$(grep -c '"benchmark"' "$json")
    alloc=$(grep -c 'gc.alloc.rate.norm' "$json")
    say "JMH arms: $arms   arms carrying gc.alloc.rate.norm: $alloc"
    if [ "$arms" -lt 20 ]; then
        bad "only $arms benchmark arms in the result; expected at least 20. Benchmarks were"
        bad "excluded, renamed, or failed in @Setup. Tier 1 cannot gate what was not run."
        record bench FAIL "$arms arms (floor 20)" "floor assertion failed - too few benchmark arms"
        return
    fi
    if [ "$alloc" -lt 20 ]; then
        bad "$alloc arms carry gc.alloc.rate.norm. The -prof gc profiler did not attach, so Tier 1"
        bad "would have measured NOTHING while reporting success."
        record bench FAIL "$alloc arms with an allocation measurement (floor 20)" "the allocation profiler did not attach"
        return
    fi
    say "Non-vacuity satisfied: $alloc of $arms arms carry an allocation measurement."

    local verdict gated
    verdict=$(grep -ohE 'GATE (PASSED|FAILED) *\([^)]*\)' /tmp/bench-gate.out | tail -1)
    gated=$(grep -ohE '[0-9]+ gated, [0-9]+ EXCLUDED' /tmp/bench-gate.out | tail -1)
    say "${verdict:-(no GATE verdict line found)}   ${gated:-}"
    cp -f /tmp/bench-gate.out "$OUT/bench-gate.out" 2>/dev/null

    if [ "$grc" -eq 0 ]; then
        record bench PASS "0 breaches; ${gated:-gating coverage unknown}; $arms arms" ""
    else
        record bench FAIL "${verdict:-exit $grc}; ${gated:-}" \
            "an allocation or op-count threshold was breached, or a TBD threshold was hit under --require-baseline"
    fi
}

# =====================================================================================
# DRIVE
# =====================================================================================
START=$(date +%s)
mkdir -p "$OUT"
: > "$OUT/results.tsv"
: > "$OUT/timings.tsv"

prepare
[ "$PREPARE_ONLY" = 1 ] && exit 0
[ "$BREAK_CONFORMANCE" = 1 ] && break_conformance 2>&1 | tee "$OUT/control.log"

for c in "${REQUESTED[@]}"; do
    t0=$(date +%s)
    case "$c" in
        ci)          check_ci          2>&1 | tee "$OUT/ci.log" ;;
        conformance) check_conformance 2>&1 | tee "$OUT/conformance.log" ;;
        golden)      check_golden      2>&1 | tee "$OUT/golden.log" ;;
        determinism) check_determinism 2>&1 | tee "$OUT/determinism.log" ;;
        bench)       check_bench       2>&1 | tee "$OUT/bench.log" ;;
    esac
    printf '%s\t%s\n' "$c" "$(( $(date +%s) - t0 ))" >> "$OUT/timings.tsv"
done

# ------------------------------------------------------------------ collect artefacts
mkdir -p "$OUT/reports"
cp -f "$WORK"/conformance/target/conformance/report.txt        "$OUT/reports/" 2>/dev/null
cp -f "$WORK"/conformance/target/conformance/summary.tsv       "$OUT/reports/" 2>/dev/null
cp -f "$WORK"/conformance/target/conformance/differences.txt   "$OUT/reports/" 2>/dev/null
cp -f "$WORK"/golden/target/golden/golden-summary.txt          "$OUT/reports/" 2>/dev/null
cp -f "$WORK"/core/target/determinism/*.tsv                    "$OUT/reports/" 2>/dev/null
cp -f "$WORK"/benchmark/target/jmh-result.json                 "$OUT/reports/" 2>/dev/null

# ------------------------------------------------------------------ summary
ELAPSED=$(( $(date +%s) - START ))
fmt_dur() { awk -v s="$1" 'BEGIN {printf "%dm%02ds", int(s/60), s%60}'; }

summary() {
    local npass=0 nxfail=0 nfail=0 nmissing=0
    printf '\n'
    printf '=========================================================================\n'
    printf ' neoproj4j Docker check run - %s / Temurin 21.0.11 / %s\n' "$(uname -m)" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    printf '=========================================================================\n\n'
    printf '%-13s %-14s %-9s %s\n' CHECK VERDICT TIME 'MEASURED'
    printf -- '------------- -------------- --------- --------------------------------\n'

    local c
    for c in "${REQUESTED[@]}"; do
        local line status detail secs
        line=$(awk -F'\t' -v k="$c" '$1 == k {print; exit}' "$RESULTS")
        secs=$(awk -F'\t' -v k="$c" '$1 == k {print $2; exit}' "$OUT/timings.tsv")
        if [ -z "$line" ]; then
            # A check that produced no verdict at all is a failure of the runner, not a pass.
            printf '%-13s %-14s %-9s %s\n' "$c" 'NO VERDICT' "$(fmt_dur "${secs:-0}")" \
                'the check recorded nothing - treat as failed'
            nmissing=$((nmissing + 1)); continue
        fi
        status=$(printf '%s' "$line" | cut -f2)
        detail=$(printf '%s' "$line" | cut -f3)
        case "$status" in
            PASS)  npass=$((npass + 1));  printf '%-13s %-14s %-9s %s\n' "$c" 'PASS'          "$(fmt_dur "${secs:-0}")" "$detail" ;;
            XFAIL) nxfail=$((nxfail + 1)); printf '%-13s %-14s %-9s %s\n' "$c" 'FAIL(expected)' "$(fmt_dur "${secs:-0}")" "$detail" ;;
            *)     nfail=$((nfail + 1));  printf '%-13s %-14s %-9s %s\n' "$c" 'FAIL'          "$(fmt_dur "${secs:-0}")" "$detail" ;;
        esac
    done

    # The "why" column is the whole point of the two expected failures. Printed in full, below the
    # table, so a red line is never just red.
    if [ "$nxfail" -gt 0 ] || [ "$nfail" -gt 0 ] || [ "$npass" -gt 0 ]; then
        printf '\n'
        while IFS=$'\t' read -r nm st dt wy; do
            [ -z "${wy:-}" ] && continue
            case "$st" in
                XFAIL) printf 'EXPECTED FAILURE - %s\n    %s\n\n' "$nm" "$wy" ;;
                FAIL)  printf 'UNEXPECTED FAILURE - %s\n    %s\n\n' "$nm" "$wy" ;;
                PASS)  printf 'NOTE - %s\n    %s\n\n' "$nm" "$wy" ;;
            esac
        done < "$RESULTS"
    fi

    printf -- '-------------------------------------------------------------------------\n'
    printf '%d passed, %d failed AS EXPECTED, %d failed unexpectedly' "$npass" "$nxfail" "$nfail"
    [ "$nmissing" -gt 0 ] && printf ', %d produced NO VERDICT' "$nmissing"
    printf '   (total %s)\n' "$(fmt_dur "$ELAPSED")"
    printf 'logs and reports: %s (host: the --out directory)\n' "$OUT"
    if [ "$nxfail" -gt 0 ] && [ "$STRICT" = 0 ]; then
        printf '\n"FAIL(expected)" means the check really did fail and the failure is a known,\n'
        printf 'explained backlog item, so it does not set the exit code. Re-run with --strict to\n'
        printf 'make it do so.\n'
    fi
    printf '=========================================================================\n'

    if [ "$nfail" -gt 0 ] || [ "$nmissing" -gt 0 ]; then return 1; fi
    if [ "$STRICT" = 1 ] && [ "$nxfail" -gt 0 ]; then return 1; fi
    return 0
}

summary | tee "$OUT/summary.txt"
exit "${PIPESTATUS[0]}"
