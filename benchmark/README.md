# `proj4j-benchmark` — JMH harness and the three-tier regression gate

**Never published. Not in the root pom's default `<modules>`.** It is added only by the `bench`
profile, so `mvn clean install` neither builds this module nor downloads JMH, and `core` stays
dependency-free.

---

## Build and run

The module targets `<release>17</release>` while `core` targets 8. That is deliberate and is
permitted *precisely because this module is never published* — no downstream consumer can be broken
by its bytecode level, and it is not in the default reactor, so it cannot raise the floor for anyone.
Do not copy that `<release>` into `core`, `epsg` or `geoapi`.

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

# From the repository root, once the bench profile is wired into the root pom:
mvn -Pbench -pl benchmark -am package

# Or, without the profile, build core+epsg into the local repo first and then this module alone:
mvn -Dmaven.install.skip=false -DskipTests -pl core,epsg -am install
mvn -f benchmark/pom.xml package
```

Either way the artifact is `benchmark/target/benchmarks.jar` — a shaded uber-jar with
`org.openjdk.jmh.Main` as its `Main-Class`.

**Always use an isolated local repository when building concurrently with other work.** Parallel
Maven runs in this repository have truncated the `epsg` jar mid-read and produced 34 spurious
repo-wide failures:

```bash
mvn -B -Dmaven.repo.local=/tmp/m2-bench ...
```

A benign jgitver `[ERROR] file doesn't exist: …jar` line appears even on success. Judge by
`BUILD SUCCESS`.

### Running benchmarks

```bash
cd benchmark

# The canonical gate run: live benchmarks only, with the allocation profiler.
./run-gate.sh

# One class.
java -jar target/benchmarks.jar SinglePointTransformBenchmark -prof gc

# One CRS pair, all classes that take the pair parameter.
java -jar target/benchmarks.jar -p pair=WGS84_TO_UTM33N

# List what exists.
java -jar target/benchmarks.jar -l
```

`java -jar target/benchmarks.jar -h` is JMH's own help, and it is the reference for every flag.

---

## Live — nothing is staged any more

**Live today** — 96 benchmark methods across ten classes, all measuring shipped code:

| class | what it measures |
|---|---|
| `SinglePointTransformBenchmark` | **the regression baseline** — one `transform` in the exact shape the consumer uses (fresh `ProjCoordinate` in, one reused out), over all 8 CRS pairs |
| `EtmercBenchmark` | `etmerc` vs `tmerc`, forward and inverse, across the 3° seam (Δλ = 0 / 1.5 / **3** / 6 / 20) |
| `SolverBenchmark` | head-to-head legacy vs Karney-core for every `numerics.md` row that has both implementations, at 4 latitudes |
| `MathDispatchBenchmark` | `Math` vs `StrictMath` for all 15 relevant functions, plus four `hypot` variants — **see the architecture caveat below** |
| `CrsParseBenchmark` | CRS creation at the init file's first / middle / last entry |
| `TransformCacheBenchmark` | cache hit vs miss, and object-key vs string-pair-key lookup |
| `GridShiftBenchmark` | real NTv1 interpolation, the iterative inverse, and the dispatch-only floor |
| `ConcurrentThroughputBenchmark` | 1/2/4/8/16 threads through **one shared** transform |
| `AllocationBenchmark` | the Tier 1 subject — one method per allocation hot spot |
| `BulkTransformBenchmark` | the bulk API's four shapes, plus a same-fork single-point loop as the control |

**The `staged` package is gone.** `org.locationtech.proj4j.benchmark.staged` held
`BulkTransformBenchmark`, a hand-written reflective bridge (`StagedApis`) and a copy of the bulk
interface, all so the benchmark could compile before `core` grew
`org.locationtech.proj4j.BulkCoordinateTransform`. `core` has it — `BasicCoordinateTransform`
implements it — so the bridge is deleted, the benchmark is an ordinary member of
`org.locationtech.proj4j.benchmark`, and `run-gate.sh` no longer passes `-e '\.staged\.'`.

That exclusion was not free while it stood: **the bulk path had no allocation ratchets at all.**
The rule `bulk-zero-allocation` matched nothing, was marked `required: false`, and GateChecker
duly reported `rule matched nothing, and is marked not required (staged benchmark)` on every run
— so the "a bulk method allocates zero bytes per point" contract was enforced by a javadoc
sentence. It is now `required: true`, `ratchetable: false`, pinned hard at 0.

`BulkTransformBenchmark`'s `loopOfSinglePoint` control lives in this class rather than in
`SinglePointTransformBenchmark` because the claim being tested is a **ratio**, batch ops/s over
loop ops/s, and it is only meaningful when both arms run **in the same JMH fork**, so that machine
speed, turbo state and noisy neighbours cancel. A control measured in a different fork on a
different day answers a different question.

`VectorApiBenchmark`, which `reference/performance.md` also lists, is **not** here. The Vector API
still requires `--add-modules` at *runtime* and prints a warning, which is unacceptable in a library
shipped into someone else's Spark executor; `performance.md` defers it, and a benchmark for a
deferred, opt-in-artifact-only idea would be the only thing in this module that could not inform a
decision. Add it if and when a separate opt-in artifact is on the table.

---

## What each tier gates, and why timing is not a PR gate

### Tier 1 — allocation. Blocking on every PR. **This is the primary gate.**

`-prof gc`'s `gc.alloc.rate.norm` is **bytes per operation**: bytes allocated divided by operations
performed. It is a property of the **bytecode**, not of the machine. The same jar reports the same
number on a laptop, on a shared CI runner, and in a container under memory pressure, because none of
those change how many objects a method constructs.

