# Running neoproj4j's checks in a container

`./docker/run.sh` runs the project's five check suites in a clean Linux container on a pinned
Temurin 21, using **the same commands the GitHub workflows use**. It exists because a run on the
authoring Mac is not evidence about a runner: the JDK, the OS, the default `Locale` and a `~/.m2`
full of other agents' snapshots all differ, and three of those have produced a wrong answer on this
project before.

It is **an additional way to run the same checks**. It does not replace `.github/workflows/**`, it
is not wired into any `pom.xml`, and nothing outside `docker/` was touched to add it.

```
docker/
  Dockerfile   pinned Temurin 21.0.11 + Apache Maven 3.9.16, SHA-512 checked
  run.sh       the single entry script - host driver AND in-container runner
  README.md    this file
```

## Quick start

```bash
./docker/run.sh                     # the four default checks (measured: 1m56s warm, 2m39s cold)
./docker/run.sh conformance         # just one
./docker/run.sh ci golden           # any subset
./docker/run.sh bench               # the opt-in performance gate (measured: 15m47s)
./docker/run.sh --help
```

Logs, reports and a machine-readable `results.tsv` land in `/tmp/neoproj4j-docker-out` (change with
`--out DIR`). The working tree is **copied** into the container out of a read-only bind mount, so
`mvn clean install` in the container cannot touch the host's `target/` directories - which matters
here, because several agents build this tree concurrently.

## What each check is, and what it measured

Measured in the container on 2026-08-01, at `726cf2c` + 32 dirty
files, Temurin 21.0.11 / aarch64 / Ubuntu 24.04:

| check | mirrors | verdict | measured |
|---|---|---|---|
| `ci` | `ci.yaml` / `build-and-test` | **PASS** *(re-measured 2026-08-03)* | `2320 tests, 0 failures, 4 skipped` in 223 report files, whole 7-module reactor `BUILD SUCCESS`. Module split: core **1917** (3 skipped), conformance 345 (1 skipped), db **52**, geoapi 6, grids-us-legacy 0. **Trail of this number:** `1792` on 2026-08-01 (`maven-javadoc-plugin:jar (attach-javadocs)` failed on `neoproj4j-db`, stopping the reactor before `db` and `conformance`), `2142` on 2026-08-02 once that was fixed (core 1749, db 42), `2320` today — +171 in `core` and +10 in `db`, all of it new tests from the security/perf hardening rounds, **0 pre-existing tests broken at any point**. |
| `conformance` | `conformance.yaml` / `corpus` | PASS | `7441/7900` genuine passes, `regressed 0`, 7,923 assertions evaluated |
| `golden` | `golden.yaml` / `golden` | **FAIL (expected)** | `2291 UNEXPLAINED`, **42/42** rules pinned, no `COUNT_MISMATCH`/`DEAD_RULE`/`EXPIRED_RULE`/`PENDING_RULE_FIRED`. Full split `12012 UNCHANGED · 41418 CHANGED · 0 ADDED · 0 REMOVED · 39127 INTENDED` over 53,430 rows. The 42nd rule is `NUM-LAEA-HYPOT-TO-NORM2`, which owns exactly 2 rows |
| `determinism` | `determinism.yaml` / `bits` (one leg) | PASS | `22 tests, 0 failures` |
| `bench` | `bench.yaml` / `gate` | PASS *(re-measured 2026-08-03)* | `0 breaches`, **`245 gated, 0 EXCLUDED`, 245 arms**, `245 of 245 arms carry an allocation measurement`, in **21 m 23 s**. Was `172 gated, 9 EXCLUDED, 181 arms` on 2026-08-02: **+64 arms** because `BulkTransformBenchmark` came out of the `staged` package and into the gate, and **−9 exclusions** because `crs-parse` rejoined Tier 1 when `io/InitFileCache` removed the per-call re-scan that made it flake |

**Failures are classified, not just counted.** An expected failure appears as `FAIL(expected)` with
its reason spelled out and does not set the exit code; anything else is `FAIL` and does. Neither
expectation is hardcoded to a verdict - each is re-derived from the run:

