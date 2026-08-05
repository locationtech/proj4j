# neoProj4J vs. upstream proj4j 1.4.3 — head-to-head JMH results

Measured 2026-08-04. **Read the caveats before the ratios**: three of these arms run a *different
algorithm* on the two sides, so a larger ns/op there is a change of work, not a regression.

## What was compared

| | baseline arm | fork arm |
|---|---|---|
| coordinates | `org.locationtech.proj4j:proj4j{,-epsg}:1.4.3` (Maven Central) | `io.github.emilevictor.neoproj4j:neoproj4j{,-epsg}:1.4.4-develop-SNAPSHOT` |
| jar | `benchmarks-ab-baseline.jar`, 4,360,124 B | `benchmarks-ab-fork.jar`, 5,247,496 B |
| sha256 | `b24d7528cee66bc446a172135fbcdfa9af3f207542c58c93b2beff78190a129b` | `fe423537c7632269fd8a767d6d939abfb8d8cd343f420c1f38d955a899a77372` |
| build | `mvn -Pbench-ab,bench-ab-baseline -pl benchmark-ab package` (no `-am`) | `mvn -Pbench-ab -pl benchmark-ab -am package` |

Both jars were built **from `mvn clean`**, so `[INFO] Compiling 1 source file` appears in each log:
the single benchmark source really does compile against both libraries, unchanged. Both contain a
generated `META-INF/BenchmarkList` of 2,005 B — JDK 21 was used for both builds, so the JMH
annotation processor ran.

### Proof the two jars are different builds

- `org/locationtech/proj4j/pipeline/Pipeline.class`: **present in the fork jar, absent from the
  baseline jar.**
- Different sizes and different sha256, above.

### Proof they enumerate the same arms

`java -jar <jar> -l` and `java -jar <jar> -lp` are **byte-identical between the two jars**: the same
3 benchmark methods, each over the same 7-constant `pair` param, i.e. 21 arms on each side. The
comparison script additionally asserts the two JSON result files carry identical `(arm, param)` key
sets before it prints anything.

### Configuration

`-f 2 -wi 5 -i 5 -w 1s -r 1s`, plus the class's own `-XX:+UseSerialGC`. JMH 1.37, JDK 21.0.11
Temurin, macOS 26.6 on an Apple M5 Max (18 cores). **This is a laptop, and the `Error` column is
JMH's 99.9 % confidence half-width** — every claim below is qualified by whether the two intervals
overlap.

## Per-arm results

| arm | pair | baseline 1.4.3 ns/op | fork ns/op | ratio fork/base | intervals overlap? |
|---|---|---:|---:|---:|:--|
| `createCrs` | WGS84_TO_WGS84 | 152,724 ± 18,637 | 196.3 ± 3.64 | 0.0013× | fork faster |
| `createCrs` | WGS84_TO_WEBMERCATOR | 2,382,138 ± 287,605 | 565.6 ± 14.38 | 0.00024× | fork faster |
| `createCrs` | WGS84_TO_UTM33N | 10,000,488 ± 6,637,570 | 567.6 ± 9.19 | 0.000057× | fork faster |
| `createCrs` | UTM33N_TO_WEBMERCATOR | 2,151,858 ± 86,067 | 568.5 ± 13.58 | 0.00026× | fork faster |
| `createCrs` | WGS84_TO_OSGB36 | 4,805,471 ± 590,288 | 714.7 ± 23.17 | 0.00015× | fork faster |
| `createCrs` | WGS84_TO_ALBERS_CONUS | 2,674,811 ± 228,189 | 573.1 ± 46.76 | 0.00021× | fork faster |
| `createCrs` | WGS84_TO_GEOCENTRIC | 6,755,528 ± 2,080,277 | 241.7 ± 5.90 | 0.000036× | fork faster |
| `createTransform` | WGS84_TO_WGS84 | 4.40 ± 0.53 | 11.75 ± 0.24 | 2.67× | **fork slower** |
| `createTransform` | WGS84_TO_WEBMERCATOR | 14.96 ± 0.55 | 17.09 ± 0.34 | 1.14× | **fork slower** |
| `createTransform` | WGS84_TO_UTM33N | 4.17 ± 0.15 | 11.89 ± 0.07 | 2.85× | **fork slower** |
| `createTransform` | UTM33N_TO_WEBMERCATOR | 14.97 ± 0.78 | 16.97 ± 0.51 | 1.13× | **fork slower** |
| `createTransform` | WGS84_TO_OSGB36 | 14.61 ± 0.42 | 18.37 ± 0.14 | 1.26× | **fork slower** |
| `createTransform` | WGS84_TO_ALBERS_CONUS | 13.32 ± 1.43 | 23.21 ± 1.57 | 1.74× | **fork slower** |
| `createTransform` | WGS84_TO_GEOCENTRIC | 4.01 ± 0.03 | 11.93 ± 0.18 | 2.97× | **fork slower** |
| `transform` | WGS84_TO_WGS84 | 5.01 ± 0.34 | 7.37 ± 0.26 | 1.47× | **fork slower** |
| `transform` | WGS84_TO_WEBMERCATOR | 34.42 ± 5.54 | 38.53 ± 1.09 | 1.12× | **OVERLAP — no difference shown** |
| `transform` | WGS84_TO_UTM33N | 79.35 ± 4.06 | 48.02 ± 1.39 | 0.61× | fork faster |
| `transform` | UTM33N_TO_WEBMERCATOR | 88.43 ± 3.07 | 77.82 ± 3.34 | 0.88× | fork faster |
| `transform` | WGS84_TO_OSGB36 | 93.56 ± 4.37 | 160.5 ± 9.03 | 1.72× | **fork slower** |
| `transform` | WGS84_TO_ALBERS_CONUS | 27.93 ± 0.75 | 30.98 ± 0.45 | 1.11× | **fork slower** |
| `transform` | WGS84_TO_GEOCENTRIC | 11.57 ± 0.30 | 15.97 ± 0.50 | 1.38× | **fork slower** |

