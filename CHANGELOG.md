
# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/locationtech/proj4j/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/locationtech/proj4j/compare/v1.1.5...v1.2.0
[1.1.5]: https://github.com/locationtech/proj4j/compare/v1.1.4...v1.1.5
[1.1.4]: https://github.com/locationtech/proj4j/compare/v1.1.3...v1.1.4
[1.1.3]: https://github.com/locationtech/proj4j/compare/v1.1.2...v1.1.3
[1.1.2]: https://github.com/locationtech/proj4j/compare/v1.1.1...v1.1.2
[1.1.1]: https://github.com/locationtech/proj4j/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/locationtech/proj4j/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/locationtech/proj4j/compare/def8d6f3a1408676969eb7ce20c1f1eafa1ce010...v1.0.0
