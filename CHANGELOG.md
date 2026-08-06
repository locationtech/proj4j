
# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Pending — in flight, not yet released

- **⏳ `conus` folded into `neoproj4j-epsg`.** Verified: `epsg/src/main/resources/proj4/nad/` currently
  ships `ntv1_can.dat` and no other grid, whose footprint is 40°N–84°N, 142°W–44°W. `9.8.1:data/tests/conus`
  is 264,424 B of CTABLE V2, a format `datum/CTABLEV2.java` already reads. Until it lands, a released
  build needs `neoproj4j-grids-us-legacy` (1,192,986 B jar, `conus` + `alaska`) on the classpath. **No
  `neoproj4j-epsg` artifact size is quoted anywhere in these notes on purpose** — the figure recorded in
  the project's own packaging notes was measured today as 7,603,235 B against a long-standing claim of
  2,518,313 B
- **⏳ Relaxing `ClasspathResourceResolver.isSafeName`** to permit interior path segments while still
  rejecting a leading `/` or `\`, spaces, and any `.` / `..` / empty segment. The corpus writes
  `+file=tests/…` and `+grids=tests/…`, which PROJ resolves by appending the token to a search
  directory; proj4j can currently reach **no** `tests/…` file, and roughly **100 assertions** sit
  behind that one rule, concentrated in `gridshift`, `geotiff_grids`, `defmodel` and `tinshift`. **No
  revised conformance figure is quoted for it here**, and none should be until it is measured on a
  quiesced tree

## [2.0.0] - 2026-08-06

The first neoProj4J release: PROJ 9.8.1 parity, forked from upstream Proj4J 1.4.3. Published to Maven
Central under `io.github.emilevictor.neoproj4j` — a new groupId, so this is not a drop-in version bump
of `org.locationtech.proj4j:proj4j`.

**Every measured behaviour change, with its magnitude, is in [RELEASE-NOTES.md](RELEASE-NOTES.md)** —
this file lists what changed, that file tells you how far your coordinates move. Read it before
upgrading.

### Breaking — read these first

These change the answer, or the reported error, for existing callers.

- **An out-of-grid `+nadgrids` point is refused instead of echoed back.** `Grid.shift`'s no-table
  `else` branch returned the input coordinate unchanged while reporting success; it now raises
  `CrsTransformException(ErrorCause.COORDINATE_OUTSIDE_GRID)`. **1,995 golden-master rows change** —
  `REG` 1,673 · `CSV` 148 · `PAIR` 144 · `SYN` 30 — of which **1,949 had reported `OK` in 1.4.3** and
  **202 were bit-identical to 1.4.3**, so the behaviour-diffing gate could not see them at all. Layer
  nuance, verified with `PROJ_DEBUG=2`: at the *operator* level PROJ errors too, but at the *CRS* level
  with `proj.db` it selects a **declared ballpark**, so proj4j is now stricter than PROJ on **131 known
  rows** (all `+datum=NAD27`, measured by cross-tabbing all 4,280 `proj4-epsg.csv` rows against
  `cs2cs` 9.8.1). In the other direction: **zero rows** where proj4j answers and PROJ refuses
- **`ErrorCause` for an unreadable or missing grid changed** from `INVALID_PARAM_VALUE` (`Group.CRS`)
  to **`MISSING_GRID` (`Group.OPERATION`)**. Anyone matching on the old cause must update. Failing to
  read a file is a statement about proj4j's readers, not about the definition, so
  `PipelineErrorCode.FILE_NOT_FOUND_OR_INVALID` (errno 1029) also carries `rejectedByProj = false`. A
  new **`PipelineErrorCode.INVALID_INIT_KEY`, errno 1027**, took the `+init=` population and still
  reports `ErrorCause.INVALID_PARAM_VALUE` — upstream's `get_init_string` (`9.8.1:src/init.cpp`) uses
  1027, not 1029, for all three of its failure paths
- **`tmerc` defaults to Poder/Engsager** — **0.83 mm at 6° from the central meridian, 4 m at 20°,
  kilometres beyond 45°**, claiming **14,038 golden-master rows**. `+approx` / `+algo=evenden_snyder`
  are the documented escape; spheres keep Evenden/Snyder automatically
- **The Karney auxiliary-latitude core** (`9.8.1:src/latitudes.cpp`, plus rewritten `mlfn` / `phi2` /
  `tsfn`) is wired into `tmerc poly laea cea aea etmerc stere`. **19,336 golden-master rows move, every
  one of them below 1 µm.** The error it removes is larger than the movement: `authlat` was **1.58 mm
  at latitude 20.8°** against a 0.1 mm tolerance class, which is what moved `laea`, `aea` and `cea`
- **A 2D datum shift no longer invents a height.** `EPSG:4326 → EPSG:27700` at (−2.0, 53.0) with no
  input Z returned `z = −49.84606796130538`; it now returns **`NaN`**, single-point and bulk alike.
  Explicit `0.0` and explicit `100.0` are **bit-identical to before**, and x/y are bit-identical in
  every case
- **Axis order is configurable and defaults to `AxisOrderPolicy.LEGACY`**, i.e. longitude-first, i.e.
  exactly 1.4.3. Adopting authority order is a **silent** breaking change that is **invisible near
  (0, 0)** — plausible in the Gulf of Guinea, a thrown `INVALID_COORDINATE` at San Francisco, and a
  confidently wrong answer hundreds of kilometres out at (45, 30). See RELEASE-NOTES.md
- **`Proj4jException` no longer captures a Java stack trace by default.** `fillInStackTrace()` returns
  `this` unless `-Dproj4j.exceptions.stackTraces=true` is set at startup or
  `Proj4jException.setStackTraceCaptureEnabled(true)` is called. **Nothing a caller can act on
  programmatically is lost** — the exception *type*, `cause()`'s `ErrorCause`, `getMessage()` (which
  for the grid and domain refusals names the grids, the coordinate in degrees and the failing
  predicate) and `getCause()` all survive; what is lost is the Java call site. It is **not** a shared
  preallocated instance: every throw still constructs a fresh object with its own message, so two
  threads refusing two different coordinates get two different accurate messages. **Why:** in this
  library an exception is frequently the *answer*, not a bug report — `COORDINATE_OUTSIDE_GRID` fires
  once per point outside the declared coverage — and the frame walk was measured at **1,440 B/op and
  585 ns per refusal** by `GridShiftBenchmark.noGridHit`, against a dispatch path the same benchmark
  prices in tens of nanoseconds. The arm now reads **576 B/op**. If you log proj4j stack traces, set
  the flag
- **The vertical-grid file ceiling dropped from a hardcoded 512 MiB to 128 MiB**, unifying it with
  `Grid`'s under one knob, `-Dproj4j.grids.maxFileBytes`. Raise it if you ship a geoid model larger
  than that; the largest grid PROJ publishes is three orders of magnitude below it. GeoTIFF keeps a
  separate *decoded*-heap budget, `-Dproj4j.grids.maxDecodedBytes`, defaulting to 4× the file ceiling,
  because DEFLATE means file length is not an upper bound on decoded size
- **`GridCache`'s byte budget is now process-wide rather than per instance.** Two caches previously
  each received the full `-Dproj4j.grids.cacheBytes` budget, so the real ceiling was double the
  configured one

### Added
- **Public API facade** `org.locationtech.proj4j.api` — `Proj`, `Crs`, `CrsOperation`, `ProjContext`,
  policy enums, and immutable `AreaOfUse` / `Accuracy` / `GridInfo` / `DatabaseInfo` /
  `ProjectionInfo`. Zero runtime dependencies. Additive: the legacy types are **not** deprecated and
  `createTransform` output is bit-identical
- **Introspection that declines to guess.** `Proj.databaseVersion()` returns `Optional.empty()`
  rather than naming a version the dictionaries do not stamp; `availableGrids()` is probe-verified and
  reported separately from `declaredGrids()`; `axisOrder()` is paired with
  `isAxisOrderAuthoritative()`; `version()` is read from the JAR manifest, never a constant
- **Bulk transform API** — `BulkCoordinateTransform`, four signatures, interleaved and
  struct-of-arrays, with a per-point status byte array and a documented zero-allocation contract
- **Pipeline engine** `org.locationtech.proj4j.pipeline`, and with it GIGS conformance at
  **1,170 / 1,170**
- **WKT2 / WKT1-OGC / WKT1-ESRI and PROJJSON** readers and writers
- **Grid resolution SPI** `org.locationtech.proj4j.resource`, replacing a resolver that searched the
  working directory first — both a determinism hazard and, on untrusted input, an arbitrary-file-open
  primitive. PROJ puts the working directory last
- **Byte-bounded grid cache** `datum/GridCache`, LRU, default 64 MiB
  (`-Dproj4j.grids.cacheBytes`), with no lock held across I/O
- **GTX vertical grid reader** (`datum/VerticalGrid`) and NTv2 multi-subgrid support
- **GeoTIFF grid reader** — `datum/tiff/**` plus `datum/GeoTiffGrid`, **zero dependencies** (DEFLATE is
  `java.util.zip.Inflater`). Classic TIFF and BigTIFF, both endiannesses, strips and tiles, predictors
  1/2/3. Verified **bit-identical to `cct` 9.8.1 to 12 decimals** on all 35 vendored fixtures, both
  subgrid hierarchies, all seven real US grids, and the real `us_nga_egm96_15.tif` and
  `us_nga_egm08_25.tif`. Every unsupported feature is **rejected by name**, including a short DEFLATE
  stream — which previously left the block tail zero, i.e. a geoid reading exactly 0 m
- **`util/FastStrictTrig`** — an allocation-free, pure-Java transcription of the JDK's
  `FdLibm.Sin/Cos/Tan`, verified bit-identical to `StrictMath` over 221,970 raw-bit comparisons on
  five JDK/architecture combinations. **Faster than `StrictMath` on every JDK tested**, by 3.97× on
  Java 8 — which is why no multi-release JAR is needed
- **Conformance harness** for PROJ's own `gie` corpus and the IOGP GIGS suite: 7,923 assertions across
  42 files, with a checked-in expected-outcome manifest (`gie-expected-failures.tsv`, 545 rows, and
  `gie-corpus-index.tsv`, 7,923 keys) so a pass→fail regression fails the build. Current verdict
  against the full index: `regressed 0, unexpected passes 0, new 0, disappeared 0`
- **Golden-master behaviour-diffing gate** (`golden/`) over 53,430 rows against a frozen 1.4.3
  baseline, with `golden/rules.yaml` requiring every changed row to be claimed by a rule that names a
  mechanism and pins an exact row count. **42 of 42 rules are pinned**, and a rule that matches the
  wrong number of rows fails the build rather than silently absorbing another rule's rows
- **Allocation and operation-count gate** (`benchmark/`), **245 arms, 245 gated, 0 excluded**, with a
  recorded baseline of 25 rules and 171 per-benchmark ratchets
- **`ProjContext.parseMode` / `withParseMode` / `Builder.parseMode`**, exposing
  `Proj4Parser.ParseMode.STRICT` through the `Proj` facade. **The default is unchanged
  (`PROJ_COMPATIBLE`)** and must stay so — PROJ has no allow-list, and `builtins.gie` feeds a literal
  `unknown_keyword`. `STRICT` changes exactly two things, enumerated from source: a key outside
  `Proj4Keyword.supportedParameters()` raises `UnsupportedParameterException` naming the key, and an
  unresolvable `+units` raises `InvalidValueException` (by default `Units.findUnits` substitutes
  metres for anything unknown and never returns null, so `+units=bananas` is otherwise a working CRS
  in metres). **Duplicate-key precedence is *not* gated** — `+lon_0=11 +lon_0=22` yields 11.0 in both
  modes, and neither reports the duplicate. Across the full shipped dictionary, 9,013 definitions:
  8,969 parse in both modes, 43 are refused in both, **exactly one parses by default and is refused
  under `STRICT` — `world:malay`, for `rot_conv`** (dropped by PROJ in 4.8.0), and none goes the other
  way
- **Explicit `serialVersionUID` on 188 `Serializable` types** in `core`, including `Projection` and
  its 167 subclasses, plus `readResolve` on `AxisOrder`. Every value is `serialver` output against the
  pre-change classes, so **existing serialised forms stay readable**; none was invented. A test fails
  the build if a `Serializable` type in `core/src/main` lacks one. **Known gap, not fixed here:** a UID
  does not make a class serialisable, and **9 of the 151 registered projections still throw
  `NotSerializableException`** outright — pinned and enumerated by class, not globbed:
  `AdamsWorldInASquareIIProjection`, `AlaskaModifiedStereographicProjection`,
  `EquidistantAzimuthalProjection`, `LeeOblatedStereographicProjection`,
  `MillerOblatedStereographicProjection`, `ModifiedStereographic48Projection`,
  `ModifiedStereographic50Projection`, `PeirceQuincuncialProjection`, `SpilhausProjection`, with the
  other 142 serialising cleanly as the non-vacuity control. They hold a `geodesic.Geodesic`, a
  `util.Complex` or an anonymous `Forward2D`, none of which is `Serializable`. Separately and worse,
  **`+proj=geocent` serialises cold and stops serialising after its first transform**, because
  `GeocentProjection$Cached` is built lazily — a driver that broadcasts a warm CRS fails where one
  that broadcasts a cold one does not. This fires on the Spark driver at first broadcast, which is a
  harder failure than the `InvalidClassException` the UID sweep addresses
- **Depth limits on the untrusted-text parsers.** `io/wkt/**` and `io/projjson/**` cap nesting at
  `MAX_DEPTH = 64` syntactic / `MAX_CRS_DEPTH = 24` semantic, throwing in-family rather than letting a
  `StackOverflowError` — an `Error`, which nothing in `core/src/main` catches — escape to the caller.
  Measured against all 5,671 shipped EPSG WKT1 definitions, the deepest real document is **7 read, 8
  written, 3 nested CRSs**, so the caps are 8× real data
- **Integer-overflow guards in every binary grid reader** — `CTABLEV2`, `NTV1`, `NTV2`,
  `datum/tiff/**` and `db/PjdxFile` now compute extents in `long` and check the product against the
  actual file or section length before allocating, via the new `datum/GridExtents`. Refusals are a
  named `GridFormatException`, not an `OutOfMemoryError` and not a `java.lang.Error`
- **`io/InitFileCache`** — each `+init=` dictionary is parsed **once** into
  `Map<authority, Map<code, String[]>>` plus a reverse index, byte-bounded
  (`-Dproj4j.initFiles.cacheBytes`, default 32 MiB), LRU. `createFromName` allocation falls
  **16× / 1,394× / 6,971×** depending on where the code sits in the file; the 200× position-dependent
  ramp is gone. `golden.tsv` is **byte-identical across all 53,430 rows** with the cache on and off
- **Determinism test suite** `org.locationtech.proj4j.determinism` — a committed table of 54,265
  raw-bit `StrictMath` results, plus `.github/workflows/determinism.yaml`, a six-leg
  x86-64 × AArch64 matrix that compares bits across architectures rather than only checking that
  tests pass
- **Many projections**, including the `adams` family, `guyou`, `peirce_q`, `spilhaus`, `mod_ster`, the
  interrupted family and `ups`

### Fixed

Numerical core, all measured against PROJ 9.8.1 — the four helpers behind the auxiliary-latitude
change listed under Breaking, whose combined effect is **19,336 golden-master rows, all below 1 µm**:
- `authlat` — **1.58 mm at latitude 20.8° → 0.7 nm**, against a 0.1 mm tolerance class. Moves `laea`,
  `aea`, `cea`, `eqearth`, `nzmg`
- `mlfn` — **4,920 nm at latitude 72.6° → under 1 nm** (50 nm tolerance class)
- `phi2` — **4,145 nm at latitude 2.8° → 2.1 nm**
- `tsfn` — returned `0.9999999999999999` at φ=0 where the answer is exactly `1.0`, against a
  `tolerance 0 m` assertion

Silent wrong answers:
- **NTv1 reader: data offset 176 instead of 192, *and* the latitude/longitude shift components
  transposed — ~13 m on every NTv1 shift ever computed.** Neither error alone nor the pair moved a
  result far enough to look like a bug
- **NAD27 → NAD83 in CONUS: 95.573 m at San Francisco.** The code half is fixed — the parser called
  `setGrids(null)` on the *static* `Datum.NAD27` singleton, destroying the grid list process-wide.
  **The data half is a packaging question that is still open**: `neoproj4j-epsg` ships `ntv1_can.dat`
  and nothing else, so `conus` reaches a released build only through `neoproj4j-grids-us-legacy`. See
  *Pending* under [Unreleased]
- **NTv2**: "only 1 subfile supported" silently used subgrid 1 for the whole file, and interpolation
  read the captured *parent* table after descending into a child. A point in Alberta got no shift at
  all while the transform reported success
- **Grid-edge clamp in `nad_intr`, `1e-11` → `1e-4`** — the old value was 10⁷× too tight, so points
  PROJ shifts were returned unchanged
- **Grid containment tolerance `1e-4` → `1e-5`** (`REL_TOLERANCE_HGRIDSHIFT`) — proj4j accepted and
  **extrapolated** 2e-5° outside `conus`'s south edge where PROJ reports a transformation error
- **Antimeridian grid extents** were unhandled: `us_noaa_alaska.tif` declares `west = -194°`, so its
  whole western half was unreachable
- **The inverse grid-shift loop declared success when only one ordinate had converged** — `&&` where
  PROJ tests the squared 2-norm — and on exhaustion returned the input unchanged. It now throws
  `ConvergenceFailureException` (`ErrorCause.NUMERICAL_FAILURE`)
- **`+rf` and `+f` setters were exactly transposed.** `world:palestine` round-tripped to latitude
  −3.3e205°
- **`GeocentProjection` read its destination coordinate instead of its source, in both directions** —
  it could not produce a correct answer for any input, and had no test
- **`ObliqueMercatorProjection`**: `+alpha` without `+gamma` got **zero rotation** (215,218 m E /
  303,073 m N), and `u_0` used `cos(Gamma)` where upstream uses `cos(alpha)` (2,532 m E on RSO Borneo)
- **`CoordinateReferenceSystem.createGeographic()` dropped `+pm`** — 187,739 m of easting, affecting
  all 94 `+pm=` definitions
- **`AzimuthalProjection` defaulted `lat_0`/`lon_0` to 45°/45°** where PROJ defaults 0/0, so every
  azimuthal projection string omitting them was silently oblique
- **`MercatorProjection` never read `+lat_ts`** — 1.3 million metres of error
- **`CassiniProjection`** wrote 17 instance fields inside `project()`, making it unsafe to share
  across threads, and its forward and inverse were not mutual inverses (68 mm at 3°, growing as `lon⁴`)
- **`AlbersProjection`'s spherical inverse took `asin(rho/dd)`** — the radius rather than `sin φ` —
  an 89.96° error
- **Six per-projection inverse bugs were one bug**: a 2006 C→Java conversion turned "mutate the
  argument then read it" into "write the output struct then read the original parameter" —
  `aea`, `collg`, `fahey`, `bonne`, `bipc`, `SimpleConicProjection` and its seven subclasses
- **`ProjectionMath.asin`/`acos` did not clamp NaN** — `Math.abs(NaN) > 1.` is `false`, so NaN passed
  straight through
- **`+proj=aitoff` had no inverse at all** and fell through to an ungated base-class identity; `wintri`
  discarded `+lat_1`, making the *forward* off by 40,590 m
- **`Ellipsoid.SPHERE`** was the GRS80 authalic radius, not PROJ's Normal Sphere — **0.41 m at 222 km**
- **`Ellipsoid.AIRY`** carried a rounded `b` instead of the exact inverse flattening — 0.76 mm
- **Datum corrections**: `carthage` was bound to the wrong ellipsoid — upstream is `clrk80ign` —
  worth **20.45 mm N** at EPSG:22391 Tunis; `OSGB36` now carries `9.8.1:src/datums.cpp`'s values
  rather than EPSG:1314's, **3.085 mm E** and ~3.5 mm max across GB; and `potsdam` declares its
  `BETA2007.gsb` grid alongside the Helmert, because upstream's live definition is the grid and
  dropping the Helmert instead would have been a **74.9 m E / 127.7 m N** regression where the grid is
  absent
- **`+vunits=m` was unknown to the parser**, so it rejected the whole definition — one defect that
  made 144 dictionary rows fail and moved 964 golden-master rows when fixed

Determinism and locale:
- **`io/Proj4FileReader` called `toLowerCase()` with no locale.** Under `tr_TR`, `"ESRI"` becomes
  `"esrı"` — **all 2,954 ESRI codes were unreachable in a Turkish-locale JVM**
- `ProjCoordinate.DECIMAL_FORMAT`, `units/Unit` and `units/AngleFormat` used the default locale
- `ProjCoordinate.equals`/`hashCode` ignored `z`, silently making every coordinate comparison 2D
- `datum/AxisOrder` deserialised to a new instance, breaking `== AxisOrder.ENU`

### Changed
- **Failures are no longer expressed as plausible coordinates.** `aasin`/`aacos` throw rather than
  clamping, and a domain guard matching PROJ's (`|λ| > 10` **radians**, pole latitudes clamped within
  `1e-12` rad) rejects genuinely out-of-domain input. A `[-180, 180]` rejection would be *stricter*
  than PROJ and is deliberately not what this does
- **Forward-only projections no longer return the input as if it were lon/lat.** They throw.
  **13 projections × 5 probes = 65 golden-master rows** today — `airy august boggs denoy larr lask
  nicol rpoly tcc wag7 adams_hemi adams_ws1 guyou` — plus a separate 40 rows in projections added this
  release. When the rule was written it claimed **90 rows, of which 75 had reported `OK` in 1.4.3**;
  that 75 is the measurement that proves this was a fix rather than a new restriction, and the other
  15 had been `InvalidValueException: Unknown projection`. The set shrank to 65 because five names —
  `lagrng`, `aitoff`, `hammer`, `nsper`, `wintri` — turned out to have an inverse **upstream**, which
  they now have here too.
  **The gate does not key on `hasInverse()`**: that declaration is wrong in both directions
  (`KrovakProjection` and `NewZealandMapGridProjection` implement `projectInverse` without declaring
  it, `LandsatProjection` declares it while overriding nothing), and a `hasInverse()`-keyed gate
  rejected **EPSG:2065, EPSG:5514 and EPSG:27200 — three working CRS**. The shipped gate looks for a
  declared `projectInverse(double, double, ProjCoordinate)` in the class hierarchy instead
- `adjlon` replaces `normalizeLongitude`, fixing a potential hang and a dateline sign flip
- Old numerical helpers in `ProjectionMath` are deprecated, not removed

### Conformance

- **PROJ 9.8.1 gie corpus: 7,378 / 7,895 — 93.5 %**, up from a **1,066 / 6,845 — 15.6 %** baseline.
  **At least 29 of the 42 active corpus files are at 100 %.** Remainder: 515 failing, 2 skipped
- **The denominator excludes vacuous rows, and says so.** The corpus holds **7,923** assertions
  (6,962 `expect` + 961 `roundtrip`, counted with a port of gie's own lexer, not with `grep`). **28
  are vacuous `expect failure` rows** — proj4j could not construct the operation at all, so "both
  failed" is evidence about neither engine — and they are excluded from **numerator and denominator
  alike** rather than banked as passes, giving 7,895. **2 skips are reported separately and are never
  passes.** 94 out-of-block lines in `DHDN_ETRS89.gie` are reported as excluded. Note that the two
  percentages sit on different denominators on purpose: the baseline's 6,845 is smaller because far
  more rows were vacuous then, so the measured population grew by 1,050 assertions as well as the
  ratio improving
- **GIGS: 1,170 / 1,170 — 100 %**, all 20 files
- **Zero rows in the 4,280-row MetaCRS corpus where proj4j and PROJ 9.8.1 both produce a coordinate
  and the coordinates differ.** The ~1,195 apparent regressions against 1.4.3 are a stale reference
  file: 775 `tmerc` rows agree with `cs2cs` 9.8.1, 280 are refused by both engines, 28 `cass`, 24
  `tmerc`+`+datum=` and 3 `eqc` agree

### Known limitations

Operator families that are **not implemented**. Each is a refusal, not a silent omission.

- **The DGGS group — `airocean`, `s2`, `isea`** — absent from `Registry`, together **188 failing
  assertions in `builtins.gie`** (92 / 56 / 40) and declined on ratio
- **`+proj=helmert` as a user-facing operator.** It exists only as the hidden static
  `+exact +convention=position_vector` helper the `cs2cs` emulation builds. Exposing a subset would
  silently ignore `convention=coordinate_frame`, `transpose` and seven time-dependent rates, all of
  which the corpus exercises. Costs 3 assertions in `GDA.gie` and 1 in `4D-API_cs2cs-style.gie`
- **`gridshift` (the unified operator) and `defmodel`** — both need the GeoTIFF reader wired into the
  pipeline layer; the reader itself ships
- **`+proj=deformation +grids=`**, the single-file three-channel form. The two-grid form works
- **`nkg`** — 33 assertions; needs `PROJ:PROJString` pipelines plus a transformed time dimension, not
  more data
- **The time dimension is not transformed** — no `+proj=unitconvert +t_in`, no `+proj=set +t`. But
  `+t_epoch` / `+t_final` on `hgridshift` / `vgridshift` **are** honoured, so a time-*gated* grid
  shift behaves as upstream's does
- **NADCON is deliberately not implemented.** It is not a 9.8.1 format: `grids.cpp` dispatches on
  NTv1, CTABLE V2, NTv2 and TIFF, and the `us_noaa_nadcon5_*` grids are NADCON 5 data *in GeoTIFF*

Other boundaries:

- **Vertical and height support is thinly evidenced.** GTX and GeoTIFF readers ship, but **only 356 of
  7,923 corpus assertions — 4.5 % — score a third ordinate at all** (194 pass, 160 fail, 2 skip); for
  the other 5,419 coordinate expects `gie.cpp` zeroes the third ordinate on both sides, so z
  contributes exactly zero to the deviation. For a height through the *datum* stage specifically the
  corpus reaches **8 assertions and none passes**. Test your own heights
- **The legacy path has no `proj.db`.** `+datum=OSGB36` differs from PROJ by **1.784 m** (PROJ picks
  OSTN15), `nzgd49` by **2.248 m**. This is a **data-vintage gap, not an arithmetic defect** — given
  the same parameter strings the two engines agree. A pure-Java zero-dependency reader for a
  transcoded 9.8.1 database exists (`neoproj4j-db`, Phase 1), but operation *selection* is not yet wired
  through it
- **The shipped EPSG dictionary is v9.2-era (2017)** against PROJ 9.8.1's v12.029
- **NaN sign and payload are architecture-dependent and outside the bit-for-bit determinism
  guarantee.** Finite results and signed zero are inside it. Measured: `Inf - Inf` is
  `0xfff8000000000000` on x86-64 and `0x7ff8000000000000` on AArch64 with the JDK held fixed

### Gate status, stated honestly

*Re-measured 2026-08-03 in the pinned container (Temurin 21.0.11 / aarch64).*

- **ci** — **green**: whole 7-module reactor, `BUILD SUCCESS` with javadoc, **2,320 tests / 0 failures
  / 4 skipped** (`core` 1,917 · `conformance` 345 · `db` 52 · `geoapi` 6). The `MetaCRSTest`
  expectation that used to make this red no longer applies
- **conformance** — live and CI-wired, **green** against a committed 7,923-key index, **7,441 / 7,900**
- **golden** — live, blocking, and **RED on 2,291 UNEXPLAINED rows** of 53,430
  (12,012 UNCHANGED · 41,418 CHANGED · 0 ADDED · 0 REMOVED · 39,127 INTENDED), down from 18,168 →
  3,304 → 2,291 over two triage passes, with **42 of 42** rules pinned. **Red is the intended state**:
  the gate fails on any changed row that no rule claims with a named mechanism and a pinned count, so
  those 2,291 are changes somebody must *explain*, not changes somebody must *undo*
- **allocation** — **0 breaches, 245 gated, 0 EXCLUDED, 245/245 arms**. The claim that
  `gc.alloc.rate.norm` does not flake was **false for 11 of 181 arms** at the time: two independent
  runs agreed to within 0.0001 B/op on 170 arms, while the 11 `CrsParseBenchmark` arms above 1 KB/op
  drifted by up to 0.121 %. That was resolved first by ungating that rule's nine arms — a stated
  reduction in coverage — and then, properly, by **removing the cause**: `io/InitFileCache` made the
  arm fixed-shape, it rejoined Tier 1, and **there are no exclusions today**. Separately,
  `BulkTransformBenchmark` left the `staged` package, so the bulk API is gated for the first time,
  at a hard 0 B/op across 56 arms
- **determinism** — runs per leg, **22** tests, 0 failures, 0 skips (the workflow's exact-count guard
  became a floor, `DET_FLOOR_TESTS=22`, and reports upward drift as a notice)
- **bench** — baseline re-captured 2026-08-02: **171 per-benchmark ratchets, all enforced**, 25 rules,
  8 CRS pairs × 20 operations pinned
- **No CI run backs any figure in this file.** The workflow files are committed; everything above was
  measured locally

## [1.4.3] - 2026-06-02

### Added
- JPMS Automatic-Module-Name to the core and epsg JAR manifests [#129](https://github.com/locationtech/proj4j/pull/129)

## [1.4.2] - 2026-05-24

### Fixed
- Transformation one projection to another in one step should not skip the datum shift [#128](https://github.com/locationtech/proj4j/pull/128)
- GRS80 should be recognized as a WGS84 transformation type [#127](https://github.com/locationtech/proj4j/pull/127)

### Added
- JPMS Automatic-Module-Name to JAR manifest [#123](https://github.com/locationtech/proj4j/pull/123)

## [1.4.1] - 2025-06-15

### Fixed 
- External GridDefinition read fix [#121](https://github.com/locationtech/proj4j/pull/121)

## [1.4.0] - 2025-03-31

### Fixed 
- LCC ProjectInverse adjustment, BasicCoordinateTransform.transform cleanup [#117](https://github.com/locationtech/proj4j/pull/117)

### Added
- GeoAPI wrappers for PROJ4J [#115](https://github.com/locationtech/proj4j/pull/115)

## [1.3.0] - 2023-05-30

### Added
- Parsing NTv2 Improvement [#99](https://github.com/locationtech/proj4j/pull/99)
- GH-89: initial support for NTv2 [#98](https://github.com/locationtech/proj4j/pull/98)

## [1.2.3] - 2023-01-25

### Fixed
- Fix the inverse Krovak transformation [#97](https://github.com/locationtech/proj4j/pull/97)

## [1.2.2] - 2022-12-12

### Fixed
- Move all core resources to epsg submodule [#95](https://github.com/locationtech/proj4j/pull/95)

## [1.2.1] - 2022-12-12

### Fixed
- Fix maven pom.xml release metadata

## [1.2.0] - 2022-12-04

### Fixed
- Fix EquidistantAzimuthalProjection through add geodesic package [#84](https://github.com/locationtech/proj4j/issues/84)
- Fix RobinsonProjection [#87](https://github.com/locationtech/proj4j/issues/87)
- Backport: Stop after successfully applying grid [#91](https://github.com/locationtech/proj4j/pull/91)

### Added
- Added support for EPSG:9054 [#93](https://github.com/locationtech/proj4j/pull/93)
- Split projects into proj4j and proj4j-epsg [#92](https://github.com/locationtech/proj4j/pull/92)

## [1.1.5] - 2022-03-25

### Fixed
- Fix Grid equals [#78](https://github.com/locationtech/proj4j/pull/78)

## [1.1.4] - 2021-11-03

### Fixed
- Adjustment to OSGB36 datum transform e.g. EPSG: 27700
- GeocentricConverter equality check after grid shift WGS param override e.g. EPSG: 27700 [#32]
- +nadgrids=@null support e.g. EPSG: 3857

## [1.1.3] - 2021-06-17

### Fixed
- Problem with omerc projection e.g. EPSG: 3375 [#21](https://github.com/locationtech/proj4j/issues/21)

## [1.1.2] - 2021-04-12

### Fixed
- Fix NZ Map projection and add a test for it [#62](https://github.com/locationtech/proj4j/issues/62)
- Update OrthographicAzimuthalProjection [#63](https://github.com/locationtech/proj4j/pull/63)
- Fix UTM, LCC, Krovak and Stere projections [#71](https://github.com/locationtech/proj4j/pull/71)
- *2 in stereographic projection near the equator [#58](https://github.com/locationtech/proj4j/issues/58)
- WebMercator EPSG code retrieved from proj4 parameters returns a legacy value [#61](https://github.com/locationtech/proj4j/issues/61)

### Added
- Geocent projection support [#60](https://github.com/locationtech/proj4j/pull/60)

## [1.1.1] - 2020-03-08

### Added
- A projection may have a radius, support `+R=` parameter [#54](https://github.com/locationtech/proj4j/issues/54)

## [1.1.0] - 2019-09-05

### Added
- Added `GeostationarySatelliteProjection`/`geos` projection [#27](https://github.com/locationtech/proj4j/pull/27)
- Registry.getProjections exposes all available projects [#31](https://github.com/locationtech/proj4j/pull/31)
- OSGi compatibility [#44](https://github.com/locationtech/proj4j/pull/44)

### Changed
- Parse `geos` (Geostationary Satellite Projection) proj4 strings [#27](https://github.com/locationtech/proj4j/pull/27)
- Projection units reported as meters by default [#28](https://github.com/locationtech/proj4j/pull/28)
- BasicCoordinateTransform now thread-safe [#29](https://github.com/locationtech/proj4j/pull/29)
- Improve CRS Caching performance [#33](https://github.com/locationtech/proj4j/pull/33), [#34](https://github.com/locationtech/proj4j/pull/34), [#36](https://github.com/locationtech/proj4j/pull/36)
- CoordinateReferenceSystem.equals considered logical equality [#45](https://github.com/locationtech/proj4j/pull/45)
- Projection.equals considered logical equality [#45](https://github.com/locationtech/proj4j/pull/45)

## [1.0.0] - 2019-12-12

### Added
- Added support for Extended Transverse Mercator [#6](https://github.com/locationtech/proj4j/pull/6)

### Changed
- Update EPSG DB v9.2 [#7](https://github.com/locationtech/proj4j/pull/7)
- Increasing accuracy of `etmerc` projection [#14](https://github.com/locationtech/proj4j/pull/14)

### Fixed
- Fix possible `null` dereference [#16](https://github.com/locationtech/proj4j/pull/16)
- Fix `cea` (Cylindrical Equal Area) projection [#10](https://github.com/locationtech/proj4j/pull/10)

[Unreleased]: https://github.com/emilevictor/neoProj4J/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/emilevictor/neoProj4J/compare/v1.4.3...v2.0.0
[1.4.3]: https://github.com/locationtech/proj4j/compare/v1.4.2...v1.4.3
[1.4.2]: https://github.com/locationtech/proj4j/compare/v1.4.1...v1.4.2
[1.4.1]: https://github.com/locationtech/proj4j/compare/v1.4.0...v1.4.1
[1.4.0]: https://github.com/locationtech/proj4j/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/locationtech/proj4j/compare/v1.2.3...v1.3.0
[1.2.3]: https://github.com/locationtech/proj4j/compare/v1.2.2...v1.2.3
[1.2.2]: https://github.com/locationtech/proj4j/compare/v1.2.1...v1.2.2
[1.2.1]: https://github.com/locationtech/proj4j/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/locationtech/proj4j/compare/v1.1.5...v1.2.0
[1.1.5]: https://github.com/locationtech/proj4j/compare/v1.1.4...v1.1.5
[1.1.4]: https://github.com/locationtech/proj4j/compare/v1.1.3...v1.1.4
[1.1.3]: https://github.com/locationtech/proj4j/compare/v1.1.2...v1.1.3
[1.1.2]: https://github.com/locationtech/proj4j/compare/v1.1.1...v1.1.2
[1.1.1]: https://github.com/locationtech/proj4j/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/locationtech/proj4j/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/locationtech/proj4j/compare/def8d6f3a1408676969eb7ce20c1f1eafa1ce010...v1.0.0