**It does not flake — for arms with a fixed object graph, which was 170 of the 181 arms when that was
measured, to within 0.0001 B/op across two independent JVMs.** The eleven that were not are now
fixed-shape too: **all 245 arms are gated as of 2026-08-03, `0 EXCLUDED`.** The two `CrsParseBenchmark`
arms that remain bimodal vary by **exactly 56 bytes and only downward** across 21 forks, and are pinned
at their maximum — which is safe precisely because the gate fails only on *exceeding*. See
[Coverage — what Tier 1 does *not* gate](#coverage--what-tier-1-does-not-gate), which is still the
first thing to read before quoting a Tier 1 pass as evidence about `CrsParseBenchmark`, and which now
documents the closure rather than the exclusion.

It also catches exactly the regression class that matters here — a reintroduced `new double[1]`
out-param, a `List` iterator on a per-point path, a boxing `Objects.hash` on the cache-lookup path.
Those are invisible to a timing gate at this signal-to-noise ratio and lethal at 100,000 vertices
per geometry.

The policy targets, from `reference/performance.md`:

- **every bulk method `== 0`** — non-negotiable and not ratchetable. With a non-null `status` array,
  zero allocation per point is how the error taxonomy is delivered at no per-point cost.
- **single-point `<= 40 B/op`** — exactly one `ProjCoordinate` (12-byte header + three doubles,
  padded to 40 under compressed oops), which is the caller's, not proj4j's.
- **`GridShiftBenchmark.inverseShift == 0`** — the sharpest target in the library. That path is
  measured at *up to 49 allocations per vertex*, i.e. 4.9 M objects for one 100 k-vertex geometry.

### Coverage — what Tier 1 does *not* gate

> **THERE ARE NO EXCLUSIONS TODAY. `allocation-baseline.json` contains no rule carrying
> `tier1Gated: false`.** This whole section describes an exclusion that existed from 2026-08-01 to
> 2026-08-02 and was **closed by fixing its subject**, exactly as its own exit condition required. It is
> kept, unabridged, because the arc is the useful part: an exclusion is a **dated loan against a named
> fix**, and this is what repaying one looks like.
>
> **What changed.** `io/InitFileCache` parses each init file once. `createFromName` went
> **39,616 → 2,480** (`EARLY`), **4,002,132 → 2,872** (`MIDDLE`), **7,919,424 → 1,136** (`LATE`) — 16× /
> 1,394× / 6,971× — which puts it *below* `createFromParameters`, the parse-only control. That control
> is **unchanged to the byte** (3,360 / 4,112 / 1,920), which is how we know only the init-file path
> moved. `tier1Gated`, `exclusionArmCount` and `exclusionReason` were deleted together and the nine
> arms were re-pinned from **21 independent forks**.
>
> **Why the arm is gateable now and was not before, measured.** Seven of the nine arms are
> single-valued across all 21 forks. Two — `createFromName` `LATE` and `MIDDLE` — are **bimodal by
> exactly 56 bytes and only downward** (1136 ×19, 1080 ×2). One-sidedness is what makes the 1.1 B slack
> safe, because the gate fails only on exceeding; each arm is pinned at its observed maximum, which is
> also its mode. Shown rejecting: **+56 B rejected** naming the arm, **+1.2 B rejected**. Shown
> accepting: **1080, the real low mode, accepted**. And the coverage restoration as a 2×2 on one frozen
> input — an 8 MB/op regression, i.e. a total reversion of the fix, draws **0 detections against the old
> baseline and 1 against the new**.

**[HISTORICAL] Nothing ratchets `CrsParseBenchmark`'s allocation. A regression in `createFromName`,
`createFromParameters` or `readEpsgFromParameters` is REPORTED, NOT BLOCKED.** All nine of its arms
(3 methods × 3 positions) carry `tier1Gated: false` in `allocation-baseline.json`. **This is a real
reduction in coverage**, and it is written here, in the rule's own `exclusionReason`, and in the
gate's output on every run — including passing ones — precisely so that it cannot be mistaken for a
gate that still gates.

**Why.** The measured claim underneath Tier 1 is that `gc.alloc.rate.norm` does not flake. It flakes
here, and the honest negative control showed **2 breaches out of 181** on an unmodified tree because
of it. The full profile does not help (spread **0.162 % quick vs 0.175 % full**, three runs each),
which strengthens the `--quick` decision rather than weakening it.

**The width of the exclusion is measured, not assumed.** Diffing two 181-arm runs of the *unmodified*
tree arm by arm, **exactly 2 arms exceed the gate's allowance of `max(0.5 B, 0.1 %)`, and both are
`CrsParseBenchmark`:**

| arm | run A | run B | allowance | verdict |
|---|---:|---:|---:|---|
| `CrsParseBenchmark.readEpsgFromParameters[LATE]` | 216697.484 | 212818.292 | 216.7 | **breach, −1.837 %** |
| `CrsParseBenchmark.createFromName[EARLY]` | 39680.073 | 39632.073 | 39.7 | **breach, −0.121 %** |
| `TransformCacheBenchmark.crsCacheMissEquivalent` | 204936.745 | 204888.741 | 204.9 | inside, 0.0234 % |
| `GridShiftBenchmark.noGridHit` | 1440.003 | 1440.003 | 1.4 | bit-exact |
| every other arm | | | | inside |

Two corrections to the note this decision was written from, both of which matter:

- **"11 of 181 arms flake" conflates two sets.** Eleven arms allocate **more than 1 KB/op** — the 9
  `CrsParseBenchmark` arms plus `crsCacheMissEquivalent` and `noGridHit`. The two non-`CrsParse` ones
  do **not** flake (0.0234 % and exactly 0), so **kilobyte-scale allocation is not by itself the
  predictor**; re-scanning a file per call is. Those two stay gated, and should.
- **The drift is larger than the 0.121 % on record**: `readEpsgFromParameters[LATE]` moved
  **−1.837 %**, fifteen times that. Downward, so it never breached — but any per-arm slack wide
  enough to absorb it would have had to be ~2 %, i.e. **~4 KB of headroom on that arm**, which is a
  further argument against the slack option.

The exclusion is **three arms broader than strictly necessary**: `createFromParameters[EARLY|MIDDLE|
LATE]` (3360 / 4112 / 1920 B/op) read **bit-identical across all three runs** and have never flaked.
They are excluded because the exclusion is per rule and the rule is per class. Narrowing `match` to
the two flaking methods would recover them — and would then need `exclusionArmCount` dropped to 6.

**The justification is what the arm measures, not that it is inconvenient.** Its 200×
`EARLY`→`LATE` spread — **39 KB → 4.0 MB → 7.9 MB** — *is* `Proj4FileReader`'s per-call re-scan of
the 888 KB init file. That is the finding, not a regression signal. Tier 1's premise is a **fixed
object graph**; an arm whose subject is data-dependent allocation never satisfied it.

**Rejected alternatives**, recorded so this is not relitigated:

| option | verdict |
|---|---|
| a **per-arm slack** | works, but introduces a second tolerance concept whose only user is the arm that misbehaves |
| **accept 2 standing breaches** | **worst.** A gate with known permanent failures *"gets muted, then ignored, then deleted — historically within about a month"* — this file's own words about Tier 3 |
| **exclude, report, and fix the defect** | chosen |

**The correct long-term fix is to make the arm gateable, not to gate it loosely.** Parse each init
file once into a `Map<String,String[]>`; allocation then becomes small and fixed-shape, the spread
vanishes, and **the arm rejoins Tier 1 on its own terms** — delete `tier1Gated`,
`exclusionArmCount` and the `exclusionReason` at that point. **The exclusion is a concession to a
defect, not a judgement that the arm is unimportant.**

> **↑ THAT PARAGRAPH IS THE EXIT CONDITION, AND IT WAS MET ON 2026-08-02.** `io/InitFileCache` does
> exactly what it describes, the three fields were deleted together, and the arms are gated again. The
> `Locale.ROOT` subtlety it does not mention, and which nearly made the cache return the wrong CRS: the
> **authority** is folded with `Locale.ROOT` while the **code inside the file** is matched with
> case-sensitive `String.equals`, so the cache is two levels and nothing flatter. Folding the code makes
> `world:PALESTINE` start resolving. Both mutations were run — folding the code fails 3 tests naming
> `world:CH1903` and `world:india-I…IVB`; not folding the authority fails 11 with
> `Unable to access CRS file: proj4/nad/EPSG`, a hard failure rather than a wrong CRS.

**What is *not* given up:**

- `required` stays `true` — a renamed or deleted `CrsParseBenchmark` still **fails** the gate.
- `--record` still ratchets all nine arms, and every run prints each one with its observed value,
  the recorded reference, and the delta in bytes and per cent.
- **`exclusionArmCount: 9` is pinned by hand.** If the `match` pattern ever covers a different number
  of arms, the gate **fails** with `EXCLUSION SCOPE CHANGED`. An exclusion that over-matches is
  exactly how a gate quietly stops working, so its blast radius is a checked number, not a comment.
- Exclusion is per **rule**, not per measurement: an arm matched by an excluded rule *and* by a gated
  rule is still gated by the gated one.

`GateChecker` **exits 2** if a rule sets `tier1Gated: false` without both an `exclusionReason` and an
`exclusionArmCount`.

### The one thing Tier 1 is structurally incapable of catching: a reintroduced `pow`

`StrictMath.pow` is **96 B/op interpreted and 0 B/op JIT-warm** — C2 folds the constant array
elements. **Escape analysis is not the mechanism**: with EA disabled, `sin` correctly goes 32 → 72
B/op while `pow` stays at 0. So `MathDispatchBenchmark.strictPow`'s ratchet of 0 is a correct reading
of a blind instrument, and **widening it would not help** — there is no byte count to widen towards.

**The instrument that does catch a reintroduced `pow` is Tier 2's `pow` counter, pinned at `0` across
all 8 CRS pairs in `op-counts.json.`** It counts call sites reached, as an integer, so no JIT
transformation can fold it away. If a review proposes relaxing the Tier 1 `pow` arm, the answer is
that Tier 2 already owns that regression.

### Tier 2 — deterministic operation counts. Blocking on every PR.

A test-only `CountingMath` / `CountingStrictMath` facade tallies
`sin cos tan pow exp log atan atan2 sqrt hypot` (plus `asin acos log10 sinh cosh log1p cbrt expm1
tanh`, which are equally deterministic and free to count) for **one** transform per CRS pair, and
the result is compared exactly against `src/main/resources/baseline/op-counts.json`.

Two runs on two architectures produce identical numbers, or one of them has a bug. So it is safe to
block on, it catches *algorithmic* regressions precisely — a Newton loop that gained a trip, a
closed form that reverted to an iteration, a `pow` reintroduced where an `exp` was — and, unlike a
timing, **it explains them**. "`pow` went from 0 to 5" names the cause; "12% slower" names nothing.

**The facade is reached without a single line of instrumentation in `core`.**
`CountingClassLoader` rewrites the string `"java/lang/Math"` in the constant pool of every
`org.locationtech.proj4j` class it loads. That is the entire transformation: no constant-pool entry
is added or removed, so every `CONSTANT_Class_info`, `CONSTANT_Methodref_info` and `invokestatic`
index is untouched. A facade that lived in `core` would put a counter increment on the hot path of a
published library, which is the kind of thing the rest of `performance.md` exists to prevent. See
`counting/CountingClassLoader.java` for the format details and the one documented false-positive
case.

Verified working on this tree: **152 core classes instrumented, 101 `Math`/`StrictMath` references
redirected**. The recorder *refuses to return a result* if either count is zero — a rewrite that
silently did nothing would make Tier 2 pass forever while measuring nothing, and that is the only
failure mode of this design that would be dangerous rather than merely loud.

**Where timing is used at all**, the candidate and a fixed reference run in the **same JMH fork** and
the gate is on the **ratio**, so machine speed cancels. That is why `SolverBenchmark` keeps its
`legacy*` arms next to its `karney*` arms and why `BulkTransformBenchmark` keeps
`loopOfSinglePoint`. Suggested bound: ±15%, 3 forks, Student-t on JMH's reported `scoreError`.

### Tier 3 — absolute ns/op. Nightly, dedicated runner. **NEVER a PR gate.**

JMH on shared CI runners varies **±20–40%**. No CPU pinning, no turbo control, noisy neighbours, and
a scheduler that will migrate a fork mid-iteration. **A gate with a 20–40% false-positive rate gets
muted, then ignored, then deleted — historically within about a month — and its absence is then
invisible.** That is a worse outcome than having no timing gate at all, because the team believes it
has one.

So timing lives on a nightly job on a dedicated self-hosted runner: pinned CPU, turbo disabled,
`performance` governor. It **alerts** (it does not fail a PR) and only on a **>10% regression
sustained across three consecutive nights**, which filters single-night noise.

`GateChecker` deliberately implements Tiers 1 and 2 only. If you find yourself adding an ns/op
assertion to it, that is the decision this section exists to prevent you from relitigating by
accident.

---

## The `MathDispatchBenchmark` caveat — read this before quoting any number from it

**`MathDispatchBenchmark` must be run on both x86-64 and AArch64 to be meaningful.**

The entire `StrictMath` question is about **cross-architecture divergence**. HotSpot ships
`@IntrinsicCandidate` implementations of `sin cos tan log log10 exp pow` on both architectures, and
they differ from each other and from fdlibm. That divergence is the reason
`reference/numerics.md` mandates `StrictMath` for those seven despite a 1.5–3× cost, and it is the
reason the cost is worth paying: the consumer requires **bit-for-bit determinism across executors
and JVMs**, and a Spark cluster is not guaranteed homogeneous.

A single-architecture run tells you the *local* tax and says **nothing** about the thing the policy
is for. A result from one architecture presented as "the cost of `StrictMath`" is a **wrong answer**,
not an incomplete one.

### A measured finding this benchmark already produced

**On Temurin 21.0.11, `StrictMath.sin`, `StrictMath.cos` and `StrictMath.tan` each allocate
32 B/op — one `double[2]`.** `Math.sin` allocates nothing (it is an intrinsic).

**JDK 21's** pure-Java `FdLibm` rewrite allocates the argument-reduction scratch array inside
`FdLibm.Sin/Cos/Tan.compute`, and escape analysis does not eliminate it. *(This said **JDK 17** and it
is wrong: `StrictMath.sin/cos/tan` are still `native` JNI calls into compiled fdlibm on 17 —
`Modifier.isNative` is true on Corretto **and** Temurin 17.0.20, and `java.lang.FdLibm$Sin` is absent
from a JDK 17 image entirely, 5 `FdLibm` nested classes on 17 against 23 on 21. The boundary is
**per-function**: on 17 `log` is also still native while `pow`/`exp` are already pure Java. Getting
this wrong nearly shipped an MR-JAR that would have pushed JDK 17 onto the slow path — see
`reference/performance.md`, "The Java baseline".)* Confirmed independently of JMH via
`ThreadMXBean.getThreadAllocatedBytes` over 5×10⁶ calls:

| call | B/op |
|---|---|
| `Math.sin` | 0.00 |
| `StrictMath.sin` | 32.41 |
| `StrictMath.cos` | 32.00 |
| `StrictMath.tan` | 32.00 |
| `StrictMath.exp` / `log` / `atan` / `asin` / `log1p` / `sinh` / `hypot` / `pow` | 0.00 |

**This bears directly on `reference/numerics.md` row 13.** That row prices the `StrictMath` policy as
"−1.5–3× on the 7 intrinsics" — a *time* cost. It does not mention allocation, and the allocation is
arguably the larger problem for this consumer: a UTM transform makes roughly four `sin` and four
`cos` calls per point, so ~256 B/point, i.e. **~25 MB of garbage for one 100,000-vertex geometry**,
inside a Spark executor. Time cost is linear and predictable; garbage at that rate is young-GC
pressure across every task on the executor.

It does not invalidate row 13 — determinism is still a hard requirement — but it changes the
mitigation. `numerics.md`'s sine/cosine-*preserving* APIs (`MeridianArc.mlfn(phi, sphi, cphi)`,
`ConformalLat.tsfnSinCos`, `AuthalicLat.forward(phi, sinphi, cosphi)`, `Clenshaw6.convert(zeta, s, c)`)
go from a micro-optimisation to the primary defence, because each avoided `sin`/`cos` now saves 32 B
as well as 100-odd ns. `exp`/`log`/`pow` are unaffected, so the policy is only expensive for three of
the seven.