Exactly **one** of the 21 arms has overlapping intervals (`transform` / `WGS84_TO_WEBMERCATOR`); the
other 20 are separated. That is a property of the arms, not a licence to read every separation as
meaningful — see the size of the effects below.

## Which arms did different work

Run on both sides, same seven pairs, same sample points, comparing the transformed output:

| pair | baseline vs. fork output |
|---|---|
| WGS84_TO_WGS84 | **bit-identical** |
| WGS84_TO_WEBMERCATOR | **bit-identical** |
| WGS84_TO_UTM33N | **bit-identical** |
| UTM33N_TO_WEBMERCATOR | **bit-identical** |
| WGS84_TO_OSGB36 | **differs**: 400096.8276301568 → 400096.83056893136 easting (≈ 2.9 mm), ≈ 0.9 mm northing, ≈ 3 mm height |
| WGS84_TO_ALBERS_CONUS | **differs**: 1774910.113646205 → 1774910.1136462037 northing (2 ulp) |
| WGS84_TO_GEOCENTRIC | **bit-identical** |

This is the discriminator the ratio table cannot supply on its own. Four of the seven pairs produce
identical doubles on both sides, so on those the fork is doing the *same arithmetic* and any timing
difference is an implementation difference. Two pairs produce different doubles, and both are the
changed-algorithm cases.

## Summary

**`createCrs` — the fork is 3 to 4 orders of magnitude faster, and this is the only large effect
here.** 1.4.3's `CRSFactory.createFromName` calls `Proj4FileReader.getParameters` →
`readParametersFromFile`, which does `getResourceAsStream("proj4/nad/epsg")` and `StreamTokenizer`s
the dictionary **from the top, on every single call** (`Proj4FileReader.java:35-55` in the 1.4.3
sources jar). There is no cache anywhere on that path in 1.4.3 — the `CRSCache` type does not exist
in that release. The cost therefore scales with where the code sits in the 995-entry file, which is
exactly what the baseline column shows: `EPSG:4326` 153 µs, `EPSG:3857` 2.4 ms, `EPSG:27700` 4.8 ms,
`EPSG:4978` 6.8 ms, `EPSG:32633` 10 ms. The fork keeps a parsed dictionary in `InitFileCache`
(`Proj4FileReader.java:120-140`), so a lookup is a map hit plus an array clone, and the residual
196–715 ns is `createFromParameters` re-parsing the proj-string and constructing the `Projection` —
the fork does **not** memoise the finished `CoordinateReferenceSystem` on this path either.
**Caveat on the baseline numbers specifically**: they are I/O- and GC-dominated and their error bars
are correspondingly awful (`WGS84_TO_UTM33N` is ±6.6 ms on a 10 ms score, i.e. ±66 %). The direction
is not in doubt at four orders of magnitude, but do not quote the individual baseline millisecond
figures as precise.

**`transform` / `WGS84_TO_UTM33N` — the fork is 39 % faster (79.4 → 48.0 ns) on bit-identical
output.** 1.4.3 mapped `+proj=utm` to `ExtendedTransverseMercatorProjection` directly
(`Registry.java:274`); the fork maps both `utm` and `tmerc` to `TransverseMercatorProjection`
(`Registry.java:448,499`), which holds Poder/Engsager as an `exact` delegate. So both sides run the
same kernel — hence the identical doubles — and the fork runs it without 1.4.3's two `new double[1]`
out-params and its non-intrinsified `Math.hypot` calls. This is the cleanest genuine win in the
table: same algorithm, same result, less work. `UTM33N_TO_WEBMERCATOR` (0.88×, also bit-identical)
is the same effect on the inverse.

### Arms where the fork is slower

**Changed-algorithm cases — slower is expected, and the output table proves the work differs:**