* **`ci`** is an expected failure *only while the one failing class is `MetaCRSTest`*, which reads
  `epsg/src/main/resources/proj4/proj4-epsg.csv`. **That regeneration has landed and so has the
  `neoproj4j-db` javadoc fix**: as of 2026-08-02 `ci` is simply **green** — `2320 tests, 0 failures` when
  re-measured 2026-08-03, `BUILD SUCCESS` across all seven modules — and the runner says so, reporting
  that the `MetaCRSTest` expectation no longer applies. The `MetaCRSTest` branch is retained because it
  is still correct on a tree with the old CSV. **This means `run.sh`'s own header comment — `ci …
  FAILS on MetaCRSTest only` — describes a tree that no longer exists.** The re-derivation is what
  saves it: the script does not hardcode the verdict, it recomputes the expectation from the run, and
  the summary prints `green; the MetaCRSTest expectation no longer applies`. Read the summary, not the
  header.
* **`golden`** is an expected failure *only while its failure is `UNEXPLAINED` rows and nothing
  else*. `COUNT_MISMATCH`, `DEAD_RULE`, `EXPIRED_RULE` and `PENDING_RULE_FIRED` are checked for
  separately and are reported as **real** failures, because they mean the rule set has stopped
  describing the tree. The 2,291 unexplained rows are a triage backlog owned by the streams that
  caused them; the gate goes green one rule at a time.

Exit code: `0` if every check either passed or failed in its expected, explained way; `1` if
anything else failed, including a floor assertion or a check that produced no verdict at all. Pass
`--strict` to make an expected failure exit non-zero too.

## Two findings this runner surfaced on its first real use

Both were invisible before because `core`'s red test phase stopped every reactor early.

**1. ~~`mvn clean install` - the exact `ci.yaml` command - now fails in `neoproj4j-db`, with every test
green.~~ FIXED — re-measured green in the container on 2026-08-02, whole reactor `BUILD SUCCESS`.
Retained as the record of the defect and of the A/B/C that isolated it.**

```
Failed to execute goal org.apache.maven.plugins:maven-javadoc-plugin:3.5.0:jar
    (attach-javadocs) on project proj4j-db
