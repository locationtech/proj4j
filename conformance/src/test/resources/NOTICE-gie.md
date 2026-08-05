# NOTICE — third-party material vendored into `conformance/src/test/resources`

**This file is a licence obligation, not documentation.** Two of the three bodies of vendored
material carry conditions that bind anyone who redistributes them: PROJ's MIT/X11 notice must
travel with the copies, and the IOGP GIGS copyright requires both that the source be acknowledged
and that *every subsequent recipient be informed of its terms*. Shipping the files without this
file does not satisfy either. If these resources end up inside a published artifact, this notice
(or its content) must go with them.

Nothing in `gie/`, `gigs/` or `proj-data/` is authored here. Every byte is a verbatim copy of
[PROJ](https://proj.org) at tag **9.8.1** (`f08fa86c478c4bbbf003b1ec751dd84aa6eca486`, 2026-04-10),
extracted by `conformance/sync-upstream.sh`. `gie-manifest.sha256` records the SHA-256 of every
vendored file so that local modification is detectable; re-running the sync script regenerates it.

| directory | upstream origin | files |
|---|---|---|
| `gie/` | `9.8.1:test/gie/` | 22 |
| `gigs/` | `9.8.1:test/gigs/` | 20 `.gie` + 10 `.gie.failing` |
| `proj-data/` | `9.8.1:data/` — the `for_tests` whitelist built by `data/CMakeLists.txt` | 96 (82 under `tests/`, plus promoted copies) |

`test/gie/tinshift_gpkg.gie` and `test/gie/tinshift_gpkg_network.gie` are **not** vendored: they do
not exist at 9.8.1, having been added to PROJ `master` afterwards.

---

## 1. PROJ — `gie/**`, and the PROJ-authored parts of `proj-data/**`

`gie/**` is derived from PROJ 9.8.1 `test/gie`. The `.gie` files are unmodified except that the
directory has been flattened from `test/gie/` to `gie/`. The `proj-data/**` tree reproduces the
`for_tests` directory that `9.8.1:data/CMakeLists.txt` assembles at configure time, including its
two renames (`tests/egm96_15_downsampled.gtx` → `egm96_15.gtx`, `tests/ntv2_0_downsampled.gsb` →
`ntv2_0.gsb`) and the deliberately awkward `dir with space/myconus` fixture.

PROJ is MIT/X11 licensed, which is compatible with proj4j's Apache-2.0. The following is
`9.8.1:COPYING` reproduced in full. Note its first sentence: it covers **data files**, not only
source.

> All source, data files and other contents of the PROJ package are 
> available under the following terms.  Note that the PROJ 4.3 and earlier
> was "public domain" as is common with US government work, but apparently
> this is not a well defined legal term in many countries. Frank Warmerdam placed
> everything under the following MIT style license because he believed it is
> effectively the same as public domain, allowing anyone to use the code as
> they wish, including making proprietary derivatives.
>
> Initial PROJ 4.3 public domain code was put as Frank Warmerdam as copyright
> holder, but he didn't mean to imply he did the work. Essentially all work was
> done by Gerald Evenden.
>
> Copyright information can be found in source files.
>
>  --------------
>
>  Permission is hereby granted, free of charge, to any person obtaining a
>  copy of this software and associated documentation files (the "Software"),
>  to deal in the Software without restriction, including without limitation
>  the rights to use, copy, modify, merge, publish, distribute, sublicense,
>  and/or sell copies of the Software, and to permit persons to whom the
>  Software is furnished to do so, subject to the following conditions:
>
>  The above copyright notice and this permission notice shall be included
>  in all copies or substantial portions of the Software.
>
>  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
>  OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
>  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
>  THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
>  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
>  FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
>  DEALINGS IN THE SOFTWARE.

Section 3 below qualifies this for the subset of `proj-data/**` that PROJ itself redistributes from
third parties.

---

## 2. IOGP GIGS — `gigs/**`

**All 30 files in `gigs/` are derived from the IOGP Geospatial Integrity of Geoscience Software
(GIGS) test dataset and are subject to the notice reproduced below.** This matters more than for
the PROJ material, because:

- the `.gie` files themselves **carry no licence header** — nothing in `gigs/*.gie` tells a reader
  that IOGP material is involved;
- the notice that once accompanied them, `test/gigs/TESTNOTES.md`, **was deleted from PROJ
  upstream**. It survives only in history, at commit `7b87c520`.

So this section is the only place the obligation is recorded. It must not be dropped.

The two blocks below are reproduced verbatim from
`git show 7b87c520:test/gigs/TESTNOTES.md` (Micah Cochran, 2016-05-24), which in turn reproduces
them from the IOGP publication. In that file the preamble reads *"The disclaimer and copyright
**only** applies to JSON files that originate from GIGS tests, which is a reformatting material
provided by the International Association of Oil & Gas Producers."* — the `.gie` files here are
machine translations of exactly those JSON files (see the chain of custody below), so the notice
carries over to them unchanged.

### Disclaimer

> Whilst every effort has been made to ensure the accuracy of the information contained in this publication,
> neither the OGP nor any of its members past present or future warrants its accuracy or will, regardless
> of its or their negligence, assume liability for any foreseeable or unforeseeable use made thereof, which
> liability is hereby excluded. Consequently, such use is at the recipient’s own risk on the basis that any use
> by the recipient constitutes agreement to the terms of this disclaimer. The recipient is obliged to inform
> any subsequent recipient of such terms.
>
> This document may provide guidance supplemental to the requirements of local legislation. Nothing
> herein, however, is intended to replace, amend, supersede or otherwise depart from such requirements. In
> the event of any conflict or contradiction between the provisions of this document and local legislation,
> applicable laws shall prevail.

### Copyright notice

> The contents of these pages are © The International Association of Oil & Gas Producers. Permission
> is given to reproduce this report in whole or in part provided (i) that the copyright of OGP and (ii)
> the source are acknowledged. All other rights are reserved.” Any other use requires the prior written
> permission of the OGP.
>
> These Terms and Conditions shall be governed by and construed in accordance with the laws of
> England and Wales. Disputes arising here from shall be exclusively subject to the jurisdiction of the
> courts of England and Wales.

### What that requires of us

1. **Acknowledge the copyright and the source.** The copyright is the International Association of
   Oil & Gas Producers (IOGP, formerly OGP). The source is the IOGP GIGS test dataset, series 5100
   (conversions) and 5200 (transformations), from *GIGS Test Dataset v2.0* (2011), reached via PROJ
   as described below. Background: <https://www.iogp.org/> (the URL in the original notice,
   `http://www.iogp.org/Geomatics#2521115-gigs`, is dead).
