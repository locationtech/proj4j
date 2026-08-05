# proj4j-db

PROJ 9.8.1's authority database — **EPSG v12.029**, ESRI ArcGIS Pro 3.6, IGNF 3.1.0, IAU_2015,
NKG 1.0.w, NRCAN — transcoded at build time into a deterministic read-only binary index, plus the
pure-Java reader that serves it through `org.locationtech.proj4j.spi.ProjDatabase`.

| | |
|---|---:|
| index, unpacked | **6,746,032 B** (6.43 MiB) |
| index, `gzip -9` | **1,720,052 B** (1.64 MiB) |
| raw `proj.db` for comparison | 10,223,616 B / 1,858,856 B |
| saving | **−34.0 % unpacked, −7.5 % compressed** |
| dependencies | `proj4j` only |
| Java level | 8, same as core (this artifact ships to consumers) |

## Why not just ship `proj.db`

A raw SQLite file inside a jar cannot be opened by JDBC SQLite without **a native library and an
extraction to a filesystem we do not control**. Core has zero runtime dependencies and must keep them:
a downstream consumer is here specifically to delete Apache SIS and the `catch (LinkageError)` over a
duplicate `org.opengis.util.CodeList` that kills their Spark executors, and a native dependency would be
worse than what they have.

Reading SQLite's b-tree pages in pure Java is possible — 4096-byte pages, 2496 of them — but it means
implementing a write-oriented format, carrying its indexes, and inheriting a header whose change-counter
fields differ between two runs that produce identical rows.

Transcoding buys three things instead:

1. **Determinism.** Every ordering in the file is a total order over the data, so two generations from
   the same input are byte-identical and CI proves it with `git diff --exit-code`. This runs in Spark
   executors that require bit-reproducible output.
2. **The bytes we do not need are gone** — the write path, the b-tree interior pages, the page slack, and
   the 798 KB `idx_usage_object` whose job is done here by a 20-byte-per-row sorted array.
3. **Strings are shared.** `'EPSG'` appears in tens of thousands of rows upstream; here it appears once,
   referenced by a varint. 97,930 distinct strings, 2,480,418 B of UTF-8.

## The format, `.pjdx` v1

Full specification in `PjdxFormat`'s javadoc — writer and reader share that class, so the two cannot
drift. In outline: a 64-byte header carrying a SHA-256 of the content, a section directory, one shared
string pool, 27 keyed row tables and 7 sorted indexes.

Two rules do most of the work:

- **String ids are assigned in ascending unsigned UTF-8 byte order.** So `id -> string` is an array
  index and `string -> id` is a binary search over the same array — no second index, no hash table
  anywhere in the file.
- **Rows are sorted by key tuple, ties broken by encoded row bytes.** A lookup is a binary search that
  touches only the key array and decodes exactly one row; and the order is total even for the tables
  whose keys are genuinely non-unique (aliases, supersessions), so nothing about the input's order can
  leak into the output.

### Where the bytes go

| section | bytes | | section | bytes |
|---|---:|---|---|---:|
| `S_STRINGS` | 2,871,672 | | `X_USAGE_BY_OBJECT` | 488,108 |
| `S_CONVERSION` | 562,587 | | `X_CRS_BY_NAME` | 470,488 |
| `S_HELMERT_TRANSFORMATION` | 449,613 | | `X_CRS_BY_CODE` | 220,648 |
| `S_ALIAS` | 439,389 | | `X_OP_BY_SOURCE_TARGET` | 114,032 |
| `S_PROJECTED_CRS` | 334,354 | | `X_OP_BY_TARGET_SOURCE` | 114,032 |
| `S_EXTENT` | 208,559 | | `X_CRS_BY_DATUM` | 66,176 |
| `S_GEODETIC_CRS` | 67,551 | | 20 smaller sections | 249,133 |

## The schema subset

