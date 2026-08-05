# proj4j-grids-us-legacy

The interim US datum-shift grid pack. **1,318,352 bytes unpacked**, ~1.13 MiB as a jar. Resources only:
no code, no dependencies, nothing to compile.

> ## ⚠️ This pack now adds only `alaska`
>
> **`conus` ships in `proj4j-epsg` as of 2026-08-01**, at `proj4/nad/conus`. The conterminous United
> States is therefore covered by `proj4j` + `proj4j-epsg` alone, with no opt-in artifact and no
> configuration — which is what retires the headline defect for a *default* deployment rather than for
> the subset of users who knew to add a grid pack.
>
> **What this pack still buys you is `alaska`, and nothing else.** Both copies of `conus` are the same
> upstream blob (`44b4900f`, SHA-256 `504d184f…`), so adding this pack changes no CONUS answer; it
> costs 1,053,928 further bytes to extend coverage to Alaska. Decide on that basis.
>
> The two copies live at **different resource paths on purpose** — `proj4/nad/conus` here,
> `proj4j-data/grids/conus` there — so the two jars never collide. That matters most for `INDEX`:
> `ClasspathResourceResolver.loadIndex()` reads it with `ClassLoader.getResource`, which returns the
> **first** match only, so two artifacts publishing `/proj4j-data/grids/INDEX` would make
> `Proj.availableGrids()` depend on classpath order. This pack keeps sole ownership of that prefix.

Adding this artifact to a classpath extends real datum-shift coverage to **Alaska**. Its `conus` is
redundant with `proj4j-epsg`'s (identical bytes) but harmless.

| resource | bytes | format | extent | nodes | also in `proj4j-epsg`? |
|---|---:|---|---|---:|---|
| `proj4j-data/grids/conus` | 264,424 | CTABLE V2 | 131°W–63°W, 20°N–50°N, 0.25° | 273 × 121 | **yes**, as `proj4/nad/conus` |
| `proj4j-data/grids/alaska` | 1,053,928 | CTABLE V2 | 194°W–128°W, 46°N–77°N, 0.125° | 529 × 249 | no — **the reason to add this pack** |
| `proj4j-data/grids/INDEX` | 13 | manifest | — | — | no |

## Why these files, and why "legacy"

`conus` and `alaska` are PROJ 9.8.1's own in-tree grids (`data/tests/conus`, `data/tests/alaska`) in
**CTABLE V2** format — bytes 0–10 are `CTABLE V2.0`, verified by reading the header, not by trusting the
directory name. `org.locationtech.proj4j.datum.CTABLEV2` **already reads that format**, so this pack costs
**zero new parsing code**.

The modern equivalents are GeoTIFF (`us_noaa_conus.tif`, 173,029 B; `us_noaa_hawaii.tif`;
`us_noaa_prvi.tif`; …), which are smaller and cover more regions, but need a GeoTIFF reader that Proj4J
does not have yet. Hence *legacy*: this pack is the cheap interim, to be superseded by
`proj4j-grids-us` once the GeoTIFF reader lands.

**`hawaii`, `prvi`, `stgeorge`, `stlrnc` and `stpaul` are not here**, and not because they were forgotten:
PROJ 9.8.1's tree contains no CTABLE V2 form of them. `git ls-tree 9.8.1:data/tests/` holds exactly two of
the seven US grids. The other five exist only as GeoTIFF in `PROJ-data`, so they arrive with the reader,
not before it.

## The `INDEX` manifest

`ClasspathResourceResolver` cannot enumerate a classpath prefix — the JDK offers no way to list resources
under a package. `INDEX` is a newline-delimited manifest, one resource name per line, that makes the
resolver `isEnumerable()`. Without it `Proj.availableGrids()` returns an empty list, and an empty list is
routinely misread as *"nothing installed"* rather than *"cannot enumerate"*.

Keep `INDEX` sorted and in step with the directory when adding a grid.

## Provenance

| | |
|---|---|
| PROJ tag | `9.8.1` = `f08fa86c478c4bbbf003b1ec751dd84aa6eca486` |
| `conus` blob | `44b4900f3168a5b87794f41d201d03d5aea0b964` |
| `alaska` blob | `bb6be2ffbd55cb96416fd77cc07845f832883549` |

Both are reproducible with `git -C <PROJ> cat-file blob <sha>`, which is the check to run if these bytes
are ever in doubt.

## Licensing

PROJ's `COPYING` at 9.8.1 states that "all source, **data files** and other contents of the PROJ package"
are available under its MIT licence, so these two grids are MIT, not Apache 2.0. MIT requires the notice
to travel with the file, so the repository-root **`LICENSE.PROJ`** is embedded in this jar at
`META-INF/LICENSE.PROJ` and declared in `pom.xml`'s `<licenses>`. The same file covers `proj4j-epsg`'s
copy of `conus`.

## Size budget

`maven-enforcer-plugin` fails the build if the packaged jar exceeds `${grid.pack.max.bytes}`
(1,400,000 B). A data bump that blows the budget is a build failure here rather than a surprise in a
downstream container image.

## Not in the reactor

This module is deliberately **not** listed in the root `pom.xml` `<modules>` yet. Add it with:

```xml
<modules>
    <module>core</module>
    <module>epsg</module>
    <module>geoapi</module>
    <module>grids-us-legacy</module>
    <module>conformance</module>
</modules>
```

Note the root pom sets `maven.install.skip=true`, so inter-module dependencies resolve from the reactor
and builds must always pass `-am`.