2. **Inform subsequent recipients of the terms.** The disclaimer text says so explicitly: *"The
   recipient is obliged to inform any subsequent recipient of such terms."* That is a transitive
   obligation — it does not stop at us. Any artifact that carries `gigs/**` must carry this notice
   too.

### Chain of custody

The `.gie` files are several transformations removed from the IOGP publication. Each step is a
commit in the PROJ repository:

| step | what | commit / date |
|---|---|---|
| 1 | **IOGP GIGS Test Dataset v2.0** published | 2011 |
| 2 | Reformatted into JSON, with a Python test driver and `TESTNOTES.md` carrying the notice above | `7b87c520`, Micah Cochran, 2016-05-24 |
| 3 | `json → gie` conversion script added | `a053ad0e`, Kristian Evers, 2017-10-24 |
| 4 | JSON auto-translated to `.gie` and registered as CMake tests | `4cf424f1`, Kristian Evers, 2017-12-11 |
| 5 | Tolerances corrected — GIGS states an infinity norm on angular coordinates in arc-seconds, `gie` needs a linear metre distance, so the values were converted (and in places `+towgs84` overridden to stop roundtrip drift) | `0770483f`, Kristian Evers, 2018-01-31 |
| 6 | Consistently-failing subset quarantined behind a filename suffix | `c75d1879`, Kristian Evers, 2018-02-02 and `ab2d175b`, Thomas Knudsen, 2018-02-12 |