**Transcoded (31 tables read, 27 sections written):** `metadata`, `unit_of_measure`, `celestial_body`,
`ellipsoid`, `prime_meridian`, `geodetic_datum`, `vertical_datum`, both `*_datum_ensemble_member`
tables, `coordinate_system`, `axis`, `geodetic_crs`, `projected_crs`, `vertical_crs`, `compound_crs`,
`engineering_crs`, `conversion_table` (names resolved from `conversion_method` and `conversion_param`),
`helmert_transformation_table` (method name from `coordinate_operation_method`), `grid_transformation`,
`other_transformation`, `concatenated_operation`, `concatenated_operation_step`, `usage`, `extent`,
`grid_alternatives`, `alias_name`, `supersession`, `deprecation`.

**Dropped on purpose**, each with a reason, in `GenerateIndex`'s javadoc: `coordinate_metadata`
(point-motion epochs for a capability proj4j does not have — 921,600 B of the SQLite file), `scope`,
`sqlite_stat1`, `grid_packages`, `builtin_authorities`, `versioned_auth_name_mapping`,
`authority_to_authority_preference`, `geoid_model`, and the free-text `description`/`anchor` columns on
datums and CRSs. `extent.description` **is** kept: it is the string a human is shown as the area of use.

### Helmert parameters are ported, not remembered

`helmert_transformation_table` stores parameters as named columns, not `paramN` slots. The mapping to
EPSG parameter codes — 8605–8611, 1040–1047, 1049, 8617/8618/8667 — and the conditional structure
deciding which exist for a 3-, 7-, 8-, 10-, 15-parameter or Molodensky-Badekas case are transcribed from
`9.8.1:src/iso19111/factory.cpp:6337-6450`, with the codes read from
`9.8.1:src/proj_constants.h:509-559`. A wrong code here binds a value to the wrong slot silently.

## The round-trip proof

`gen/VerifyIndex` reads the SQLite dump and the generated index and compares **every row of every
transcoded table, field by field**, with doubles compared by `Double.compare` so a value that came back
as a neighbouring representable number fails.

```
VerifyIndex: 486,491 field comparisons in 6.2 s
VerifyIndex: OK -- every transcoded row matches the SQLite source
```

It runs inside `-Pregen-db` and fails the build on any mismatch. A size measurement and a handful of
spot checks cannot catch a field read in the wrong order; this can — and on its first run it caught two
real problems, both upstream data quirks rather than transcoder bugs:

### Two upstream quirks this pinned

1. **`PROJ:ENh` is a 3-dimensional Cartesian coordinate system whose three axes are numbered 1, 2 and
   2.** Easting (`PROJ:1`, order 1), Northing (`PROJ:2`, order 2) and Ellipsoidal height (`PROJ:3`,
   **order 2**). Keying axes on `(cs, order)` alone left those two tied, and the tiebreak put
   *Ellipsoidal height* before *Northing* — silently reordering the axes of a 3D system. The axis key is
   therefore `(cs authority, cs code, order, axis authority, axis code)`, which reproduces the order
   PROJ's own `ORDER BY coordinate_system_order` yields, since SQLite returns equal keys in primary-key
   order. Every other one of the 149 coordinate systems is well-formed.
2. **Two concatenated operations number their steps 2 and 3, with no step 1** —
   `NKG:ITRF2000_TO_NKG_ETRF00` and `NKG:ITRF2014_TO_NKG_ETRF14`. So `DbOperationStep.stepNumber()` is
   **not** a 1-based index into `steps()`, and a consumer that treats it as one reads the wrong step for
   those two. Pinned by `PjdxDatabaseTest.stepNumbersAreNotNecessarilyOneBased`.

## Provenance: two sources that must agree

`Proj4jDb.open()` cross-checks the `metadata` table *inside* the index against the build-stamped
`db.properties` sidecar next to it, and **throws if they disagree**. An EPSG version string alone cannot
do this — the same EPSG version can be packaged differently.

The sidecar carries two digests, deliberately:

- `artifactSha256` — the whole file. This is what the `maven-enforcer-plugin` `requireFileChecksum` gate
  compares in **every** default build, so a hand-edited artifact fails.
- `contentSha256` — the digest embedded in the header at offset 32, covering bytes `[64, len)`. The
  reader verifies this against the bytes it actually read on every open, so it is the value a Spark job
  logs per executor to **prove all executors ran the same data**.

Comparing the wrong one of those two fails on every correct artifact — which is exactly what happened on
the verifier's first run, and is why they are now named apart.

## Building

