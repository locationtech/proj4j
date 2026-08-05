# CI workflows

CI is where the properties that cannot be checked on one laptop get checked: a second architecture, a
hostile locale, and the corpus sweep. That is why there is more here than a single build job.

> **Retracted, and worth keeping as a caution.** The first version of this file opened *"There is no
> JDK and no Maven on the machine these workflows were authored on, so CI is the only place this
> project is verified at all."* **That was wrong** — Temurin 21 and Maven 3.9.16 are installed, and
> Gradle-managed JDKs were there all along, in a directory the original survey did not check. Several
> design decisions below were made under the false belief and are flagged where they were. Anything
> here that says "could not be confirmed locally" should be read as a claim about a specific past
> moment, not a standing fact — check before trusting it.

Five workflows:

- **`ci.yaml`** — the normal build. Fast enough for every push.
- **`conformance.yaml`** — the PROJ 9.8.1 corpus sweep and the vendoring guarantee. Kept separate so
  the corpus never sits in the path of a normal build.
- **`determinism.yaml`** — the cross-architecture bit-identity proof. Separate from `ci.yaml`'s
  `determinism` job, which checks only that the suite still *passes* per environment; this one
  compares raw-bit output between architectures. It is also **the only workflow here whose Java side
  has actually been executed** — see the bottom of this file.
- **`golden.yaml`** — the golden-master behavioural regression sweep. Added 2026-08-01.
- **`bench.yaml`** — Tiers 1 and 2 of the performance gate. Added 2026-08-01.

> **Why the last two were added, and what their absence had cost.** `golden` and `benchmark` are
> absent from the root pom's default `<modules>` — deliberately, so `mvn install` stays fast and
> `core` stays dependency-free — and until 2026-08-01 **no workflow passed `-Pgolden` or `-Pbench`
> either**. The consequences were not theoretical:
>
> * The tree's only behavioural change detector ran **only when a human typed the command**.
> * `golden/pom.xml` states a signal — *"if a change to core breaks the default-profile compile of
>   `golden/src/main`, that is a signal, not an inconvenience"* — that **could not fire, because
>   nothing in CI compiled `golden/src/main`**.
> * `benchmark/pom.xml` calls its Tier 1 and Tier 2 checks *"blocking PR gates"*. **No job invoked
>   `GateChecker`.**
>
> Both new jobs were **blocking and red** when added, on real findings, and that was the intended
> state. **`bench` / `gate` is now GREEN** — the baselines were captured and committed, and the job
> was re-run to confirm it (see below). `golden` is still red on its triage backlog, which is what
> it is for. See "Jobs expected to fail today" below. Neither may be made `continue-on-error` —
> `golden/README.md` says so explicitly, and the reason generalises: a `status: pending` rule or an
> unpinned-but-named threshold is visible in the diff and self-correcting, while a
> `continue-on-error` job is invisible and fails never.

## Jobs