The `math-dispatch-strictmath` rule pins this at a ratchet of 64 (the `strictSinCos` arm calls two of
them) with a target of 0, so a JDK that fixes `FdLibm`'s scratch array tightens it automatically
rather than the finding being quietly forgotten.

Two secondary points from the same file:

- The `asin acos atan atan2 sinh cosh log1p` arms should measure as **equal** to their `Math`
  counterparts, because `Math` already delegates to `StrictMath` for them. They are benchmarked
  anyway so that the day HotSpot gains an intrinsic for one of them shows up as a measurement rather
  than as a stale claim in a document.
- `hypot`: the policy says use **neither**. Four arms (`mathHypot`, `strictHypot`,
  `sqrtOfSumOfSquares`, `mathHelpersNorm2`) plus `mathSqrt` as the denominator, so the "10–30× a
  `sqrt`" claim is measured rather than asserted.

---

## Running the gate

```bash
cd benchmark

# Full run + both blocking tiers. This is what CI does.
./run-gate.sh --quick --require-baseline

# Reduced-iteration run. Legitimate for Tier 1, NOT for timing.
./run-gate.sh --quick

# Tier 2 alone — no JMH run needed, takes about a second. Good as a pre-commit hook.
./run-gate.sh --tier2-only

# Exercise the harness's own plumbing (JSON round trip, bytecode rewrite).
./run-gate.sh --self-test

# Retry only the shards that did not complete; reuse the ones that did.
./run-gate.sh --quick --resume

# Extra JMH arguments go after a literal `--`.
./run-gate.sh --quick -- -bm avgt
```