- **`transform` / `WGS84_TO_OSGB36`, 1.72× (93.6 → 160.5 ns).** `EPSG:27700` is `+proj=tmerc`.
  1.4.3 registered `tmerc` to the cheap truncated Evenden/Snyder series while reserving the
  Poder/Engsager kernel for `etmerc`/`utm`; the fork makes Poder/Engsager the default for `tmerc`
  too, matching PROJ 9.8.1. The 2.9 mm easting change is that switch, and the fork's value is the
  accurate one (Poder/Engsager is ~1 nm inside 150° of the central meridian, the truncated series is
  not). **The largest single slowdown in the table is therefore a correctness change, not a
  regression.** Note this arm also carries a 7-parameter Helmert, so the +67 ns is *not* purely the
  tmerc switch and these three arms cannot decompose it further; an `etmerc`-vs-`tmerc` arm on the
  same datum would be needed to separate the two.
- **`transform` / `WGS84_TO_ALBERS_CONUS`, 1.11× (27.9 → 31.0 ns).** `aea` is in the set where
  Karney auxiliary latitudes are now wired in. The 2 ulp northing change is that. A 3 ns cost for
  replacing an iterative `authlat` with a series is a small price and the effect size is barely
  above the noise floor.

**Not changed-algorithm cases — these are real per-call overhead the fork added, on identical
arithmetic, and should be treated as regressions:**

- **`transform` / `WGS84_TO_WGS84`, 1.47× (5.01 → 7.37 ns, +2.4 ns).** This pair is nothing but the
  transform envelope — no projection, identity datum. Bit-identical output. The fork has added
  roughly 2.4 ns of fixed cost to *every* `transform` call, and because it is a floor it is present
  inside every other arm in this table.
- **`transform` / `WGS84_TO_GEOCENTRIC`, 1.38× (11.6 → 16.0 ns, +4.4 ns).** Bit-identical output,
  and `geocent` is not in the Karney or Poder/Engsager sets. About 2.4 ns of this is the envelope
  above; the remaining ~2 ns is unexplained by anything in this measurement.
- **`transform` / `WGS84_TO_WEBMERCATOR`, 1.12× nominal — but the intervals OVERLAP**
  (34.42 ± 5.54 vs 38.53 ± 1.09). No difference is demonstrated on this arm and it must not be
  reported as one. The wide baseline error is the tell; a longer run would be needed to say
  anything.
- **`createTransform`, all seven pairs, 1.13×–2.97×.** Every pair is separated, so the effect is
  real, but the absolute numbers are 4–23 ns against a `createCrs` cost of hundreds of nanoseconds
  to milliseconds — this arm is ~2 % of the cost of getting to the point where you can call it, and
  the fork's absolute worst case is 23 ns. The largest *ratios* (2.7×–3.0×) are all on pairs where
  1.4.3 scored ~4 ns while its own other pairs scored ~15 ns; that 4-vs-15 split inside a single
  library's own results is unexplained and smells of a JIT/escape-analysis artefact, so the 2.9×
  ratios in particular should not be quoted without it.

### What this measurement does not cover

- **No grid-shifted pair.** `benchmark/`'s `NAD27_TO_NAD83` was deliberately omitted: this module has
  no grids dependency because upstream 1.4.3 publishes no `proj4j-grids-us-legacy` to swap to. NADCON
  interpolation cost is therefore unmeasured here; `benchmark/`'s Tier 1/Tier 2 gates cover it
  fork-side only.
- **No allocation figures**, no bulk-API comparison (`BulkCoordinateTransform` does not exist at
  1.4.3), and nothing else needing fork-only API. Those stay in `benchmark/`.
- **`createCrs` measures a warm process**, one code repeatedly. It does not measure first-call cost,
  where the fork must populate `InitFileCache` and 1.4.3 need not.

## Reproducing

```
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home   # JDK 21, not 23+
mvn -B -ntp -Pbench-ab -pl benchmark-ab clean
mvn -B -ntp -Pbench-ab,bench-ab-baseline -pl benchmark-ab package -DskipTests     # no -am
cp benchmark-ab/target/benchmarks-ab-baseline.jar /tmp/
mvn -B -ntp -Pbench-ab -pl benchmark-ab clean
mvn -B -ntp -Pbench-ab -pl benchmark-ab -am package -DskipTests
java -jar benchmarks-ab-baseline.jar -f 2 -wi 5 -i 5 -w 1s -r 1s -rf json -rff baseline.json
java -jar benchmarks-ab-fork.jar     -f 2 -wi 5 -i 5 -w 1s -r 1s -rf json -rff fork.json
```

**JDK 21 is not optional.** On JDK 23+ javac silently stops running classpath annotation processors,
JMH emits no `META-INF/BenchmarkList`, and the jar reports zero benchmarks with no other symptom.

The two `clean`s are not optional either: without them the second build reuses the first's
`target/classes` and you never learn whether the sources compile against the other library.
