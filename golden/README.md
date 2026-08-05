# `proj4j-golden` — the golden-master compatibility regime

> **Did we change proj4j's behaviour in a way we did not mean to?**
>
> That is the only question this module answers. It has no opinion about whether any output is
> *correct*. See [Policy](#policy-gie-always-wins) — that distinction is the whole design.

The gie/GIGS conformance module tells us whether we match PROJ 9.8.1. It says nothing about the
behaviour PROJ never exercises — and proj4j's registry dictionaries hold **9,013 CRS** that the gie
corpus does not mention. Six streams are concurrently changing numerical behaviour (the Karney
auxiliary-latitude core, `+rf`/`+f`/`+R` parsing, the Albers spherical inverse, `TYPE_UNKNOWN`
hoisting, six new projections, the fail-closed work). This module is the mechanism that distinguishes
an intended change from an accidental one.

---

## Commands

Everything is profile-gated; a plain `mvn install` compiles this module and runs only its fast
self-tests (~50 tests, under a second). Always pass an isolated local repository — concurrent Maven
runs in this repo have truncated the `epsg` jar mid-read and produced 34 spurious repo-wide failures.

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"
M2="-Dmaven.repo.local=/tmp/m2-golden -Dmaven.javadoc.skip=true"
```

### Run the gate

```bash
mvn -B $M2 -Pgolden -pl golden -am verify
```

Generates a table from the working tree into `golden/target/golden/`, merge-joins it against
`golden/baseline/1.4.3/`, applies `golden/rules.yaml`, and **exits non-zero** on any `UNEXPLAINED`
row, `DEAD_RULE`, `PENDING_RULE_FIRED`, `EXPIRED_RULE` or `COUNT_MISMATCH`. The full per-row verdict
lands in `golden/target/golden/golden-report.tsv`.

`-am` is required: `-pl golden` alone cannot resolve `proj4j-epsg` from the reactor.

### Re-pin the baseline against released 1.4.3

```bash
mvn -B $M2 -Pgolden,golden-baseline -pl golden verify -Dgolden.regenerate=true
```

Note the deliberate **absence of `-am`**. The `golden-baseline` profile swaps the `proj4j` and
`proj4j-epsg` coordinates to `1.4.3` from Maven Central, and without `-am` the working tree's code
cannot leak into the baseline at all. That the same `GoldenGenerator` source compiles and runs against
both 1.4.3 and the working tree is not a convenience — it is the property that makes the baseline mean
anything, and it is why `GoldenGenerator` may only touch API that shipped in 1.4.3.

### Regenerate the committed inputs (rare, and never casually)

```bash
mvn -B $M2 -Pgolden-baseline -pl golden exec:java \
    -Dexec.mainClass=org.locationtech.proj4j.golden.GoldenInputs \
    -Dexec.args=golden
# or, without the exec plugin:
java -cp golden/target/classes:$(...)/proj4j-1.4.3.jar:$(...)/proj4j-epsg-1.4.3.jar \
     org.locationtech.proj4j.golden.GoldenInputs golden
```

This rewrites `probes.tsv` and `pairs.tsv`. **Regenerating the probes moves every probe point, so all
53,430 rows differ and the diff carries no information.** Regenerate, then *immediately* re-pin the
1.4.3 baseline from the same inputs, **in one commit**. A probe file and a baseline generated from
different probes describe nothing.

---

## Layout

```
golden/
  pom.xml                        artifact proj4j-golden; never published
  README.md                      this file
  rules.yaml                     the change declarations; the only file most work touches
  probes.tsv                     COMMITTED INPUT: 9,732 keys x 5 probes, hex doubles
  pairs.tsv                      COMMITTED INPUT: 200 curated CRS->CRS pairs
  baseline/1.4.3/
    golden.tsv                   53,430 rows, 5.94 MiB -- the pinned observations
    golden-index.tsv             per-key metadata the rules engine matches on (6 columns)
    golden-messages.tsv          exception messages, kept out of the numeric table
    golden-summary.txt           tallies
  src/main/java/...golden/
    GoldenGenerator.java         main(); 1.4.3 API ONLY. Writes a golden table.
    GoldenInputs.java            main(); rewrites probes.tsv and pairs.tsv
    GoldenFormat.java            the format, the total order, and why toHexString
    Probes.java                  probe derivation + the committed table
    Angles.java                  DMS parser, for reading definition text
    RegistryDict.java            an independent reader for the five init dictionaries
    InputSet.java                the four sections, incl. the synthetic parameter matrix
    MetaCrsCsv.java              the MetaCRS CSV reader
  src/test/java/...golden/
    GoldenDiff.java              main(); the merge join and the rules engine (snakeyaml)
    GoldenRules.java             rules.yaml schema and matcher
    GoldenMasterTest.java        THE GATE (skipped unless -Pgolden)
    GoldenFormatTest.java        format self-tests
    GoldenDiffTest.java          merge-join and rules-engine self-tests
    GoldenRulesTest.java         schema validation + loads the committed rules.yaml
    ProbesTest.java              the four gridExtent defects, as regression tests
```

`snakeyaml` is **test scope and only in this module**. `core` stays dependency-free; this module is
never published, so a YAML parser here costs downstream nothing.

---

## The input set

53,430 rows over 14,502 cases, in four sections. Sections are emitted in US-ASCII order.

| section | keys | probes | what |
|---|---|---|---|
| `CSV` | 4,770 | 1 | the existing MetaCRS CSV rows, at their own pinned coordinates |
| `PAIR` | 200 | 5 | curated non-WGS84-hub CRS→CRS pairs, all 25 `Datum.TYPE_*` combinations |
| `REG` | 9,013 | 5 | every def in every registry dictionary, WGS84 lon/lat → CRS |
| `SYN` | 519 | 5 | the synthetic parameter matrix |

`REG` is `epsg` 5,755 · `esri` 2,954 · `nad27` 134 · `nad83` 123 · `world` 47 = **9,013**, asserted by
`ProbesTest.registryDictionaryCountsAreUnchanged`.

### The synthetic matrix is not optional

**`+rf=` appears in no registry dictionary and in no test CSV — zero occurrences, verified by grep.**
The largest behavioural fix in this project is the `+rf`/`+f` transposition in
`parser/DatumParameters.java:119-133`. Without a synthetic matrix that fix has *no baseline rows to
move* and this regime would report it as a no-op. Three parts:

* `proj/<name>` — all 188 of PROJ 9.8.1's `PROJ_HEAD` names (`pipeline`, `helmert`, `cart`, `noop`,
  `push`/`pop`/`set`/`id`/`name` included). Names proj4j does not implement produce `EXC:` rows on purpose: when a projection lands, its rows change status from
  `EXC:` to `OK`, and that transition is what the rules engine matches. A name list restricted to what
  proj4j implements today could not see a new projection arrive.
* `mod/<host>/<slug>` — 47 modifier parameters × 6 host projections (`aea lcc longlat merc tmerc utm`).
  The modifier is **appended** to a canonical parameter set, so `+ellps=` and `+units=` appear twice in
  some rows — deliberately probing the duplicate-key precedence defect (`Proj4Parser` uses a `HashMap`
  and keeps the *last* occurrence; PROJ keeps the *first*).
* `ellps/<name>` — all 46 of PROJ 9.8.1's `ellps.cpp` names plus `NAD27`, `NAD83` and `australian`.

### The `PAIR` section

200 pairs covering every one of the 25 `(srcType, tgtType)` combinations of `Datum.TYPE_UNKNOWN`,
`TYPE_WGS84`, `TYPE_3PARAM`, `TYPE_7PARAM`, `TYPE_GRIDSHIFT`. The key encodes the combination
(`t04/epsg:4267>epsg:26731`), which is what lets a rule scope itself to the exact predicate an
upstream guard tests. Sources are all geographic CRS so the probe is a lon/lat pair and no separate
input pinning is needed. `EPSG:4326` is excluded from both ends: a WGS84-hub pair cannot exercise the
datum-transform decision that three confirmed defects live in.

**Curation finding, recorded here because it changed the curation:** across all 9,013 defs there is
**not one geographic CRS that proj4j reports as `TYPE_GRIDSHIFT`**, so five of the 25 combinations were
unreachable from observed types. The cause is the confirmed defect at `parser/Proj4Parser.java:53` —
`EPSG:4267` is `+proj=longlat +datum=NAD27 +no_defs` with no `+nadgrids` token, so the first parse of
it executes `Datum.NAD27.setGrids(null)` permanently, JVM-wide, flipping
`TYPE_GRIDSHIFT → TYPE_UNKNOWN` for all 205 codes on that datum. `pairs.tsv` therefore records the
**declared** type: observed where proj4j reports one, corrected to `TYPE_GRIDSHIFT` where the
definition declares a grid-shifted datum.

### Probe derivation

No area-of-use database exists, so each CRS's probes are derived from its own definition **text** —
`lat_0`, `lat_1`, `lat_2`, `lat_ts`, `lon_0`/`lonc`, `+south`, `+zone` — not from a parsed
`Projection`. Reading the text means a change to a `Projection` getter cannot move the probes. The
alternative in-repo approach is worse: **all 4,280 rows of `core/src/test/resources/proj4-epsg.csv`
probe the single point `(1.0, -1.0)`**, in the Gulf of Guinea, so a Malaysian `omerc` is evaluated at
`x = -1.24e7`.

`core/src/test/java/org/locationtech/proj4j/proj/ProjectionGridRoundTripper.gridExtent` attempts the
same thing and has four defects. Each is a regression test in `ProbesTest`:

1. **`Double.MIN_VALUE` as a max-tracker seed** (`:123`). It is `+4.9e-324` — the smallest *positive*
   subnormal, not the most negative double — so for an all-negative-latitude CRS the guard at `:132`
   fails and it falls back to a 10° box **on the equator**. *Every southern-hemisphere CRS is probed in
   the wrong hemisphere.* Here there is no sentinel: the derivation counts what it found.
2. **`lat == 0.0` conflated with "absent"** (`:150`). `+lat_0=0` is explicit, legal and carried by
   every UTM zone. Presence is decided by whether the token is in the text and parses.
3. **Unbounded box height** (`:136`, `gridWidth = 2 * dlat`). `+lat_1=-70 +lat_2=70` gives a 280°-tall
   box with corners past both poles. Clamped to ±15° half-height and ±89° latitude.
4. **No `cos(lat)` scaling on the longitude half-width** (`:128`, `:140-143`). Longitude half-width is
   divided by `cos(latC)` with a floor, so the box is roughly square on the ground.

Five probes per key, in a fixed order: centre, SW, SE, NW, NE. Spot checks from the committed file:
`epsg:32701` (UTM 1S) → `(-177, -25)`; `nad27:5300` (American Samoa, `lat_1=-14d16`) → `(-170,
-14.2667)`; `world:CH1903` (`46d57'8.660"N 7d26'22.500"E`) → `(7.4396, 46.9524)`; `epsg:27700`
(`lat_0=49`) → half-width 7.62° from the cosine scaling.

---

## File format

**TSV, LF, US-ASCII, no quoting, no escaping.** Tab, newline and any byte outside `0x20–0x7e` are
rejected on the way out rather than escaped — the format promises no quoting and the only honest way to
promise that is to refuse.

Total order: `(section, key, probe)`, each compared as raw US-ASCII bytes. That is `sort` order, `git
diff` order, and the order the streaming merge join requires. It is **not** numeric on the code
(`epsg:10000` sorts before `epsg:2000`); a total order only has to be total and stable, and rules that
need numeric ranges parse the code. `GoldenDiff` verifies the order as it reads and fails hard on a
violation — a hash-map diff would report a mis-sorted file as thousands of phantom ADDED plus REMOVED
and the reviewer would blame the code under test.

`golden.tsv` columns: `section, key, probe, status, fx, fy, fz, ix, iy, iz, inside`.
`f*` is the forward transform's output; `i*` is the inverse applied to that forward output; `status` is
`OK`, `EXC:<FQCN>`, or `NO_PROBE`; `inside` is the advisory `Projection.inside(lon,lat)` flag —
`T`/`F`, or `E` when it throws (it can: it routes through `normalizeLongitude`), or `-` when the input
is not lon/lat. `Projection.inside` has **zero callers in `core`**; this is the only place its value is
ever observed.

Exception *messages* live in `golden-messages.tsv`, and per-key metadata in `golden-index.tsv`, so that
neither message rewording nor a definition-text edit perturbs the numeric table.

`golden-index.tsv` columns: `section, key, srcproj, tgtproj, params, datums`. `params` is the sorted
union of both sides' parameter **key** names; `datums` is the sorted union of both sides'
`+datum=` **values**. The sixth column was added on 2026-08-01 and is what makes
`DATUM-NAD27-NADCON-SHIFT-APPLIED` a five-line rule instead of a 205-key enumeration — see
[the fourth triage](#fourth-triage-the-nad27-cluster-retired-and-the-out-of-grid-fail-open-closed--2026-08-01-late).
`GoldenDiff.readIndex` still accepts the five-column header, because `baseline/1.4.3/golden-index.tsv`
is a committed artefact of a released build that a normal run does not rewrite; a legacy row yields an
**empty** datum set, so a `datums:` predicate declines it rather than silently reading the `params`
column. That path is reachable only for `REMOVED` rows, of which there are currently none, and it makes
a rule under-claim rather than over-claim — which `expected_rows` turns into a build failure instead of
a silence.

### Why `Double.toHexString` and not `%.17g`

Four properties, all load-bearing, and decimal formatting fails all four:

1. **Bijective on bit patterns.** Every double has exactly one rendering and `parseDouble` inverts it
   exactly, so a diff of two golden files is a diff of raw bits — and *a change to the formatting code
   cannot make every row differ*, because there is no rounding decision to change. With `%.17g`, a
   future tweak from 17 to 16 digits rewrites all 53,430 rows and buries whatever real change was in
   there.
2. **Distinguishes `+0.0` from `-0.0`.** A real distinction, not noise: it is the difference between
   approaching the equator from the north and from the south, and between the two sides of the
   antimeridian, and it flips on sign-handling changes in exactly the code being rewritten. `%.17g`
   erases it.
3. **Locale-immune.** proj4j has live locale-dependent formatting defects — `ProjCoordinate.DECIMAL_FORMAT`
   is a public mutable static `DecimalFormat` with no `Locale`, and `units/Unit` and `units/AngleFormat`
   use `NumberFormat.getNumberInstance()`. A suite built on `String.format` would inherit that bug class
   and its baseline would be unreproducible on a colleague's machine.
4. **A 1-ULP difference is exact by construction** — one hex digit, no arithmetic needed to read it.

NaN payloads are *not* preserved (`toHexString` collapses every NaN to `NaN`); the payload is a JVM
implementation detail, not behaviour.

---

## `rules.yaml`

Full schema in `GoldenRules.java`. Rules are evaluated in **file order, first match wins**, so order
them most-specific first.

```yaml
version: 1
rules:
  - id: PARSE-VUNITS-ACCEPTED         # required, unique, [A-Za-z0-9._-]+
    status: active                    # active (default) | pending
    expires: 2027-06-30               # required, ISO-8601
    expected_rows: 964                # required: an exact integer, or the literal TBD
    reason: |                         # required, substantive
      why this change is intended
    match:                            # every predicate present must hold (AND)
      sections: [REG, SYN]            #   golden section
      keys: ["mod/*/rf"]              #   glob on the key ('*' only; '.' is literal)
      authorities: [epsg, esri]       #   authority prefix of the key
      code_min: 2000                  #   inclusive numeric code range; non-numeric codes never match
      code_max: 32766
      src_proj: [longlat]             #   +proj= of the source CRS
      tgt_proj: [merc, tmerc]         #   +proj= of the target CRS
      params_present: [rf, f]         #   ANY of these parameter keys present (OR)
      params_absent: [datum]          #   ALL of these absent
      datums: [NAD27]                 #   ANY of these +datum= VALUES, either side (OR)
      datums_absent: [WGS84]          #   ALL of these values absent
      probes: [0, 1, 2, 3, 4]         #   probe index
      classifications: [CHANGED]      #   CHANGED | ADDED | REMOVED
      status_from: "OK"               #   glob on the baseline status
      status_to: "EXC:*"              #   glob on the current status
    expect:                           # constraints; failing one makes the rule DECLINE the row
      dimensions: [fy, iy]            #   only these numeric columns may move
      allow_status_change: true       #   default false
      allow_inside_change: false      #   default false
      allow_nonfinite: true           #   default false
      magnitude: {min: 1.0, max: 1.0e9}
```

### The four things that stop a rule becoming a rubber stamp

1. **`expected_rows` is exact and two-sided.** Not a maximum. A fix that moves *fewer* rows than
   declared is as interesting as one that moves more — usually it means a second defect is masking part
   of it, which is exactly how the `+R` / Albers-spherical-inverse coupling was found.
2. **A rule matching zero rows is a `DEAD_RULE` failure.** Stale rules are permanent licences to change
   behaviour nobody is watching. Deleting one is a one-line commit; leaving it is a hole.
3. **`expires` is mandatory.** A rule says "this change is intended *during this piece of work*". Past
   the release it is folded into a new baseline and deleted. The expiry forces that conversation.
4. **A failing `expect` clause makes the rule DECLINE the row, not claim it.** The row falls through to
   the next rule and ultimately to `UNEXPLAINED`, and the report records which rules declined and why.
   The alternative — treating matched-but-unexpected as explained — *is* the rubber stamp.

`status: pending` exists because rules must be written before the code lands, so that the first person
to land a change does not meet an `UNEXPLAINED` wall. A pending rule must match **exactly zero** rows;
the moment it matches one the build fails with `PENDING_RULE_FIRED`, telling you to flip it to `active`
and pin the count. It cannot hide anything.

### Units warning

`magnitude` is `|current − baseline|` **in the raw units of the column**, and those differ by row:
`fx/fy/fz` are in the target CRS's units (metres for a projected target, degrees for a geographic one)
and `ix/iy/iz` are always WGS84 degrees. There is deliberately **no unit normalisation**. Normalising
would mean choosing a metric branch per row — exactly the decision that inflated a downstream team's
entire worklist by ~111,319×. Choose the band with the section's units in mind and say which you meant
in `reason`.

---

## Seeded rules, and the counts that are honestly unknown

> **SUPERSEDED 2026-08-01: there are no unknown counts left. All 42 rules are `status: active` with
> a pinned integer `expected_rows`, and `GoldenRulesTest.noActiveRuleMayLeaveItsExpectedRowsUnpinned`
> now makes that a build failure rather than a convention.** *(This said 38 until 2026-08-02 and 41
> until 2026-08-03; the file grows and the prose does not, which is why the count is re-derived here
> rather than copied. Three independent methods that agree as of 2026-08-03: a YAML parse of
> `golden/rules.yaml` gives **42** rules, 42 unique `id`s, 42 with `status: active` and 42 with
> `expected_rows`; `grep -ac '^  - id:'` gives **42**; and the gate itself prints
> `rules.yaml: 42/42 rules carry a pinned expected_rows`. Note a bare `grep -ac 'id:'` overcounts —
> some occurrences are prose inside `reason` blocks and comments — which is exactly the kind of
> unanchored count that put a wrong number here in the first place. The nine remaining `TBD` tokens
> in the file are all inside comments explaining why something is **not** TBD.)*
>
> *The 42nd rule is **`NUM-LAEA-HYPOT-TO-NORM2`**, and how it was discovered is the argument for
> pinning in one line: `LambertAzimuthalEqualAreaProjection`'s `Math.hypot` → `MathHelpers.norm2`
> moved 2 rows into `NUM-KARNEY-LATITUDE-CORE`'s territory, which raised a `COUNT_MISMATCH` at 19,326
> against its pin of 19,324. **A globbed rule would have absorbed them in silence.** The new rule
> sits immediately before `NUM-KARNEY-LATITUDE-CORE` so that rule keeps its 19,324, carries
> `expected_rows: 2`, and enumerates both keys — `proj4-epsg.csv:00619` (EPSG:4326→2163) and
> `proj4-epsg.csv:01495` (EPSG:4326→3409). `EPSG:3408`, the north-polar twin one line earlier, did
> not move.* The section below is kept because its
> *reasoning* about when to pin is still right, and because the argument it makes turned out to be
> half wrong in a way worth recording.
>
> **What the argument got wrong.** "The tree is mid-flight, so any number pinned today describes a
> transient state" is true, and it is an argument *for* pinning, not against. A pinned count that
> becomes wrong fails with a `COUNT_MISMATCH` naming the rule — a two-line re-pin and a sentence of
> explanation. A `TBD` that becomes wrong says nothing at all. Fifteen of the 38 rules **the file
> held on 2026-08-01** were `active`
> with `TBD`, which is 40% of the file with its only size check switched off, and the rule this
> project most often cites as proof the mechanism works —
> `FAILCLOSED-UNCHECKED-ISE-REPLACED`, which grew 55 → 71 by absorbing 16 grid rows — **was one of
> them**, so that theft was silent by construction.
>
> **How the 15 were pinned, and why the numbers can be believed.** One `mvn -Pgolden -pl golden -am
> verify` on a frozen `rsync` snapshot of the working tree (`--exclude .git --exclude .claude
> --exclude target/`), so nothing could move under the run; the tree was confirmed unchanged
> afterwards. Every one of the 15 was then cross-checked against a *second, independent* source:
> `FAILCLOSED-NAD27-OUTSIDE-GRID-REPORTED`'s `reason` already contains written arithmetic predicting
> the post-grid-rule count for eight of them (`PROJ-AEA-SPHERICAL-INVERSE 164 -> 159`,
> `PARSE-DMS-PARAMETER-VALUE 10 -> 8`, `DATUM-ISEQUAL-SELF-COMPARISON 332 -> 284`,
> `NUM-KARNEY-LATITUDE-CORE 20,191 -> 19,336`, and this rule itself "lands at 37"), and each rule's
> own header comment records an observed figure for the rest. **Both sources agree on all 15.** A
> re-run with the counts in place reported **no `COUNT_MISMATCH` and no `DEAD_RULE`**, and the
> INTENDED/UNEXPLAINED split was unchanged at 39,116 / 2,291 — pinning froze the attribution, it did
> not alter it.
>
> **No rule theft was exposed, and two candidates were checked rather than assumed.** (a)
> `PARSE-DMS-PARAMETER-VALUE` claims 8 where its own comment predicted the structural 10; the two
> missing rows are `nad27:5001` probes 1 and 3, which now end at `EXC:CrsTransformException` and are
> claimed by the grid rule. That is **not** an ordering artefact — this rule's `status_to: "OK"`
> declines them at any position in the file. (b) 16 of the PAIR rows matching
> `DATUM-TYPE-UNKNOWN-HOISTED`'s key globs are claimed earlier by
> `FAILCLOSED-UNCHECKED-ISE-REPLACED`; all 16 are status changes
> (`IllegalStateException → ProjectionException`) and `DATUM-TYPE-UNKNOWN-HOISTED` does not set
> `allow_status_change`, so it declines them wherever it sits. Both were verified on the frozen
> report, not inferred from the file order.
>
> **The 15 counts, as pinned:**
>
> | rule | pinned | corroborating source |
> |---|---:|---|
> | `PARSE-DMS-PARAMETER-VALUE` | 8 | NAD27 rule predicts `10 -> 8` |
> | `PROJ-AEA-SPHERICAL-INVERSE` | 159 | NAD27 rule predicts `164 -> 159` |
> | `PARSE-RF-F-TRANSPOSED` | 40 | this README's own "40 — the only rows this fix can move" |
> | `PARSE-R-DECLARES-SPHERE` | 250 | this README's seeded table |
> | `PARSE-RF-IN-WORLD-DICTIONARY` | 5 | rule header comment |
> | `PARSE-FIRST-MATCH-WINS` | 71 | rule header comment |
> | `FAILCLOSED-UNCHECKED-ISE-REPLACED` | 37 | NAD27 rule predicts "lands at 37" |
> | `DATUM-TYPE-UNKNOWN-HOISTED` | 251 | derived here: 347 candidate PAIR rows = 251 + 16 + 80 |
> | `DATUM-ISEQUAL-SELF-COMPARISON` | 284 | NAD27 rule predicts `332 -> 284` |
> | `NUM-PHI2-CONVERGES-ON-EXTREME-ELLIPSOIDS` | 6 | rule header comment |
> | `PROJ-INVERSE-CORRECTED-ROUND-TRIP-NOW-EXACT` | 48 | rule header comment, per-key breakdown |
> | `PROJ-INVERSE-CORRECTED-ROUND-TRIP-NOW-EXACT-FWD-ALSO` | 45 | rule header comment |
> | `PROJ-EQC-EQDC-SINU-REGISTRY-ROWS` | 67 | rule header comment: `82 − 15` |
> | `PARSE-UNITS-PRECEDES-TO-METER` | 17 | rule header comment: `5+4+4+4` |
> | `NUM-KARNEY-LATITUDE-CORE` | 19336 | NAD27 rule predicts `20,191 -> 19,336` |
>
> `DATUM-TYPE-UNKNOWN-HOISTED` is the only one with no pre-existing prediction anywhere, which is
> why its derivation is written out in the rule's header rather than just its number.

Seven rules ship. **Only `PARSE-VUNITS-ACCEPTED` has a pinned `expected_rows`**; the rest are `TBD`,
because the working tree is mid-flight across six concurrent streams and any number pinned today
describes a transient state. **No count here is invented.** Pin each one from the observed value the
gate prints (`rows claimed per rule`) once its stream has landed.

| id | `expected_rows` | observed today | note |
|---|---|---|---|
| `PARSE-VUNITS-ACCEPTED` | **964** | 964 | pinned; measured 2026-07-31 |
| `PROJ-AEA-SPHERICAL-INVERSE` | TBD | 164 | ordered before the `+R` rule; the two are one release |
| `PARSE-RF-F-TRANSPOSED` | TBD | 40 | the only rows in the suite this fix can move |
| `PARSE-R-DECLARES-SPHERE` | TBD | 250 | |
| `DATUM-TYPE-UNKNOWN-HOISTED` | TBD | 253 | |
| `DATUM-ISEQUAL-SELF-COMPARISON` | TBD | 431 | |
| `NUM-KARNEY-LATITUDE-CORE` | TBD | 16,202 | **provisional; not owned by this module** |

The "observed today" column is a snapshot of a mid-flight tree, printed by the gate itself. It is
recorded so the numbers are not lost, **not** as a licence to paste them into `expected_rows`: pin a
count only when you know its stream has finished landing, and pin it from a run you did yourself.

**On the `+vunits` count.** The plan and the task brief quote a smaller figure — "943 rows: 158 registry
defs × 5 probes + 143 CSV rows" (which is 933, not 943) and `expected_rows: 944`. The measured value
with *this* input set is **964** = 790 `REG` (158 defs × 5 probes, matching the plan's 158) + 144 `CSV`
+ 30 `SYN`. It differs for two reasons that are properties of this input set rather than errors in
theirs: the CSV side is 144 rows here, not 143, and neither figure includes the synthetic `+vunits`
modifier sweep, which did not exist when those numbers were written.

**`NUM-KARNEY-LATITUDE-CORE` is a placeholder owned by the numerical-core stream, not by this module.**
It absorbs sub-microscopic drift only — magnitude ≤ `1e-6` in raw column units, no status change, no
`inside` change, no non-finite endpoint — so it cannot claim anything structural. It exists so that
16,202 rows of genuine sub-micron movement do not bury the ~2,700 rows of structural change
underneath them. It expires 2026-12-31 deliberately: it is a migration-window rule, not a
specification. The band was chosen from the measured magnitude distribution, which is **bimodal with an
empty valley between 1e-5 and 1e-3** — consistent with the plan's independent observation that proj4j's
errors are either sub-metre or enormous, with no long tail of subtle drift.

### Current state of the gate: red, by design

As of this commit, `mvn -Pgolden -pl golden -am verify` reports:

```
32,397 UNCHANGED · 21,033 CHANGED · 0 ADDED · 0 REMOVED · 18,304 INTENDED · 2,729 UNEXPLAINED
```

The 2,729 unexplained rows are **the backlog**, not a defect in this module. They are other streams'
in-flight changes, and each stream owner adds the rule that claims theirs. This module deliberately
does not declare another agent's change intended on their behalf — that would be exactly the failure
mode it exists to prevent.

Notable shapes in the unexplained set, worth routing to their owners:

* ~1,700 rows of `OK → OK` numeric movement above `1e-5`, i.e. above the numerical-core band.
* `23` rows `OK → EXC:java.lang.IllegalStateException` in `PAIR` — a **new** non-`Proj4jException`
  escape, which belongs to the fail-closed work (`GeocentricConverter.java:122-125` throws
  `IllegalStateException`).
* `45` rows turning into `EXC:java.lang.NullPointerException` (`35` from
  `EXC:UnsupportedParameterException`, `10` from `OK`) in `SYN` — an NPE is never an intended
  failure mode.
* `15` rows `EXC:InvalidValueException → EXC:UnsupportedParameterException` in `SYN` — a parse-error
  reclassification.
* A handful of rows with magnitudes of `1e23`–`1e212`.

---

## Triage of the unexplained backlog — 2026-08-01

> **Superseded in its numbers, not in its reasoning.** A second triage later the same day, after
> eight more streams landed, is [below](#second-triage-of-the-unexplained-backlog--2026-08-01-later-the-same-day).
> Read this section for the mechanisms and the arguments; read that one for the current tallies. Two
> of this section's own predictions came true and are recorded there: the `NoSuchElementException`
> blind spot became visible, and `FAILCLOSED-POLE-OVERSHOOT-REJECTED` became the `DEAD_RULE` its
> reason said it would.

A pass over the whole unexplained set, on a tree frozen to `/tmp` so that the A/B differed in one
file only. **Every count on this page is from a run the author did; none is inherited.**

### The number the section above quotes is already historical

The gate reported `2,729 UNEXPLAINED` when this module landed. On a snapshot taken the next morning
it reported **4,548**, and on a snapshot taken *twenty minutes after that*, **34,533** — of which
32,213 rows were one in-flight `ExtendedTransverseMercatorProjection` change rejecting every
`etmerc`/`utm` CRS with *"the Poder/Engsager transverse Mercator requires an ellipsoid; eccentricity
must not be zero"*, including CRS on GRS80. That third snapshot was discarded rather than triaged.

**The lesson is procedural and belongs here permanently: a single number from this gate describes a
moment, not a state.** Two things follow, and both were load-bearing in this triage.

1. **Freeze before you triage.** `tar` the tree to `/tmp`, run there, and do the A/B between two
   copies that differ in exactly the files you changed. A cluster that appears between two runs of
   the live tree is more likely another agent's fix landing than anything about your change.
2. **Re-check a cluster against the live tree before you write a rule for it.** 54 rows in this
   triage (`Krovak` × 44, `NZMG` × 10, *"which has no inverse"*) were already fixed in the live tree
   by the time they were analysed — `BasicCoordinateTransform.inverseAvailable` had replaced a bare
   `hasInverse()` test. A rule written for them would have been a `DEAD_RULE` within the hour.

### How the backlog broke down

On the frozen tree the set was **4,548** rows. After this pass it is **3,006**, and the movement is:

| | rows | |
|---|---|---|
| **Category 2 — regression, fixed here** | 1,058 | `+proj=geocent`; 1,045 back to `UNCHANGED`, 8 to the Karney band, 5 declared |
| **Category 1 — intended, rule now written** | 484 | eight new rules, below |
| **Category 3 — honestly unexplained** | 3,006 | mechanisms identified for most; owners named |

The 484 is 392 rows this pass identified for itself plus **92 handed over already attributed** by the
stream that caused them — 90 `NO_INVERSE_AVAILABLE` and 2 pole-overshoot rows. A handover with the
mechanism attached is the cheapest rule in the file to write and the most trustworthy; **the two
counts pinned exactly rather than left `TBD` are both from that handover**, which is not a
coincidence.

### Category 1: the eight rules this pass added

| id | rows | pinned? | mechanism |
|---|---|---|---|
| `PROJ-GEOCENT-LON0-APPLIED` | 5 | **yes** | `+lon_0` now applied to a cartesian right-hand side, per `fwd.cpp:105-112` |
| `PARSE-NO-KEYWORD-ALLOWLIST` | 95 | TBD | `ParseMode.PROJ_COMPATIBLE` is the default; PROJ has no allow-list |
| `PARSE-DMS-PARAMETER-VALUE` | 10 | TBD | `alpha=-36d52'11.6315` no longer escapes as a raw `NumberFormatException` |
| `PARSE-RF-IN-WORLD-DICTIONARY` | 5 | TBD | `world:palestine` carries `rf=` — see the correction below |
| `PARSE-FIRST-MATCH-WINS` | 71 | TBD | duplicate parameter keys: last-wins → first-wins |
| `FAILCLOSED-UNCHECKED-ISE-REPLACED` | 211 → 55 | TBD | 1.4.3's unchecked `java.lang.IllegalStateException` is now a `Proj4jException` |
| `FAILCLOSED-NO-INVERSE-FOR-FORWARD-ONLY` | 90 | **yes** | 18 forward-only projections refused as a transformation source — **75 of the 90 were `OK` in 1.4.3** |
| `FAILCLOSED-POLE-OVERSHOOT-REJECTED` | 2 | **yes** | `checkForwardDomain` rejects a round trip that landed 176 m past the north pole |

Three carry a pinned `expected_rows`; the rest are `TBD` with the observed count in a comment, per
the rule this file already states. `FAILCLOSED-UNCHECKED-ISE-REPLACED` is the clearest case for
`TBD`: it claimed **211** rows on the frozen tree and **55** on the live tree four hours later,
because the 156 rows in the difference moved from `IllegalStateException → ProjectionException` to
`IllegalStateException → OK` as the datum stream landed. Same rule, same mechanism, same code, and a
number that moved by a factor of four without anything being wrong.

**Where a pin is deliberately a tripwire.** `FAILCLOSED-NO-INVERSE-FOR-FORWARD-ONLY` is pinned at 90
= 18 projections × 5 probes, which is structural. If one of the 18 acquires an inverse the count
drops and the gate fails with `COUNT_MISMATCH`. That is the point: a projection gaining an inverse is
a change somebody should have to describe, and this is the cheapest place to make them.

**The `hasInverse()` trap, encoded in the rule rather than smoothed over.** The gate that raises
these errors (`BasicCoordinateTransform.inverseAvailable`) deliberately does *not* key on
`hasInverse()`. `KrovakProjection` and `NewZealandMapGridProjection` implement `projectInverse` and
never declare `hasInverse()`, so a `hasInverse()`-only gate rejected **EPSG:2065, EPSG:5514 and
EPSG:27200** — three working CRS — while `LandsatProjection` declares it and overrides nothing. The
rule therefore lists its 18 projections by name instead of globbing `proj/*`, which makes it
*structurally* impossible for those three CRS to be swept in later. `+proj=geocent` is excluded by
the same list, and for the same reason in reverse: it is invertible, and it now says so.

**A rule that looked right and was not.** `PARSE-NO-KEYWORD-ALLOWLIST` was first written with the
obvious predicate — `status_from: UnsupportedParameterException, status_to: OK` and nothing else. It
claimed **270** rows instead of 95, and `PARSE-R-DECLARES-SPHERE` dropped from 250 to **75**. The
175 rows it had taken were `mod/<host>/R*`: 1.4.3 rejected `+R_A` and its siblings as unsupported
parameters, so the row moves from an exception to a coordinate *and* that coordinate is the one the
`+R` sphere fix changed. The rule would have absorbed the entire `+R` numeric change behind a
parse-acceptance reason. It is now scoped to the three tokens it means. **`expected_rows` being
exact and two-sided on the *other* rule is what caught it** — that is the mechanism working, and it
is worth knowing that it works by protecting rules from each other, not only from code.

### Correction: `+rf` is **not** absent from the registry dictionaries

The "The synthetic matrix is not optional" section above states, in bold, that `+rf=` appears in no
registry dictionary and no test CSV — *"zero occurrences, verified by grep"* — and concludes that the
synthetic rows are "the **ONLY** baseline rows this fix can move". The CSV half is right. The
dictionary half is **wrong**, and the reason is worth recording because it will recur: **the
dictionaries write parameters without the leading `+`**, so `grep '+rf='` finds nothing and
`grep 'rf='` finds two —

```
epsg/src/main/resources/proj4/nad/world:96   <palestine>  proj=tmerc a=6378300.79 rf=293.488307656
epsg/src/main/resources/proj4/nad/world:110  <malay>      proj=omerc a=6377295.66402 rf=300.8017
```

The synthetic matrix is still not optional — it is what puts `+rf` in front of six different host
projections, and `world:malay` is unreachable in 1.4.3 for an unrelated reason. But
`PARSE-RF-F-TRANSPOSED` is scoped `sections: [SYN]` and so left `world:palestine` unexplained, and
that CRS is a good demonstration of the defect's size: at its own origin
(`lat_0=31d44'2.749"N lon_0=35d12'43.490"E`) the 1.4.3 round trip returns latitude **−3.3e205
degrees**, and two probes have eastings around **1.3e24 m**, because `es = rf(2 − rf) = −85,540`.
`PARSE-RF-IN-WORLD-DICTIONARY` claims it, with a magnitude **floor** of `1e3` so that it cannot also
absorb future sub-kilometre `tmerc` movement on the same five rows.

### Category 2: the one regression found, and where it came from

**`+proj=geocent` could not be used as a transformation source. 1,058 rows, 330 keys.**
`GeocentProjection` implements its inverse as an override of `inverseProjectRadians`, not as a
`projectInverse(double, double, ProjCoordinate)` — it cannot supply the latter, because that
signature has no `z` — and it did not declare `hasInverse()`. `BasicCoordinateTransform`'s new
inverse-availability gate therefore classified all 181 `epsg` `+proj=geocent` defs, the 148 MetaCRS
CSV cases that reference them and the one synthetic row as non-invertible and refused them with
`CrsTransformException: ... uses projection None, which has no inverse`. Every one of them
round-tripped in 1.4.3.

Fixed in `GeocentProjection` (see its javadoc for the whole account, including the separate
read-`dst`-instead-of-`src` defect that the golden regime **cannot** see, because
`BasicCoordinateTransform` aliases the two arguments). The golden proof is the cleanest shape this
regime produces:

```
1,045 rows  UNEXPLAINED -> UNCHANGED     bit-identical to 1.4.3, no rule needed
    8 rows  -> NUM-KARNEY-LATITUDE-CORE  1e-9 m in the height ordinate, from OTHER streams'
                                          datum-stage drift, newly observable because the row
                                          used to be an exception
    5 rows  -> PROJ-GEOCENT-LON0-APPLIED  the one deliberate behaviour change
```

**1,045 rows returning to the baseline is a better outcome than a rule for them**, and it is worth
being explicit about why: a rule would have recorded "we intended to change these", when what
actually happened is that they should never have moved.

### Category 3: what is left, and who owns it

**2,379 rows of `OK → OK` numeric movement.** All of it on the ten actively-owned projections:
`tmerc` 1,392 · `lcc` 490 · `utm` 152 · `cass` 131 · `poly` 55 · `eqdc` 49 · `merc` 29 · `omerc` 16.
Magnitudes are bimodal exactly as this file predicted — 1,230 rows in `1e-6 .. 1e-5` (just above the
Karney band) and 937 in `1 .. 1e3`, with 17 rows in the whole decade between. Mechanisms identified
but **deliberately not ruled on, because their files are being rewritten concurrently and this
module does not declare another agent's change on their behalf**:

* **`+lat_ts` is now applied on Mercator.** `REG/epsg:3388` (Caspian Sea Mercator, `+lat_ts=42`) at
  probe `(44.271836, 47.0)` moves `fy` `5,910,915.08 → 4,399,262.94` m — a factor of
  `0.7443 = 1/1.3436`, which is `k0 = msfn(42°)`. The same factor appears on `epsg:3994`,
  `SYN/mod/merc/lat_ts` and `proj4-epsg.csv:01480/01766/01862`. → Mercator owner.
* **`SYN/proj/bonne` probe 0**: `ix` `−170 → 10` at a probe of longitude 10 — the inverse was 180°
  out. → projections owner.
* **`SYN/proj/eqdc` probe 0**: `fy` `5,009,377 → 0` at the CRS centre — the `rho0` subtraction was
  missing. → projections owner.
* **`SYN/proj/putp2` probe 4**: `iy` `90 → 60` at a probe of latitude 60. → projections owner.

**478 rows `OK → ProjectionException`, and they are all improvements.** 468 are
`inv: LongLat: invalid latitude ...` — 1.4.3 returned latitudes of **−135°, −207°, −489°** as
coordinates (`proj4-epsg.csv:00518`, `:00520`, `:00521`) and they are now rejected by
`Projection.checkForwardDomain`. Ten are `PAIR` rows whose 1.4.3 forward was
**`fx = fy = Infinity`** (`t01/epsg:4034>epsg:2150`, `t04/epsg:4007>epsg:26716`,
`t11/epsg:4283>epsg:2150`, `t14/epsg:4075>epsg:26716`, probes 0/2/4). → fail-closed owner, whose
rule this is; no numeric regression is hiding here. The remaining 2 of the original 480 are
`SYN/proj/wag2`, now claimed by `FAILCLOSED-POLE-OVERSHOOT-REJECTED`.

**64 rows `OK → CrsTransformException`, down from 154.** 54 (`Krovak`, `NZMG`) were already fixed in
the live tree before they were analysed, and 90 are now claimed by
`FAILCLOSED-NO-INVERSE-FOR-FORWARD-ONLY`. What is left is the ~20 rows of the
geodetic-latitude-out-of-range guard plus the residue of the forward-only set on keys the rule's
18-name list deliberately excludes. → fail-closed owner.

**70 rows in small heterogeneous clusters**, all exception-to-exception or exception-to-`OK`, listed
here so nobody has to re-derive them: `world:new_zealand`, `proj/nzmg`, `proj4-epsg.csv:03288`
(`InvalidValueException → CrsTransformException`, 11 — the other 15 of that cluster were
`proj/adams_hemi`, `proj/adams_ws1` and `proj/guyou`, now claimed by
`FAILCLOSED-NO-INVERSE-FOR-FORWARD-ONLY`); `epsg:5472`, `esri:102766`, `esri:65061`, `esri:65161`, `nad27:5400`, `proj/adams_ws2`,
`proj/peirce_q`, `proj/poly`, `proj/spilhaus` (`→ OK`, 21 — newly registered projections);
`proj/alsk`, `proj/apian`, `proj/bacon` (`→ UnsupportedParameterException`, 15);
`world:madagascar` (`→ InvalidValueException`, 5 — `+rot_conv` now accepted, `labrd` still
unimplemented); `ellps/andrae`, `ellps/NWL9D` (`ConvergenceFailureException → OK`, 6); `proj/omerc`
(5); `proj4-epsg.csv:02092` (1).

**`EXC:java.lang.IllegalStateException → OK` — 6 rows frozen, 162 live — left unexplained on
purpose**, and this is the clearest case in the file of an honest `UNEXPLAINED` beating a rule.
`FAILCLOSED-UNCHECKED-ISE-REPLACED` bounds itself with `status_to: "EXC:org.locationtech.proj4j.*"`
precisely to exclude them. Claiming them would mean writing *"1.4.3 crashed and we now return X"*
with no statement about X, and the baseline row is all-NaN so there is nothing to compare X against.
The datum stream has since attributed them to its own work; **the mechanism statement and the rule
are theirs to write**, and until they exist an `UNEXPLAINED` row is the correct record. On the frozen
tree the six were `t03/epsg:4007>epsg:20022`, `t03/epsg:4034>epsg:20092`, `t20/epsg:4214>epsg:2366`,
`t24/epsg:4155>epsg:26748#4`, `t30/epsg:4817>epsg:2366`, `t34/epsg:4289>epsg:26748#4`.

### Reserved for the transverse-Mercator stream — do not write these

Two clusters are explicitly **not** this pass's to claim, recorded here so that nobody writes them
twice and nobody mistakes them for backlog:

* **~332 rows, `fwd: Extended Transverse Mercator: longitude … outside the projection domain`.**
  An in-flight `tmerc` regression with the same root cause as the concurrent `MetaCRSTest`
  failures, being fixed as this was written. **A rule here would be a rule for a bug.** An earlier
  snapshot of the same defect wearing a different message (*"requires an ellipsoid; eccentricity
  must not be zero"*) accounted for 32,213 rows, which is how the 34,533 figure quoted at the top
  of this section arose.
* **1,163 rows of `proj4-epsg.csv` being re-pinned against PROJ 9.8.1**, because their reference
  values were generated by the Evenden/Snyder series 9.8.1 no longer ships. The `tmerc` stream owns
  that rule, and its mechanism is the model this file should be measured against: every affected
  row probes **≥ 12.5° from its central meridian**, exactly where E/S first exceeds the file's
  0.1 m tolerance — 0.1118 m at 12.5°, 23 mm at 10°, 0.83 mm at 6° — and **not one row inside
  12.5° moved.** A mechanism that predicts which rows move *and* which do not is the standard; a
  row list is not.

### The non-`Proj4jException` escapes: two closed, one open

Measured on the live tree, 2026-08-01. **The current side of the table now contains exactly one
non-`Proj4jException` status**, and it is the one nobody had noticed:

```
current side:   50  EXC:java.util.NoSuchElementException
baseline side: 217  EXC:java.lang.IllegalStateException
                50  EXC:java.util.NoSuchElementException
                10  EXC:java.lang.NumberFormatException
```

* **`NullPointerException`: 45 → 0.** Gone between two runs four hours apart. **No rule was written
  for them, and none should be**: no mechanism was verified for the fix, and a rule asserting an
  intent nobody stated is worse than an `UNCHANGED` row. They are simply gone.
* **`IllegalStateException`: 217 → 0** on the current side. `GeocentricConverter.java:131` was the
  only source on the coordinate path and now raises `CrsTransformException(INVALID_COORDINATE)`, so
  `catch (Proj4jException)` sees it. The 217 split **55** `→ ProjectionException` (claimed by
  `FAILCLOSED-UNCHECKED-ISE-REPLACED`), **162** `→ OK` (the datum stream's, unclaimed — see below)
  and **0** remaining. The `23` rows the brief described as `OK → IllegalStateException` no longer
  exist in either direction.
* **`NumberFormatException`: 10 → 0**, claimed by `PARSE-DMS-PARAMETER-VALUE`.

Which leaves one, and it is invisible to this gate:

* **50 rows throw `java.util.NoSuchElementException`, in the baseline *and* in the current run.**
  `Projection.setSouthernHemisphere` (`Projection.java:946`) and `Projection.setHeightOfOrbit`
  (`Projection.java:1053`) throw a bare `NoSuchElementException` — not a `Proj4jException` — for any
  projection that does not implement them, with **no message at all**. `SYN/mod/{aea,lcc,longlat,
  merc,tmerc,utm}/h` and `SYN/mod/{aea,lcc,longlat,merc}/south` hit it. Because it is unchanged
  since 1.4.3 the diff never mentions it: **this regime reports changes, so a defect that predates
  the baseline is exactly what it cannot see.** → fail-closed owner.

And one **regression in kind** rather than in value, which the no-sentinels rule covers but the
`(0,0,0)` fix did not reach:

* **`PAIR/t14/epsg:4173>epsg:26748` probe 4** (`(5, 5)` into NAD27 Alaska zone 8). The `tmerc`
  forward answers `1.289e8, 2.662e7` m for a point 5,000 km outside the CRS. The inverse then lands
  on the Z axis, and `GeocentricConverter.convertGeocentricToGeodeticIter`'s `P/a < genau` branch
  (`GeocentricConverter.java:192-193`, `At_Pole = true; Longitude = 0.0`) answers
  **`(0°, 90°, 0.000104 m)`** — the north pole, at a tenth of a millimetre. 1.4.3 answered latitude
  `−9.36e10°`, which no caller could mistake for a coordinate. The `(0,0,0)` centre-of-mass fiction
  was removed a few lines below; the on-axis fiction was not, and it is *more* dangerous because it
  is entirely plausible. → fail-closed owner.

---

## Fourth triage: the NAD27 cluster retired and the out-of-grid fail-open closed — 2026-08-01, late

> This pass **wrote code**, which the previous three did not, so it is the first one whose numbers
> move because behaviour changed rather than because a rule was written. Read the third triage above
> for the clusters this inherited.

Everything here is from a **frozen `/tmp` A/B** in which the two trees differed in exactly the
fourteen files this stream touched — one of them in `core/src/main` (`datum/Grid.java`) — and every
number is from a run the author did. The live tree had three other streams writing at the time
(`GeocentricConverter`, `AzimuthalProjection`, `conformance/**`), which is precisely why the A/B was
frozen: measured live, this change would have been credited with their movement too.

```
before   12,225 UNCHANGED · 41,205 CHANGED · 0 ADDED · 0 REMOVED · 37,901 INTENDED ·  3,304 UNEXPLAINED
after    12,023 UNCHANGED · 41,407 CHANGED · 0 ADDED · 0 REMOVED · 39,116 INTENDED ·  2,291 UNEXPLAINED
```

No `COUNT_MISMATCH`, no `DEAD_RULE`, no `PENDING_RULE_FIRED`, no `EXPIRED_RULE`. **Two rules added,
both pinned exactly, and both pins were right on the first run** — because the predicate was measured
against the frozen report before the rule was written, not pasted from the gate's own count
afterwards. Four rules re-pinned downward in place, with the arithmetic itemised.

### The change is 1,995 rows, not 41

`Grid.shift`'s no-table `else` branch was empty: the coordinate came back **bit-identical to the
input**, which is indistinguishable from "the shift was zero", and the transform reported success. It
now raises `CrsTransformException(COORDINATE_OUTSIDE_GRID)`.

The third triage found this through **40 rows** at the 40°N edge of `ntv1_can.dat`. That was the
symptom that made it visible, and it is a small part of the population: **1,995 rows change status**,
across all four sections (`REG` 1,673 · `CSV` 148 · `PAIR` 144 · `SYN` 30). **202 of them were
bit-identical to 1.4.3 beforehand** — the fail-open was so faithful that the golden regime, which
reports *change*, could not see them at all. That is the same blind spot
`FAILCLOSED-NO-SUCH-ELEMENT-REPLACED` came out of, and it is worth stating as a standing property:
**a defect that both sides share is exactly what this suite cannot report**, and it becomes visible
only when somebody changes one side.

Upstream was measured rather than read. With `PROJ_DATA` pointed at a directory holding only the
grids this repository ships:

```
echo "1 -1" | cs2cs -f "%.10f" +proj=longlat +datum=WGS84 \
     +to +proj=longlat +ellps=clrk66 +nadgrids=@conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat
  *	* inf
```

That is the exact `+datum=NAD27` grid list, with **every** grid present, refusing a point outside all
of them. proj4j answered `1, -1`.

### Two controls, and the negative one is the more interesting

**The guard discriminates, proven by reverting it.** Replacing the single `throw` with the 1.4.3
no-op makes **14 of 56** tests in the affected classes fail, each naming the coordinate that would
have been returned silently — `(-40.0, 35.0)`, `(1.0, -1.0)`, *"leaving the round trip 92.95 m from
where it started"*. **The other 42 keep passing**, and that half is what makes it a discriminator
rather than a throw-detector: a guard that fired on everything would have failed those too. The
sharpest single pair is 1.9e-5° apart — `conus` at (100°W, 19.999999N) must shift and (100°W,
19.99998N) must refuse, which are PROJ 9.8.1's own two answers on the same bytes.

**`+datum=potsdam` did not move, and that is the negative control the change needed.** 17
`proj4-epsg.csv` rows reference a `datum=potsdam` def. `BETA2007.gsb` is absent, so potsdam's grid
list resolves **empty**, and an empty list is a no-op. That demonstrates the distinction the whole
fix rests on — *"the grid file is not there"* stays silent, only *"outside a grid that loaded"*
errors — **on a population that could have moved and did not**. PROJ's `@`-optional wart is
untouched.

### The deliberate divergence is kept, and is now observable

`Grid.shift` still falls through to the *next* grid when the grid it selected refuses to interpolate,
where PROJ commits to `findGrid`'s choice. That was a recorded conscious decision and this pass did
not reverse it: reversing it is a separate behavioural change with its own row set. It is also
orthogonal — the fall-through can only ever return a value that *some grid the caller listed*
produced for this point, whereas the `else` branch invented one, so keeping it makes the change as
small as it can be.

Reaching the divergence at all needs a grid whose containment epsilon exceeds its interpolation
clamp, and **for a square grid it never does**: the epsilon is `(resX + resY) * 1e-5` degrees while
the clamp is `1e-4` of a cell, so on `conus` (0.25° square) every admitted point is clamped. The
window opens only when `resX > 9 * resY`. `grids/OutsideGridFailsClosedTest` therefore builds a
10° × 0.1° CTABLE V2 fixture, and pairs it with a plain grid to show the fall-through still happens,
then with neither to show each grid alone behaves as claimed.

### Two rules added

| id | rows | pinned? | mechanism |
|---|---:|---|---|
| `FAILCLOSED-NAD27-OUTSIDE-GRID-REPORTED` | **1,995** | yes | the `else` branch raises `COORDINATE_OUTSIDE_GRID`; every row reports the grid list `[ntv1_can.dat]` |
| `DATUM-NAD27-NADCON-SHIFT-APPLIED` | **852** | yes | the third triage's 907-row cluster, finally scopable |

**Ordering is load-bearing in both directions this time, and the file now has one example of each.**

* `FAILCLOSED-NAD27-OUTSIDE-GRID-REPORTED` is **first**. Placed anywhere after
  `FAILCLOSED-UNCHECKED-ISE-REPLACED`, that rule *grows* 55 → 71 by absorbing 16 of these rows behind
  *"1.4.3 threw an unchecked `IllegalStateException` and we now throw a `Proj4jException`"* — true of
  the row and silent about the mechanism. It is `TBD`-pinned, so the theft would not have failed the
  build. With the new rule ahead of it, it gives up all 34 of its NAD27 grid rows and lands at **37**.
  This is `PARSE-NO-KEYWORD-ALLOWLIST` against `PARSE-R-DECLARES-SPHERE` again, caught by ordering
  rather than by a pin.
* `DATUM-NAD27-NADCON-SHIFT-APPLIED` is **last**. Its band (1e-4 .. 1e4) overlaps the top of
  `PROJ-TMERC-PODER-ENGSAGER-DEFAULT`'s (1e-6 .. 1e-2), so placed earlier it would take that rule's
  NAD27 `tmerc` rows and turn a pinned count into a `COUNT_MISMATCH`. Verified on the frozen tree that
  all 852 rows it claims were `UNEXPLAINED` beforehand and that it takes nothing from any other rule.

**Scoping the first rule by `status_to` alone would have been wrong**, and the measurement says by how
much: 105 changed rows reach `EXC:CrsTransformException` **without** a NAD27 datum, and they belong to
`FAILCLOSED-NO-INVERSE-FOR-FORWARD-ONLY` (65) and `FAILCLOSED-NO-INVERSE-FOR-NEW-FORWARD-ONLY` (40) —
same exception type, entirely different reason. `datums: [NAD27]` separates them completely.

### Four rules re-pinned downward, with the arithmetic

A row that now throws has no numeric movement left for another rule to describe, so five rules lose
rows to the new one. The four pinned ones would otherwise be `COUNT_MISMATCH`es, and each carries the
note in place:

| rule | was | now | lost |
|---|---:|---:|---:|
| `PROJ-TMERC-PODER-ENGSAGER-DEFAULT` | 14,724 | **14,038** | 686 |
| `PARSE-VUNITS-ACCEPTED` | 964 | **956** | 8 |
| `PROJ-POLYCONIC-KERNEL-CORRECTED` | 51 | **43** | 8 |
| `PROJ-POLYCONIC-INVERSE-CONVERGES` | 6 | **4** | 2 |
| *(`TBD`, recorded not pinned)* `NUM-KARNEY-LATITUDE-CORE` | 20,191 | 19,336 | 855 |
| *(`TBD`)* `DATUM-ISEQUAL-SELF-COMPARISON` | 332 | 284 | 48 |
| *(`TBD`)* `PROJ-AEA-SPHERICAL-INVERSE` | 164 | 159 | 5 |
| *(`TBD`)* `PARSE-DMS-PARAMETER-VALUE` | 10 | 8 | 2 |

That is 1,614 rows accounted for; 202 more came from `UNCHANGED` and 179 were already `UNEXPLAINED`,
which is the 1,995. **A count dropping is as interesting as one going up**, and the reason each of
these dropped is written into the rule rather than left to be re-derived.

### The `datums` column: five lines of rule, and what they replace

`golden-index.tsv` gains a sixth column holding the sorted set of `+datum=` **values** on either side,
and `rules.yaml` gains `datums:` (ANY-of) and `datums_absent:` (all-absent). That is the fix the
second and third triages both named and neither could write.

What it replaces: `params_present` matches parameter **keys**, and `datum` is a key that 1,962
dictionary lines carry, so scoping "the defs whose datum is NAD27" meant enumerating ~205 keys — which
would also have swept in ~1,066 rows belonging to a live stream. `DATUM-NAD27-NADCON-SHIFT-APPLIED` is
now four match lines.

Three details that are decisions rather than implementation:

1. **It is a set, not a scalar.** `CSV` and `PAIR` cases have two real CRS and either side can be the
   one a rule means; a single column would make the predicate depend on which way the pair happened to
   be written. The consequence — `datums: [NAD27]` also matches a pair whose *source* is NAD27 — is
   stated in `InputSet.datumsOf` rather than hidden, and `sections`/`src_proj`/`tgt_proj` are there for
   when it matters.
2. **Values are verbatim, with no case folding and no aliasing.** `+datum=nad83` and `+datum=NAD83`
   are different strings here because they are different strings in the dictionaries, and
   `SYN mod/*/datum_nad83_lower` exists to probe exactly that. A rule meaning both must list both.
3. **The `REG` source's own `+datum=WGS84` is excluded.** The hub's datum would otherwise appear in all
   9,013 `REG` rows and make the column useless; only the def's datum is recorded.

### What the cluster looked like once it could be seen

865 `REG` rows carry `datum=NAD27` and are `OK → OK` after Job 1. Their magnitudes are not one
population, and the rule claims two of the three:

| magnitude | rows | what |
|---|---:|---|
| 1.71e-4 .. 7.18e-4 **degrees** | 12 | `longlat` targets — **no projection in the path at all**, 19 m to 80 m. A kernel cannot move a row that runs no kernel; this is what identifies the cluster as the datum. |
| 1 m .. ~1e3 m | 840 | the NADCON shift through a projection |
| 6.1e5 .. 2.03e6 m | **13** | `esri:26731`, `esri:102120`, `esri:102122` — all `omerc`, 610 km to 2,030 km. **Not claimed.** |

The 13 are three orders of magnitude off the mechanism and are the `omerc` far field, the same shape
this file already declines to rule for the `PAIR` residue. The rule *declines* them on magnitude, so
the report records which rule nearly fit. → omerc owner.

Also not claimed, on the sections' own reserved grounds: the 10 `CSV` and 29 `PAIR` NAD27 `OK → OK`
rows. `sections: [REG]` is what keeps the rule out of both.

### A consequence outside this module: `proj4-epsg.csv`'s reserved re-pin is now a different ask

**148 of the 4,280 rows in `proj4-epsg.csv` now refuse instead of returning a coordinate**, and in
`MetaCRSTest` that shows as `1,195 regressed → 1,308` (+113; the other 35 were already failing).
Counted two independent ways that agree exactly: a dictionary parse resolving both CRS of every row
finds **148** rows referencing a def carrying `datum=NAD27`, and the frozen golden A/B finds **148**
`CSV` rows changing status, all in that file and all naming `[ntv1_can.dat]`.

The reason this matters to the pending re-pin is that it changes what the file is wrong *about*.
Every row in it probes the single point **(1.0, −1.0)**, in the Gulf of Guinea. Those 148 rows passed
only because proj4j and the reference generator **fail-opened in the same way**; `cs2cs` 9.8.1 answers
`* * inf` there with every grid present, so the recorded coordinates are **wrong against 9.8.1, not
merely stale**. A regeneration must record them as refusals. The file is untouched here.

> **A grep lesson, correcting this project's own rule.** The count above was nearly missed: the skill's
> non-negotiable 4a says the dictionaries write parameters *without* a leading `+`, and following that
> faithfully scores **zero on a population of 509**, because `epsg` and `esri` write `+datum=NAD27`
> **with** the plus. The dictionaries are inconsistent; `(?<![\w])\+?datum=` handles both. The durable
> lesson is the one that caught it: **two independent counting methods that must agree**.

---

## Third triage of the unexplained backlog — 2026-08-01, evening

> **Superseded in its numbers, not in its reasoning**, by the
> [fourth triage](#fourth-triage-the-nad27-cluster-retired-and-the-out-of-grid-fail-open-closed--2026-08-01-late)
> above, which wrote code and took the backlog from 3,304 to 2,291. Two of this section's own findings
> were closed there: the 40&deg;N round-trip regression, and the NAD27 cluster it could not scope.
>
> Read the two sections below this one for the mechanisms and the arguments. This section carries
> the tallies as of the evening of 2026-08-01, and it is the one that finally moved the number.

Roughly fifteen streams landed between the second triage and this one. Everything below is from
**one frozen `/tmp` snapshot** (`rsync -a --exclude .git --exclude target/`), taken with one live
stream still writing (`BasicCoordinateTransform` plus three test files), and every number is from a
run the author did.

```
before   12,225 UNCHANGED · 41,205 CHANGED · 0 ADDED · 0 REMOVED · 23,037 INTENDED · 18,168 UNEXPLAINED
after    12,225 UNCHANGED · 41,205 CHANGED · 0 ADDED · 0 REMOVED · 37,901 INTENDED ·  3,304 UNEXPLAINED
```

One `COUNT_MISMATCH` on the way in and none on the way out; no `DEAD_RULE`, no
`PENDING_RULE_FIRED`, no `EXPIRED_RULE`, and no rule declined a row. **Six rules added, four
amended, 14,864 rows claimed** — a factor of 5.5 off the backlog in one pass, and almost all of it
is one cluster that had been waiting for its stream to land rather than waiting to be understood.

### Nothing returned to baseline, and the previous return is holding

**0 rows returned to `UNCHANGED` in this pass, because this pass wrote no code.** The pattern was
looked for first, per this file's own instruction, and found only in the negative — every cluster
claimed here has a 1.4.3 value that is wrong and a current value that is a different number.

**The geocent result from the first triage is intact: 1,045 of the 1,058 `+proj=geocent` rows are
still bit-identical to 1.4.3.** Only 13 geocent rows appear in the report at all (5 for
`PROJ-GEOCENT-LON0-APPLIED`, 8 for the Karney band), which is exactly the split that pass recorded.
This is worth re-measuring every time: a return to baseline is the strongest outcome this regime
produces and the easiest to silently lose.

`UNCHANGED` did fall, 13,353 → 12,225. That is 1,128 rows that used to be bit-identical and are not
any more, and 14,724 of the claims below are the reason: the transverse-Mercator kernel moves
essentially every projected CRS in the suite by a fraction of a millimetre.

### 16,956 of the 18,168 were one cluster: `tmerc`

`PROJ-TMERC-PODER-ENGSAGER-DEFAULT` claims **14,724** of them, and the shape of the argument is the
part worth keeping. PROJ 9.8.1 runs Poder/Engsager for `+proj=tmerc` and `+proj=utm`; proj4j to
1.4.3 always ran Evenden/Snyder. `TransverseMercatorProjection`'s class javadoc tabulates the
difference *independently of this suite* — 2 µm at 2°, 0.9 mm at 6°, metres at 20°, kilometres at
45° — so the mechanism predicts a magnitude from a probe's distance to its own central meridian.
That prediction was tested against the rows rather than assumed:

| distance from the def's own `lon_0` | rows | median movement |
|---|---:|---:|
| 0–5° | 112 | **37 m** |
| 5–10° | 14,748 | **2.53e-4 m** |
| 60–65° | 164 | **120 m** |

The 5–10° bucket is where the dictionaries put almost every UTM and State Plane zone, and 0.25 mm
at 5–10° is the javadoc's 0.9 mm at 6°. The other two buckets **cannot** be this mechanism — a
series-truncation difference is *smallest* on the central meridian, not 37 m — and they are the
NADCON grid shift, which is 3.7 m to 378 m. **The 1 cm ceiling is what separates the two**, and it
separates them completely. The rule's `reason` records the per-column maxima over all 14,724 claimed
rows (`fx` 3.5 mm, `ix` 1.2e-6°) so that the one loose end — `magnitude` is a max over columns with
different units, and 1e-2 in a degree column would be 1.1 km — is bounded by measurement instead of
by hope.

It also has to be ordered **after** `DATUM-LEGACY-OSGB36-PRECISION`, because `epsg:27700` is a
`tmerc` moving 2.55–3.26 mm, i.e. inside the band. Placed earlier it would have taken all ten OSGB36
rows and turned a pinned rule into a `COUNT_MISMATCH` — the same laundering
`PARSE-NO-KEYWORD-ALLOWLIST` committed against `PARSE-R-DECLARES-SPHERE`, three sections up this
page. Verified on the frozen tree that neither `epsg:27700` nor `epsg:4277` is among the 14,724.

### The `COUNT_MISMATCH` fired again, and was right again

`FAILCLOSED-NO-INVERSE-FOR-FORWARD-ONLY` reported `expected_rows=85 but matched 65`. The four
missing keys were `proj/aitoff`, `proj/hammer`, `proj/nsper` and `proj/wintri`, and **upstream has
an inverse for all four**, verified by reading 9.8.1 one file at a time:
`aitoff.cpp:200-201` (the shared setup for `aitoff` *and* `wintri`, which is why those two left
together), `hammer.cpp:86-87`, `nsper.cpp:167-168`.

**That rule has now shed five of its original eighteen names — `lagrng`, `aitoff`, `hammer`,
`nsper`, `wintri` — and every one left because upstream turned out to have an inverse that 1.4.3
did not.** A rule written as `keys: ["proj/*"]` would have absorbed all five silently and this file
would still be asserting that Aitoff is one-way. The enumeration is doing exactly the work it was
enumerated for, and this is now the strongest single argument in this document for never globbing a
key list.

The pin on `PROJ-NEW-PROJECTIONS-REGISTERED` demonstrated the same discipline in the *arrival*
direction without failing at all: it held at exactly 180, and the 8 projections that landed after it
(`calcofi ccon gstmerc imw_p labrd lcca ocea tpeqd`) surfaced as 40 `UNEXPLAINED` rows carrying
`crs-tgt: Unknown projection: <name>` rather than being swallowed. It is now 44 names, 220 rows.

### The six rules added and the four amended

| id | rows | pinned? | mechanism |
|---|---:|---|---|
| `PROJ-TMERC-PODER-ENGSAGER-DEFAULT` | **14,724** | yes | Evenden/Snyder → Poder/Engsager, `proj_internal.h:840-841`; band 1e-6 .. 1e-2 m, which excludes NAD27, the reserved ETM cluster, all of `CSV` and all of `PAIR` |
| `PROJ-POLYCONIC-KERNEL-CORRECTED` | 51 | yes | `poly.cpp:37-40` forward, `:88` inverse — the Newton denominator was `1/es` where upstream is `one_es/mlp³`, **off by a factor of 150 on GRS80** |
| `PROJ-POLYCONIC-INVERSE-CONVERGES` | 6 | yes | the probe-0 half of the same defect: `inv: Infinite longitude` → `OK`, the six rows the second triage listed and left |
| `NUM-ELLIPSOID-FOUR-NAMES-ADDED` | 20 | yes | `GSK2011 PZ90 clrk80ign danish`, all four read out of `9.8.1:src/ellps.cpp` |
| `PROJ-AITOFF-HAMMER-WINTRI-INVERSES-LANDED` | 13 | yes | the three that left the forward-only list by gaining an inverse; Winkel Tripel's forward also moved and the arithmetic is reproduced in both directions |
| `PROJ-NSPER-REQUIRES-A-POSITIVE-H-RATIO` | 5 | yes | the fourth: `+h` absent → `h/a = 0` → refused, per `nsper.cpp`'s `if (Q->pn1 <= 0 \|\| Q->pn1 > 1e10)` |
| `FAILCLOSED-NO-INVERSE-FOR-FORWARD-ONLY` | 85 → **65** | yes | four names removed, each with its upstream line |
| `PROJ-NEW-PROJECTIONS-REGISTERED` | 180 → **220** | yes | eight names added |
| `PARSE-NO-KEYWORD-ALLOWLIST` | TBD → **100** | yes | `world:madagascar` added; `+azi` is the `+vunits` defect again |
| — | — | — | — |

**All six new rules are pinned exactly, and every pin was right on the first run.** That is not a
claim about care; it is a consequence of measuring the predicate on the frozen tree before writing
the rule rather than pasting the gate's own count afterwards.

**`world:madagascar` is the cleanest illustration of an honest `UNEXPLAINED` paying off later.** The
first triage refused it in writing — *"+rot_conv now accepted, labrd still unimplemented … calling
that 'intended' would launder an unimplemented projection into a declared change"* — and the second
repeated the refusal. `labrd` has now landed, the row set is
`UnsupportedParameterException: azi parameter is not supported → OK`, and it is claimed. Two changes
had to land for those five rows to move and the rule says so; it speaks only for the parse side.

**The four new ellipsoids, refused by the second triage, are claimed here for one reason only.**
That refusal was conditional on an open decision: the same four entries in `Ellipsoid.ellipsoids`
were the suspected cause of four failing `core` tests (`ProjJsonTest` ×2, `Wkt1ReaderTest`,
`Wkt2ReaderTest`) through `io/wkt/WktNames.java:276`, which matches ellipsoids **numerically**. On
this frozen tree `core`'s eight remaining failures are `CoordinateTransformTest` ×2,
`InventedHeightTest` ×4, `MetaCRSTest` and `Proj4JSTest` — **none of the four**. The array stands,
so the parse side can be declared.

### A regression this pass found: the NAD27 grid-shift round trip is asymmetric at a grid edge

**41 `REG` rows round-tripped *exactly* in 1.4.3 and are now 30–110 m out.** Every one of them
probes **latitude 40.000000** — and `ntv1_can.dat`, the only NAD27 grid actually shipped in
`proj4j-epsg`, has its southern boundary at exactly that latitude. Read from the header bytes of
`epsg/src/main/resources/proj4/nad/ntv1_can.dat`, which stores its extent in **degrees**, not the
seconds an NTv1 header usually carries:

```
S LAT 40.0   N LAT 84.0   E LONG 44.0   W LONG 142.0   grid 0.2°
```

Every affected probe is at 40.000000°N with a longitude between 49.9°W and 103.9°W, i.e. exactly on
the southern edge and well inside the east–west span. The forward finds the point inside the grid
and applies the Canadian shift; the shifted point is a few tens of metres **south** of 40°N,
therefore outside; and `Grid.shift`'s no-table branch — *"1.4.3 leaves the coordinate untouched,
which is indistinguishable from 'the shift was zero'"*, in its own comment — returns it unchanged.
So the round trip comes back displaced by exactly one grid shift.

```
REG epsg:26721 probe 2 at (-49.928932, 40.0)   fx 1103866.798 -> 1103774.042 m (92 m)
                                               ix -49.92893219 -> -49.93002221  (0.001091°, ~93 m)
REG epsg:26712 probe 2 at (-103.928932, 40.0)  fx 1103866.798 -> 1103917.819 m (51 m)
                                               ix -103.9289322 -> -103.9283365  (0.000596°, ~51 m)
```

In both rows the inverse residue equals the forward shift to two digits, which is the tell: the
inverse leg is not failing to converge, it is not running at all. 2,504 of the 2,545 NAD27 `REG`
rows still round-trip exactly, so this is a boundary effect and not a broken inverse.

**No rule is written for it**, on two grounds: it sits inside the NAD27 cluster this file already
declines to rule (below), and *"the shift silently did not happen"* is precisely the failure mode
the fail-closed work exists to remove — `Grid.shift`'s comment already names
`ErrorCause.COORDINATE_OUTSIDE_GRID` as the intended signal and says reporting it "belongs on the
transform". → **fail-closed owner and datum owner jointly**;
`core/src/main/java/org/locationtech/proj4j/datum/Grid.java`, the `else` branch of `shift`.

> **CLOSED, 2026-08-01 (fourth triage, below). The diagnosis above is confirmed and the count is 40,
> not 41.** Re-measured on a frozen tree with the same criterion — round-tripped bit-exactly in 1.4.3,
> now more than a metre out — the answer is **40 rows, every one at probe latitude exactly 40.0 and
> none at any other latitude**, displaced 12.6 m to 93.1 m. The `else` branch now raises
> `COORDINATE_OUTSIDE_GRID`.
>
> **They do not round-trip after the fix, and they must not: PROJ 9.8.1 does not round-trip them
> either.** The return leg needs a grid value at a point outside the only grid there is.
> `cs2cs +proj=utm +zone=21 +ellps=clrk66 +nadgrids=@ntv1_can.dat +to +proj=longlat +datum=WGS84`
> answers `* * inf`, and `cct +proj=hgridshift +grids=ntv1_can.dat` at the shifted point is
> `TRANSFORMATION ERROR (Coordinate to transform falls outside grid)`. The fix is that the failure is
> reported rather than expressed as a coordinate 93 m away. Pinned by `grids/Nad27EdgeRoundTripTest`,
> which also carries the control: one degree further north the same pair round-trips exactly.

### What is left: 3,304 rows

| | rows | |
|---|---:|---|
| `CSV` — the reserved `proj4-epsg.csv` re-pin, in its entirety | **1,308** | reserved; **do not rule** |
| `REG`, `datum=NAD27` | **907** | `lcc` 485 · `tmerc` 228 · `utm` 152 · `omerc` 18 · `longlat` 15 · `sterea` 5 · `aea` 4 |
| `PAIR` | **358** | see below |
| `REG`, everything else | **576** | `tmerc` 306 (≥1 cm) · `cass` 157 · `omerc` 65 · `merc` 20 · `stere` 10 · `mill`/`vandg`/`aeqd`/`longlat` 4 each · `lcc` 2 |
| `SYN` | **155** | `mod/tmerc/*` 105 · one key each for `bonne krovak putp2 mbt_fps mbtfpp mbtfpq merc cass gnom leac ortho` |

Of the 1,308 `CSV` rows, **332 are the reserved ETM domain cluster** (267 `CSV` + 65 `PAIR`), whose
count the second triage predicted as "~332" and which is 332 for the third measurement running.

**The `PAIR` residue is 358 rows and none of them should be ruled.** 166 are `OK → OK` on
transverse-Mercator targets probed thousands of kilometres outside their own zone, with magnitudes
from 1e3 to **1.8e12 metres**; both series are meaningless out there and a change from one
meaningless number to another is not something a rule should dignify. 127 are the
`IllegalStateException → OK` population the second triage routed to the datum stream and which is
still theirs to state — and it is worth noticing that many of *those* land on the same absurd
coordinates, so "1.4.3 crashed and we now return X" would be claiming an X of 5e8 m.

> **SUPERSEDED, 2026-08-01: the `datum` column and the `datums:` clause were added and the cluster is
> ruled.** `DATUM-NAD27-NADCON-SHIFT-APPLIED` claims **852** of the 865 that survive Job 1; the other
> 13 are `omerc` far field and are deliberately left. See the
> [fourth triage](#fourth-triage-the-nad27-cluster-retired-and-the-out-of-grid-fail-open-closed--2026-08-01-late).

**The NAD27 cluster is still not ruled and the reason has not changed**: `rules.yaml` has no
predicate for a parameter's *value*, so scoping to "the defs whose datum is NAD27" means enumerating
several hundred keys. That is now *less* dangerous than it was — the transverse-Mercator stream has
landed and its rows below 1 cm are claimed — but it is still 907 rows across two mechanisms
(the NADCON shift and the kernel above 1 cm) with no way to tell a rule which one it is claiming.
**The five-line fix is still the one the second triage named: add a `datum` column to
`golden-index.tsv` in `GoldenGenerator` and a `datums:` match clause to `GoldenRules`.** Whoever
next opens `GoldenGenerator` should do it; it would retire the largest remaining honest
`UNEXPLAINED` in this file.

Two rows are singletons and are recorded so nobody re-derives them:

* `proj4-epsg.csv:01241` probe 0, `OK → ProjectionException`,
  `inv: generic 2D inverse did not converge in 15 iterations for (-3.081581016439677, …)`. This is
  the one non-ETM `OK → ProjectionException` row in the whole suite, it is new since the second
  triage, and it is unattributed. → whoever owns `util/GenericInverse2D`.
* `proj4-epsg.csv:02092` probe 0, `ProjectionException → ProjectionException`, message `inv: I` on
  both sides, `fx`/`fy` moving 10.3 m and 7,552 m. Known since the second triage, still unexplained,
  and inside the reserved file.

---

## Second triage of the unexplained backlog — 2026-08-01, later the same day

> **Superseded in its numbers, not in its reasoning**, by the
> [third triage](#third-triage-of-the-unexplained-backlog--2026-08-01-evening) above. Three of this
> section's predictions came true and are recorded there: the `tmerc` cluster was its stream's and
> was claimable the moment that stream landed; the four new ellipsoids' open decision resolved in
> favour of keeping the array; and `world:madagascar`'s `labrd` arrived.

Eight streams landed between the pass above and this one. Everything below is from **one frozen
`/tmp` snapshot**, taken with the two live streams (`core/proj/**` and the new `db/` module) still
writing, and every number is from a run the author did.

```
13,353 UNCHANGED · 40,077 CHANGED · 0 ADDED · 0 REMOVED · 21,967 INTENDED · 18,110 UNEXPLAINED
```

No `COUNT_MISMATCH`, no `DEAD_RULE`, no `PENDING_RULE_FIRED`, no `EXPIRED_RULE`, and no rule declined
a row. The gate is still red on the 18,110, which is the backlog, and **16,627 of them are one
cluster with one owner** — see below.

### How to run the gate while another stream's tests are red

`core`'s test phase fails on the frozen tree (15 failures from four other streams), and because
`golden` sits downstream in the reactor that failure **skips the gate entirely** — the first run of
this triage produced `Proj4J ... FAILURE / Golden Master ... SKIPPED` and no report at all. Add
`-Dmaven.test.failure.ignore=true`:

```bash
mvn -B $M2 -Dmaven.test.failure.ignore=true -Pgolden -pl golden -am verify
```

The gate's own verdict is unaffected — it is written to `golden-report.tsv` and printed either way,
and `GoldenRulesTest`/`GoldenDiffTest`/`ProbesTest` all still pass. This belongs in this file
permanently: a measurement that cannot be taken while somebody else's tests are red is a measurement
that stops being taken.

### 16 rules added, 1 corrected, 1 deleted; 539 rows claimed

| id | rows | pinned? | mechanism |
|---|---|---|---|
| `FAILCLOSED-NO-SUCH-ELEMENT-REPLACED` | 50 | **yes** | the blind-spot defect this file predicted: `NoSuchElementException` on `+south`/`+h` was unchanged since 1.4.3 and therefore invisible until the current side moved |
| `FAILCLOSED-NO-INVERSE-FOR-NEW-FORWARD-ONLY` | 40 | **yes** | 8 newly-ported forward-only projections; each verified `P->inv`-free in 9.8.1 by reading the file |
| `PROJ-NEW-PROJECTIONS-REGISTERED` | 180 | **yes** | 36 names × 5 probes, `Unknown projection: <name>` → `OK` |
| `PROJ-MOD-STER-GS50-DOMAIN-REFUSED` | 5 | **yes** | `gs50` now resolves and refuses a probe 4,000 km outside the 50-state polynomial |
| `PROJ-OMERC-ALPHA-90-NO-LONGER-REJECTED` | 20 | **yes** | `+alpha=90` was rejected as `Obl 1`; upstream's azimuth branch validates nothing |
| `PROJ-OMERC-TWO-POINT-FORM-REWRITTEN` | 5 | **yes** | `fwd: Infinite longitude` on the `+lat_1`/`+lat_2` form |
| `PROJ-NZMG-NONCONVERGENCE-TYPED` | 11 | **yes** | `InvalidValueException` → `ConvergenceFailureException`; the input was never invalid |
| `NUM-PHI2-CONVERGES-ON-EXTREME-ELLIPSOIDS` | 6 | TBD | the Karney `phi2.cpp` rewrite converges where a 15-trip fixed point did not |
| `PROJ-INVERSE-CORRECTED-ROUND-TRIP-NOW-EXACT` | 48 | TBD | **`dimensions: [ix, iy, iz]`** — the forward is bit-identical, so no forward regression can hide |
| `PROJ-INVERSE-CORRECTED-ROUND-TRIP-NOW-EXACT-FWD-ALSO` | 45 | TBD | 9 keys whose forward also moved, each change named with its arithmetic |
| `PROJ-EQC-EQDC-SINU-REGISTRY-ROWS` | 67 | TBD | the registry/CSV consequence of the same three forward defects |
| `PARSE-UNITS-PRECEDES-TO-METER` | 17 | TBD | `init.cpp:691`'s short-circuit: `+units` shadows `+to_meter` entirely |
| `NUM-ELLIPSOID-SPHERE-IS-NORMAL-SPHERE` | 5 | **yes** | GRS80 authalic radius → PROJ's Normal Sphere, 1.848 ppm |
| `NUM-ELLIPSOID-AIRY-EXACT-INVERSE-FLATTENING` | 5 | **yes** | rounded `b` → exact `rf`, ~1 mm |
| `DATUM-LEGACY-CARTHAGE-ELLIPSOID` | 25 | **yes** | `clrk80` → `clrk80ign`, 55 mm of equatorial radius |
| `DATUM-LEGACY-OSGB36-PRECISION` | 10 | **yes** | four truncated Helmert terms restored; ~3 mm |

Eleven of the sixteen carry a pinned `expected_rows`, and all eleven are pinned because the count is
**structural** (keys × probes), not because the number looked stable. The five `TBD`s are all
data-driven counts in files two agents are still writing.

### The two things the gate caught that a human review would not have

**1. `FAILCLOSED-NO-INVERSE-FOR-FORWARD-ONLY`'s pin fired, and it was right.** It reported
`COUNT_MISMATCH expected_rows=90 but matched 85`. The missing key was `proj/lagrng`:
`LagrangeProjection` acquired a `projectInverse` this release, and **upstream agrees** —
9.8.1 `src/projections/lagrng.cpp:101-102` assigns `P->inv = lagrng_s_inverse` as well as `P->fwd`,
so the 1.4.3-era belief that Lagrange was forward-only was simply wrong. The name is removed from the
list and the pin is now 85 = 17 × 5. **Route this: `core`'s own
`NoInverseGateTest.baseProjectInverseRaisesForForwardOnlyProjections:96` asserts "Lagrange must
declare no inverse for this test to mean anything" and fails on the frozen tree.** Two streams
disagree about `lagrng` and upstream sides with the one that gave it an inverse. → fail-closed owner.

**2. `PROJ-MOD-STER-GS50-DOMAIN-REFUSED` was a `DEAD_RULE` on its first run** — it matched all five
rows and **declined all five**, reporting `inside changed (- -> F) and allow_inside_change is not
set`. The `inside` column moved from `-` ("the CRS could not be built, so `Projection.inside` was
never called") to a real `F`. That is part of the change, but a rule that does not say so is a rule
that has not read its own row set. This is `expect`-clauses-decline-rather-than-claim working exactly
as the section above describes, on a rule written by somebody who had read that section.

### `FAILCLOSED-POLE-OVERSHOOT-REJECTED` is deleted, as its own reason predicted

It said: *"If the wag2 inverse is later fixed so that it stops overshooting, this rule becomes a
`DEAD_RULE` and the gate will say so, which is the correct outcome."* The gate said so. Wagner II's
forward **and** inverse have both been rewritten — probe 3's `fy` moves 9,265,677.82 → 6,912,649.14 m
and its `iy` now returns 60 exactly instead of 90.0015835811672 — so there is no overshoot left to
reject. Deleted rather than left as a `pending` stub, because `pending` means "not landed yet" and
this landed and was then superseded. The guard itself is unaffected and is still exercised by
`FAILCLOSED-UNCHECKED-ISE-REPLACED`'s 46 `exceeds PJ_EPS_LAT` rows.

### The previous pass's `UNEXPLAINED → UNCHANGED` result is holding

**897 of the 905 `REG` `+proj=geocent` rows are still bit-identical to 1.4.3** (181 keys × 5 probes;
the other 8 are the height-ordinate drift `NUM-KARNEY-LATITUDE-CORE` claims). That is worth
re-measuring at each triage rather than assuming: a return to baseline is the strongest outcome this
regime produces and it is also the easiest to silently lose.

**Nothing returned to baseline in *this* pass, because this pass wrote no code** — it is a triage,
not a fix. Every row it claimed had to be claimed: in each cluster the 1.4.3 value was wrong (a
`±π` longitude clamp, a 156° inverse error, a missing `rho0`, the authalic radius) and the correct
value is a different number, so there was no baseline to return to. The pattern was looked for
first, per this file's own instruction, and it was found only in the negative.

Also re-verified, because the earlier rule was written specifically to make it impossible:
**`EPSG:2065`, `EPSG:5514` and `EPSG:27200` are not claimed by any forward-only rule.** All three are
`OK → OK` numeric rows in the `krovak`/`tmerc` buckets, exactly as they should be.

### What is left, and who owns it

| | rows | |
|---|---|---|
| `tmerc` + `utm` numeric, `OK → OK` | **16,627** | transverse-Mercator stream (live) |
| the reserved `Extended Transverse Mercator ... outside the projection domain` cluster | **332** | reserved, do not rule |
| `IllegalStateException → OK` | **162** | datum stream — mechanism theirs to state |
| `lcc`, `datum=NAD27` | **485** | see the NAD27 finding below |
| `cass` | **189** | Cassini owner |
| `omerc` | **97** | omerc owner |
| `poly` | **55** | Polyconic owner |
| four new ellipsoids | **20** | **suspected-caused-by-the-user; deliberately not ruled, see below** |
| everything else | ~143 | itemised below |

The `OK → ProjectionException` population is now **333 rows, of which 332 are exactly the reserved
ETM cluster** — the count in the section above was "~332" and it is 332. The 478-row
`inv: LongLat: invalid latitude` population that section describes is **gone**. The one non-ETM row
is new and unattributed: `inv: generic 2D inverse did not converge in 15 iterations for
(-3.08158101643967, ...)`.

The residual exception transitions are now only five shapes, down from twelve:
**332 + 1** `OK → ProjectionException`; **162** `IllegalStateException → OK`; **26**
`InvalidValueException → OK` (20 new ellipsoids + 6 `inv: Infinite longitude` on `epsg:5472`,
`esri:102766`, `esri:65061`, `esri:65161`, `nad27:5400` and `SYN proj/poly`, all Polyconic); **5**
`world:madagascar`, which this file already says must not be claimed; and **1**
`proj4-epsg.csv:02092`, `ProjectionException → ProjectionException` with the same message `I` on both
sides and `fx`/`fy` moving 10.3 m and 7,552 m.

#### The 1,163-row `proj4-epsg.csv` re-pin: its mechanism independently corroborated

The reserved re-pin is still unapplied, so its rows are still moving. This pass measured its
predicate rather than its row list, because the section above sets that as the standard: of the
**1,280** unexplained `proj4-epsg.csv` rows, **1,145 probe ≥ 12.5° from their own central meridian**
(1,099 `tmerc`, 27 `cass`, 8 `omerc`, 5 `poly`, 4 `lcc`, 2 `merc`), 132 probe inside 12.5°, and 3
have no resolvable `lon_0`. 1,145 against a claimed 1,163 is agreement, from a completely different
direction, on a cluster nobody has re-pinned yet.

#### The largest cluster this pass understood and deliberately did not rule: `datum=NAD27`

**1,603 `REG` rows on 205 registry defs carrying `datum=NAD27` now receive the NADCON grid shift.**
Magnitudes are 3.7 m to 378 m and the shape is unmistakable: `epsg:4267` probe 0 moves `fx`
−100.0 → −99.99949894 (the baseline was the input **echoed back**, i.e. no shift at all), and
`epsg:26741` probe 0 moves `fx` 2,000,000.0000000012 → 2,000,345.2157 — exactly the false easting in
1.4.3, 345 US survey feet (105 m) off it now.

The mechanism is the confirmed defect this file already documents under "Determinism and known
couplings": `parser/Proj4Parser.java` used to call `Datum.NAD27.setGrids(null)` on the **shared
static singleton** while parsing any `+datum=NAD27` definition that carried no `+nadgrids` token, so
the first NAD27 CRS parsed destroyed the grid list process-wide — including its own — and every
NAD27 row in the 1.4.3 baseline is unshifted. `Proj4Parser.java:95-97` now says so in a comment and
copies into a `DatumParameters` instead. This is a fix, and it matches PROJ, whose `+datum=NAD27`
is `nadgrids=@conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat`.

**It is not ruled, and the reason is a gap in this module rather than a judgement about the change:
`rules.yaml` has no predicate for a parameter's VALUE.** `params_present` matches parameter *keys*,
`golden-index.tsv`'s fifth column stores key names only, and there is no `datum:` match clause — so
the only way to scope a rule to "the 205 defs whose datum is NAD27" is to enumerate 205 keys, which
would also sweep in the 914 `tmerc` and 152 `utm` rows belonging to a live stream. Enumerating 205
keys to claim 1,603 rows across two streams' territory is exactly the shape of rule this file exists
to prevent, so the honest record is an `UNEXPLAINED` row and this paragraph. → parser/datum owner,
and **whoever next changes `GoldenGenerator` should consider adding a `datum` column to
`golden-index.tsv` and a `datums:` match clause**, which would make this a five-line rule.

Two supporting measurements, so the cluster is bounded rather than merely described: of the 492
unexplained `lcc` rows, **485 are `datum=NAD27` and exactly 2 are not** (the other 5 are now claimed
by `DATUM-LEGACY-CARTHAGE-ELLIPSOID`). And the 15 `longlat`/`datum=NAD27` rows move by 1.7e-4 to
7.2e-4 **degrees**, i.e. 19–80 m — a pure datum shift with no projection in the path at all, which
is the cleanest possible demonstration that this is the datum and not a kernel.

#### Left unexplained on purpose: the four new ellipsoids

**20 rows, `SYN ellps/{GSK2011, PZ90, clrk80ign, danish}`, `Unknown ellipsoid: <name>` → `OK`.**
These are unambiguously intended — 9.8.1's `src/ellps.cpp` ships all four — and a rule would be two
minutes' work. It is deliberately not written, because the same four entries added to
`Ellipsoid.ellipsoids` are the suspected cause of four failing `core` tests (`ProjJsonTest` ×2,
`Wkt1ReaderTest`, `Wkt2ReaderTest`) on an `+ellps=<name>` versus `+a=/+b=` emission change:
`io/wkt/WktNames.java:276` matches ellipsoids **numerically** against that array, so a fifth entry
with the same axes changes which name is emitted. The open decision is whether to re-pin those tests
or revert the array, and if it is reverted a rule here becomes a `DEAD_RULE` within the hour.

The golden shape is *not* the same as the test shape — these rows are the **parse** side (a
definition that used to be rejected now builds) and the tests are the **emission** side (`toString`
picking a name) — but they have one cause, and one cause with an undecided fate should not acquire a
rule in a file whose rules are promises. → the author of the `Ellipsoid.ellipsoids` change.

#### `probes.tsv` was not touched, and here is why it looked as though it should be

This pass was handed a report that "`proj/lsat`'s 5 probes were silently wrong in
`golden/probes.tsv`", with `ix = 136.758446215` recorded at three different probes and latitudes of
−56°/−61°/−83° for inputs at +45°/+30°/+30°. Every one of those numbers is real and is now fixed —
but **they are `baseline/1.4.3/golden.tsv`'s output columns, not `probes.tsv`'s input columns.**
`probes.tsv` had `proj/lsat` at the same canonical synthetic points as every other `proj/<name>` key
and was correct throughout; what was wrong was 1.4.3's answer, because
`LandsatProjection.projectInverse` was entirely inside a block comment and
`Projection.inverseProjectRadians` clamped longitude to ±π where 9.8.1 wraps with `adjlon`. The
three identical `ix` values are the clamp, which is the tell — a real coordinate does not repeat
itself at three different probes.

The distinction matters enough to record: editing `probes.tsv` would have moved every probe point,
invalidated the entire baseline, and destroyed the evidence for the very fix being recorded. The
five rows are claimed by `PROJ-INVERSE-CORRECTED-ROUND-TRIP-NOW-EXACT`, whose
`dimensions: [ix, iy, iz]` clause proves the forward never moved.

---

## Rebaseline-at-release workflow

The baseline is pinned to a **released** version, never to a working tree. At each release:

1. Get the gate green against the old baseline: every remaining change claimed by a rule with a pinned
   `expected_rows`. **This is the release gate.** A red golden gate at release means there is a
   behavioural change nobody has described.
2. Publish the release (say `1.5.0`).
3. `mvn -B $M2 -Pgolden,golden-baseline -pl golden verify -Dgolden.regenerate=true`
   with `-Dgolden.proj4j.version=1.5.0 -Dgolden.baseline=1.5.0`, writing `baseline/1.5.0/`.
4. Delete every rule whose change is now *in* the new baseline. They are all `DEAD_RULE`s by
   construction after a rebaseline — that is the mechanism that clears the file.
5. Set `<golden.baseline>1.5.0</golden.baseline>` in `golden/pom.xml`, and delete `baseline/1.4.3/`
   in the same commit.

Keep at most two baselines. A directory per historical release is a museum, and git already has them.

Regenerating `probes.tsv`/`pairs.tsv` is a **separate** and much rarer act, and must be committed
together with a freshly generated baseline (see [Commands](#commands)).

---

## Policy: gie always wins

**This regime has no opinion about correctness and must never acquire one.**

* A rule in `rules.yaml` is **never** evidence that an output is right. It records that a change was
  *expected*, by a named person, for a stated reason, with an exact count and an expiry date.
* **A rule must never be written to make a gie failure go away.** If gie says we diverge from PROJ
  9.8.1 and a golden rule says the change was intended, gie is the one that decides whether to ship.
  The golden rule's only job was to prove the change was not an accident.
* Conversely, a green gie run does **not** license an unexplained golden row. gie covers ~8,017
  assertions; the `REG` section alone is 45,065 rows over CRS the corpus never mentions. "gie is green"
  is not an answer to "why did 12,000 rows move".
* Tolerances belong to gie. There is no epsilon anywhere in this comparison: a 1-ULP move is a change.
  If a 1-ULP move is acceptable, say so in a rule with a magnitude band — do not weaken the comparison.

---

## Determinism and known couplings

* **Generation order is load-bearing and fixed.** `parser/Proj4Parser.java:53` mutates process-global
  static `Datum` singletons as a side effect of parsing, so the output depends on the order CRS are
  created. The order is the golden total order, and both sides of a comparison walk it identically.
  Parsed CRS are cached per run (`Proj4FileReader` re-scans the 888 KB `epsg` resource on *every* call),
  which changes how many times each idempotent global write happens but not the order in which distinct
  writes first occur.
* **Locale, charset and timezone are pinned via `argLine`, not `systemPropertyVariables`.**
  `Locale.getDefault()` and the default charset are initialised during JVM startup, before surefire can
  set a system property inside the fork — setting them that way looks right and does nothing. It matters
  here: `Proj4FileReader.java:41` calls `toLowerCase()` with no `Locale`, so under `tr_TR` "ESRI"
  becomes "esri" with a dotless ı and **all 2,954 ESRI codes become unresolvable**, which would show up
  as 2,954 spurious `REMOVED` rows.
* **The `CSV` section's key set floats with the working tree.** Those files are `core`'s test resources
  and `core` publishes no test jar, so they are read from `core/src/test/resources` rather than the
  classpath. A change to them appears as `ADDED`/`REMOVED` rows, which a rule can declare. At the time
  this baseline was generated, `PROJ4_SPCS_ESRI_nad83.csv` and `TestData.csv` had **already been deleted**
  by the test-debt stream, so their rows are legitimately absent from `baseline/1.4.3` — not missing.
  The remaining three files give 4,770 rows: `proj4-epsg.csv` 4,280 · `PROJ4_SPCS_nad27.csv` 265 ·
  `PROJ4_SPCS_EPSG_nad83.csv` 225 (220 live plus the **5 `#`-commented `ESRI:102631` `omerc` rows**,
  which are included on purpose — hiding a known-bad case behind `#` is how it stops being tracked).
* **The registry dictionaries in `proj4j-epsg` 1.4.3 are byte-identical to the working tree's**, verified
  by `cmp` on all five. So `baseline/1.4.3` and a current run enumerate the same 9,013 `REG` keys and
  the diff is purely about code.
* `NO_PROBE` is how a def added to the dictionaries after the last probe regeneration announces itself,
  rather than silently acquiring a made-up probe.

---

## CI wiring

> **DE-SCOPED 2026-08-05.** The job still exists at `.github/workflows/golden.yaml` and still fails
> on the real backlog when run, but it no longer triggers on push or pull_request -- only on a weekly
> schedule and `workflow_dispatch`. The decision and what it costs are recorded in that file's header.
> Re-enable the triggers once the backlog is triaged; do not soften the job instead.
>
> Previously: **DONE, 2026-08-01. The job exists.** It was blocking, it is red
> today on the 2,291-row backlog, and that is the intended state. Read
> `.github/workflows/README.md` for the surrounding context; what follows is kept because the
> reasoning still applies and because the snippet below is *not* what shipped — three flags had to
> be added and one had to be left out, each for a reason worth not rediscovering.
>
> **What shipped differs from the snippet below in four ways:**
>
> 1. **`-Dtest='org.locationtech.proj4j.golden.*Test'`.** `-am` also builds `core`, whose suite has
>    one expected failure (`MetaCRSTest`), so the reactor stops before `golden` runs. The obvious
>    workaround is `-Dmaven.test.failure.ignore=true` — which is what the local diagnostic command
>    in [Commands](#commands) uses, deliberately, because there the goal is to read the report. **In
>    CI that flag would also ignore the gate's own failure and turn the job green while the gate is
>    red.** Narrowing gets both properties.
> 2. **`-Dsurefire.failIfNoSpecifiedTests=false`.** Required once `-Dtest=` is in play, and it looks
>    backwards. `-am` builds `proj4j-epsg`, which has no test sources at all, so the strict setting
>    fails *there* and `golden` never runs. Same trap `determinism.yaml` documents.
> 3. **A non-vacuity guard step.** `GoldenMasterTest` opens with `Assume.assumeFalse(golden.skip)`,
>    so a missing or ineffective `-Pgolden` makes surefire report it **SKIPPED — and exit 0**. This
>    was not a hypothetical: running the shipped command with `-Dgolden.skip=true` produces
>    `BUILD SUCCESS` with `Tests run: 58, Skipped: 1`. Skips are never passes. The guard asserts the
>    gate ran, was not skipped, generated a table with the same row count as the baseline it is
>    diffed against, and that nothing else in the module was skipped either.
> 4. **No `cache: maven` on `setup-java`.** `actions/cache` is used explicitly instead, so the
>    load-bearing "scrub `~/.m2/repository/org/locationtech/proj4j` last" step from `ci.yaml` still
>    applies. Without it a locally-built snapshot is baked into the cache and a later run can
>    "pass" against stale jars.

The original snippet, kept for the record:

```yaml
  golden:
    name: Golden master (behavioural regression)
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - name: Golden master
        run: >
          mvn -B -ntp -Dmaven.javadoc.skip=true
          -Pgolden -pl golden -am verify
      - name: Upload the report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: golden-report
          path: |
            golden/target/golden/golden-report.tsv
            golden/target/golden/golden-summary.txt
          if-no-files-found: error
```

`if: always()` on the upload is the point of the job: when it fails, the artefact is the answer.

Two notes for whoever wires this in:

* The plan's determinism regime — a matrix over `ubuntu-latest` (x86-64) and `ubuntu-24.04-arm`
  (AArch64) asserting a golden table of raw-bit results — is a *different* regime from this one, but it
  can reuse this module's table verbatim: run the same command on both legs and require
  `golden.tsv` to be byte-identical between them. Because every float is a hex double, byte-identity
  *is* bit-identity, which is precisely what the `StrictMath` policy claims and what the ARM leg exists
  to prove. That is one extra job and no new code.
* Do **not** make this job `continue-on-error` to get past the current 2,729 unexplained rows. Use
  `status: pending` rules, which are visible in the diff and self-correcting; a `continue-on-error` job
  is neither.