Exit codes: **0** pass, **1** breach, **2** usage or I/O error. A breach prints the offending
benchmark, the before and after figures, the delta, the policy target, and the `why` recorded on the
rule.

`--require-baseline` turns "this baseline entry is `TBD`" from a warning into a failure. **CI passes
it**, now that the baselines are real.

### An unknown option is a hard error

`run-gate.sh` used to append anything it did not recognise to the **JMH** command line rather than
to `GateChecker`'s. Seven `GateChecker` options were silently swallowed that way — `--skip-tier1`,
`--skip-tier2`, `--alloc-baseline`, `--op-counts`, `--baseline-dir`, `--commit`, `--self-test` — and
`--record --tier2-only` took a branch that dropped the forwarded options entirely, so
`--require-baseline` was ignored on that path specifically. Every option now has an explicit case
and anything else exits 2. Extra JMH arguments must come after a literal `--`.

### Sharding, and the two ways a JMH run loses its results

`run-gate.sh` runs **one JMH invocation per benchmark class**, each writing its own
`target/jmh-<Class>.json`, then concatenates the arrays into `target/jmh-result.json`. This is not
an optimisation. Both of the following were measured on JMH 1.37, not assumed:

| failure | what JMH does | what you see |
|---|---|---|
| the JMH **parent** dies (OOM killer, CI cancellation, a tool timeout, `^C`) | the `-rff` file was **truncated at startup** and is only written at the end | a **zero-byte** result file, and `set -euo pipefail` kills the script before `GateChecker` runs |
| a **forked VM** dies (SIGKILL, JVM crash, OOM in the fork) | prints `<forked VM failed with exit code 137>`, **continues**, writes a well-formed file, **exits 0** | *nothing.* The dead benchmark is simply absent |