Step 6 is why ten files here end in `.gie.failing` rather than `.gie`. The suffix is upstream's; it
is preserved verbatim. Those ten are: `5101.4-jhs`, `5105.1`, `5110`, `5111.2`, `5203.1`, `5204.1`,
`5205.1`, `5206`, `5207.1`, `5207.2`.

Because of steps 3–5 the numeric expectations in `gigs/**` are **PROJ's rendering** of the GIGS
dataset, not the IOGP publication's own numbers. Agreement with these files is agreement with PROJ.
It is evidence of GIGS conformance, but it is not a GIGS certification, and nothing here should be
described as one.

---

## 3. `proj-data/**` — grids, dictionaries and fixtures

All of it is checked into the PROJ source repository and is therefore covered by the PROJ MIT/X11
notice in section 1, which extends to "data files and other contents of the PROJ package". That is
the operative licence for our redistribution. The per-source detail below is recorded because PROJ
redistributes several of these files from national mapping agencies under those agencies' own
terms, and because attribution is owed regardless of the umbrella licence.

### What is here

| group | files | origin |
|---|---|---|
| Init dictionaries and runtime config | `nad27`, `nad83`, `GL27`, `ITRF2000`, `proj.ini` | `9.8.1:data/`, authored by the PROJ project (`GL27` carries an SCCS ID dating it to 1993-08-25) |
| Grids promoted to the `proj-data/` root | `alaska`, `BETA2007.gsb`, `conus`, `MD`, `ntf_r93.gsb`, `ntv1_can.dat`, plus the renamed `egm96_15.gtx` and `ntv2_0.gsb` | `9.8.1:data/tests/`; copies of the same eight files also remain under `proj-data/tests/` |
| The awkward-path fixture | `dir with space/myconus` | a byte-identical copy of `conus`; upstream creates it for `test_cs2cs_datumfile`, and the space in the directory name is the point of the fixture |
| Format fixtures | the remaining 74 files under `proj-data/tests/` — GeoTIFF, GTX, CT2, NTv2 and JSON variants | `9.8.1:data/tests/` |

### Provenance of the eight grids