| Workflow | Job | Blocking? | Trigger | What it proves | Expected runtime |
|---|---|---|---|---|---|
| `ci.yaml` | `build-and-test` (JDK 17, 21) | **blocking** — 21 green, **17 fails today** | push, PR | All seven reactor modules compile and their fast tests pass on both supported JDKs. This is the gate. | ~4–8 min per leg |
| `ci.yaml` | `jdk-ea` | advisory | push, PR | The next JDK line (27-ea) still accepts `<release>8</release>` and the felix bundle plugin still works. Early warning, months ahead of anyone using that JDK. | ~5 min |
| `ci.yaml` | `jdk8-runtime` | advisory | push, PR | Jars built on JDK 21 actually *run* on a real JDK 8, and every emitted class file is class-file major version 52. Keeps `<release>8</release>` from being an unverified promise. | ~5 min |
| `ci.yaml` | `determinism` — `en_US/UTF-8/UTC/x64` | **blocking** | push, PR | `core` passes in the reference environment. | ~3 min |
| `ci.yaml` | `determinism` — `tr_TR/UTF-8/Europe-Istanbul/x64` | advisory — **fails today** | push, PR | No result depends on the default `Locale`. Currently red, see below. | ~3 min |
| `ci.yaml` | `determinism` — `de_DE/ISO-8859-1/Asia-Kolkata/x64` | advisory | push, PR | No result depends on the default charset, decimal separator, or a half-hour UTC offset. | ~3 min |
| `ci.yaml` | `determinism` — `en_US/UTF-8/UTC/arm64` | advisory | push, PR | Cross-architecture *smoke*: `core`'s suite still passes on AArch64. **Superseded for the `StrictMath` claim** by `determinism.yaml`, which compares bits rather than pass/fail. | ~4 min |
| `determinism.yaml` | `bits` (6 legs: x86-64 × aarch64, JDK 11/17/21) | **blocking** | push, PR | Each leg asserts the committed 54,265-result raw-bit golden for `StrictMath` and `FastStrictTrig`. Six green legs = six architecture/JDK combinations produced identical bits. | ~3–5 min per leg |
| `determinism.yaml` | `cross-arch` | **blocking** | push, PR | (a) every leg reported; (b) **at least one leg shows `Math` diverging** from the golden — the non-vacuity check, which cannot be made inside a leg; (c) reports the NaN-payload carve-out across architectures. | ~1 min |
| `conformance.yaml` | `corpus` | **blocking** — green today | push, PR | `mvn -Pconformance -pl conformance -am verify` with `-Dtest='…conformance.**.*Test'`: the full vendored gie/GIGS sweep (**7,441 / 7,900**, `regressed 0`), diffed against the checked-in expected-outcome manifest. Catches any pass→fail regression. | sweep itself **0.7 s**, whole reactor **8 s** warm; the 90-min timeout is all cold cache, untuned |
| `conformance.yaml` | `vendored-corpus-matches-upstream` | **blocking** | push, PR | The vendored corpus is byte-for-byte what PROJ `9.8.1` (`f08fa86…`) produces: manifest verified with `shasum -a 256 -c`, `sync-upstream.sh` re-run against a real PROJ checkout, then `git diff --exit-code`. | ~4–6 min (a full PROJ clone dominates) |
| `conformance.yaml` | `upstream-drift` | advisory | weekly cron (Mon 04:17 UTC), `workflow_dispatch` | What syncing from PROJ **`master`** instead of the pin would change. A news feed, so upstream corpus changes are known before re-pin time. Never fails the build. | ~4–6 min |
| `golden.yaml` | `golden` | **DE-SCOPED 2026-08-05** — manual/scheduled only, still red on the 2,291-row backlog | `schedule` (weekly), `workflow_dispatch` | `mvn -Pgolden -pl golden -am verify`: generate 53,430 rows from the working tree, merge-join against `baseline/1.4.3`, apply `rules.yaml`. Fails on any `UNEXPLAINED` row, `DEAD_RULE`, `PENDING_RULE_FIRED`, `EXPIRED_RULE` or `COUNT_MISMATCH`. | ~20 s of sweep on top of the reactor build |
| `bench.yaml` | `gate` | **blocking** — **green today** | push, PR, `workflow_dispatch` | `run-gate.sh --quick --require-baseline`: Tier 1 allocation bytes/op and Tier 2 deterministic transcendental call counts, over **245 arms** in 10 shards, **245 gated, 0 EXCLUDED**. | **21.4 min** measured for `--quick` (Temurin 21 / aarch64, in the container, 2026-08-03; was 15.8 min at 181 arms); a full run is ~90 min. Still never timed on a runner |

## Jobs expected to fail today, and why

> **Every figure in this section was re-derived on 2026-08-02 by running the job's exact command**,
> not carried forward. Four of them had gone stale — the corpus totals, the golden rule count, the
> whole `bench` entry, and the absence of `build-and-test`. Numbers here are true once; re-measure
> before quoting.

- **~~`build-and-test` / the JDK 17 leg~~ — FIXED 2026-08-02. The three tests now SKIP, with the
  reason printed, and a skip is reported as a skip.** Re-measured in the container on 2026-08-03:
  `mvn -B clean install` exits **0** with javadoc enabled, all seven reactor modules, **2,320 tests
  and 0 failures, 4 skipped** (`core` **1,917** / 3 skipped, `conformance` 345 / 1 skipped, `db` 52,
  `geoapi` 6, `grids-us-legacy` 0), in 223 surefire report files.

  The finding as recorded was: the JDK **17** leg failed on three `core` tests that have nothing to
  do with the build — `FastStrictTrigAllocationTest.directCallsAllocateNothing`,
  `FastStrictTrigAllocationTest.strictMathAllocationTiersWithArgumentMagnitude` and
  `Clenshaw6AllocationTest.theStrictMathFormStillAllocates`. All three were **non-vacuity premise
  assertions**: they exist to prove `FastStrictTrig` is buying something, by requiring
  `StrictMath.sin/cos/tan` to allocate. On JDK 17 they measure **0.0 B/op**, so the premise check
  fired. Reproduced 5×: Corretto 17.0.20 failed 3/3 twice; Corretto 21.0.12, Corretto 25.0.4 and
  Temurin 21.0.11 all passed 10/10 — a **JDK property, not a vendor difference and not a flake**.

  **What changed, and one detail that matters for anyone re-reading the tests.** The guard is
  `requirePureJavaStrictMath` (`FastStrictTrigAllocationTest:165-175`) and the equivalent inline
  block at `Clenshaw6AllocationTest:232-242`. **It probes the capability, not the version** — it
  asks whether this JVM's `StrictMath.sin/cos/tan` are the pure-Java `FdLibm` port or native JNI,
  and reports which, the vm name, the version, the arch, and whether `java.lang.FdLibm$Sin` exists.
  So a future JDK that moves the boundary again is handled without an edit. The reason goes to
  **stdout as well as into the `Assume`**, deliberately, so it is visible in a build log and not
  only in the surefire XML.

  **The premise assertion was split, not weakened.** `directCallsAllocateNothing` — the first of the
  three names above — is now **unconditional and green on every JDK**, because `FastStrictTrig`'s own
  0 B/op is a fact about `FastStrictTrig`. Only the arms that require `StrictMath` to allocate skip:
  `strictMathAllocatesWhereItsImplementationIsPureJava`, `strictMathAllocationTiersWithArgumentMagnitude`
  and `Clenshaw6AllocationTest.theStrictMathFormStillAllocates`. The guard is **nativeness, not the
  measurement**, which is the part that keeps it able to fail: a pure-Java `StrictMath` that stopped
  allocating would still be caught. It is *not* the db javadoc failure below, which is also fixed.