The second is the dangerous one, and `-foe` does not help — it governs whether a benchmark
*exception* is fatal, not whether the forked VM died. So every shard's record count is checked
against JMH's own `-lp` arm enumeration (**245 arms across 10 classes** as of 2026-08-03; the run
prints `==> 10 shards, one JMH invocation per benchmark class, 245 arms expected`). A shard that comes
back short is reported, `--record` is refused, and `--resume` re-runs only the shards that are missing.
Demonstrated by SIGKILLing a forked VM mid-run: `!!! shard GridShiftBenchmark FAILED: exit 0, 3/4
arms`, the other shards intact, and `--resume` re-running that one class alone.

### The gate reads the baseline from **inside the jar**

`GateChecker` falls back to the classpath resource `/baseline/*.json` — the copy in
`benchmarks.jar`. `--record` writes to `src/main/resources/baseline/`. **After a `--record` those
two disagree until the module is rebuilt.** This is not hypothetical: the first negative control for
the 2026-08-01 capture recorded a full baseline, re-ran the gate immediately, and got 298
"baseline is `TBD`" failures — sixteen minutes of measurement checked against the pre-capture copy
still sitting in the jar, with output that looked like a legitimate result. CI never sees it,
because there the jar is built from source in the same job.

`run-gate.sh` now **exits 2** if either baseline source file is newer than the jar, and tells you to
rebuild or to pass `--alloc-baseline` / `--op-counts` explicitly.

---

## Baseline state — read this first

**CAPTURED 2026-08-01. Both baseline files are real; nothing in them was fabricated.**

| | |
|---|---|
| machine | Apple **M5 Max**, 18 cores, 64 GiB, macOS 26.6 |
| JDK | Temurin **21.0.11+10-LTS**, `aarch64` |
| tree | `7362c85` **plus 265 modified tracked files and an untracked `benchmark/`**; `core/src`+`benchmark/src` sha256 `5f38f507d407b992` |
| profile | `./run-gate.sh --quick --record` (`-f 1 -wi 2 -i 3 -w 1s -r 1s`), 181 arms, 15 min 49 s |
| result | **162 per-benchmark ratchets** — 153 enforced, **9 reported-only** (`CrsParseBenchmark`, see Coverage) — 22 rule ceilings, **8 CRS pairs × 19 ops all pinned** |

**RE-CAPTURED SINCE, AND THE ROW ABOVE IS THE 2026-08-01 CAPTURE, KEPT FOR THE PROVENANCE ARGUMENT
BELOW.** `allocation-baseline.json`'s own `capturedAt` now reads
`8f0fbd81bf53f95bd3c824a18a65f673c7ae2635+uncommitted`, `2026-08-02T16:21:38Z`, JDK 21.0.11+10-LTS /
aarch64 / Mac OS X, and the file holds **25 rules and 171 per-benchmark ratchets, all enforced, none
reported-only**; `op-counts.json` holds **8 pairs × 20 leaves = 160**, no `TBD`. Verified independently
in the container on 2026-08-03: `0 breaches; 245 gated, 0 EXCLUDED; 245 arms`, 21 m 23 s.

**One provenance caveat, and it is the same class of defect this table exists to prevent.**
`GateChecker.gitCommit()` is a bare `git rev-parse HEAD` and does **not** mark a dirty tree. The
`+uncommitted` suffix above was **hand-added**; `op-counts.json` and any future `--record` will stamp a
clean-looking sha over a dirty tree. Until that is fixed, read `capturedAt.commit` as "at least this
sha", never as "this tree".

The `capturedAt.commit` field deliberately does **not** read as a bare sha. `HEAD` does not contain
this module at all, so a bare `7362c85` would say the baseline came from a tree that cannot produce
it. The recorded string names the sha, the dirty count and a content hash of the sources.

