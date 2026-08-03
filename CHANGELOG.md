
# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- `+f` is the flattening and `+rf` the reciprocal flattening, as in PROJ. The two were swapped, so definitions that passed a `1/f` value under `+f` (e.g. `+f=298.257222101`) built a nonsensical ellipsoid; they must now use `+rf`. PROJ rejects such a `+f` value outright
- Transverse Mercator (`+proj=tmerc`) uses the exact Poder/Engsager algorithm for ellipsoids, which has been PROJ's default since 6.0, and accepts `+approx` to select the Evenden/Snyder series. Inside a normal zone the two agree to well under a millimetre; 40 degrees from the central meridian they differ by ~1.5 km, and beyond ~80 degrees the series returns garbage where the exact algorithm reports the point as outside the projection domain
- Equidistant Cylindrical (`+proj=eqc`) implements the ellipsoidal method (EPSG:1028) as well as the spherical one (EPSG:1029), matching PROJ 9.8.0. Northings on an ellipsoid are now meridional arc lengths rather than `a * phi`

### Fixed
- Oblique Mercator: compute the natural-origin (uc) offset from the central-line azimuth (`+alpha`) instead of the rectified bearing (`+gamma`), so the projection centre maps to the false easting/northing when `+gamma` differs from `+alpha` (e.g. an explicit `+gamma=0` with a non-zero azimuth)
- Oblique Mercator: `+alpha` alone no longer behaves as `+gamma=0`, and `+gamma` alone no longer falls back to an azimuth of -45 degrees. Both parameters are now tracked as given, as PROJ does, and the two-point form (`+lat_1`/`+lon_1`/`+lat_2`/`+lon_2`) is reachable
- Oblique Mercator: use `atan2` in the forward transform, as PROJ does; the previous `atan(y/x)` plus a fixed `+PI` correction picked the wrong branch for points more than 90 degrees from the central line, and the near-meridian fallback had a spurious `B` factor
- `+R` defines a sphere. It used to set only the semi-major axis and leave the ellipsoid's eccentricity in place, which shifted every projection with an ellipsoidal branch (`merc`, `tmerc`, `laea`, `aea`, `cea`, `leac`, `lcc`, `stere`, `sterea`, `somerc`, `aeqd`, `omerc`, `geos`, `cass`) by tens to hundreds of kilometres
- Mercator: `+lat_ts` was ignored; it now sets the scale factor as in PROJ (`cos(lat_ts)` on a sphere, `msfn(lat_ts)` on an ellipsoid)
- Equal Area Cylindrical: a supplied `+k_0` was overwritten by `cos(lat_ts)` even when `+lat_ts` was absent
- The Snyder conics (`euler`, `murd1`, `murd2`, `murd3`, `pconic`, `vitk1`) ignored `+lat_1`/`+lat_2` and used hardcoded 30/60 degree parallels; their shared inverse also used the unshifted northing in `atan2` and dropped the sign flip for a negative cone constant
- Equidistant Conic (`+proj=eqdc`) was not PROJ's algorithm at all: it used a Lambert-Conformal-style formulation with a hardcoded eccentricity of 0.822719, a unit radius, and hardcoded standard parallels, and its inverse was never wired into the framework. Rewritten from PROJ's `eqdc.cpp`
- Bonne: `+lat_1` was ignored and the standard parallel was pinned at 90 degrees (the Werner limit)
- Polyconic: the ellipsoidal branch was unreachable (`spherical` was forced true), its forward transform used an uninitialised value in place of the longitude, and its inverse used `1/es` where PROJ uses `1 - es`
- Cassini: sign error in the ellipsoidal forward easting series (`C1 - ...` instead of `C1 + ...`)
- Rectangular Polyconic: `+lat_ts` and `+lat_0` were ignored
- Transverse Mercator: the spherical branch applied the scale factor twice to the easting
- Winkel Tripel: `+lat_1` was ignored, and the Winkel constant was being passed as the latitude of origin
- Krovak: PROJ's fixed defaults (Bessel 1841, `lat_0` 49°30′N, `lon_0` 42°30′ of Ferro − 17°40′, `k_0` 0.9999) are applied when the definition omits them, and `+czech` selects the native westing/southing orientation
- Bipolar Conic: dropped a spurious `lon_0` default of −90 degrees that PROJ does not have
- Near-sided Perspective (`+proj=nsper`): `+h` threw `NoSuchElementException`, the aspect (oblique/equatorial/polar) was hardcoded to equatorial, and the far-side-of-the-globe domain check was commented out
- Space Oblique Mercator for Landsat (`+proj=lsat`): `+lsat` and `+path` were rejected as unsupported and the satellite/path were hardcoded to 1/120
- Hammer: `+W` and `+M` were rejected as unsupported, and the initialisation overwrote whatever was set with the defaults
- Lagrange: `+W` was rejected as unsupported, and the default was 1.4 instead of PROJ's 2
- Urmaev Flat-Polar Sinusoidal (`+proj=urmfps`): `+n` was rejected as unsupported
- Lambert Equal Area Conic (`+proj=leac`): `+south` threw `NoSuchElementException`
- `putp2`, `nell`, `mbtfpq`, `mbt_fps`: the Newton iteration ran on the output northing instead of the latitude, so it never converged and the unconverged latitude was used
- `wag1`/`urmfps`, `wag2`, `mbtfpp`: the transformed latitude was computed and then discarded, and the raw latitude used in its place
- Loximuthal: a missing `abs()` in the near-parallel test sent every point south of `+lat_1` down the wrong branch
- Robinson: latitudes landing exactly on a 5-degree table node selected the node below through `floor()` rounding
- `+units=ch`, `+units=fath`, `+units=link` and `+units=us-ch` were silently treated as metres because those units, though defined, were missing from the lookup table; the Indian units (`ind-yd`, `ind-ft`, `ind-ch`) were added

### Added
- `ProjAlignmentTest`, pinning 67 cases against values generated from raw PROJ pipelines and cross-checked on PROJ 9.5.1 and 9.6.0

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

[Unreleased]: https://github.com/locationtech/proj4j/compare/v1.4.3...HEAD
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