All eight arrived in `data/tests/` in a single commit, `a9bc6e5f` (Even Rouault, 2020-02-26,
*"Make tests independent of proj-datumgrid"*, fixing PROJ issue #1984). Its message states that
`BETA2007.gsb`, `MD`, `alaska`, `conus`, `ntf_r93.gsb` and `ntv1_can.dat` were **copied from the
`proj-datumgrid` package**, and that `egm96_15_downsampled.gtx` and `ntv2_0_downsampled.gsb` are
**downsampled/subsetted versions** of the production `egm96_15.gtx` and `ntv2_0.gsb` created for
testing. They are reduced-fidelity test fixtures; they are not the production grids and must not be
used as such, notwithstanding the names they are copied to.

Underlying sources, by grid:

| file | underlying source |
|---|---|
| `ntv1_can.dat`, `ntv2_0.gsb` | Natural Resources Canada (NTv1 / NTv2) |
| `BETA2007.gsb` | AdV, Germany (embedded header: `SYSTEM_F DHDN90`, `SYSTEM_T ETRS89`) |
| `ntf_r93.gsb` | IGN France (embedded header: `IGN07_01`, `SYSTEM_F NTF`, `SYSTEM_T RGF93`) |
| `egm96_15.gtx` | NGA EGM96 geoid |
| `conus`, `alaska`, `MD` | NOAA/NGS NADCON NAD27→NAD83 grids, converted to PROJ's CTABLE V2 format (verified: each begins with the ASCII bytes `CTABLE V2.0`) |

### Licence status — verified versus assumed

**Verified.** The GeoTIFF fixtures under `proj-data/tests/` carry machine-readable licence strings
in their embedded GDAL metadata. Reading them directly out of the vendored files gives:

| statement found in the file | files |
|---|---|
| `Derived from work by NOAA. Public Domain` | `us_noaa_geoid06_ak_subset_at_antimeridian.tif`, `us_noaa_nadcon5_nad83_1986_nad83_harn_conus_extract_sanfrancisco.tif`, `us_noaa_nadcon5_nad83_2007_nad83_2011_alaska_extract.tif`, `us_noaa_nadcon5_nad83_2007_nad83_2011_conus_extract.tif` |
| `Derived from work by NGA. Public Domain` | `egm96_15_uncompressed_truncated.tif` |
| `Derived from work by IGN France. Open License` (Etalab Licence Ouverte) | `fr_ign_RAGTBT2016.tif`, `subset_of_gr3df97a.tif` |
| `Derived from work by Natural Resources Canada. Open Government Licence - Canada` | `test_hgrid_with_subgrid.tif`, `test_hgrid_with_subgrid_no_grid_name.tif` |
| `The Nordic Geodetic Commission. Creative Commons Attribution 4.0` | `nkgrf03vel_realigned_extract.tif`, `nkgrf03vel_realigned_extract_tiled_256x256.tif` |
| `Land Information New Zealand (2013): Released under Creative Commons Attribution 4.0 International` | `simple_model_polar.tif`, `simple_model_wrap_east.tif`, `simple_model_wrap_west.tif`, `test_3d_grid_projected.tif` |

**Verified.** `proj.db`'s `grid_alternatives` table has an `open_license BOOLEAN` column
(`9.8.1:data/sql/proj_db_table_defs.sql:905`). A CHECK constraint at line 915 makes it structurally
impossible for a row to point at `https://cdn.proj.org/` unless `direct_download = 1` **and**
`open_license = 1`. So `open_license = 1` on a CDN row is PROJ's assertion that the *PROJ-data
CDN copy* of that grid may be freely redistributed. Querying `9.8.1:data/sql/grid_alternatives.sql`
for the grids we vendor: `NTv1_0.gsb`, `NTv2_0.gsb`, `BETA2007.gsb`, `rgf93_ntf.gsb` and `WW15MGH.GRD`
(EGM96) all appear with `open_license = 1`. `conus`, `alaska` and `MD` have **no** row under those
names — the modern NADCON equivalents are separate `us_noaa_*` entries.

**Assumed, not verified.** That `open_license = 1` transfers to the *in-tree* copies here. It is a
statement about the CDN-hosted PROJ-data artifact, and the in-tree files are older-format,
reduced-fidelity copies from `proj-datumgrid`. What we actually rely on is section 1: these files
are checked into the PROJ repository, whose `COPYING` covers "data files and other contents of the
PROJ package".

**Assumed, not verified.** Licence terms for the untagged binary grids — `alaska`, `conus`, `MD`,
`ntv1_can.dat`, `ntf_r93.gsb`, `BETA2007.gsb`, `egm96_15.gtx`, `ntv2_0.gsb` — beyond the agency
attributions above. Unlike the GeoTIFFs, these legacy formats carry no embedded licence field, and
`proj-datumgrid` was retired, so no per-file statement was located at 9.8.1.

**Out of scope.** `proj.db` itself is *not* vendored here. Upstream generates it from
`data/sql/*.sql` at build time and copies it into `for_tests/`; it is not a checked-in artifact.
Note for whoever ships it later: it aggregates EPSG, ESRI, IGNF, IAU and NKG content under
different terms, and proj4j's existing `LICENSE.EPSG` covers only the EPSG portion.

---

## Refreshing

```sh
conformance/sync-upstream.sh [/path/to/PROJ]     # default /Volumes/git/PROJ
```

The script fails loudly if `9.8.1^{commit}` in the given checkout is not
`f08fa86c478c4bbbf003b1ec751dd84aa6eca486`. Re-pinning to a different PROJ release means editing
`PROJ_REV`/`PROJ_REV_SHA` at the top of the script — and re-checking this notice, since a new
release can change what third-party data is in `data/tests/`.