**How portable are these numbers?** `gc.alloc.rate.norm` is bytes per operation, a property of the
bytecode — and that is now measured rather than asserted. Two independent 16-minute runs, separate
JVMs: **170 of the 181 arms agreed to within 0.0001 B/op**. The exception is real and is recorded
below. The Tier 2 counts are integers from a deterministic single transform and should be identical
on any JVM; if they are not, *that* is the finding (see the file's own `_doc`). Absolute ns/op is
**not** stored in either file and is not gated, deliberately — see `GateChecker`'s javadoc.

> **[2026-08-01 → 2026-08-02] The one place `gc.alloc.rate.norm` did flake, measured — RESOLVED first
> by an exclusion, then by removing its cause. The exclusion no longer exists; everything below is the
> record of it, and the mutation controls at the end are still the model to copy.**
>
> The 11 `CrsParseBenchmark` arms that allocate more than 1 KB/op drift run to run
> by up to **0.121 %** — `createFromName[EARLY]` read 39632.073 then 39680.073 B/op.
> `GateChecker.ALLOC_RELATIVE_SLACK` is **0.001** (0.1 %), so two of those arms failed the gate on an
> *unmodified* tree. Every arm at or below 1 KB/op was exact.
>
> `ALLOC_RELATIVE_SLACK` is **unchanged at 0.001**; widening a tolerance would have weakened all 181
> arms to accommodate 9. Instead the `crs-parse` rule carries `tier1Gated: false`: its nine arms are
> measured, recorded and reported on every run but **no longer block**.
>
> **The before/after is on identical input, and that detail is load-bearing.** Re-running the
> benchmarks and getting a green gate proves nothing here — a fresh 16-minute negative control after
> the change did read 0 breaches, but so does the *pre-change* configuration against that same fresh
> data, because those arms happened to land within slack that run. The controlled comparison holds
> the measurement fixed:
>
> | JMH result file | pre-change config | with the exclusion |
> |---|---|---|
> | run of 2026-08-01 11:12 | **2 breaches** | **0** |
> | fresh negative control | 0 | 0 |
>
> Read
> [Coverage — what Tier 1 does *not* gate](#coverage--what-tier-1-does-not-gate) for the cost, which
> is that `createFromName`'s allocation is now ungated.

The baseline numbers themselves were **not re-captured** for that change — the edit is policy
metadata only, and re-recording would have discarded a measurement whose portability is established
in order to reproduce it. `capturedAt.amendment` in `allocation-baseline.json` says so, and
disappears on the next real `--record`.

The rule *structure* was already load-bearing before capture, and still is — a `required` rule that
matches no benchmark **fails**, so a renamed or accidentally-excluded benchmark cannot silently
un-gate itself.

<details><summary>The pre-capture note, kept for the register</summary>

> Both baseline files are checked in with explicit `TBD` markers. No numbers have been fabricated.
> Six streams are concurrently rewriting proj4j's hot paths, so any number captured now would be
> attributed to the wrong commit and would be stale within hours. A fabricated number is
> indistinguishable from a measured one in review, which is exactly the failure these files exist to
> prevent. Until they are populated the gate **warns** on every `TBD` and **passes**.

</details>

### The two-column design in `allocation-baseline.json`

Each rule carries two numbers, and the difference is the point:

- `targetBytesPerOp` — the **policy**. What `reference/performance.md` says the number should be once
  the work has landed. **Never edit this to make a build green.**
- `maxBytesPerOp` — the **ratchet**. What the number actually is today. The gate fails on exceeding
  *this*. It may only ever go down.

Asserting the target outright today would leave `master` permanently red, and a permanently red gate
is a disabled gate. The ratchet gives a monotone approach to the target with no window in which a
regression slips through, and the gap between the two columns is a legible, reviewable to-do list.
When an observation comes in comfortably below the ratchet, the gate says so and tells you to tighten
it.

Three flags modify a rule:

- **`ratchetable: false`** marks a rule as *normative rather than empirical*, and `--record` will not
  widen it. Used for the bulk contract (zero allocation per point is the contract, not an
  observation) and for the `Math`-intrinsic canary (a nonzero reading there means the *measurement*
  is broken, so widening it would hide a broken harness).
- **`required`** defaults to `true`: a rule that matches no benchmark **fails**. This is what stops a
  renamed or accidentally-excluded benchmark from silently un-gating itself, which is the way
  long-lived gates usually die.
- **`tier1Gated: false`** makes a rule **reported but not blocking**, and it is **a reduction in
  coverage** — see [Coverage — what Tier 1 does *not* gate](#coverage--what-tier-1-does-not-gate).
  It requires `exclusionReason` (prose in this file, not in a commit message) and
  `exclusionArmCount` (the pinned blast radius); `GateChecker` exits 2 without them, and fails the
  gate if the arm count moves. **There are no users today** — `crs-parse` was the only one and it
  rejoined Tier 1 on 2026-08-02 when `io/InitFileCache` removed its subject. The mechanism is intact
  and its self-tests are green; the *user* was removed, not the feature. The bar for a second one is
  *"the arm's subject is data-dependent allocation, so it never satisfied Tier 1's
  fixed-object-graph premise"* — not *"the arm is inconvenient"* — and it must carry its own exit
  condition, as `crs-parse` did.

### Per-benchmark ratchets

The `ratchets` block at the end of the file holds ceilings keyed by
`SimpleClass.method[param=value]`, and it takes **precedence** over the matching rule's
`maxBytesPerOp`.

Both exist because a rule's single number spans every arm it matches, and some rules legitimately
cover arms two orders of magnitude apart. `CrsParseBenchmark.createFromName` **used to measure
39 KB/op at `EARLY`, 4.0 MB at `MIDDLE` and 7.9 MB at `LATE`** — a ~200× ramp, which was not noise
but the whole finding: `Proj4FileReader` re-tokenised the 888 KB init file linearly on every call, so
allocation was proportional to the code's position in it. A single rule ceiling set by `LATE` would
leave `EARLY` completely ungated at 1/200th of the limit. Per-key ratchets gate every arm at its own
figure while the rule keeps the policy, the `why` and the class-level cap.

**Those three numbers are now 2,480 / 2,872 / 1,136 B/op** (`io/InitFileCache`, 2026-08-02) and the
ramp is gone — but **the design argument is not retired with it**, because the ratchets are still
per-arm and the arms still differ: `createFromParameters` runs 3,360 / 4,112 / 1,920 while
`createFromName` runs 2,480 / 2,872 / 1,136, and one ceiling over both would leave the smaller ones
loose by a factor of three.

`--record` does not write per-key entries for `ratchetable: false` rules, because a per-key entry
would override a normative ceiling.

**It does still write them for `tier1Gated: false` rules** — which today is none. When `crs-parse`
carried the flag, the per-key number was the *reference the drift was reported against*, not a
ceiling anything failed on. The gate prints, per arm and per run,
`<observed> B/op   recorded <n>, delta +x.x (+y.yyy%)`, whether the rule is gated or not.

---

## Adding a benchmark

1. **Put it in `org.locationtech.proj4j.benchmark`.** There is no longer a staging package: the
   one class that needed it now measures a real interface, and the exclusion that hid it also hid
   it from the gate. If you need a benchmark for an API that does not exist yet, land the API
   first — an excluded benchmark is an ungated one, and `bulk-zero-allocation` spent its whole
   staged life reporting `matched nothing`. Never invent an API in a benchmark and never leave the
   module uncompilable.

2. **Copy the annotation block** from an existing class:
   `@BenchmarkMode`, `@OutputTimeUnit`, `@State(Scope.Benchmark)`,
   `@Fork(value = 3, jvmArgsAppend = {"-XX:+UseSerialGC"})`, `@Warmup(5, 1s)`, `@Measurement(5, 1s)`.
   The serial collector is not an optimisation — it makes `gc.alloc.rate.norm` bit-stable run to run,
   which is what a blocking gate needs.

3. **Parameterise over CRS pairs with a bare `@Param public CrsPair pair;`.** JMH expands that to all
   eight constants. Do not hard-code EPSG codes: a new pair should extend every parameterised
   benchmark and the Tier 2 baseline with one edit to `CrsPair`.

4. **Return the result; do not take a `Blackhole`** unless you genuinely produce more than one value.
   JMH treats a returned reference as consumed, and a `Blackhole` adds its own small,
   version-dependent cost to numbers that Tier 1 compares against a checked-in byte count.

5. **Never allocate in the measured method unless the shape being measured allocates.** If it does,
   say so in the javadoc and add a rule for it. `SinglePointTransformBenchmark.transform` is the
   worked example: it allocates on purpose because that is the consumer's shape, and its rule targets
   40 rather than 0 for exactly that reason.

6. **Add a rule to `allocation-baseline.json`** with an `id`, a `match` regex, a
   `targetBytesPerOp`, `maxBytesPerOp: "TBD"`, and a `why` explaining *which specific regression*
   the rule is a tripwire for. A rule without a `why` is a rule nobody will dare delete when it stops
   being relevant. `match` is applied to `SimpleClassName.method[param=value,...]` and to the fully
   qualified name.

   Keep a rule's arms **comparable**, or rely on per-key ratchets to do the gating. One rule over
   arms that differ by 200× has a ceiling that only constrains the worst arm; the per-key ratchets
   cover that, but the rule's `maxBytesPerOp` then means very little and its `why` should say so.

7. **Refresh the ratchet** on a quiet tree, and say in the commit message what changed and why.

8. If the benchmark inspects an intended-but-absent API, **name the blocking work in the javadoc**,
   not just in a commit message.

### Adding a counted operation

Add the constant to `OpCounters.Op` (at the end, so baseline diffs stay readable), then add the
delegating method to **both** `CountingMath` and `CountingStrictMath`. The two facades exist
separately so that a `StrictMath` call site keeps `StrictMath`'s exact result: delegating a
`StrictMath.log1p` to `Math.log1p` can differ by an ulp, and an ulp inside one of core's fixed-point
iterations changes the **trip count** — which is the thing Tier 2 measures.

If someone adds a call to a `java.lang.Math` method that the facades do not declare, the Tier 2 run
fails with a `NoSuchMethodError` naming it. That is loud, it only happens in the gate, never in
production, and the fix is one delegating method.

---

## Refreshing a baseline

```bash
cd benchmark
mvn -f pom.xml package        # or the -Pbench form from the root

# Full run with the allocation profiler.
java -jar target/benchmarks.jar -prof gc -rf json -rff /tmp/jmh.json

# Rewrite BOTH baselines from this run, stamping the commit, date, JDK and architecture.
java -cp target/benchmarks.jar \
     org.locationtech.proj4j.benchmark.gate.GateChecker --record /tmp/jmh.json

# Tier 2 only (fast; no JMH run needed).
java -cp target/benchmarks.jar \
     org.locationtech.proj4j.benchmark.gate.GateChecker --record --skip-tier1
```

`--record` writes to `src/main/resources/baseline/` by default; use `--baseline-dir` to point
elsewhere. It fills `capturedAt` from `git rev-parse HEAD` and the running JVM, so a refreshed
baseline always says which tree and which architecture it came from — without that, a differing
number cannot be told apart from a different starting point.

**Refresh on a quiet tree, not mid-churn**, and review the diff. Both files are pretty-printed one
entry per line specifically so that the diff is readable: a Tier 2 refresh should be a small number
of changed integers, and if it is not, something larger happened than the commit claims.

**A falling op count is the expected, correct outcome** of a `reference/numerics.md` rewrite —
closed forms replacing Newton loops. Say so in the commit message. **A rising count is an extra
iteration, a reintroduced `pow`, or a lost fast path**, and it is not noise: there is no noise in
this measurement.

---

## CI wiring

> **DONE, 2026-08-01. The job exists: `.github/workflows/bench.yaml`.** Until then this module's pom
> called Tiers 1 and 2 *"blocking PR gates"* while **no job anywhere invoked `GateChecker`** — the
> statement was accurate about intent and false about fact.
>
> **Three differences from the snippet below, each deliberate:**
>
> 1. **`./run-gate.sh --quick --require-baseline`, not two hand-written `java` invocations.** The
>    `-prof gc` and `-rf json` are both load-bearing — *"WITHOUT
>    `-prof gc` TIER 1 SILENTLY CHECKS NOTHING"* — and duplicating them in YAML is how they drift
>    apart. The script is the single definition.
> 2. **`--require-baseline` is on. Before the capture that made the job red on 176 unpinned
>    thresholds** — 24 in `allocation-baseline.json` (22 `maxBytesPerOp` + 2 `targetBytesPerOp`) and
>    152 in `op-counts.json`, which the gate reported as **298 breaches** because a single TBD rule
>    ceiling covers many arms. (The figure previously quoted here, *"187 = 29 + 158"*, counted raw
>    `TBD` strings including the two `capturedAt` blocks and two lines of prose.) Without the flag
>    every one of them warns and passes: a green job over 176 unpinned thresholds measures nothing
>    and reports success, which is the failure this workflow was added to end. **Captured
>    2026-08-01; all 176 are now real.**
> 3. **A non-vacuity step.** If `-prof gc` fails to attach, or the `-e` exclusion matches too much,
>    Tier 1 examines zero measurements and has nothing to complain about. The job asserts at least
>    20 arms in `jmh-result.json` and at least 20 carrying `gc.alloc.rate.norm`.
>
> `run-gate.sh` also gained an explicit `--require-baseline` case, and since 2026-08-01 an unknown
> option is a **hard error** rather than a pass-through to JMH — see "An unknown option is a hard
> error" above for the seven options that were silently swallowed.
>
> **Proof the Tier 1 comparison can reject**, run on Temurin 21 against the committed
> `allocation-baseline.json`: a fixture with `62.4 B/op` injected into `MathDispatchBenchmark.math`
> — the shape of swapping a `FastStrictTrig` call back to `StrictMath` — produced
> `ALLOCATION REGRESSION … delta +62.4 B/op` naming the benchmark and the rule
> (`math-dispatch-intrinsic-zero-allocation`, the normative 0 that `--record` may not widen), while
> the otherwise-identical clean fixture reported `0.000 B/op, at target (0)`.
>
> **Superseded by a whole-library control, 2026-08-01, now that the baseline is real.** With
> `Clenshaw6.convert`'s `FastStrictTrig.sin/cos` swapped to `StrictMath.sin/cos` in a `/tmp` rsync
> copy — a one-line change to a real call site, not an injected fixture — **both tiers fire
> independently** against the recorded baseline. Re-run after the `crs-parse` exclusion landed:
>
> - **25 allocation regressions**, and **every single delta is exactly `+64.0 B/op`** — one
>   `StrictMath.sin` plus one `StrictMath.cos`, 32 B each, on `EtmercBenchmark` (10 forward arms),
>   `ConcurrentThroughputBenchmark` (all 5), `SinglePointTransformBenchmark` (UTM33N and OSGB36,
>   both `transform` and `transformReusedInput`), `SolverBenchmark` (`karneyInvMlfn` and
>   `karneyAuthalicInverse` at 70° and 89.9°) and two `AllocationBenchmark` arms.
>   `transform[pair=WGS84_TO_UTM33N]` reads **40 → 104.000**, reproducing this repository's stale
>   `104` figure exactly, as an artefact of the very allocation that was removed.
> - **4 op-count changes**, `sin` and `cos` each `+1` per transform on `WGS84_TO_UTM33N` (2 → 3) and
>   `WGS84_TO_OSGB36` (4 → 5). Tier 2 needs no JMH run and would have caught this alone.
> - **And the coverage loss shows up in the same run, which is the point of still printing it.** The
>   nine excluded `CrsParseBenchmark` arms moved by **+72 to +120 B/op** — a real regression of the
>   same +64 shape — and were **reported, not blocked**. Before the exclusion those would have been
>   breaches. That is the cost, visible in the gate's own output rather than inferred.
>
> The claim that only two Tier 1 rules could reject anything was true of the all-`TBD` state and is
> no longer true: **171 enforced per-benchmark ratchets are live** (153 when this was written, before
> the bulk arms rejoined and `crs-parse` was re-gated). Nothing is reported-only any more, so the
> "coverage loss shows up in the same run" bullet above describes a state that no longer exists —
> those nine arms would now be **breaches**, which is what it said should eventually happen.
>
> **Exclusion-specific controls**, because an exclusion that over-matches is how a gate quietly stops
> working. Against the negative-control result file, one arm's `gc.alloc.rate.norm` mutated at a time:
>
> | injected | expected | got |
> |---|---|---|
> | `AllocationBenchmark.webMercatorForward` 0 → 999 | reject | **exit 1**, names the arm |
> | `SinglePointTransformBenchmark.transform[WGS84_TO_UTM33N]` 40 → 104 | reject | **exit 1** |
> | `TransformCacheBenchmark.crsCacheMissEquivalent` 204937 → +0.5 % (a 200 KB arm) | reject | **exit 1** — size is not what is excluded |
> | `CrsParseBenchmark.createFromName[EARLY]` 39632 → 999999 | pass, reported | **exit 0**, printed as `delta +960367.0 (+2423.211%)` |
> | `crs-parse`'s `match` widened to cover `AllocationBenchmark` too | reject | **exit 1**, `EXCLUSION SCOPE CHANGED … declared 9, matched 17` — *and* the injected `webMercatorForward` regression still rejected by its own rule, so two independent defences |
> | `tier1Gated: false` with `exclusionReason` removed | usage error | **exit 2** |
> | `tier1Gated: false` with `exclusionArmCount` removed | usage error | **exit 2** |

The original snippet, kept for the record:

```yaml
  performance-gate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - name: Build
        run: mvn -B -Pbench -pl benchmark -am -DskipTests package
      # Tier 2 alone: about a second, no JMH run. Cheap enough to run on every push.
      - name: Tier 2 - operation counts
        run: |
          java -cp benchmark/target/benchmarks.jar \
            org.locationtech.proj4j.benchmark.gate.GateChecker --skip-tier1
      # Tier 1 needs a real JMH run. Reduced iteration counts are fine: gc.alloc.rate.norm is a
      # property of the bytecode, so it converges almost immediately. Do NOT reduce iterations for
      # a Tier 3 timing run - that is what makes timing runs unreliable in the first place.
      - name: Tier 1 - allocation
        run: |
          java -jar benchmark/target/benchmarks.jar \
            -f 1 -wi 2 -i 3 -w 1s -r 1s -prof gc -rf json -rff jmh-result.json
          java -cp benchmark/target/benchmarks.jar \
            org.locationtech.proj4j.benchmark.gate.GateChecker jmh-result.json --skip-tier2
```

Tier 3 is a separate scheduled workflow on a self-hosted runner and must not be attached to
`pull_request`. The `MathDispatchBenchmark` caveat means the nightly matrix should include an
**AArch64** leg as well as x86-64 — that is also the only leg that *proves* the `StrictMath` claim
rather than asserting it.

---

## Root pom wiring

This module is added by a profile, never to the default `<modules>`. The exact snippet is in this
module's git history and in the task report; it consists of a `<module>benchmark</module>` inside a
`bench` profile's `<modules>`.