**Default build, on every machine and in CI:**

```
mvn -B -Dmaven.repo.local=/tmp/m2 -pl db -am install -Dmaven.javadoc.skip=true
```

Nothing but a SHA-256 check happens: **no `sqlite3`, no `cmake`, no Python, no PROJ checkout, no
network.** A machine without the toolchain builds successfully and cannot silently use a stale artifact.
`-am` is needed because the root pom sets `maven.install.skip=true`.

**Regeneration**, manual plus CI on any `db/**` or pin change:

```
mvn -Pregen-db -pl db -am validate -Dproj.db.source=/opt/homebrew/share/proj/proj.db
```

1. `src/gen/dump.sh` fails fast if `sqlite3` is absent, with a message pointing at omitting the profile.
   Never a silent fallback.
2. `GenerateIndex` transcodes, writes `db.properties` with `epsgVersion` **read back from the `metadata`
   table** and `generatedAtUtc` from `SOURCE_DATE_EPOCH`, and prints the SHA-256 to paste into
   `<proj4j.db.sha256>`.
3. `VerifyIndex` runs the exhaustive round-trip and fails the build on any mismatch.

CI then runs `git diff --exit-code`. Two consecutive runs are byte-identical — verified.

| machine | invocation | outcome |
|---|---|---|
| no sqlite3, no PROJ checkout | `mvn install` | **builds**; verifies checksum |
| no sqlite3 | `mvn install -Pregen-db` | fails fast, message points at omitting the profile |
| full toolchain | `mvn install -Pregen-db` | regenerates; byte-identical or CI fails |
| tampered artifact | `mvn install` | fails on SHA-256 mismatch |

`proj4j.db.max.jar.bytes` is 2,600,000: a data bump that blows the budget fails the build here rather
than surprising a downstream container image.

## Using it

Discovery is **opt-in**. Core never scans for a provider implicitly — an implicit `ServiceLoader` walk
touches a classpath proj4j does not control, and that is how a library minding its own business triggers
a `LinkageError` in somebody else's jar.

```java
try (ProjDatabase db = Proj4jDb.open()) {          // null if the data is not on the classpath
    DbCrs crs = db.crs("EPSG", "4979");            // geographic 3D, which no +init= file can express
    DbCoordinateSystem cs = db.coordinateSystem(crs.coordinateSystem().authName(),
                                                crs.coordinateSystem().code());
    // cs.dimension() == 3, cs.axes().get(2).name() == "Ellipsoidal height"

    for (DbOperation op : db.operationsBetween("EPSG", "4267", "EPSG", "4269")) {
        // nine published grid transformations, 0.15 m to 2.0 m, not one of them a ballpark
    }
}
```

Or through the SPI, sorted by `(priority, name)` with duplicates **rejected** rather than ordered by
luck:

```java
ProjDatabase db = ProjDatabaseProvider.openFirst(myClassLoader);
```

`Proj4jDb.open(ResourceResolver)` takes any resolver, so an unpacked data directory works via
`DirectoryResourceResolver`.

## Not in the reactor

This module is **deliberately not in the root `<modules>`**. Add it with:

```xml
<!-- PROJ 9.8.1's authority database (EPSG v12.029 + ESRI + IGNF + IAU_2015 + NKG), transcoded to a
     deterministic read-only binary index and read by a pure-Java reader. Published; implements
     org.locationtech.proj4j.spi.ProjDatabase. 6,746,032 B unpacked / ~1.72 MB as a jar, and its only
     dependency is proj4j itself -- no SQLite driver, no native library. See db/README.md. -->
<module>db</module>
```

## Licensing

`proj.db` is **not** covered by `LICENSE.EPSG` alone. It also contains ESRI (ArcGIS Pro 3.6), IGNF
3.1.0, IAU_2015, NKG 1.0.w and NRCAN data — 2,991 ESRI, 2,201 IAU_2015 and 864 IGNF CRSs. Those have
distinct terms, which is why this is a separate artifact with its own aggregated `NOTICE` rather than
something folded into `proj4j-epsg` and misrepresenting that one.

It is also why existing `proj4j-epsg` consumers can upgrade the code without taking on a 6.4 MB
dependency they did not ask for, and vice versa.