error: No source files for package org.locationtech.proj4j.db.gen
```

`db/pom.xml` sets `Automatic-Module-Name: org.locationtech.proj4j.db`, which puts
`maven-javadoc-plugin` into module mode - the generated `db/target/apidocs/options` carries
`--add-modules ALL-MODULE-PATH` and `--patch-module org.locationtech.proj4j.db=…`. The same pom's
`maven-jar-plugin` excludes `org/locationtech/proj4j/db/gen/**` from the artifact, so the module on
the module path has no such package, while the plugin's `packages` file still lists it. Confirmed
with a three-way A/B/C in one frozen container workspace, the host tree untouched:

| | result |
|---|---|
| A unmodified | exit 1, one `No source files for package` error |
| B without the jar `<exclude>` of `db/gen` | exit 0 |
| C without `Automatic-Module-Name` | exit 0 |

So it needs **both** conditions; neither alone is the defect. Not fixed here - `docker/**` is this
change's whole scope.

**2. ~~`determinism.yaml`'s `EXPECTED_TESTS=15` is stale.~~ FIXED UPSTREAM 2026-08-02, and this
runner has been brought back into step.** The finding as written was: 22 tests match the `-Dtest`
pattern, the extra 7 being `NoAmbientLocaleInCoreTest`, so the workflow's *exact-count* guard would
have failed on its first run — the same catch its own header records happening before at 9-vs-15.

`determinism.yaml` has since replaced the exact count with **`DET_FLOOR_TESTS=22`, a floor**, and
reports an upward drift as a `::notice::` rather than failing. So the workflow is no longer stale in
that direction, and this runner's own constants were stale in the *opposite* one — it still held a
floor of 15 and still described the workflow as pinning an exact count. Both are now **22**, and the
drift branch reports a rise rather than accusing the workflow of being about to go red.

Re-derived rather than copied: `mvn -pl core -am test -Dtest='org.locationtech.proj4j.determinism.*Test'`
on Temurin 21 / aarch64 reports **22 tests** across four surefire XMLs (`StrictMathGoldenTableTest` 6,
`NanBitPatternTest` 3, `NoAmbientLocaleInCoreTest` 7, `NoJdkAngleConversionTest` 6), cross-checked
against **22 `@Test` methods** in the package's sources. Raise both constants together when tests are
added — it is a ratchet, not a ceiling.

## Proving the container can fail

A verification without a positive control is a claim. Three controls, all runnable:

### 1. A deliberately broken check is caught

```bash
./docker/run.sh conformance --break-conformance ; echo "exit=$?"
```

This removes one row from `gie-expected-failures.tsv` **in the container's throwaway copy** - never
the host tree, which is mounted read-only. The manifest lists only assertions that are *not*
expected to pass, so deleting a row asserts that a known-failing assertion now passes. Measured:

```
========== POSITIVE CONTROL: removing one manifest row from the container's copy ==========
  key:  gie/4D-API_cs2cs-style.gie#11:0@c4fe674b
...
diff.regressed = 1
!! REGRESSION: diff.regressed=1  -- see conformance/target/conformance/differences.txt
REGRESSED (was expected to pass, did not) - 1
  gie/4D-API_cs2cs-style.gie#11:0@c4fe674b  expected=PASS observed=FAIL
      detail: operation could not be created: NOT_IMPLEMENTED: InvalidValueException:
              Unknown projection: helmert

conformance   FAIL   0m09s   7441/7900 genuine passes; diff.regressed=1
0 passed, 0 failed AS EXPECTED, 1 failed unexpectedly
exit=1
```

Note that the non-vacuity numbers are all still satisfied - 7,923 assertions swept, 7441/7900 passes
- so this is a *targeted* red, not a broken run. The removed key is written to
`<out>/broken-manifest-key.txt` and the whole control block to `<out>/control.log`, so it is
auditable after the fact. This is the same control `conformance.yaml`'s header records having been
run by hand. Afterwards, `git status conformance/` on the host shows no new modification: the sweep
that went red never touched a committed input.

### 2. The all-checks run distinguishes expected from unexpected failures

The tally has three separate terms, not two, and the run above happens to exercise all three:

```
CHECK         VERDICT        TIME      MEASURED
ci            FAIL           1m19s     1792 tests, 0 test failures, exit 1; Failed to execute goal
                                       org.apache.maven.plugins:maven-javadoc-plugin:3.5.0:jar
                                       (attach-javadocs) on project proj4j-db
conformance   PASS           0m06s     7441/7900 genuine passes, regressed 0, 7923 evaluated
golden        FAIL(expected) 0m26s     2291 UNEXPLAINED rows, 41/41 rules pinned, no
                                       COUNT_MISMATCH/DEAD_RULE/EXPIRED_RULE/PENDING_RULE_FIRED
determinism   PASS           0m04s     22 tests, 0 failures (one leg: aarch64 / Temurin 21)

2 passed, 1 failed AS EXPECTED, 1 failed unexpectedly   (total 1m56s)      -> exit 1
```

`golden` and `ci` both failed; only one of them set the exit code. That discrimination is not a
scripted demo - `ci`'s expectation was written for `MetaCRSTest`, the tree moved underneath it, and
the runner correctly refused to call the new failure expected.

**That transcript is from 2026-08-01 and is kept because it exercises all three terms of the tally.
Re-measured 2026-08-03, `ci` reads `PASS 2320 tests, 0 failures` and `golden` reads
`2291 UNEXPLAINED rows, 42/42 rules pinned`**, so a run today shows two terms, not three
(`3 passed, 1 failed AS EXPECTED, 0 failed unexpectedly`, total `0m46s`, exit 0); the positive
control in §1 is the one that still demonstrates the third on demand.

### 3. The container actually ran the tests

Every check asserts a floor on its own test count, and prints the count whether it passes or fails,
because *"three of this project's four gates were decorative because nobody checked they had
actually executed"* is the failure this runner is built against.

| check | floor asserted | measured in the container |
|---|---|---|
| `ci` | ≥ 1,700 tests across all surefire XMLs | **2,320 tests** in 223 report files, 0 failures, 4 skipped *(re-measured 2026-08-03; 2,142 in 202 files on 2026-08-02; 1,792 in 175 files on 2026-08-01, before the `neoproj4j-db` javadoc fix let `db` and `conformance` reach their test phases)*. **The floor is a ratchet, not a ceiling — raise it deliberately, but note that 1,700 is now 620 below the reading, so it would no longer catch an entire module dropping out.** |
| `conformance` | `GieConformanceTest` ≥ 7,900 tests; ≥ 25 other classes; `evaluated == index keys`; 0 skips outside the sweep; `skipped == still_failing` | **7,971 sweep tests**, **7,923 assertions evaluated** against a 7,923-key index, 26 other classes, 0 outside skips, 482 == 482 *(unchanged, 2026-08-03)* |
| `golden` | exactly 1 `GoldenMasterTest`, 0 skips; generated row count == baseline; ≥ 40 tests in the module | **53,431 rows** generated vs 53,431 baseline, **58 tests**, 0 skips *(unchanged, 2026-08-03)* |
| `determinism` | ≥ 22 tests, 0 skips, ≥ 2 report files | **22 tests** in 4 classes, 0 failures *(unchanged, 2026-08-03)* |
| `bench` | ≥ 20 JMH arms and ≥ 20 arms carrying `gc.alloc.rate.norm` | see the `bench` row in the table at the top of this file |

A check that produces no verdict line at all is counted as a failure, not skipped over.

**One difference from the Mac worth knowing:** `NoGeoApiInCoreTest` reports 2 skips and
`Proj4VariousTest` 1 in every container run. That is the documented `Assume` on the jar-scanning
legs, which surefire runs before `maven-jar-plugin` on a *clean first build*. The container is
always a clean first build, so on this machine those legs never execute - on a laptop with a warm
`target/` they do. The runner prints the skip counts rather than swallowing them.

## Traps this is written against

Each of these has produced a false green on this project, and each is guarded here:

* **`-Dmaven.test.failure.ignore=true` forces exit 0.** It appears nowhere in `docker/`. Getting
  past `core`'s expected `MetaCRSTest` failure is done with `-Dtest=` narrowing, exactly as the
  workflows do.
* **Surefire's `*` does not cross a package separator.** `conformance` needs `**` because its tests
  live in `bridge/ manifest/ parse/ report/ runner/`; `golden` and `determinism` are flat and use
  `*`. A pattern that matches nothing prints `No tests to run.` then `BUILD SUCCESS`.
* **`mvn install` installs nothing** here - the root pom sets `maven.install.skip` as a property.
  That is fine, because every command uses `-am` rather than relying on installed artifacts, and it
  is why the `~/.m2` scrub below is about third-party jars, not neoproj4j ones.
* **`-Djgitver.skip=true` is required whenever `-Dmaven.repo.local` is non-default.** It is
  deliberately **not** passed: the container's Maven repository *is* the default `~/.m2`, which is
  precisely what makes these invocations byte-identical to the workflows'. `.git` is copied into the
  container so jgitver resolves the version the same way it does after `actions/checkout`.
* **`bc` is not installed**, on purpose. Three workflow guard steps claim in a comment that they use
  `awk` "because `bc` is not guaranteed present on every runner image". Leaving it out keeps that
  claim under test.
* **A NUL byte in a source file makes `grep` skip it silently.** Nothing here greps sources; if that
  changes, use `grep -a`.
* **Stale artefacts cannot satisfy a guard.** Each check deletes the report directories it is about
  to assert on *before* running, so every number in the summary was produced by that invocation.
* **A locally built snapshot in the cache cannot satisfy a later run.** `~/.m2/repository/io/github/emilevictor/neoproj4j`
  is scrubbed after every check, mirroring the last step of every workflow. With a persistent cache
  volume that is a standing hazard here, not just a CI one.

## The Maven cache, and how to distrust it

`~/.m2` lives in a named Docker volume (`neoproj4j-m2`) so repeat runs are fast. A stale cache is
exactly the kind of thing that makes a green run untrustworthy, so it is trivially resettable:

```bash
./docker/run.sh --reset-cache          # delete the volume, then run cold
docker volume rm neoproj4j-m2             # same thing, by hand
docker volume inspect neoproj4j-m2        # where it lives, how old it is
```

Measured: a cold run of the four default checks is 2 m 39 s against 1 m 56 s warm, so the cache is
worth about 45 seconds and nothing else. **If a result differs between a warm and a cold run, the
cache is the finding** - the volume is where a stale inter-module jar would hide, which is why every
check scrubs `io/github/emilevictor/neoproj4j` out of it afterwards.

## Architecture, and what that means for `bench`

The image is built for the host's architecture: **`arm64` on this Apple Silicon machine, `amd64` on
almost any CI**. The base image is pinned by its multi-arch manifest-list digest, so both resolve
from the same pin. Use `docker build --platform linux/amd64` (or `--platform` on `docker run`) if
you want the x86-64 image under emulation, but expect it to be several times slower and do not take
`bench` numbers from an emulated run at all.

**The allocation baseline was captured on aarch64/macOS.** `gc.alloc.rate.norm` is a property of the
bytecode and both platforms use compressed oops, so the numbers *should* agree - but the gate's
slack is deliberately tight, `max(0.5 B, 0.1%)`. If a run on amd64 fails by small deltas across many
arms, **that is a portability finding, not a regression**: re-record on that architecture. Do not
widen the slack and do not raise a ratchet.

## Why `bench` is opt-in

Measured at **21 m 23 s** in the container on 2026-08-03 (was 15 m 47 s at 181 arms; the arm count is
now **245**), and it wants a quiet machine. JMH shards one invocation per benchmark class; sharing the
box does not make it lie - `gc.alloc.rate.norm` is a bytecode property - but it does make it slow, and
a twenty-minute default would stop people running the other four, which together take **46 s**. It is
also the only check that is a *measurement* rather than a comparison, so it belongs in a deliberate run
rather than in a habit.

```bash
./docker/run.sh bench
```

## What this reproduces faithfully, and what it does not

Faithful: every `mvn` invocation, flag for flag, and every non-vacuity guard, translated from the
workflow that owns it.

Not reproduced, with reasons:

* **`determinism.yaml`'s `cross-arch` job.** Its three assertions are properties of a six-leg matrix
  (x86-64 × aarch64 × JDK 11/17/21). In particular the non-vacuity check - *at least one leg must
  show `Math` diverging from the golden* - **cannot** be made inside a single leg: on
  Temurin 11/aarch64 the true value is 0, so an in-leg assertion would ship a permanently red leg
  for a non-defect. This container runs one leg and *reports* its divergence (298 of 54,265 on
  aarch64/JDK 21) without asserting on it.
* **`ci.yaml`'s `jdk-ea`, `jdk8-runtime`, and the three advisory `determinism` locale legs.** They
  need a second or third JDK in the image and, for the locale legs, a different ambient environment
  per leg. The container fixes en_US/UTF-8/UTC - `ci.yaml`'s single *blocking* leg.
* **`conformance.yaml`'s `vendored-corpus-matches-upstream` and `upstream-drift`.** Both clone
  `OSGeo/PROJ` (a full clone for the pinned job, because `sync-upstream.sh` resolves the tag by
  name). That is a network-heavy job about the corpus's provenance, not about neoproj4j's behaviour,
  and it is left to CI.
* **The multi-JDK `ci.yaml` matrix (17 and 21).** The image carries Temurin 21 only.

## The corporate-CA accommodation

The machine this was written on sits behind a TLS-intercepting firewall, so `archive.apache.org` and
`repo.maven.apache.org` present certificates issued by an internal CA and an un-doctored container
cannot download anything. `run.sh` detects the host bundle - `$PROJ4J_DOCKER_CA`, then
`$SSL_CERT_FILE`, then `$REQUESTS_CA_BUNDLE`, then `/etc/ssl/certs/ca-bundle.crt` - and the
Dockerfile installs it into **both** trust stores: `update-ca-certificates` for curl and git, and a
`keytool` import for the JVM, whose `cacerts` the OS store does not feed. Missing the second half
looks like a Maven "peer not authenticated" error next to a perfectly working `curl`.

On a machine without such a bundle, and on GitHub Actions, `extra-ca.crt` is written empty and that
whole stage is a no-op. **This is the one place where the image is not identical to what a runner
would build**, and it is the one genuinely floating input alongside the unpinned `apt` packages
(`ca-certificates curl git rsync locales procps tzdata` - none on the measurement path).

## Measured wall-clock

18-core Apple Silicon, Docker Desktop with 7.7 GiB, `linux/arm64`. Every figure below was timed, not
estimated.

| | warm `~/.m2` | warm, re-measured 2026-08-03 | cold `~/.m2` (`--reset-cache`) |
|---|---:|---:|---:|
| image build (`--no-cache`, includes the Maven download) | — | — | 55 s |
| `ci` (`mvn clean install`, whole reactor) | 79 s | **28 s** | 119 s |
| `conformance` | 6 s | **6 s** | 7 s |
| `golden` | 26 s | **6 s** | 27 s |
| `determinism` | 4 s | **5 s** | 3 s |
| **default run, all four** | **1 m 56 s** | **0 m 46 s** | **2 m 39 s** |
| `bench`, opt-in | 15 m 47 s | **21 m 23 s** (245 arms, was 181) | — |

The 2026-08-03 column was taken on a fully warm image and volume; `ci`'s 28 s against 79 s is the
compile cache, not a change in the build. **Only `bench` is a real movement**, and it is arms, not
speed: 245 arms against 181.

A cold cache costs about 45 extra seconds, all of it in whichever check runs first. The image build
is a one-off; after it, `docker build` is a cached no-op of about a second.

Two notes on why these are lower than the workflows' timeout budgets. The default run shares one
`/work` across its four checks, so `conformance`, `golden` and `determinism` reuse the classes `ci`
compiled - each check still deletes the report directories it asserts on, so no number is inherited,
but the *compile* is. And a single-check invocation gets a fresh container and does compile from
scratch: `conformance` alone is 9 s including a 360-file `core` compile on this machine.