- **`build-and-test`, the db javadoc failure — fixed 2026-08-02, recorded because it was invisible
  for a long time.** `mvn clean install` failed in `neoproj4j-db` with
  `error: No source files for package org.locationtech.proj4j.db.gen`. `db/pom.xml` declares an
  `Automatic-Module-Name`, which puts maven-javadoc-plugin into module mode (`--module-path` with
  the module's own jar, plus `--patch-module`), while the *same* pom's jar plugin excludes
  `org/locationtech/proj4j/db/gen/**`. The module's package set is read from that jar, so `gen` is
  not in it — but the plugin's generated `packages` file still listed it. **It needed both
  settings**: an A/B/C showed unmodified → exit 1, without the jar exclude → exit 0, without
  `Automatic-Module-Name` → exit 0. Fixed by scoping javadoc instead of dropping either, with
  `<excludePackageNames>org.locationtech.proj4j.db.gen</excludePackageNames>` — the same pattern
  `geoapi/pom.xml` already uses for `…geoapi.spi`. This was structurally unreachable until the
  reactor first got as far as `db`, which is why it appears only now.
- **`determinism` / `tr_TR`.** `core/src/main/java/org/locationtech/proj4j/io/Proj4FileReader.java:41`
  calls `authorityCode.toLowerCase()` with no `Locale`. Under `tr_TR` the Turkish casing rule maps
  `I` to dotless `ı`, so `"ESRI"` becomes `"esrı"`, the resource `proj4/nad/esri` is never found, and
  all 2,954 ESRI codes are unreachable. The leg is `continue-on-error: true` so it is a
  green-when-fixed signal rather than a permanent red. **Remove `advisory: true` from that matrix
  entry when the `toLowerCase(Locale.ROOT)` fix lands.**
- **`conformance` / `corpus`** is no longer in this list. The job's exact command has been run
  locally and is green — **7,441 / 7,900 genuine passes with `regressed 0`**, re-measured
  2026-08-02 (it read 7,378 / 7,895 on 2026-08-01; both the numerator and the denominator have
  moved, so quote the pair, never one of them). It has been shown to go red both on an injected
  regression and on an absent baseline. See the section at the end of this file. The *YAML* has
  still never executed on a runner; treat the first run as its first test.
- **`jdk-ea`** goes yellow rather than red whenever Adoptium has no `27-ea` build. That is the
  correct outcome for an advisory signal; bump the version each time a JDK GAs.

- **`golden` / `golden`.** Still red, on **2,291 `UNEXPLAINED` rows**. Re-measured 2026-08-03 in the
  container by running the job's exact command (exit 1):

  ```
  12,012 UNCHANGED · 41,418 CHANGED · 0 ADDED · 0 REMOVED · 39,127 INTENDED · 2,291 UNEXPLAINED
  ```

  **The headline has now held at 2,291 across three readings while the line underneath it moved
  twice**, and that is the reusable point, not a coincidence:

  | date | UNCHANGED | CHANGED | INTENDED | UNEXPLAINED |
  |---|---:|---:|---:|---:|
  | 2026-08-01 | 12,023 | 41,407 | 39,116 | **2,291** |
  | 2026-08-02 | 12,014 | 41,416 | 39,125 | **2,291** |
  | 2026-08-03 | 12,012 | 41,418 | 39,127 | **2,291** |

  Each time, rows moved from UNCHANGED into CHANGED and were **all** absorbed by rules. The last two
  are `LambertAzimuthalEqualAreaProjection`'s `Math.hypot` → `MathHelpers.norm2` conversion, claimed
  by the new rule `NUM-LAEA-HYPOT-TO-NORM2` with `expected_rows: 2` and both keys **enumerated** —
  `proj4-epsg.csv:00619` (EPSG:4326→2163) and `proj4-epsg.csv:01495` (EPSG:4326→3409). **A stable
  headline is not a stable measurement — check the whole line.**

  That the rule was needed at all was surfaced by the count pinning, not by a human: those 2 rows
  landed inside `NUM-KARNEY-LATITUDE-CORE`'s territory and tripped `COUNT_MISMATCH` at 19,326 against
  its pin of 19,324. **That is the naturally occurring positive control for `expected_rows` pinning**,
  and a globbed rule would have absorbed them in silence.

  The backlog is other streams' changes that no rule has claimed yet, and it belongs to those streams,
  not to this workflow. `golden/README.md`'s triage sections break it down by owner. **No
  `COUNT_MISMATCH`, no `DEAD_RULE`, no `EXPIRED_RULE`, no `PENDING_RULE_FIRED`** in the same run, and
  `GoldenRulesTest` is 14/14 green. There are now **42 rules**, not the 41 this file said a day ago
  nor the 38 `golden/README.md` said before that; all 42 are `status: active` and all 42 have a pinned
  integer `expected_rows` — counted two ways, `grep -c '^  - id:'` and a YAML parse, which agree, and
  the gate prints `rules.yaml: 42/42 rules carry a pinned expected_rows` as a third. The nine `TBD`
  tokens left in `rules.yaml` are all inside comments recording why something is *not* TBD. The job
  goes green when the backlog is claimed, one rule at a time.

- **`bench` / `gate` is no longer in this list — it is GREEN.** Re-derived 2026-08-03 in the container
  (`./docker/run.sh bench`, which runs the job's exact command):
  **`GATE PASSED (0 warning(s))`, exit 0, 0 breaches, 245 gated, 0 EXCLUDED, 245 arms**, with the
  non-vacuity line `245 of 245 arms carry an allocation measurement`, in **21 m 23 s**.

  **`181 arms` was right on 2026-08-02 and is now wrong by 64.** `BulkTransformBenchmark` moved out of
  `org.locationtech.proj4j.benchmark.staged` and `run-gate.sh` stopped passing `-e '\.staged\.'`, so
  the bulk API — the one the consumer is actually told to use — is measured for the first time; its 56
  non-`loopOfSinglePoint` arms are gated at a hard **0 B/op**, `required: true, ratchetable: false`.

  **`9 CrsParseBenchmark arms are reported but not gated (tier1Gated: false)` is also now wrong.** No
  rule in the file carries `tier1Gated` at all. See `benchmark/README.md`'s Coverage section for the
  whole arc — the exclusion was written with an exit condition, the condition was met by
  `io/InitFileCache`, and the three fields were deleted together.

  What this file said before that — *"187 of the gate's thresholds are `TBD`, 29 in
  `allocation-baseline.json` (with an empty `ratchets` block) and 158 in `op-counts.json`"* — was
  **stale in every part, including the paths**. Both baselines are captured, committed and live at
  `benchmark/src/main/resources/baseline/`. Re-counted 2026-08-03 by walking the JSON:

  | | rules / pairs | `ratchets` | `TBD` leaves |
  |---|---|---|---|
  | `allocation-baseline.json` | 25 rules | **171** | **2** |
  | `op-counts.json` | 8 pairs / 160 leaves | — | **0** |

  The 2 remaining `TBD`s are not unpinned thresholds: both are a `targetBytesPerOp` on a rule whose
  `maxBytesPerOp` is a real number — `transform-cache-miss` (1136) and `crs-parse` (4112) — i.e. the
  *policy* column is open while the *ratchet* column gates. `--require-baseline` therefore has nothing
  left to reject, which is why the job is green rather than merely quiet.

  **Wiring `--require-baseline` in before the capture rather than after was the right call** and is
  worth keeping on the record: a green job over 187 unpinned thresholds measures nothing and says
  it passed, which is the exact failure this workflow was added to end. Re-record with:

  ```bash
  cd benchmark && ./run-gate.sh --record     # then commit both baseline JSON files
  ```

  **Ratchet portability is still unproven and the first runner execution is its test.** The capture
  was taken on Temurin 21 / aarch64 / macOS and confirmed green there today; this job runs Temurin
  21 / x86-64 / Linux. `gc.alloc.rate.norm` is a property of the bytecode and both use compressed
  oops, so they *should* agree — but the gate's slack is deliberately tight (`max(0.5 B, 0.1%)`).
  If that run fails by small deltas across many arms, that is a portability finding; re-record on
  this architecture. Do not widen the slack and do not raise a ratchet.

  > **A local trap that costs 20 minutes and looks like a broken gate.** `run-gate.sh` dies with
  > `Unable to find the resource: /META-INF/BenchmarkList` if the benchmarks jar was built on
  > **JDK 23 or newer**, because javac no longer runs annotation processors found on the classpath
  > by default and JMH's generator is exactly that. Nothing is wrong with the gate — build with the
  > JDK the workflow pins (21). Seen here with Maven running on Homebrew OpenJDK 26.

## Design notes worth not rediscovering

**No JDK 8 in the build matrix.** The root pom sets `<release>8</release>`. `--release` is a javac 9+
flag, so JDK 8 cannot run this build at all. Bytecode-level Java 8 compatibility is proven by
`jdk8-runtime` instead: compile on a modern JDK, fork the *test* JVM onto a real JDK 8 with surefire's
`-Djvm=…`, then sweep every class file through JDK 8's `javap`.

**No JDK 11 leg.** Not added, because it could not be confirmed. Every plugin pinned in the root pom
is nominally JDK 11 compatible, but there is no JDK on the authoring machine to check with, and a
matrix leg asserting an untested claim is worse than no leg. Add it once someone has watched it pass.

**The `adopt` → `temurin` change.** AdoptOpenJDK was renamed Eclipse Temurin in 2021; `adopt` is
deprecated in `setup-java` and resolves to an archive that receives no new releases. Same vendor,
still shipping.

**The cache-scrub step is load-bearing and must stay last.** `actions/cache` saves `~/.m2/repository`
in a post-job step that runs after every step in the job. Without the scrub, a locally-built neoproj4j
snapshot would be baked into the cache and could silently satisfy a later run's inter-module
dependencies — a run could then "pass" against stale jars from an earlier commit. It was inherited
from the original `ci.yaml`; the only change is `if: always()`, so a *failed* build also scrubs
instead of leaving a poisoned cache behind.

**Determinism is applied via `argLine`, not `systemPropertyVariables`.** `user.language`,
`user.country` and `user.timezone` are read during JVM startup. Surefire's `systemPropertyVariables`
are applied with `System.setProperty` inside an already-running fork, which is too late to move
`Locale.getDefault()`. They have to be on the forked JVM's command line, which is what `-DargLine`
does. (`conformance/pom.xml` currently pins them via `systemPropertyVariables`; that is worth a look
by whoever owns that pom.) `MAVEN_OPTS` covers the Maven JVM and `LANG`/`LC_ALL`/`TZ` the OS default —
with a tolerated `locale-gen` step, because the Ubuntu images generate only `C.UTF-8` and
`en_US.UTF-8` and `LC_ALL=tr_TR.UTF-8` would otherwise silently fall back to `C`.

**What `ci.yaml`'s `determinism` job does *not* prove.** It runs the same suite under different
environments and checks it still passes. It does not diff numeric output between legs. What it
establishes is that no result depends on the ambient environment, which is the failure mode that has
actually bitten here. The numeric comparison is **`determinism.yaml`'s** job, added later; the two are
complementary and neither replaces the other.

**Why `determinism.yaml`'s non-vacuity check lives in the cross-leg job and not in the test.** The
obvious design is to fail a leg when `Math` and `StrictMath` agree too closely, on the reasoning that
a golden both satisfy cannot distinguish them. Measured on four JDKs, that reasoning is right about the
matrix and wrong about any single JVM: on **Temurin 11 / AArch64, `Math` and `StrictMath` are
bit-identical on all 54,265 probes — 0.00%, every function** — while the *same JDK* on x86-64 diverges
on 2.95% and Temurin 21/AArch64 on 0.55%. An in-leg assertion would therefore have shipped a
permanently red JDK 11 arm64 leg for a non-defect, and a permanently red gate is a disabled gate.

That same-JDK x86-64-versus-AArch64 pair is also the finding: it demonstrates directly that HotSpot's
`Math` intrinsics are architecture-dependent, which is the premise the whole `StrictMath` policy rests
on and which had never been demonstrated for this project.

**`failIfNoSpecifiedTests` is deliberately `false` in `determinism.yaml`, which looks backwards.**
Setting it `true` fails on `neoproj4j-epsg`, which `-am` builds and which has no test sources at all
(verified: *"No tests matching pattern … were executed!"* on project `neoproj4j-epsg`). The guard moved to
an explicit assertion on the count of tests in `core`'s own surefire XML, which is strictly stronger —
it catches a rename, a package move, *and* a test silently dropped from a class.

**`fetch-depth: 0` on the pinned PROJ checkout is required.** `sync-upstream.sh` resolves the pin by
*name* — `git rev-parse --verify 9.8.1^{commit}`, then `git archive 9.8.1 test/gie`. A shallow clone
can leave the tag object absent locally, and the script dies with `revision '9.8.1' does not exist`.
The `upstream-drift` job uses `fetch-depth: 1` because `actions/checkout` creates a local `master`
branch, so no tag has to resolve there.

**`fetch-depth: 0` on every *proj4j* checkout is required too, for a different reason.** The first
real runs of these workflows all logged `version '0.0.0-SNAPSHOT' computed`. jgitver derives the
project version by walking back from `HEAD` to the nearest reachable tag; at the default
`fetch-depth: 1` there is no history to walk and no tags to find, so every module built as
`0.0.0-SNAPSHOT`. All ten proj4j checkouts across the five workflows now set `fetch-depth: 0`.
`fetch-tags: true` is *not* an alternative — it brings the tag refs but leaves the ancestry
truncated, so the tags stay unreachable and the answer does not change. Note also that the remote
currently has no tags at all (`git ls-remote --tags origin` is empty), so this fix only takes effect
once the local tags are pushed; see `HOWTORELEASE.txt`.

**The `push` trigger is filtered to `master`/`main` in all five workflows.** With an unfiltered
`push` alongside `pull_request`, a single commit on a PR branch fires each workflow twice — ten runs
across this directory per commit. `concurrency` cannot collapse them: the two events carry different
`github.ref` values (`refs/heads/<branch>` vs `refs/pull/<n>/merge`) and therefore land in different
concurrency groups. A topic branch is now covered by `pull_request` alone, `master`/`main` by `push`
alone.

**`git add --intent-to-add` before `git diff --exit-code`.** A plain `git diff` sees only
modifications and deletions; a file *added* upstream would be untracked and the check would pass
straight over it. `--intent-to-add` makes additions visible to the diff.

**Syncing from master needs the script's pin rewritten.** `PROJ_REV`/`PROJ_REV_SHA` in
`sync-upstream.sh` are plain assignments, not env-overridable, and the script always extracts from
`$PROJ_REV`. Pointing it at a master checkout is not enough — it would re-vendor 9.8.1 from the tag
that checkout also fetched. `upstream-drift` therefore rewrites those two lines in a sibling copy of
the script (sibling because `SCRIPT_DIR` comes from `BASH_SOURCE` and decides where files land). That
copy is deleted before the diff. A master sync is also *expected* to abort on the script's
`tinshift_gpkg*.gie` guard, since those files exist only on master — the abort happens after the
corpus directories have been replaced, so the diff is still the drift report.

## What has and has not been validated

Validated: YAML parses (`python3 -c 'import yaml; yaml.safe_load(...)'`, every file in this
directory), action versions pinned to `actions/*` at the current major of each — `checkout@v7`,
`setup-java@v5`, `cache@v6`, `upload-artifact@v7`, `download-artifact@v8` — no
third-party actions, artifact names unique across every matrix, matrix expressions and
`continue-on-error` values well-formed, and the shell in `conformance.yaml` written against a close
reading of `conformance/sync-upstream.sh` (its `$1`/`$PROJ_DIR` override, its `BASH_SOURCE`-derived
`SCRIPT_DIR`, its pin check, its `tinshift_gpkg` guard, and the three comment lines in
`gie-manifest.sha256` that would otherwise make `shasum -c` exit 1).

**On those action versions.** They were `@v4` everywhere until the first real runs, which warned on
every job that Node 20 is deprecated and that `setup-java@v4` is deprecated. The majors above are
*not* uniform and that is not a mistake: each was read from that action's own `releases/latest` on
the GitHub API, and each one's `action.yml` at the floating major tag was confirmed to declare
`using: node24`. `upload-artifact@v7` and `download-artifact@v8` are the matched pair — the two
repositories' majors have been offset by one since download's extra bump, and both were cut on the
same day. Do not "fix" that to make the numbers agree. Re-check with
`gh api repos/actions/<name>/releases/latest --jq .tag_name` before the next bump rather than
inferring from this list, which is a snapshot.

**`conformance.yaml`'s `corpus` job is now a second exception, on both its Maven and its shell
side.** Its two steps were extracted from this YAML by `yaml.safe_load` and executed verbatim
against a scratch `-Dmaven.repo.local`, under all three controls tabulated at the end of this file.
The other two jobs in that file remain unexecuted.

**`determinism.yaml` is the exception, and only on its Java side.** The tests it runs were executed
locally on five JDK/instruction-set combinations before the YAML was written:

| JDK | `os.arch` | `StrictMath.sin` | result |
|---|---|---|---|
| Temurin 8.0.502 | `x86_64` | native (JNI fdlibm) | 54,265 pass |
| Temurin 11.0.32 | `x86_64` | native (JNI fdlibm) | 54,265 pass |
| Temurin 11.0.32 | `aarch64` | native (JNI fdlibm) | 54,265 pass |
| Temurin 21.0.11 | `aarch64` | pure Java `FdLibm` | 54,265 pass |
| OpenJDK 26.0.2 | `aarch64` | pure Java `FdLibm` | 54,265 pass |

271,325 `StrictMath` and 221,970 `FastStrictTrig` raw-bit comparisons, **zero value mismatches**,
across two instruction sets and both `StrictMath` implementations. The two-phase Maven invocation and
the surefire-count guard were also run locally. The **YAML itself has still never executed.**

**Executed locally on 2026-08-02, so no longer in the unvalidated set:**

* **`ci.yaml` / `build-and-test`'s only command**, `mvn -B clean install`, on three JDKs. Exit 0 on
  Temurin 21.0.11 and on Homebrew OpenJDK 26.0.2; exit 1 on Corretto 17.0.20 for the three `core`
  allocation-premise tests described above.
* **`ci.yaml` / `jdk8-runtime`'s bytecode sweep**, extracted from the YAML by `yaml.safe_load` and
  run against the real built tree inside `eclipse-temurin:8-jdk` with a **real Temurin 8.0.492** —
  1,082 class files, all class-file major 52. Four controls; see the section below, one of which
  found the check was asserting nothing.
* **`determinism.yaml`'s test-count guard**, extracted the same way and run under five controls
  against real surefire output. See the section above.
* **`conformance.yaml` / `corpus`**, **`golden.yaml` / `golden`** and **`bench.yaml` / `gate`** —
  each job's exact Maven / script invocation, run to completion, with the numbers quoted in this
  file taken from those runs and nowhere else.

**Still not validated: any of it running as YAML.** No workflow, job or step here has executed on a
runner. Treat the first CI run as the first test of the files themselves.

---

## `determinism.yaml` and the aarch64 legs

`determinism.yaml` compares raw bit patterns **between architectures**, so its `cross-arch` job is
only meaningful if both an x86-64 and an aarch64 leg actually ran. GitHub-hosted arm64 runners are
free for public repositories on github.com, which is the condition this repository meets.

**If the three `ubuntu-24.04-arm` legs ever stop allocating, the `cross-arch` job degenerates into
comparing x86-64 against itself while still reporting green.** That is the failure this project keeps
getting bitten by, so the legs fail loudly rather than skipping. Do not quietly delete them: either
restore an arm64 runner, or remove them and record that the cross-architecture claim has been lost.
`ci.yaml`'s `en_US/UTF-8/UTC/arm64` determinism leg has the same dependency.

### The test-count guard: an exact count that went stale twice, now a floor

**`EXPECTED_TESTS` is gone. The guard now asserts `DET_FLOOR_TESTS=22` and prints drift.**

The history is the argument. The constant was **9**, counting `StrictMathGoldenTableTest` (6) plus
`NanBitPatternTest` (3) — the two classes `determinism.yaml`'s header discusses — but the job's
`-Dtest='org.locationtech.proj4j.determinism.*Test'` also matches `NoJdkAngleConversionTest`, in
the same package, worth 6 more. It was corrected to **15** on 2026-08-01. By 2026-08-02
`NoAmbientLocaleInCoreTest` had joined the same package with 7 more, so the true count is **22**
and the exact-count guard **would have gone red on its first ever run, for the second time, on a
bookkeeping error**. Measured, not counted from a brief: that exact Maven invocation on Temurin 21
reports `Tests run: 22, Failures: 0, Errors: 0, Skipped: 0` across four surefire XMLs
(6 + 3 + 7 + 6), cross-checked against 22 `@Test` methods in the package's sources.

Twice is a pattern, and the pattern is structural: the constant is coupled to a package that other
streams legitimately grow, and the stream that grows it has no reason to be looking at this
workflow. So the guard is now a **floor plus a printed drift**, the same shape `docker/run.sh`'s
`check_determinism` already used — *"the floor still makes 'a container that ran nothing'
impossible, which is the property that matters"*. Keep the two constants in step.

What is given up is noticing a rise, and that is emitted as a `::notice::` on every run rather than
swallowed. What is kept is noticing a **drop**, because the floor *is* the current count.

Verified against real surefire output, five ways:

| control | result |
|---|---|
| unmodified: the real 4-class output | exit **0**, `ran=22`, no drift notice |
| **injected violation** — delete `NoAmbientLocaleInCoreTest`'s XML, i.e. exactly the 15 the old guard pinned | exit **1**, `only 15 determinism tests ran; the floor is 22` |
| empty `surefire-reports/` — the vacuous-green case the guard exists for | exit **1**, `expected at least 2 determinism surefire reports, found 0` |
| **drift** — a fabricated 5-test class added to the package | exit **0**, with `::notice::…drifted UP: 27 ran, floor is 22 (+5)` |
| a skip injected into one XML | exit **1**, `1 determinism tests were skipped` |

> **One real bug found by running the empty case.** With `set -euo pipefail`, the old
> `xml=$(ls … | wc -l)` made `ls` fail when the glob matched nothing, `pipefail` propagated it, and
> `set -e` killed the script **on the assignment** — exit 1 with not one word printed, in precisely
> the case this guard exists to explain. It is now `xml=$(…) || xml=0`, which suppresses `set -e`
> and lets the message out. The third control above is what surfaced it.

### What was checked and holds

Run locally on Temurin 21 / aarch64 against the real emitted tables, so the `cross-arch` job's
parsers are no longer assumptions:

* Both filenames the `cross-arch` job globs for are produced at
  `-Dproj4j.determinism.outDir`: `math-divergence.tsv` and `nan-patterns.tsv`.
* The job's `awk -F'\t' '$1=="TOTAL"{print $2}'` reads the real file correctly: `d=298`,
  `t=54265`.
* **Assertion 2, the non-vacuity check, would pass**: 298 of 54,265 `Math` results differ from the
  `StrictMath` golden on this leg — 0.55%, matching the figure recorded for Temurin 21 / aarch64.

## `conformance.yaml`'s `corpus` job — reported here, fixed 2026-08-01

`corpus` used to run `mvn -B -ntp -Pconformance -pl conformance -am verify`. `-am` builds `core`,
whose suite currently has one expected failure (`MetaCRSTest`), so the reactor stopped in `core` and
**the corpus sweep never ran**. Measured before the fix:

```
Proj4J ........................ FAILURE [ 25.736 s]
Proj4J PROJ Conformance Suite . SKIPPED
```

*(Quoted verbatim. The pom `<name>` is now `neoProj4J`, so the same failure prints `neoProj4J …`
today; the transcript is left unedited because it is a measurement, not an example.
`docker/run.sh:370`, which greps reactor output for that name, **was** updated.)*

The section that stood here said the fix was being left alone because it could not be verified. It
now can: the baseline is on disk, so the job was fixed with `golden.yaml`'s `-Dtest=` narrowing plus
`-Dsurefire.failIfNoSpecifiedTests=false` — **not** `-Dmaven.test.failure.ignore=true`, which forces
exit 0 and would ignore the gate's own failure — and all three controls were run locally against the
exact YAML text:

| control | result |
|---|---|
| unmodified | exit 0, `7378/7895 genuine passes`, `diff.regressed = 0` — **re-run 2026-08-02: exit 0, `7441/7900 genuine passes`, `regressed 0`** |
| one manifest row removed | exit 1, naming `gie/4D-API_cs2cs-style.gie#11:0@c4fe674b` as `REGRESSED` |
| baseline file absent | exit 1, naming *"CONFORMANCE BASELINE INCOMPLETE … NOTHING HAS REGRESSED"* |

**One trap discovered doing it, and it is the opposite of an optimisation.** Copying golden's
pattern verbatim — `org.locationtech.proj4j.conformance.*Test` — matches **nothing**, because every
conformance test lives in a sub-package and surefire's single `*` does not cross a package
separator. Measured: `No tests to run.` followed by **`BUILD SUCCESS`**. The `**` is required, and
the job's non-vacuity step exists to catch exactly that shape of green.

## `ci.yaml`'s `jdk8-runtime` job — two defects, fixed 2026-08-02

The job is `continue-on-error: true`, so anything that goes wrong inside it is invisible by
construction. Two things had.

**1. The bytecode assertion carried no `if:`, so it never ran after a failure.** GitHub skips a step
whose predecessor failed unless it says otherwise. The `<release>8</release>` promise therefore went
unchecked on exactly the runs where checking it was most informative, while the job reported
success. Fixed with `if: always()`.

**2. The assertion asserted nothing — and this was only found because fixing (1) called for a
positive control.** The step swept every class through `javap -p` and relied on the claim, written
in its own comment, that *"JDK 8's javap refuses anything with a class file major version above
52"*. **It does not.** Measured against a real Temurin 8.0.492 in `eclipse-temurin:8-jdk`:

| input | `javap -p` (JDK 8) |
|---|---|
| a real major-52 class from `core/target/classes` | disassembles, exit **0** |
| a major-65 class (`javac --release 21`) | disassembles, exit **0** |
| a major-68 class (`javac --release 24`) | disassembles, exit **0** |

`javap` is a disassembler, not a verifier; it has no maximum-version check. **Making a vacuous check
run reliably would have been strictly worse than leaving it skipped**, because it would then look
like a kept promise. The step now reads the `u2 major_version` at byte offset 6 of each class file
directly — no JDK needed — and keeps `javap` only as a weaker second leg proving JDK 8's class-file
reader can parse the constant pool (which does catch post-8 constant-pool tags).

Four controls, the step extracted from the YAML by `yaml.safe_load` and run verbatim:

| control | result |
|---|---|
| the **real built tree**, real Temurin 8 javap, in a container | exit **0** — `All 1082 class files are class-file major version 52` |
| **injected violation**: one major-65 class dropped into `core/target/classes` | exit **1**, naming the file and its version. *The pre-fix step passed this.* |
| nothing built | exit **1**, `no class files found - nothing was verified` |
| **the prune is load-bearing**: same real tree, `-not -path './benchmark/*'` removed | exit **1** on **175** `benchmark/target/classes` files at major 61 |

**On the scoping, since it was asked whether it is cheap: it is one `-not -path`, and it is not
hypothetical on this workspace right now.** `benchmark/pom.xml` sets `<release>17</release>`
deliberately (never published, so no consumer can be broken), and 175 of its class files are major
61 and present in the tree today from an earlier `-Pbench` run. `-pl core -am` never builds them on
a clean GitHub-hosted runner, so the prune changes nothing there — it matters on a self-hosted or
reused workspace, and only `benchmark` is pruned. Every module that ships stays in scope.
