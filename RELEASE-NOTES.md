# neoProj4J 2.0.0 release notes

Released 2026-08-06 to Maven Central as `io.github.emilevictor.neoproj4j:neoproj4j:2.0.0`, forked from
LocationTech Proj4J 1.4.3. Figures are measured unless labelled otherwise; where a number is an
estimate or is still pending it says so, because on this project laundering an estimate into a fact
has cost real rework.

**2.0.0 is the engine flip.** The corrected numerical core, the corrected defaults and fail-closed
error semantics are the behaviour of the existing API, not an opt-in alongside it. The new
`org.locationtech.proj4j.api` facade is additive and costs you nothing, but the numbers underneath the
old API have moved. Upgrading means reading this document.

An earlier plan staged this work as an additive 1.5.0 followed by a behaviour-changing 2.0.0. That
split was abandoned and everything shipped in 2.0.0; there is no 1.5.0 and there will not be one. The
groupId changed too, so this is not a version bump of `org.locationtech.proj4j:proj4j` — nothing
upgrades into it by accident, which is the protection the two-release split was there to provide.

If you only read one section, read [Compatibility](#compatibility-what-moves-and-by-how-much).

**Where the numbers come from.** Every figure below was measured locally, most of them on a frozen
snapshot with an A/B against an otherwise identical tree. The CI workflow files are committed, but
**nothing here should be read as a green CI run** — see
[The gates](#the-gates-what-is-enforced-and-what-is-red).

---

## Compatibility: what moves, and by how much

Every row is **measured**. The magnitude is the point — "improved accuracy" is not a release note, it
is a way of not telling you that your output changed by four metres.

The first six items **change the answer for existing callers**, so they lead. Everything after them is
a correction that moves values without changing the shape of the API.

### 1. An out-of-grid `+nadgrids` point is now refused instead of echoed back

`Grid.shift`'s no-table `else` branch returned the **input coordinate unchanged while reporting
success**. It now raises `CrsTransformException` with `ErrorCause.COORDINATE_OUTSIDE_GRID` — the cause
its own comment already named.

| measure | value |
|---|---:|
| golden-master rows that change | **1,995** — `REG` 1,673 · `CSV` 148 · `PAIR` 144 · `SYN` 30 |
| of those, reported `OK` in 1.4.3 | **1,949** |
| the remaining 46 | 34 `IllegalStateException`, 8 `UnsupportedParameterException`, 2 `InvalidValueException`, 2 `NumberFormatException` |
| of the 1,995, **bit-identical to 1.4.3** beforehand | **202** |

**Read the 202 twice.** For those rows the fail-open was so faithful that a behaviour-diffing gate saw
no change at all — 1.4.3 and the fixed tree produced the same bytes, because the defect was to return
the input and the input does not change. A defect that has always been present produces nothing to
diff. That is the sharpest argument on record that a change-detecting gate cannot be the only net.

The change is not merely "an exception where a number used to be": the number was **wrong**. The 40
witness rows that motivated the fix all probe latitude exactly `40.000000` — the southern edge of
`ntv1_can.dat`, the only NAD27 grid `neoproj4j-epsg` ships. The forward finds the point inside the box
and shifts it *south* of 40°N, out of the box; the `else` branch then returned it unchanged, so the
inverse leg never ran. `epsg:26721` probe 2 moved `fx` by −92.756 m and `ix` by +0.001090° ≈ 93.1 m —
the residue *equals* the forward shift, which is what proves the inverse was not running rather than
failing to converge.

**These points do not round-trip after the fix, and they must not.** PROJ 9.8.1 does not round-trip
them either — verified with `cct`, not inferred: `-I +proj=hgridshift +grids=ntv1_can.dat` at
(−49.928932188, 40.0) lands at 39.9999534467, and the forward on *that* point answers
`TRANSFORMATION ERROR (Coordinate to transform falls outside grid)`.

#### The layer nuance, and where proj4j is now stricter than PROJ

**Quote the layer with the claim.** Verified with `PROJ_DEBUG=2`:

- At the **operator** level (`+proj=hgridshift`) PROJ 9.8.1 **errors** — that is the measurement above.
- At the **CRS** level *with `proj.db`*, `cs2cs +datum=NAD27` at an out-of-area point selects
  **"Ballpark geographic offset"**: a **declared** no-op, not a silent one.

proj4j's legacy path has no such operation factory and **is** the operator path, so erroring is
faithful to the layer proj4j occupies. Both statements are true at once.

The consequence is measurable. Cross-tabbed over **all 4,280** rows of `proj4-epsg.csv`, proj4j against
`cs2cs` 9.8.1 on the dictionary strings on both sides:

| | PROJ answers | PROJ refuses (`* * inf`) |
|---|---:|---:|
| **proj4j answers** | 3,869 | 0 |
| **proj4j throws** | **131** | 280 |

**Not one row where proj4j returns a coordinate and PROJ refuses.** The residual risk is the mirror
image: **131 rows where proj4j throws `COORDINATE_OUTSIDE_GRID` and PROJ 9.8.1 answers.** All 131 carry
`+datum=NAD27`, and all 131 are PROJ selecting the declared ballpark described above. That zero is a
measurement, not a blind instrument: short-circuiting the new `throw` in a scratch copy flips exactly
131 rows back to answering.

The **`@`-optional** wart is untouched, with a negative control proving the scope: 17 `datum=potsdam`
rows did **not** move, because `BETA2007.gsb` is absent, the grid list resolves *empty*, and an empty
list is correctly a no-op. *"The grid file is not there"* stays silent; only *"outside a grid that
loaded"* errors.

**What to do about it** depends on an unresolved packaging question — see
[Pending, not done](#pending-not-done-two-changes-in-flight).

### 2. `ErrorCause` for an unreadable or missing grid changed — update any code matching on it

If you match on `ErrorCause`, this is a source-compatible but **behaviour-visible** break.

| | was | is |
|---|---|---|
| a `+grids=` / `+xy_grids=` / `+file=` value proj4j cannot find, read or parse | `ErrorCause.INVALID_PARAM_VALUE` (`Group.CRS`) | **`ErrorCause.MISSING_GRID`** (`Group.OPERATION`) |
| a malformed or unresolvable `+init=<file>:<section>` | folded into the same `INVALID_PARAM_VALUE` | **new `PipelineErrorCode.INVALID_INIT_KEY`**, errno **1027**, still surfacing `ErrorCause.INVALID_PARAM_VALUE` |

The reasoning is that the two are statements about different things. `PipelineErrorCode
.FILE_NOT_FOUND_OR_INVALID` (errno 1029) carries `rejectedByProj = false`, because **proj4j failing to
read a file is a statement about proj4j's readers, not about your definition** — the `+grids=` value
you wrote may be perfectly valid and simply unreadable here. Reporting that as an invalid parameter
turned a capability gap into apparent conformance. An unresolvable `+init=` key, by contrast, really is
a bad *parameter value*, and upstream agrees: `get_init_string` (`9.8.1:src/init.cpp:105,119,134`) sets
`PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE` — **1027, not 1029** — for all three of its failure paths.

`INVALID_INIT_KEY` is kept distinct from `ILLEGAL_ARG_VALUE` so a caller can tell an unresolvable init
key from a bad `+order` or unit id without parsing the message, even though both report the same errno
and the same `ErrorCause`.

### 3. `tmerc` defaults to Poder/Engsager

| change | movement | escape hatch |
|---|---|---|
| **`tmerc` default algorithm** → Poder/Engsager | **0.83 mm at 6° from the central meridian; 4 m at 20°; kilometres beyond 45°** | `+approx`, or `+algo=evenden_snyder`. Spheres keep Evenden/Snyder automatically — no flag needed. |

**This is the widest-reaching numerical change in the release.** It claims **14,038** golden-master rows
on its own — nearly every projected CRS in the corpus moves by a fraction of a millimetre.

It is small in the zone a Transverse Mercator is *supposed* to be used in and unbounded outside it,
which means the users who see metres are the ones already outside the intended domain, and who are
therefore least likely to be watching. `+approx` reproduces 1.4.3.

Two independent corroborations that this is the algorithm change and not something hiding behind it:

- The rule was pinned **predictively** rather than by listing rows. Bucketing every `tmerc` probe by
  distance from *its own* `lon_0` gives a median movement of **2.53e-4 m at 5–10°**, against **37 m at
  0–5°** and **120 m at 60–65°**. The latter two are impossible for series truncation — they are the
  NADCON shift — and the band `1e-6 .. 1e-2 m` separates the two populations completely. Per-column
  maxima across the whole set: `fx` 3.5 mm, `ix` 1.2e-6°.
- `MetaCRSTest`'s reference file (`proj4-epsg.csv`) says in its own header that it was *"auto-generated
  from proj.4 epsg database"* — **its series is what 9.8.1 now spells `+approx`.** Canonical row
  `4326→2000`: the file's `9413505.328467` is `cs2cs 9.8.1 +approx` **exactly**, and the new
  `9523653.022922916` is `cs2cs 9.8.1` **exactly**.

### 4. The auxiliary-latitude core is PROJ 9.4–9.6's, and it moves `laea` / `aea` / `cea`

`9.8.1:src/latitudes.cpp` plus the rewritten `mlfn.cpp` / `phi2.cpp` / `tsfn.cpp` are now wired into
`tmerc poly laea cea aea etmerc stere`.

**Measured movement: 19,336 golden-master rows, every one of them below 1 µm** (the rule's magnitude
band is `0 .. 1e-6` in raw column units, and the measured distribution is bimodal with an empty valley
between 1e-5 and 1e-3, so the band separates this change from everything else cleanly).

The *error being removed* is larger than the movement, because the old helpers were wrong in a way that
partially cancelled downstream. Measured against 9.8.1 on GRS80:

| helper | 1.4.3 | now | gie tolerance class |
|---|---|---|---|
| `authlat` (authalic latitude) | **1.58 mm at latitude 20.8°** | 0.7 nm | 0.1 mm |
| `mlfn` | 4,920 nm at latitude 72.6° | < 1 nm | 50 nm |
| `phi2` | 4,145 nm at latitude 2.8° | 2.1 nm | 50 nm |
| `tsfn` | `0.9999999999999999` at φ=0 | exactly `1.0` | **0 m** |

`authlat` is the one that matters for this item: 16× its own tolerance class, and it is what moves
`laea`, `aea`, `cea`, `eqearth` and `nzmg`. There is no escape hatch, because the old value was simply
wrong.

> **This rule is still marked provisional in the golden gate.** It is pinned at 19,336 so that a change
> to its size becomes an event rather than a silence, but ~19,000 rows of sub-micron drift is the
> easiest place in the suite for an unrelated change to hide, and the numerical-core owner is expected
> to replace it with a per-section band.

### 5. A 2D datum shift no longer invents a height

If you call the two-argument `ProjCoordinate` constructor — i.e. you have no Z — a datum shift used to
hand you back a fabricated one.

`EPSG:4326 → EPSG:27700` at (−2.0, 53.0):

| input z | before | after |
|---|---|---|
| `NaN` (2-arg constructor), single point | **`−49.84606796130538`** | **`NaN`** |
| `NaN`, bulk `transform3D` | **`−49.84606796130538`** | **`NaN`** |
| explicit `0.0` | `−49.84606796130538` | **unchanged** |
| explicit `100.0` | `50.15598100144416` | **unchanged** |

**x and y are bit-identical in every case.** If you pass an explicit height — including an explicit
`0.0` — nothing about your output changes. Only the "I never supplied a Z" case changes, and it changes
from a plausible-looking ellipsoidal height to `NaN`, which is what "you did not give me one" means.

If you were reading `.z` off a 2D transform and treating it as data, it was never data.

### 6. Axis order — read this even if nothing else applies to you

**`AxisOrderPolicy.LEGACY` is the default. It means longitude-first, which is exactly 1.4.3.** Nothing
changes unless you opt in.

**Opting in to authority order is a silent breaking change, and it is invisible near (0, 0).** `EPSG:4326`
is officially latitude-first. If you switch to `AxisOrderPolicy.AUTHORITY`, every call site that passes
`(lon, lat)` starts passing `(lat, lon)`, and:

- near the origin — the Gulf of Guinea — the two are numerically similar and the output looks plausible;
- at San Francisco, `(-122.4, 37.8)` under `AUTHORITY` is an invalid latitude and **throws**, which is
  the good case;
- at, say, `(45, 30)` both orders are valid coordinates and you get a **confidently wrong answer**
  hundreds of kilometres away, with no error.

So: **do not flip this flag globally and run your tests near the equator.** Verified in both
directions — `EPSG:4326 → EPSG:3857` at `(-122.4, 37.8)` under `LEGACY` and `(37.8, -122.4)` under
`AUTHORITY` agree **bit-for-bit**, and feeding `AUTHORITY` the legacy order at San Francisco throws
`INVALID_COORDINATE` rather than guessing.

---

### Other projection, ellipsoid and datum changes

| change | movement | escape hatch |
|---|---|---|
| **`Ellipsoid.SPHERE` corrected** | **0.41 m at 222 km** | pass an explicit `+R=` if you depended on the old value |
| **`Ellipsoid.AIRY` corrected** | **0.76 mm** | none |
| **`carthage` datum** — was bound to the wrong ellipsoid | **20.45 mm N** at EPSG:22391 Tunis | none |
| **`OSGB36` datum** — now `9.8.1:src/datums.cpp`'s values rather than EPSG:1314's rounded ones | **3.085 mm E**, ~3.5 mm max across GB | pass an explicit `+towgs84=` |
| **`potsdam` datum** now declares its grid | changes results where `BETA2007.gsb` is present | omit the grid to keep the Helmert-only path |
| **`MercatorProjection` now reads `+lat_ts`** | **1.3 million metres** on `EPSG:3388` | none — Mercator variant B did not previously exist |
| **`AzimuthalProjection` defaults `lat_0`/`lon_0` to 0/0**, matching PROJ, not 45°/45° | every azimuthal proj-string omitting them was silently oblique | state `+lat_0`/`+lon_0` explicitly |
| **`CoordinateReferenceSystem.createGeographic()` no longer drops `+pm`** | **187,739 m** of easting, across all 94 `+pm=` definitions | none |
| **`ObliqueMercatorProjection`**: `+alpha` without `+gamma` got zero rotation; `u_0` used `cos(Gamma)` where upstream uses `cos(alpha)` | 215,218 m E / 303,073 m N, and 2,532 m E on RSO Borneo | none |

Notes on two of these, because the size is misleading in both directions:

- **`Ellipsoid.SPHERE` was the GRS80 *authalic* radius**, not a normal sphere. It is now PROJ's Normal
  Sphere. If you were using `Ellipsoid.SPHERE` as a stand-in for "roughly the Earth" the change is
  immaterial; if you were using it to reproduce a specific pipeline, it is 0.41 m.
- **`Ellipsoid.AIRY` had a rounded `b`** where the definition gives an exact inverse flattening. 0.76 mm
  is below most tolerances and above a few.

### Errors where there used to be values

**This is the second most likely thing to break your build, and it is deliberate.** A failure expressed
as a plausible coordinate is worse than an exception, because nothing downstream can tell it apart from
an answer.

| change | what it means for you |
|---|---|
| **`aasin`/`aacos` throw** instead of clamping silently | an ill-conditioned intermediate now surfaces instead of producing a finite wrong answer |
| **Domain guard** on input coordinates | matches PROJ: rejects `\|λ\| > 10` **radians** (≈ ±573°) and *wraps* everything inside that; latitude within `1e-12` rad of a pole is *clamped*, not rejected. **A `[-180, 180]` rejection would be stricter than PROJ and is not what this does** — passing 200° still works. |
| **Forward-only projections stopped returning the input as if it were lon/lat.** They throw. | **13 projections × 5 probes = 65 golden rows** today: `airy august boggs denoy larr lask nicol rpoly tcc wag7 adams_hemi adams_ws1 guyou`. A separate rule covers 40 more rows in projections newly added this release. |

**The measurement that made the case, kept because it is what proves this was a fix rather than a new
restriction.** When the rule was first written it claimed **90 rows, of which 75 had been reported `OK`
in 1.4.3.** Those 75 were silently wrong answers and are now errors. The other 15 had been
`InvalidValueException: Unknown projection` — never wrong, merely absent.

The row set is now **65, not 90, and the shrinkage is itself the good news**: five names left the list —
`lagrng`, `aitoff`, `hammer`, `nsper`, `wintri` — every one of them because **upstream turned out to
have an inverse that 1.4.3 lacked** (`aitoff.cpp:200-201`, which is the shared setup for both `aitoff`
and `wintri`; `hammer.cpp:86-87`; `nsper.cpp:167-168`; `lagrng.cpp:101-102`). They are now invertible
here too.

> **The gate does *not* key on `hasInverse()`**, and that detail matters if you were relying on it.
> `hasInverse()` is a hand-maintained declaration that was **read nowhere in `core/src/main` in
> 1.4.3**, and it is wrong in both directions: `KrovakProjection` and `NewZealandMapGridProjection`
> implement `projectInverse` without declaring it, while `LandsatProjection` declares it while
> overriding nothing. A `hasInverse()`-keyed gate rejected **EPSG:2065, EPSG:5514 and EPSG:27200 —
> three working CRS**. The shipped gate interrogates the class hierarchy for a declared
> `projectInverse(double, double, ProjCoordinate)` instead, and those three still work.

If your code has `try { inverse } catch { }` or treats a finite result as success, that code was
relying on the old behaviour. Find out which before you upgrade: a call that used to return a
plausible-looking finite number now throws.

### Grid handling

| change | movement |
|---|---|
| **NTv1 reader**: data began at offset **192, not 176**, *and* the latitude/longitude shift components were **transposed** | **~13 m on every NTv1 shift ever computed.** 8 m E + 10 m N at Chicago |
| **NTv2 multi-subgrid**: "only 1 subfile supported" silently used subgrid 1 for the whole file, and interpolation read the captured **parent** table after descending into a child | a point in Alberta got **no shift at all** from `ntv2_0.gsb` while the transform reported success |
| **Grid-edge clamp in `nad_intr`**: `1e-11` → `1e-4` | the old value was **10⁷× too tight**; points PROJ shifts were returned unchanged |
| **Containment tolerance**: `1e-4` → `1e-5` (`REL_TOLERANCE_HGRIDSHIFT`) | proj4j accepted and **extrapolated** 2e-5° outside `conus`'s south edge where PROJ reports a transformation error |
| **Antimeridian extents** now handled | `us_noaa_alaska.tif` declares `west = -194°`, so its whole western half was unreachable |
| **Inverse grid shift** declared success when only *one* ordinate had converged (`&&` where PROJ tests the squared 2-norm), and on exhaustion returned the input unchanged | now throws `ConvergenceFailureException` (`ErrorCause.NUMERICAL_FAILURE`) |
| **NAD27 → NAD83 in CONUS** | **95.573 m at San Francisco.** Two independent causes, both fixed in code: a parser bug that destroyed the grid list on a static singleton, and the absence of the `conus` grid. **The second half is a packaging question that is still open — see [Pending, not done](#pending-not-done-two-changes-in-flight).** |

Neither NTv1 error alone, nor the pair together, moved a result far enough to look like a bug. That is
why it survived. **If you have stored coordinates computed through an NTv1 grid, they are wrong by
about 13 m**, and recomputing them is a data migration, not a library upgrade.

**One deliberate divergence from PROJ is kept**, and it is worth distinguishing from the fail-open in §1
above, because the distinction is exactly why one was fixed and the other was not. `Grid.shift` falls
through to the *next* grid when interpolation fails, where PROJ commits to the first containing grid and
reports outside-grid. The fall-through can only ever return a value that **some grid the caller listed**
actually produced; the `else` branch **invented** one. Reaching the fall-through requires `resX > 9·resY`,
so it is pinned by a purpose-built 10°×0.1° CTABLE V2 fixture plus a plain grid, each alone as its own
control.

---

## Conformance: 15.6 % → 93.5 %, and what the denominator excludes

| | |
|---|---|
| **gie corpus** | **7,378 / 7,895 genuine passes — 93.5 %** |
| **baseline (1.4.3-era harness)** | **1,066 / 6,845 — 15.6 %** |
| **GIGS** | **1,170 / 1,170 — 100 %**, all 20 files |
| complete files | **at least 29 of the 42** active corpus files are at 100 % |
| the rest | 515 failing · 2 skipped · 28 vacuous · 94 excluded (out of block) |

**The denominator is not the corpus size, and this is the honest part of the number.** The corpus holds
**7,923 assertions** across 42 active files — 6,962 `expect` plus 961 `roundtrip` — counted with a port
of gie's own lexer rather than with `grep`, because `grep '^expect'` models neither block boundaries nor
left-trimming. From that:

- **28 rows are *vacuous* `expect failure` rows and are excluded from both numerator and denominator.**
  A vacuous row is one where proj4j could not construct the operation *at all*: PROJ built it and
  rejected the coordinate, proj4j failed to build it, both "failed", and a naive harness scores a pass.
  **That is failure-to-implement counting as conformance**, and it is excluded rather than banked.
  7,923 − 28 = **7,895**.
- **2 skips are reported separately and are never passes.**
- **94 out-of-block lines** in `DHDN_ETRS89.gie` (which closes `</gie-strict>` at line 161 of 375) are
  reported as `94 excluded (out of block)`, not as "not run".

**What this means for reading the two percentages together.** The baseline denominator is 6,845, not
7,895, because far more rows were vacuous then — proj4j could not build the operation for most of the
`adams` family, `guyou`, `peirce_q` and `spilhaus`. So the ratio improved **and** the measured
population grew by 1,050 assertions. Both halves are progress; neither is visible in the percentage
alone. Under-counting is visible in the report; over-counting would not be, which is why the rule
resolves ambiguity to vacuous.

### Zero disagreements with PROJ 9.8.1 on the MetaCRS corpus

The strongest single piece of correctness evidence the project has produced. `MetaCRSTest`'s reference
file, `proj4-epsg.csv`, reports ~1,195 "regressions" against 1.4.3. Triaged row by row against
`cs2cs` 9.8.1 on the dictionary strings, with the target parameter strings verified byte-identical
between the two trees so that every one is a pure algorithm change and not a parse change:

| rows | cluster | vs `cs2cs` 9.8.1 |
|---:|---|---|
| 775 | `tmerc` | **agrees** |
| 280 | `tmerc` | **both refuse the point** (`cs2cs` prints `* * inf`) |
| 28 | `cass` | agrees |
| 24 | `tmerc` + `+datum=` | agrees |
| 22 | `tmerc`/`cass` + `NAD27`/`potsdam` | PROJ refuses, proj4j answers *(pre-existing; superseded by the fail-closed work in §1)* |
| 3 | `eqc` | agrees |
| **0** | — | **proj4j disagrees numerically** |

**Across all 4,280 rows of the corpus there is not one where proj4j and PROJ 9.8.1 both produce a
coordinate and the coordinates differ.** The reference file is stale, not the library — and regenerating
it is legitimate here precisely because `cs2cs` 9.8.1 is an independent oracle that agrees bit-for-bit.
Re-pinning it from proj4j's own output would make it circular and self-confirming forever, and is not
what will be done.

## The gates: what is enforced, and what is red

Five regimes now gate this work, and **one** of them is **red on purpose**. Reading a red gate as a
defect would be exactly backwards: they were all green once for the same reason a scan that cannot fail
always passes. *(This said "four regimes … two of them red"; `ci` is a fifth, and it went green when
`proj4-epsg.csv` was regenerated, so `golden` is the only intentional red left.)*

*Every figure below re-measured 2026-08-03 in the pinned container (Temurin 21.0.11 / aarch64).*

| gate | state | figure |
|---|---|---|
| **ci** | **green** | whole 7-module reactor, `BUILD SUCCESS` with javadoc, **2,320 tests / 0 failures / 4 skipped** in 223 report files (`core` 1,917 · `conformance` 345 · `db` 52 · `geoapi` 6) |
| **conformance** | **live, CI-wired, green** | baseline pair committed — `gie-expected-failures.tsv` (545 rows) and `gie-corpus-index.tsv` (**7,923 keys**). **7,441 / 7,900**, verdict `regressed 0, unexpected passes 0, new 0, disappeared 0` against the full index |
| **golden** | **live, blocking, RED** | **12,012 UNCHANGED · 41,418 CHANGED · 0 ADDED · 0 REMOVED · 39,127 INTENDED · 2,291 UNEXPLAINED** over 53,430 rows; **42 of 42** rules pinned with `expected_rows` (was 38, then 41) |
| **allocation** | **live, green** | **0 breaches**; **245 gated, 0 EXCLUDED**, 245 / 245 arms, Tier 2 green. Was `172 gated / 9 excluded / 181 arms`: `BulkTransformBenchmark` joined the gate (+64) and `crs-parse` rejoined Tier 1 (−9 exclusions) |
| **determinism** | **runs per leg, green** | **22** tests, 0 failures, 0 skips (was 15; the count is a floor, and the workflow now reports upward drift as a notice rather than failing) |
| **bench** | baseline captured 2026-08-02 | **171 per-benchmark ratchets, all enforced, none reported-only**; 25 rules; 8 CRS pairs × 20 operations pinned; 2 remaining `TBD`s are `targetBytesPerOp` policy cells on rules whose ratchet is a real number |

**Why golden being red is the intended state.** The gate exits non-zero on any `UNEXPLAINED` row: a row
whose behaviour changed and for which no rule in `golden/rules.yaml` claims responsibility with a stated
mechanism and a pinned row count. 2,291 rows are in that state, down from **18,168 → 3,304 → 2,291** over
two triage passes. Every one of them is a change somebody must *explain*, not a change somebody must
*undo*, and the largest blocks are known: the reserved `proj4-epsg.csv` re-pin, `tmerc` rows probed
thousands of kilometres outside their own zones where both series are meaningless, and a handful of
`cass` / `omerc` / `merc` clusters. Turning the gate green by relaxing it would discard the only
instrument that has caught a silent behaviour change on this project — twice.

**Two honesty notes about the instruments themselves**, because a gate that cannot fail is worse than no
gate:

- **`gc.alloc.rate.norm` does flake, and it was previously claimed not to.** Two independent 16-minute
  runs in separate JVMs agreed to within 0.0001 B/op on **170 of the 181 arms**. The exception was
  real: the 11 `CrsParseBenchmark` arms above 1 KB/op drifted run-to-run by up to **0.121 %**, enough
  for two of them to fail the 0.1 % gate on an *unmodified* tree. That was resolved first by marking
  the `crs-parse` rule's nine arms `tier1Gated: false` — measured and reported every run, but not
  blocking — rather than by widening `ALLOC_RELATIVE_SLACK`, which would have weakened all 181 arms to
  accommodate nine. **The exclusion was written with an exit condition and the exit condition was met:**
  `io/InitFileCache` removed the per-call dictionary re-scan that made the arm's allocation
  data-dependent, the numbers fell to 2,480 / 2,872 / 1,136 B/op, and `tier1Gated`,
  `exclusionArmCount` and `exclusionReason` were deleted together. **There are no exclusions today —
  `245 gated, 0 EXCLUDED`** — and `ALLOC_RELATIVE_SLACK` was never widened. The two arms that remain
  bimodal vary by exactly 56 bytes and **only downward** across 21 forks, and are pinned at their
  maximum, which is safe because the gate fails only on exceeding.
- **The workflow files are committed but no CI run backs any figure here.** Everything above was
  measured locally. Do not read a green badge into this document.

---

## Determinism: the guarantee, and its one exception

proj4j targets **bit-for-bit identical results across JVMs and CPU architectures**, because the
motivating consumer caches one `CoordinateTransform` and shares it across Spark executors that are not
guaranteed to be on the same hardware. Implemented by routing every transcendental through
`StrictMath` — whose results are *specified to a bit*, unlike `Math`, which is specified to 1–2 ulp and
which HotSpot substitutes per architecture.

**This is not only a reproducibility policy; it decides a conformance verdict.** At
`+proj=adams_ws2 +ellps=WGS84` and `(179.999, 0)` the map's conditioning amplifies a last-bit
difference in `sin` by ~3×10⁸:

```
Math.sin(lam/2)        x = 16686159.3838 m    misses a 1 mm bar by 27.8 mm
StrictMath.sin(lam/2)  x = 16686159.3563 m    hits it, 0.35 mm
exact (60 digits)      x = 16686159.3639 m    <- sits BETWEEN the two
```

**Neither function is more accurate.** What is being preserved is *fidelity* to the fdlibm-equivalent
`sin` that generated PROJ's expected values.

### What is now verified rather than asserted

Until this release the guarantee was a design intention. It is now a test — `StrictMathGoldenTableTest`,
a committed table of **54,265 raw-bit results across 19 functions** — run on five JDK and instruction-set
combinations:

| JDK | `os.arch` | `StrictMath.sin` implementation | result |
|---|---|---|---|
| Temurin 8.0.502 | `x86_64` | native, JNI into compiled fdlibm | 54,265 pass |
| Temurin 11.0.32 | `x86_64` | native, JNI into compiled fdlibm | 54,265 pass |
| Temurin 11.0.32 | `aarch64` | native, JNI into compiled fdlibm | 54,265 pass |
| Temurin 21.0.11 | `aarch64` | pure Java `FdLibm` | 54,265 pass |
| OpenJDK 26.0.2 | `aarch64` | pure Java `FdLibm` | 54,265 pass |

**271,325 `StrictMath` and 221,970 `FastStrictTrig` raw-bit comparisons, zero value mismatches**, across
two instruction sets and both `StrictMath` implementations — **JDK 21** rewrote `StrictMath.sin/cos/tan`
from JNI into pure Java, so 8, 11 and 17 exercise a genuinely different code path and a pass on 21 is not
evidence about them. The table above spans that boundary in both directions, which is what makes the
guarantee empirical rather than inferred. JDK 17 is on the *native* side, alongside 8 and 11
(`Modifier.isNative(StrictMath.sin)` is `true` on Corretto and Temurin 17.0.20, and
`java.lang.FdLibm$Sin` does not exist in a JDK 17 image).

That the assertion is not vacuous is established two ways. `util/FastStrictTrig` is an independent
~800-line transcription of the JDK's `FdLibm` kernels and it matches the same table — which cannot
happen if the table is meaningless. And `Math` demonstrably departs from it: on Temurin 21/AArch64,
`Math.sin` differs on 2.14% of probes and `Math.cos` on 1.89%.

### The exception, stated plainly

> **NaN sign and payload are architecture-dependent and are NOT covered by the bit-for-bit guarantee.
> Every finite result is. Signed zero is.**

Measured with the JDK held fixed at Temurin 11.0.32 and only the instruction set varied:

| expression | x86-64 | AArch64 |
|---|---|---|
| `Inf - Inf`, `Inf * 0.0`, `Inf / Inf`, `sqrt(-1)` | `0xfff8000000000000` | `0x7ff8000000000000` |
| `0.0 / 0.0`, `Double.NaN` | `0x7ff8000000000000` | `0x7ff8000000000000` |

x86-64's default NaN has the sign bit set; AArch64's does not, and the JLS specifies only that these
expressions yield *a* NaN. **This matters concretely**, because the fail-closed sentinel policy writes
`NaN` to every output ordinate of a failed point — so error rows are exactly the rows that cannot be
compared on raw bits across architectures. If you checksum or raw-bit-compare transform output,
normalise NaN first. `Double.isNaN` is unaffected; so is every tolerance comparison; so is the
`gie` metric, which maps NaN-on-both-sides to zero distance.

---

## Capability boundary — what proj4j does not do

Stated as a boundary rather than buried in a footnote, because the consumer for this work explicitly
values legibility over coverage, and because a release note that lists only capabilities is an
advertisement.

### Operator families that are not implemented

Each is a **refusal**, not a silent omission: proj4j reports `PROJECTION_NOT_IMPLEMENTED` rather than
producing something.

- **The DGGS group — `airocean`, `s2`, `isea`.** Verified absent from `Registry`. Together they account
  for **188 failing assertions in `builtins.gie`** (`airocean` 92, `s2` 56, `isea` 40) — the three
  largest single blocks left in the corpus, and all three are **declined on ratio**: they are
  discrete-global-grid systems whose implementation cost is disproportionate to any consumer need on
  record. If you need them, use PROJ.
- **`+proj=helmert` as a user-facing operator.** It exists only as the hidden static
  `+exact +convention=position_vector` helper the `cs2cs` emulation builds. Deliberately not exposed,
  because the user-facing operator additionally carries `convention=coordinate_frame`, `transpose` and
  seven time-dependent rates — **all of which appear in the corpus** — and shipping a subset would
  silently ignore a token PROJ acts on. Costs 3 assertions in `GDA.gie` and 1 in `4D-API_cs2cs-style.gie`.
- **`gridshift` (the unified operator) and `defmodel`.** Both need the GeoTIFF grid reader wired into
  the pipeline layer; the reader itself exists (below).
- **`+proj=deformation +grids=`** — the single-file three-channel Geodetic TIFF Grid form. The two-grid
  form (`+xy_grids=` / `+z_grids=`) works.
- **`nkg`** — 33 assertions. Unblocked in *data* terms but not in operators: the transformations are
  concatenations whose "method name" is itself a PROJ pipeline (`PROJ:PROJString`), needing
  `deformation` plus a transformed time dimension.
- **The time dimension is not transformed.** No `+proj=unitconvert +t_in`, no `+proj=set +t`. Note the
  distinction: `+t_epoch` / `+t_final` on `hgridshift` and `vgridshift` **are** honoured, so a
  time-*gated* grid shift behaves as upstream's does; it is time as a transformed *ordinate* that is
  absent.

Implemented and often assumed otherwise: `longlat` (and its three aliases), `geocent`, `unitconvert`,
`axisswap`, `cart`, `vgridshift`, `hgridshift`, `deformation` (two-grid), `tinshift`, `affine`, `push`,
`pop`, `set`, and every projection in `Registry`.

### Vertical and height support is thinly evidenced, and you should know how thinly

A **GTX vertical grid reader** and a **GeoTIFF grid reader** both ship (the latter verified bit-identical
to `cct` 9.8.1 to 12 decimals across 35 vendored fixtures, both subgrid hierarchies, all seven real US
grids, and the real `us_nga_egm96_15.tif` and `us_nga_egm08_25.tif`). But the *conformance evidence* for
heights is much weaker than the headline suggests, and the honest figures are:

- **Only 356 of 7,923 corpus assertions — 4.5 % — score a third ordinate at all** (225 with three
  numbers, 131 with four): **194 pass, 160 fail, 2 skip.** For the other 5,419 coordinate expects,
  `gie.cpp:1117` zeroes the third ordinate on both sides, so **z contributes exactly zero to the
  deviation regardless of what the operation writes.** That is upstream's masking, not proj4j's
  insulation.
- All 961 roundtrips carry z unmasked and 878 pass — but a roundtrip only asserts that z returns where
  it started, which **any pass-through satisfies trivially**.
- **For a height through the *datum* stage specifically, the corpus evidence is essentially none.**
  `BasicCoordinateTransform` is reachable from the whole corpus through exactly one route, and
  `crs_src`/`crs_dst` appear in only two files — **8 assertions, 6 of them with 3-D expects, and none
  passes** (4 fail, 2 skip).

So: treat 3-D and vertical transforms as **supported but lightly covered**, and test your own heights.
The invented-height fix in §5 above is covered by a dedicated unit test, not by the corpus.

### Database vintage and operation selection

- **The legacy path has no `proj.db`.** PROJ 9.x resolves `+datum=` and operation search through it;
  proj4j's legacy path resolves against the shipped PROJ.4-style dictionaries. Concretely:
  `+datum=OSGB36` in PROJ picks OSTN15, **1.784 m** from the legacy Helmert proj4j applies; `nzgd49` is
  **2.248 m**. This is a **data-vintage gap, not an arithmetic defect** — on the parameter strings each
  engine is actually given, the two agree.
- **A pure-Java, zero-dependency reader for a transcoded 9.8.1 database exists** (`neoproj4j-db`, Phase 1),
  but wiring operation *selection* through it is not complete, so the numbers above still describe the
  default path. When it does land it changes an answer that is currently correct-by-accident: the facade
  throws `BALLPARK_REJECTED` for `EPSG:4267 → EPSG:4269`, which is right without the database and
  **wrong with it** — the authority publishes **nine** transformations for that pair, from 0.15 m to
  2.0 m accuracy, and not one is ballpark.
- **The shipped EPSG dictionary is v9.2-era (2017), against PROJ 9.8.1's v12.029.** The dictionary
  carries no version stamp, so **`Proj.databaseVersion()` returns `Optional.empty()` rather than
  guessing a version.** The prose lives in `DatabaseInfo.vintageNote()`. This is the pattern throughout
  the new introspection API: it declines to answer rather than answer plausibly.
  - `availableGrids()` is probe-verified and `declaredGrids()` is separate, so a grid that is declared
    but unreachable is *reported* rather than omitted.
  - `axisOrder()` is paired with `isAxisOrderAuthoritative()`, so an inference is never presented as an
    authority statement.
  - `version()` is read from the JAR manifest and says `unknown (no jar manifest on this classpath)`
    from exploded classes, rather than a compiled-in constant that can lie.
  - `Crs.toWkt(WKT1_*)` **throws** rather than emitting lossy WKT1.
- **NADCON is deliberately not implemented, and will not be.** It is not a PROJ 9.8.1 format:
  `grids.cpp`'s `HorizontalShiftGridSet::open` dispatches on exactly four things — NTv1, CTABLE V2,
  NTv2 and TIFF — and the `us_noaa_nadcon5_*` grids are NADCON 5 data *in GeoTIFF*, read by the TIFF
  path. Writing a NADCON reader would be a divergence from the target revision, not parity with it.

---

## Pending, not done: two changes in flight

**Neither of the following is in this build.** They are listed here so that nobody reads their absence
as a decision, and so that no figure below is quoted before it exists.

> ### ⏳ PLACEHOLDER — `conus` in `neoproj4j-epsg`
>
> **Status: in flight, not landed.** Verified today: `epsg/src/main/resources/proj4/nad/` ships
> `ntv1_can.dat` and no other grid.
>
> `Datum.NAD27` requests `@conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat`. Only `ntv1_can.dat` ships, and its
> footprint is **40°N–84°N, 142°W–44°W** — Canada. So in a default deployment San Francisco (37.8°N) and
> Kansas (39.0°N) are *south* of every grid proj4j has, and with the §1 fix in place they now raise
> `COORDINATE_OUTSIDE_GRID` rather than silently returning the input. Everything in CONUS *north* of 40°N
> — Chicago 41.9, Boston 42.4, Minneapolis 45.0, Seattle 47.6 — is inside the box and is interpolated
> from a Canada-authoritative grid.
>
> `9.8.1:data/tests/conus` is **264,424 bytes** of CTABLE V2, a format `datum/CTABLEV2.java` already
> reads, so folding it in costs zero new parsing code. Today it is available only through the separate
> `neoproj4j-grids-us-legacy` artifact (**1,192,986 B** as a jar, `conus` + `alaska`, resources only) and,
> at *test* scope, to `core`.
>
> **This section will state the artifact contents and a measured size when the change lands. No
> `neoproj4j-epsg` size is quoted here on purpose** — the figure that stood in the project's own packaging
> notes was measured today as **7,603,235 B** against a long-recorded 2,518,313 B, a 3× error, and it is
> exactly the kind of number that goes straight into release material.

> ### ⏳ PLACEHOLDER — relaxing `ClasspathResourceResolver.isSafeName`
>
> **Status: in flight, not landed. No conformance figure is quoted for it, and none should be until it
> is measured on a quiesced tree.**
>
> The resolver rejects any resource name containing `/`. The corpus writes
> `+file=tests/tinshift_simplified_kkj_etrs.json`, `+grids=tests/us_noaa_nadcon5_*.tif` and
> `+xy_grids=tests/nkgrf03vel_realigned_xy_extract.ct2`; PROJ resolves these by appending the whole
> token to a search directory. So proj4j can currently reach **no** `tests/…` file, and roughly **100
> assertions** sit behind that one rule — concentrated in `gridshift`, `geotiff_grids`, `defmodel` and
> `tinshift`. **No GeoTIFF reader unlocks them on its own**; the reader is already done and verified.
>
> The guard is a security boundary — CRS strings are untrusted per-row input — so relaxing it is a
> deliberate decision, not a cleanup. The proposed change permits interior path segments while
> continuing to reject a leading `/` or `\`, spaces, and any `.` / `..` / empty segment.

---

## Java baseline: staying on 8, and the multi-release JAR is **not** needed

The plan of record was **Java 11 plus a multi-release JAR with `META-INF/versions/17`**, on the
reasoning that `StrictMath` on Java 8 is a JNI call per transcendental while JDK 17 rewrote it into pure
Java, and that this is what makes the determinism guarantee affordable. **That premise is false: the
pure-Java rewrite of `StrictMath.sin/cos/tan` is JDK 21, not 17.**

**Recommendation: keep `<release>8`, drop the MR-JAR.** The pom currently targets 8; no change is
proposed. The recommendation is unchanged by the correction above, but **one of its two original reasons
is not**, so both are restated here.

**The logical one, as originally written, does not survive.** It ran: *a multi-release JAR can only
change behaviour on a newer runtime — `META-INF/versions/17` is never read by a Java 8 or 11 JVM — but
JNI `StrictMath` is slow only on 8 and 11, so the MR-JAR was aimed at the only JDKs that do not have the
problem.* **JNI `StrictMath` is slow on 8, 11 *and 17*.** `META-INF/versions/17` would therefore have
been read by exactly one runtime that *does* have the problem: the MR-JAR was aimed **one release early
at the boundary**, not at JDKs lacking the problem. Worse, it would have been actively wrong there —
switching JDK 17 onto "plain `StrictMath`, it is pure Java here" means switching it onto the JNI path.
So the logical argument now cuts the same way for a different reason: the MR-JAR would have shipped a
slow path to the one JDK it reached.

**The measured reason is the one that decides it, and it is unaffected.** `util/FastStrictTrig` — a pure-Java,
allocation-free transcription of `FdLibm.Sin/Cos/Tan`, compiled to Java 8 bytecode, and verified
bit-identical to `StrictMath` on all five combinations above — is faster than `StrictMath` on *every*
JDK, by the largest margin precisely where the MR-JAR was supposed to help:

| JDK / arch | `StrictMath` | `FastStrictTrig` | `Math` | `FastStrictTrig` vs `StrictMath` |
|---|---|---|---|---|
| 8 / x86-64 (JNI) | 29.83 ns | **7.51 ns** | 30.63 ns | **3.97×** |
| 11 / x86-64 (JNI) | 10.13 ns | **7.03 ns** | 4.53 ns | 1.44× |
| 11 / aarch64 (JNI) | 18.31 ns | **3.80 ns** | 34.62 ns | **4.82×** |
| 21 / aarch64 (pure Java) | 6.56 ns | **3.71 ns** | 2.39 ns | 1.77× |

> **Measurement caveat, because these numbers will be quoted.** Not JMH: best-of-5 after 3 warm-up
> rounds, 8 M calls per measurement, one JVM per row, single-threaded, `sin` + `cos` over ±π. The
> **within-row ratios** are the trustworthy part. **Cross-row comparisons between architectures are
> confounded** — the x86-64 rows ran under Rosetta 2 on Apple silicon, so their absolute times are not
> native x86-64 times. Nothing in the verdict depends on a cross-row comparison.

So the determinism tax is **~1.55× against `Math`** on JDK 21 with `FastStrictTrig`, not the 1.5–3×
that was priced in — and it is **allocation-free**, where `StrictMath.sin` costs ~62 B/op on JDK 21 and
later (a `double[]` carrier that escape analysis does not remove; on 8 through 17 it is native JNI and
allocates nothing, so the figure there is a structural 0). On JDK 11/AArch64 the policy is better than
free: `FastStrictTrig` is 9× faster than `Math` there, because `Math.sin` has no useful intrinsic on
that combination.

**What this buys the release:** the recorded risk *"MR-JAR × OSGi interaction is fiddly — `Multi-Release: true`
in the bnd instructions, versioned classes excluded from `Export-Package`. Budget for it; test the
bundle"* is **removed, not mitigated.** There is no second class-file version, so there is nothing for
bnd to double-export. One source root, one bytecode level, one artifact.

### One prerequisite for keeping Java 8, and it is still open

**`Math.toRadians` is not bit-stable across the Java 8 / 9 boundary.** Java 8 computes
`angdeg / 180.0 * PI`; Java 9 changed it to a multiply by a precomputed constant. Measured over the 721
whole degrees in [−360, 360]:

| comparison | Java 8 | Java 11 | Java 21 |
|---|---|---|---|
| `Math.toRadians(d)` vs `d * ProjectionMath.DTR` | **182 of 721 differ (25.2%)** | 0 | 0 |
| `d * DTR` vs PROJ's `PJ_TORAD` (`d * M_PI / 180.0`) | 186 of 721 | 186 | 186 |

proj4j uses **both** idioms — `ProjectionMath.DTR` on the projection path and `Math.toRadians` in 137
places across 42 files. On Java 11+ the two agree exactly. **On Java 8 they disagree by 1 ulp on a
quarter of whole-degree inputs.** Most of the 137 sites are exception messages and bound
initialisation, where 1 ulp is harmless, but they have not been individually audited and at least a few
(`RobinsonProjection` lines 99 and 162, inside the inverse; the geodesic package; the grid-shift
operators) are result-bearing.

**This is a Java-8-only internal inconsistency, and it is cheap to remove: never call
`Math.toRadians`/`Math.toDegrees` in `core/src/main`, use the explicit constant.** Doing so makes the
Java 8 baseline sound and is a smaller change than raising the floor. **It remains a release blocker for
the determinism claim on Java 8, and it is not yet done** — the audit of which of the 137 sites are
result-bearing is outstanding.

The last-ulp difference from PROJ's own `PJ_TORAD` in the third row is separate, pre-existing,
JDK-independent, and already documented in `gie/GieComparator`. It is not a defect: 1 ulp of a radian
is ~2 pm, eight orders inside the tightest corpus tolerance.

---

## Upgrade guidance

Coming from `org.locationtech.proj4j:proj4j:1.4.3`, change the groupId and artifactId as well as the
version — see the [README](README.md) for the coordinates. Package names are unchanged, so no imports
move: the new `org.locationtech.proj4j.api` facade is additive, has zero runtime dependencies, and the
legacy types are **not** deprecated. Verified: `EPSG:4267 → 4269` still transforms through the legacy
factory.

What to check, in order:

1. **If you use `+nadgrids` or `+datum=NAD27` anywhere near the edge of your grid coverage**, expect
   `COORDINATE_OUTSIDE_GRID` where you previously got the input coordinate back. 1,949 golden-master
   rows that reported `OK` now raise. Decide whether you need the grid shipped (see the `conus`
   placeholder) or whether refusing is the right answer for your data.
2. **If you match on `ErrorCause`**, an unreadable or missing grid is now `MISSING_GRID`
   (`Group.OPERATION`), not `INVALID_PARAM_VALUE` (`Group.CRS`).
3. **Find every place you swallow an exception or treat a finite result as success.** The
   forward-only-inverse change alone moved 75 rows that had reported `OK`.
4. **If you use `tmerc`, `utm`, or anything derived from them beyond ~10° from the central meridian**,
   decide between the corrected default and `+approx`. Measure; do not assume the zone you are in.
5. **If you have stored coordinates computed through an NTv1 grid**, they are wrong by ~13 m.
6. **If you read `.z` off a transform you fed only x and y**, it is now `NaN`. It was never data.
7. **If you use `Ellipsoid.SPHERE`**, check whether you meant the authalic radius.
8. **Leave `AxisOrderPolicy` on `LEGACY`** unless you have a specific reason, and if you change it, test
   away from the equator.
9. **If you raw-bit-compare or checksum output**, normalise NaN first.

## Acknowledgements

The gie and GIGS conformance corpora are vendored from PROJ 9.8.1 and from the IOGP's Geospatial
Integrity of Geoscience Software project. Licences and notices: `conformance/NOTICE-gie.md`.
