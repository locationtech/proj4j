# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.0] - 2019-09-05

### Added
- Added `GeostationarySatelliteProjection`/`geos` projection
- Registry.getProjections exposes all available projects
- OSGi compatibility

### Changed
- Parse `geos` (Geostationary Satellite Projection) proj4 strings
- Projection units reported as meters by default
- BasicCoordinateTransform now thread-safe
- Improve CRS Caching performance
- CoordinateReferenceSystem.equals considered logical equality
- Projection.equals considered logical equality

## [1.0.0] - 2019-12-12

### Added
- Added support for Extended Transverse Mercator

### Changed
- Update EPSG DB v9.2
- Increasing accuracy of `etmerc` projection

### Fixed
- Fix possible `null` dereference
- Fix `cea` (Cylindrical Equal Area) projection
